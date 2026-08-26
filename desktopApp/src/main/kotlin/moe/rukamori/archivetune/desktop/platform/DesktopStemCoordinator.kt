/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.desktop.VlcjPlaybackController
import org.json.JSONObject

enum class DesktopStemPhase {
    IDLE,
    PREPARING,
    SEPARATING,
    COMPRESSING,
    READY,
    UNAVAILABLE,
    ERROR,
}

data class DesktopStemState(
    val trackId: String? = null,
    val phase: DesktopStemPhase = DesktopStemPhase.IDLE,
    val progress: Float = 0f,
    val message: String? = null,
    val cacheDirectory: Path? = null,
    val rendererActive: Boolean = false,
    val rendererMessage: String? = null,
) {
    val busy: Boolean
        get() = phase == DesktopStemPhase.PREPARING || phase == DesktopStemPhase.SEPARATING || phase == DesktopStemPhase.COMPRESSING

    val ready: Boolean
        get() = phase == DesktopStemPhase.READY && cacheDirectory != null
}

data class DesktopStemMix(
    val vocals: Float = 1f,
    val drums: Float = 1f,
    val bass: Float = 1f,
    val other: Float = 1f,
) {
    internal fun sanitized(): DesktopStemMix = copy(
        vocals = vocals.sanitizeStemGain(),
        drums = drums.sanitizeStemGain(),
        bass = bass.sanitizeStemGain(),
        other = other.sanitizeStemGain(),
    )

    /** Worker/source order is drums, bass, other, vocals. */
    internal fun workerGains(): List<Float> = listOf(drums, bass, other, vocals)
}

/** Owns the decode -> DirectML separator -> Opus-cache lifecycle for desktop stems. */
class DesktopStemCoordinator(
    private val runtime: DesktopRuntime,
    private val playback: VlcjPlaybackController,
) : AutoCloseable {
    private val _state = MutableStateFlow(DesktopStemState())
    val state = _state.asStateFlow()

    private val activeProcess = AtomicReference<Process?>(null)
    private var generationJob: Job? = null
    private val activeRendererProcess = AtomicReference<Process?>(null)
    private val rendererEpoch = AtomicLong(0L)
    private val rendererWriteLock = Any()
    private var rendererWriter: BufferedWriter? = null
    private var rendererJob: Job? = null
    @Volatile private var rendererTrackId: String? = null
    @Volatile private var desiredMix = DesktopStemMix()

    fun inspect(item: PlaybackItem?) {
        if (item == null) {
            _state.value = DesktopStemState()
            return
        }
        val cache = trackDirectory(item)
        _state.value = if (isUsableCache(cache)) {
            DesktopStemState(
                trackId = item.metadata.id,
                phase = DesktopStemPhase.READY,
                progress = 1f,
                cacheDirectory = cache,
            )
        } else {
            DesktopStemState(trackId = item.metadata.id, phase = DesktopStemPhase.IDLE)
        }
    }

    fun ensureReady(item: PlaybackItem?) {
        if (item == null) {
            inspect(null)
            return
        }
        val cache = trackDirectory(item)
        if (isUsableCache(cache)) {
            inspect(item)
            return
        }
        val existing = generationJob
        if (existing?.isActive == true && _state.value.trackId == item.metadata.id) return
        generationJob = runtime.applicationScope.launch(Dispatchers.IO + CoroutineName("ArchiveTuneStemGeneration")) {
            generate(item)
        }
    }

    fun retry(item: PlaybackItem?) {
        if (item == null) return
        generationJob?.cancel()
        activeProcess.getAndSet(null)?.let(::terminateProcess)
        generationJob = runtime.applicationScope.launch(Dispatchers.IO + CoroutineName("ArchiveTuneStemRetry")) {
            generate(item)
        }
    }

    /** Start the sample-synchronous stem renderer for an already-generated current track. */
    fun activateRenderer(item: PlaybackItem?) {
        if (item == null) {
            deactivateRenderer()
            return
        }
        val current = _state.value
        val cache = current.cacheDirectory
        if (current.trackId != item.metadata.id || !current.ready || cache == null || !isUsableCache(cache)) return
        if (rendererJob?.isActive == true && rendererTrackId == item.metadata.id) return

        val epoch = rendererEpoch.incrementAndGet()
        rendererJob?.cancel()
        activeRendererProcess.getAndSet(null)?.let(::terminateProcess)
        clearRendererWriter()
        rendererTrackId = item.metadata.id
        rendererJob = runtime.applicationScope.launch(Dispatchers.IO + CoroutineName("ArchiveTuneStemRenderer")) {
            runRenderer(item, cache, epoch)
        }
    }

    fun deactivateRenderer() {
        val epoch = rendererEpoch.incrementAndGet()
        rendererTrackId = null
        rendererJob?.cancel()
        rendererJob = null
        clearRendererWriter()
        activeRendererProcess.getAndSet(null)?.let(::terminateProcess)
        runtime.applicationScope.launch(CoroutineName("ArchiveTuneStemRendererRestore")) {
            if (rendererEpoch.get() != epoch) return@launch
            runCatching { playback.setExternalAudioRendererActive(false) }
            _state.value = _state.value.copy(rendererActive = false, rendererMessage = null)
        }
    }

    fun setMix(mix: DesktopStemMix) {
        val safe = mix.sanitized()
        desiredMix = safe
        sendRendererCommand(
            JSONObject()
                .put("type", "gains")
                .put("values", safe.workerGains()),
        )
    }

    private suspend fun generate(item: PlaybackItem) {
        val trackId = item.metadata.id
        val targetDirectory = trackDirectory(item)
        if (isUsableCache(targetDirectory)) {
            inspect(item)
            return
        }

        val worker = DesktopStemRuntime.findWorker()
        if (worker == null) {
            _state.value = DesktopStemState(
                trackId = trackId,
                phase = DesktopStemPhase.UNAVAILABLE,
                message = "Stem engine is not installed in this build.",
            )
            return
        }
        val model = DesktopStemRuntime.findModel(runtime.paths)
        if (model == null) {
            _state.value = DesktopStemState(
                trackId = trackId,
                phase = DesktopStemPhase.UNAVAILABLE,
                message = "HTDemucs model is not installed yet.",
            )
            return
        }

        Files.createDirectories(targetDirectory)
        val input = targetDirectory.resolve(".input.wav")
        try {
            Files.deleteIfExists(input)
            STEM_CACHE_NAMES.forEach { name ->
                Files.deleteIfExists(targetDirectory.resolve("$name.wav"))
                Files.deleteIfExists(targetDirectory.resolve(".$name.opus.part"))
            }

            _state.value = DesktopStemState(
                trackId = trackId,
                phase = DesktopStemPhase.PREPARING,
                progress = 0.02f,
                message = "Preparing audio…",
            )
            playback.decodeStemInput(item, input)

            _state.value = DesktopStemState(
                trackId = trackId,
                phase = DesktopStemPhase.SEPARATING,
                progress = 0.06f,
                message = "Separating stems…",
            )
            runWorker(worker, model, input, targetDirectory, trackId)

            _state.value = DesktopStemState(
                trackId = trackId,
                phase = DesktopStemPhase.COMPRESSING,
                progress = 0.96f,
                message = "Compressing stem cache…",
            )
            playback.compressGeneratedStemSetForCache(targetDirectory)
            writeManifest(targetDirectory, item)
            _state.value = DesktopStemState(
                trackId = trackId,
                phase = DesktopStemPhase.READY,
                progress = 1f,
                message = "Stems ready",
                cacheDirectory = targetDirectory,
            )
        } catch (cancelled: CancellationException) {
            activeProcess.getAndSet(null)?.let(::terminateProcess)
            throw cancelled
        } catch (error: Throwable) {
            runtime.logger.log(Level.WARNING, "Stem generation failed for ${item.metadata.id}", error)
            _state.value = DesktopStemState(
                trackId = trackId,
                phase = DesktopStemPhase.ERROR,
                message = error.message?.takeIf(String::isNotBlank) ?: "Stem generation failed",
            )
        } finally {
            runCatching { Files.deleteIfExists(input) }
            STEM_CACHE_NAMES.forEach { name ->
                runCatching { Files.deleteIfExists(targetDirectory.resolve("$name.wav")) }
                runCatching { Files.deleteIfExists(targetDirectory.resolve(".$name.opus.part")) }
            }
        }
    }

    private suspend fun runWorker(
        worker: Path,
        model: Path,
        input: Path,
        output: Path,
        trackId: String,
    ) = withContext(Dispatchers.IO) {
        val processBuilder = ProcessBuilder(
            worker.toString(),
            "--model", model.toString(),
            "--input", input.toString(),
            "--output", output.toString(),
        )
            .directory(worker.parent?.toFile())
            .redirectErrorStream(true)
        DesktopStemRuntime.configureWorkerEnvironment(processBuilder, worker)
        val process = processBuilder.start()
        activeProcess.set(process)
        var workerError: String? = null
        try {
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    when (event.optString("type")) {
                        "progress" -> {
                            val workerProgress = event.optDouble("value", 0.0).toFloat().coerceIn(0f, 1f)
                            _state.value = DesktopStemState(
                                trackId = trackId,
                                phase = DesktopStemPhase.SEPARATING,
                                progress = 0.06f + workerProgress * 0.88f,
                                message = "Separating stems… ${(workerProgress * 100).toInt()}%",
                            )
                        }

                        "loading" -> _state.value = DesktopStemState(
                            trackId = trackId,
                            phase = DesktopStemPhase.SEPARATING,
                            progress = 0.06f,
                            message = "Loading stem model…",
                        )

                        "loaded" -> _state.value = DesktopStemState(
                            trackId = trackId,
                            phase = DesktopStemPhase.SEPARATING,
                            progress = 0.08f,
                            message = "Separating stems…",
                        )

                        "error" -> workerError = event.optString("message").takeIf(String::isNotBlank)
                    }
                }
            }
            val exit = process.waitFor()
            if (exit != 0) {
                throw IllegalStateException(workerError ?: "Stem worker exited with code $exit")
            }
        } finally {
            activeProcess.compareAndSet(process, null)
        }
    }

    private suspend fun runRenderer(item: PlaybackItem, cache: Path, epoch: Long) {
        val trackId = item.metadata.id
        val worker = DesktopStemRuntime.findWorker()
        if (worker == null) {
            if (rendererEpoch.get() == epoch) {
                _state.value = _state.value.copy(rendererActive = false, rendererMessage = "Stem audio helper is unavailable.")
            }
            return
        }

        // If a previous renderer owned VLC suppression, restore normal audio while this new helper
        // boots. The helper itself starts paused, so there is never a doubled-audio launch window.
        if (rendererEpoch.get() == epoch) {
            playback.setExternalAudioRendererActive(false)
            _state.value = _state.value.copy(rendererActive = false, rendererMessage = "Starting stem audio…")
        }

        val initial = playback.state.value
        if (!initial.speed.isStemPlaybackRateSupported()) {
            if (rendererEpoch.get() == epoch) {
                _state.value = _state.value.copy(
                    rendererActive = false,
                    rendererMessage = "Stem playback currently requires 1× speed.",
                )
            }
            return
        }

        val process = ProcessBuilder(
            worker.toString(),
            "play",
            "--input", cache.toString(),
            "--start-ms", initial.positionMs.coerceAtLeast(0L).toString(),
        )
            .directory(worker.parent?.toFile())
            .redirectErrorStream(true)
            .start()
        activeRendererProcess.set(process)
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        synchronized(rendererWriteLock) {
            if (rendererEpoch.get() == epoch) rendererWriter = writer
        }

        var workerError: String? = null
        val ready = CompletableDeferred<Unit>()
        try {
            coroutineScope {
                val outputJob = launch(Dispatchers.IO + CoroutineName("ArchiveTuneStemRendererOutput")) {
                    BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                        lines.forEach { line ->
                            val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                            when (event.optString("type")) {
                                "ready" -> ready.complete(Unit)
                                "error", "audio_error" -> {
                                    workerError = event.optString("message").takeIf(String::isNotBlank)
                                    if (!ready.isCompleted) {
                                        ready.completeExceptionally(
                                            IllegalStateException(workerError ?: "Stem audio renderer failed to start"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                withTimeout(RENDERER_READY_TIMEOUT_MS) { ready.await() }
                if (rendererEpoch.get() != epoch || playback.state.value.currentItem?.metadata?.id != trackId) {
                    terminateProcess(process)
                    return@coroutineScope
                }

                // Re-read position after helper startup so the renderer doesn't begin a few hundred
                // milliseconds behind VLC just because process/device initialization took time.
                val aligned = playback.state.value
                if (!aligned.speed.isStemPlaybackRateSupported()) {
                    _state.value = _state.value.copy(
                        rendererActive = false,
                        rendererMessage = "Stem playback currently requires 1× speed.",
                    )
                    terminateProcess(process)
                    return@coroutineScope
                }
                sendMixCommand(desiredMix)
                sendMasterCommand(aligned.volume)
                sendSeekCommand(aligned.positionMs)
                sendPauseCommand(true)

                playback.setExternalAudioRendererActive(true)
                sendPauseCommand(!aligned.isPlaying)
                _state.value = _state.value.copy(rendererActive = true, rendererMessage = "Live stem mix")

                val syncJob = launch(CoroutineName("ArchiveTuneStemRendererSync")) {
                    syncRendererTransport(process, trackId, epoch, aligned)
                }
                val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
                syncJob.cancelAndJoin()
                outputJob.join()
                if (exitCode != 0 && rendererEpoch.get() == epoch) {
                    throw IllegalStateException(workerError ?: "Stem audio renderer exited with code $exitCode")
                }
            }
        } catch (cancelled: CancellationException) {
            terminateProcess(process)
            throw cancelled
        } catch (error: Throwable) {
            runtime.logger.log(Level.WARNING, "Stem playback renderer failed for $trackId", error)
            if (rendererEpoch.get() == epoch) {
                _state.value = _state.value.copy(
                    rendererActive = false,
                    rendererMessage = error.message?.takeIf(String::isNotBlank) ?: "Stem playback failed",
                )
            }
        } finally {
            synchronized(rendererWriteLock) {
                if (rendererWriter === writer) rendererWriter = null
            }
            runCatching { writer.close() }
            activeRendererProcess.compareAndSet(process, null)
            terminateProcess(process)
            if (rendererEpoch.get() == epoch) {
                withContext(NonCancellable) {
                    runCatching { playback.setExternalAudioRendererActive(false) }
                }
                _state.value = _state.value.copy(rendererActive = false)
                rendererTrackId = null
            }
        }
    }

    private suspend fun syncRendererTransport(
        process: Process,
        trackId: String,
        epoch: Long,
        initial: PlaybackSnapshot,
    ) {
        var previous = initial
        var previousNanos = System.nanoTime()
        var lastPaused = !initial.isPlaying
        var lastMaster = initial.volume

        playback.state.collect { snapshot ->
            if (!process.isAlive || rendererEpoch.get() != epoch || snapshot.currentItem?.metadata?.id != trackId) {
                terminateProcess(process)
                return@collect
            }
            if (!snapshot.speed.isStemPlaybackRateSupported()) {
                _state.value = _state.value.copy(
                    rendererActive = false,
                    rendererMessage = "Stem playback currently requires 1× speed.",
                )
                // Restore VLC immediately; runRenderer's finally also enforces this cleanup.
                playback.setExternalAudioRendererActive(false)
                terminateProcess(process)
                return@collect
            }

            val paused = !snapshot.isPlaying
            if (paused != lastPaused) {
                sendPauseCommand(paused)
                lastPaused = paused
            }
            if (abs(snapshot.volume - lastMaster) >= 0.001f) {
                sendMasterCommand(snapshot.volume)
                lastMaster = snapshot.volume
            }

            val now = System.nanoTime()
            val elapsedMs = (now - previousNanos).coerceAtLeast(0L) / 1_000_000.0
            val expectedPosition = previous.positionMs + if (previous.isPlaying) elapsedMs else 0.0
            if (abs(snapshot.positionMs - expectedPosition) >= TRANSPORT_SEEK_RESYNC_THRESHOLD_MS) {
                sendSeekCommand(snapshot.positionMs)
            }
            previous = snapshot
            previousNanos = now
        }
    }

    private fun sendMixCommand(mix: DesktopStemMix) {
        sendRendererCommand(
            JSONObject()
                .put("type", "gains")
                .put("values", mix.sanitized().workerGains()),
        )
    }

    private fun sendMasterCommand(volume: Float) {
        sendRendererCommand(JSONObject().put("type", "master").put("value", volume.coerceIn(0f, 1f)))
    }

    private fun sendPauseCommand(paused: Boolean) {
        sendRendererCommand(JSONObject().put("type", "pause").put("value", paused))
    }

    private fun sendSeekCommand(positionMs: Long) {
        sendRendererCommand(JSONObject().put("type", "seek").put("position_ms", positionMs.coerceAtLeast(0L)))
    }

    private fun sendRendererCommand(command: JSONObject): Boolean = synchronized(rendererWriteLock) {
        val writer = rendererWriter ?: return@synchronized false
        runCatching {
            writer.write(command.toString())
            writer.newLine()
            writer.flush()
        }.isSuccess
    }

    private fun clearRendererWriter() = synchronized(rendererWriteLock) {
        runCatching { rendererWriter?.close() }
        rendererWriter = null
    }

    private fun trackDirectory(item: PlaybackItem): Path =
        DesktopStemStorage.stemsDirectory(runtime.paths).resolve(cacheKey(item))

    private fun cacheKey(item: PlaybackItem): String {
        val identity = buildString {
            append(MODEL_CACHE_VERSION).append('\u0000')
            append(item.metadata.id).append('\u0000')
            append(item.metadata.durationHintMs ?: -1L)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun writeManifest(directory: Path, item: PlaybackItem) {
        val properties = Properties().apply {
            setProperty("format", "ogg-opus-4x-stereo")
            setProperty("model", MODEL_CACHE_VERSION)
            setProperty("trackId", item.metadata.id)
            setProperty("title", item.metadata.title)
            setProperty("createdAtEpochMs", System.currentTimeMillis().toString())
            setProperty("stems", STEM_CACHE_NAMES.joinToString(","))
        }
        Files.newOutputStream(directory.resolve("manifest.properties")).use { output ->
            properties.store(output, "ArchiveTune stem cache")
        }
    }

    override fun close() {
        rendererEpoch.incrementAndGet()
        rendererJob?.cancel()
        rendererJob = null
        clearRendererWriter()
        activeRendererProcess.getAndSet(null)?.let(::terminateProcess)
        // close() is called before playback.close() by DesktopMusicServices, so restore VLC
        // synchronously enough for normal shutdown semantics without launching another app job.
        runCatching {
            kotlinx.coroutines.runBlocking { playback.setExternalAudioRendererActive(false) }
        }
        val job = generationJob
        generationJob = null
        job?.cancel()
        activeProcess.getAndSet(null)?.let(::terminateProcess)
    }

    private companion object {
        const val MODEL_CACHE_VERSION = "htdemucs-1s-dml-v1"
        const val RENDERER_READY_TIMEOUT_MS = 8_000L
        const val TRANSPORT_SEEK_RESYNC_THRESHOLD_MS = 650.0
        val STEM_CACHE_NAMES = listOf("drums", "bass", "other", "vocals")

        fun isUsableCache(directory: Path): Boolean =
            Files.isDirectory(directory) && STEM_CACHE_NAMES.all { name ->
                val path = directory.resolve("$name.opus")
                Files.isRegularFile(path) && runCatching { Files.size(path) > 1_024L }.getOrDefault(false)
            }

        fun terminateProcess(process: Process) {
            runCatching { process.destroy() }
            runCatching {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
}

private fun Float.sanitizeStemGain(): Float = takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f

private fun Float.isStemPlaybackRateSupported(): Boolean = isFinite() && abs(this - 1f) <= 0.001f

private object DesktopStemRuntime {
    private const val WORKER_NAME = "archivetune-stems.exe"
    private const val MODEL_NAME = "htdemucs-core-1s-dml.onnx"

    fun findWorker(): Path? {
        val explicit = System.getenv("ARCHIVETUNE_STEM_WORKER")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
        if (explicit != null && Files.isRegularFile(explicit)) return explicit

        val appDir = System.getProperty("archivetune.app.dir")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
        val installed = appDir?.resolve("stem-worker")?.resolve(WORKER_NAME)
        if (installed != null && Files.isRegularFile(installed)) return installed

        // Native Image sets archivetune.app.dir explicitly from the running executable. Never
        // fall back to JVM/classpath heuristics in that mode: an installed build has exactly one
        // supported helper location beside ArchiveTune.exe.
        if (appDir != null) return null

        return developmentRoots()
            .flatMap { root ->
                sequenceOf(
                    root.resolve("stem-worker/target/release/$WORKER_NAME"),
                    root.resolve("stem-worker/target/debug/$WORKER_NAME"),
                )
            }
            .firstOrNull(Files::isRegularFile)
    }

    fun findModel(paths: AppPaths): Path? {
        val explicit = System.getenv("ARCHIVETUNE_STEM_MODEL")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
        if (explicit != null && Files.isRegularFile(explicit)) return explicit

        val cached = DesktopStemStorage.modelsDirectory(paths).resolve(MODEL_NAME)
        return cached.takeIf(Files::isRegularFile)
    }

    fun configureWorkerEnvironment(builder: ProcessBuilder, worker: Path) {
        val runtimeDirectory = findRuntimeDirectory(worker) ?: return
        val onnxRuntime = runtimeDirectory.resolve("onnxruntime.dll")
        builder.environment()["ARCHIVETUNE_ONNXRUNTIME"] = onnxRuntime.toString()
        val existingPath = builder.environment()["PATH"].orEmpty()
        builder.environment()["PATH"] = buildString {
            append(runtimeDirectory)
            if (existingPath.isNotBlank()) append(';').append(existingPath)
        }
    }

    private fun findRuntimeDirectory(worker: Path): Path? {
        val explicit = System.getenv("ARCHIVETUNE_STEM_RUNTIME")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
        if (explicit != null && hasRuntime(explicit)) return explicit

        val installed = worker.parent?.toAbsolutePath()?.normalize()
        if (installed != null && hasRuntime(installed)) return installed

        if (!System.getProperty("archivetune.app.dir").isNullOrBlank()) return null

        return developmentRoots()
            .map { it.resolve("build/stem-runtime/windows-x64") }
            .firstOrNull(::hasRuntime)
    }

    /**
     * Development-only fallback. Production/native builds resolve from archivetune.app.dir, which
     * NativeMain derives from the actual ArchiveTune.exe path. Do not use java.class.path here:
     * Graal Native Image has no JVM classpath to discover and its java/jvm DLLs are only shims.
     */
    private fun developmentRoots(): Sequence<Path> {
        val starts = buildList {
            System.getenv("ARCHIVETUNE_PROJECT_ROOT")
                ?.takeIf(String::isNotBlank)
                ?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
                ?.let(::add)

            runCatching {
                Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
            }.getOrNull()?.let(::add)
        }

        return starts
            .asSequence()
            .flatMap { start -> generateSequence(start) { it.parent }.take(8) }
            .distinct()
    }

    private fun hasRuntime(directory: Path): Boolean =
        Files.isRegularFile(directory.resolve("onnxruntime.dll")) &&
            Files.isRegularFile(directory.resolve("DirectML.dll")) &&
            Files.isRegularFile(directory.resolve("onnxruntime_providers_shared.dll"))
}
