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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.desktop.VlcjPlaybackController
import org.json.JSONObject

private const val STEM_MODEL_VERSION = "htdemucs-6s-1s-dml-v1"
private const val STEM_MODEL_NAME = "htdemucs-6s-core-1s-dml.onnx"
private const val STEM_MODEL_SIZE_BYTES = 110_858_418L
private const val STEM_MODEL_SHA256 = "30ef61623d5d3a768f12dd87faadfdaaca26c5b8ef6a1c73f26b8c949b6e047c"

enum class DesktopStemPhase {
    IDLE,
    DOWNLOADING_MODEL,
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
        get() = phase == DesktopStemPhase.DOWNLOADING_MODEL ||
            phase == DesktopStemPhase.PREPARING ||
            phase == DesktopStemPhase.SEPARATING ||
            phase == DesktopStemPhase.COMPRESSING

    val ready: Boolean
        get() = phase == DesktopStemPhase.READY && cacheDirectory != null
}

data class DesktopStemQueueState(
    val pending: Int = 0,
    val activeTrackId: String? = null,
    val activeTitle: String? = null,
    val completed: Int = 0,
    val failed: Int = 0,
    val lastError: String? = null,
) {
    val active: Boolean get() = pending > 0 || activeTrackId != null
}

data class DesktopStemMix(
    val vocals: Float = 1f,
    val drums: Float = 1f,
    val bass: Float = 1f,
    val other: Float = 1f,
    val guitar: Float = 1f,
    val piano: Float = 1f,
) {
    internal fun sanitized(): DesktopStemMix = copy(
        vocals = vocals.sanitizeStemGain(),
        drums = drums.sanitizeStemGain(),
        bass = bass.sanitizeStemGain(),
        other = other.sanitizeStemGain(),
        guitar = guitar.sanitizeStemGain(),
        piano = piano.sanitizeStemGain(),
    )

    /** Worker/source order is drums, bass, other, vocals, guitar, piano. */
    internal fun workerGains(): List<Float> = listOf(drums, bass, other, vocals, guitar, piano)
}

data class DesktopStemChannelControl(
    val value: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
) {
    internal fun sanitized(): DesktopStemChannelControl = copy(value = value.sanitizeStemGain())
}

data class DesktopStemControls(
    val vocals: DesktopStemChannelControl = DesktopStemChannelControl(),
    val drums: DesktopStemChannelControl = DesktopStemChannelControl(),
    val bass: DesktopStemChannelControl = DesktopStemChannelControl(),
    val guitar: DesktopStemChannelControl = DesktopStemChannelControl(),
    val piano: DesktopStemChannelControl = DesktopStemChannelControl(),
    val other: DesktopStemChannelControl = DesktopStemChannelControl(),
) {
    internal fun sanitized(): DesktopStemControls = copy(
        vocals = vocals.sanitized(),
        drums = drums.sanitized(),
        bass = bass.sanitized(),
        guitar = guitar.sanitized(),
        piano = piano.sanitized(),
        other = other.sanitized(),
    )

    internal fun effectiveMix(): DesktopStemMix {
        val channels = listOf(vocals, drums, bass, guitar, piano, other)
        val anySolo = channels.any(DesktopStemChannelControl::solo)
        fun effective(channel: DesktopStemChannelControl): Float =
            if (!channel.muted && (!anySolo || channel.solo)) channel.value else 0f
        return DesktopStemMix(
            vocals = effective(vocals),
            drums = effective(drums),
            bass = effective(bass),
            guitar = effective(guitar),
            piano = effective(piano),
            other = effective(other),
        )
    }

    internal fun isDefault(): Boolean = this == DesktopStemControls()
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
    private val controlsByTrackId = ConcurrentHashMap<String, DesktopStemControls>()
    private val modelDownloadMutex = Mutex()
    // DirectML creates a large fixed execution allocation for this graph. Serialize only this
    // GPU-bound step; decode and cache compression from another queued song may proceed.
    private val separatorMutex = Mutex()
    private val batchLock = Any()
    private val batchQueue = ArrayDeque<PlaybackItem>()
    private val _queueState = MutableStateFlow(DesktopStemQueueState())
    val queueState = _queueState.asStateFlow()
    private val _readyTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val readyTrackIds = _readyTrackIds.asStateFlow()
    private var batchJob: Job? = null
    private val activeGenerationTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val activeWorkerTrackId = AtomicReference<String?>(null)

    init {
        runtime.applicationScope.launch(Dispatchers.IO + CoroutineName("ArchiveTuneStemCacheIndex")) {
            _readyTrackIds.value = _readyTrackIds.value + scanReadyTrackIds()
        }
        runtime.applicationScope.launch(CoroutineName("ArchiveTuneStemMixFollow")) {
            var previousTrackId: String? = null
            playback.state.collect { snapshot ->
                val item = snapshot.currentItem
                val trackId = item?.metadata?.id
                if (trackId == previousTrackId) return@collect
                previousTrackId = trackId

                if (rendererTrackId != null && rendererTrackId != trackId) {
                    deactivateRenderer()
                }
                if (item != null && !controlsFor(item).effectiveMix().isNeutral()) {
                    val cache = trackDirectory(item)
                    if (isUsableCache(cache)) {
                        inspect(item)
                        activateRenderer(item)
                    }
                }
            }
        }
    }

    fun inspect(item: PlaybackItem?) {
        if (item == null) {
            _state.value = DesktopStemState()
            return
        }
        val previous = _state.value
        val keepRenderer =
            previous.trackId == item.metadata.id &&
                previous.rendererActive &&
                rendererTrackId == item.metadata.id
        val cache = trackDirectory(item)
        _state.value = if (isUsableCache(cache)) {
            DesktopStemState(
                trackId = item.metadata.id,
                phase = DesktopStemPhase.READY,
                progress = 1f,
                cacheDirectory = cache,
                rendererActive = keepRenderer,
                rendererMessage = if (keepRenderer) previous.rendererMessage else null,
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
            markReady(item.metadata.id)
            inspect(item)
            return
        }
        val existing = generationJob
        if (existing?.isActive == true && _state.value.trackId == item.metadata.id) return
        generationJob = runtime.applicationScope.launch(Dispatchers.IO + CoroutineName("ArchiveTuneStemGeneration")) {
            runGeneration(item, publishState = true)
        }
    }

    fun retry(item: PlaybackItem?) {
        if (item == null) return
        generationJob?.cancel()
        if (activeWorkerTrackId.get() == item.metadata.id) {
            activeProcess.getAndSet(null)?.let(::terminateProcess)
        }
        generationJob = runtime.applicationScope.launch(Dispatchers.IO + CoroutineName("ArchiveTuneStemRetry")) {
            runGeneration(item, publishState = true)
        }
    }

    /** Queues album/playlist stem work without running multiple DirectML jobs at once. */
    fun enqueueAll(items: List<PlaybackItem>): Int = synchronized(batchLock) {
        val existingIds = buildSet {
            addAll(activeGenerationTrackIds)
            batchQueue.forEach { add(it.metadata.id) }
        }
        val accepted = items
            .distinctBy { it.metadata.id }
            .filterNot { item ->
                val ready = isUsableCache(trackDirectory(item))
                if (ready) markReady(item.metadata.id)
                item.metadata.id in existingIds || ready
            }
        if (accepted.isEmpty()) return@synchronized 0

        if (batchJob?.isActive != true && batchQueue.isEmpty()) {
            _queueState.value = DesktopStemQueueState()
        }
        accepted.forEach(batchQueue::addLast)
        publishQueueState()
        if (batchJob?.isActive != true) {
            batchJob = runtime.applicationScope.launch(Dispatchers.IO + CoroutineName("ArchiveTuneStemBatchQueue")) {
                runBatchQueue()
            }
        }
        accepted.size
    }

    private suspend fun runBatchQueue() = coroutineScope {
        val inFlight = ArrayDeque<kotlinx.coroutines.Deferred<Unit>>()
        while (true) {
            while (inFlight.size < STEM_PIPELINE_PARALLELISM) {
                val item = synchronized(batchLock) {
                    val next = batchQueue.pollFirst()
                    if (next != null) publishQueueState(activeItem = next)
                    next
                }
                if (item == null) break
                inFlight.addLast(async { completeBatchItem(item) })
            }

            if (inFlight.isNotEmpty()) {
                inFlight.removeFirst().await()
                continue
            }

            synchronized(batchLock) {
                // Clear the job while holding the queue lock so a concurrently enqueued item
                // starts its own consumer instead of being left behind an exiting one.
                if (batchQueue.isEmpty()) {
                    batchJob = null
                    publishQueueState(activeItem = null)
                    return@coroutineScope
                }
            }
        }
    }

    private suspend fun completeBatchItem(item: PlaybackItem) {
        val error = try {
            runGeneration(item, publishState = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failure.message ?: "Stem generation failed"
        }
        synchronized(batchLock) {
            val previous = _queueState.value
            _queueState.value = previous.copy(
                pending = batchQueue.size,
                activeTrackId = null,
                activeTitle = null,
                completed = previous.completed + if (error == null) 1 else 0,
                failed = previous.failed + if (error != null) 1 else 0,
                lastError = error ?: previous.lastError,
            )
        }
    }

    private fun publishQueueState(activeItem: PlaybackItem? = null) {
        val previous = _queueState.value
        _queueState.value = previous.copy(
            pending = batchQueue.size,
            activeTrackId = activeItem?.metadata?.id ?: previous.activeTrackId,
            activeTitle = activeItem?.metadata?.title ?: previous.activeTitle,
        )
    }

    private suspend fun runGeneration(item: PlaybackItem, publishState: Boolean): String? {
        activeGenerationTrackIds.add(item.metadata.id)
        try {
            return generate(item, publishState)
        } finally {
            activeGenerationTrackIds.remove(item.metadata.id)
        }
    }

    /** Start the sample-synchronous stem renderer for an already-generated current track. */
    private fun activateRenderer(item: PlaybackItem?) {
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

    private fun deactivateRenderer() {
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

    fun controlsFor(item: PlaybackItem?): DesktopStemControls =
        item?.metadata?.id?.let(controlsByTrackId::get) ?: DesktopStemControls()

    fun setControls(item: PlaybackItem?, controls: DesktopStemControls) {
        val trackId = item?.metadata?.id ?: return
        val safeControls = controls.sanitized()
        if (safeControls.isDefault()) controlsByTrackId.remove(trackId) else controlsByTrackId[trackId] = safeControls
        val safeMix = safeControls.effectiveMix()
        if (safeMix.isNeutral()) {
            if (rendererTrackId == trackId) deactivateRenderer()
            return
        }
        if (rendererTrackId == trackId && _state.value.rendererActive) {
            sendRendererCommand(
                JSONObject()
                    .put("type", "gains")
                    .put("values", safeMix.workerGains()),
            )
        } else if (playback.state.value.currentItem?.metadata?.id == trackId) {
            activateRenderer(item)
        }
    }

    private suspend fun generate(item: PlaybackItem, publishState: Boolean): String? {
        val trackId = item.metadata.id
        val targetDirectory = trackDirectory(item)
        if (isUsableCache(targetDirectory)) {
            markReady(trackId)
            if (publishState || playback.state.value.currentItem?.metadata?.id == trackId) inspect(item)
            return null
        }

        val worker = DesktopModelRuntime.findRunner()
        if (worker == null) {
            val message = "Stem engine is not installed in this build."
            if (publishState) {
                _state.value = DesktopStemState(trackId = trackId, phase = DesktopStemPhase.UNAVAILABLE, message = message)
            }
            return message
        }
        val model = try {
            ensureModel(trackId, publishState)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runtime.logger.log(Level.WARNING, "Stem model download failed", error)
            val message = "Could not download HTDemucs model: ${error.message ?: "unknown error"}"
            if (publishState) {
                _state.value = DesktopStemState(trackId = trackId, phase = DesktopStemPhase.ERROR, message = message)
            }
            return message
        }

        Files.createDirectories(targetDirectory)
        val input = targetDirectory.resolve(".input.wav")
        try {
            Files.deleteIfExists(input)
            STEM_CACHE_NAMES.forEach { name ->
                Files.deleteIfExists(targetDirectory.resolve("$name.wav"))
                Files.deleteIfExists(targetDirectory.resolve(".$name.opus.part"))
            }

            if (publishState) {
                _state.value = DesktopStemState(
                    trackId = trackId,
                    phase = DesktopStemPhase.PREPARING,
                    progress = 0.02f,
                    message = "Preparing audio…",
                )
            }
            playback.decodeStemInput(item, input)

            if (publishState) {
                _state.value = DesktopStemState(
                    trackId = trackId,
                    phase = DesktopStemPhase.SEPARATING,
                    progress = 0.06f,
                    message = "Separating stems…",
                )
            }
            separatorMutex.withLock {
                runWorker(worker, model, input, targetDirectory, trackId, publishState)
            }

            if (publishState) {
                _state.value = DesktopStemState(
                    trackId = trackId,
                    phase = DesktopStemPhase.COMPRESSING,
                    progress = 0.96f,
                    message = "Compressing stem cache…",
                )
            }
            playback.compressGeneratedStemSetForCache(targetDirectory)
            writeManifest(targetDirectory, item)
            markReady(trackId)
            if (publishState || playback.state.value.currentItem?.metadata?.id == trackId) {
                inspect(item)
                if (!controlsFor(item).effectiveMix().isNeutral()) activateRenderer(item)
            }
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runtime.logger.log(Level.WARNING, "Stem generation failed for ${item.metadata.id}", error)
            val message = error.message?.takeIf(String::isNotBlank) ?: "Stem generation failed"
            if (publishState) {
                _state.value = DesktopStemState(trackId = trackId, phase = DesktopStemPhase.ERROR, message = message)
            }
            return message
        } finally {
            runCatching { Files.deleteIfExists(input) }
            STEM_CACHE_NAMES.forEach { name ->
                runCatching { Files.deleteIfExists(targetDirectory.resolve("$name.wav")) }
                runCatching { Files.deleteIfExists(targetDirectory.resolve(".$name.opus.part")) }
            }
        }
    }

    private suspend fun ensureModel(trackId: String, publishState: Boolean): Path = modelDownloadMutex.withLock {
        DesktopModelRuntime.findModel(runtime.paths, DesktopModels.STEMS)?.let { return@withLock it.model }
        if (publishState) {
            _state.value = DesktopStemState(
                trackId = trackId,
                phase = DesktopStemPhase.DOWNLOADING_MODEL,
                progress = 0f,
                message = "Downloading HTDemucs model…",
            )
        }
        DesktopModelRuntime.ensureModel(runtime.paths, DesktopModels.STEMS) { downloaded, total ->
            if (publishState && _state.value.trackId == trackId) {
                val progress = if (total > 0L) downloaded.toFloat() / total.toFloat() else 0f
                _state.value = DesktopStemState(
                    trackId = trackId,
                    phase = DesktopStemPhase.DOWNLOADING_MODEL,
                    progress = progress.coerceIn(0f, 1f),
                    message = "Downloading HTDemucs model… ${formatModelBytes(downloaded)} / ${formatModelBytes(total)}",
                )
            }
        }.model
    }

    private suspend fun runWorker(
        worker: Path,
        model: Path,
        input: Path,
        output: Path,
        trackId: String,
        publishState: Boolean,
    ) = withContext(Dispatchers.IO) {
        val command =
            buildList {
                add(worker.toString())
                // New builds use an explicit task subcommand. Keep a narrow compatibility path for
                // an ARCHIVETUNE_STEM_WORKER override that still points at the pre-generalization helper.
                if (!worker.fileName.toString().equals("archivetune-stems.exe", ignoreCase = true)) {
                    add("stems")
                }
                addAll(
                    listOf(
                        "--model", model.toString(),
                        "--input", input.toString(),
                        "--output", output.toString(),
                    ),
                )
            }
        val processBuilder = ProcessBuilder(command)
            .directory(worker.parent?.toFile())
            .redirectErrorStream(true)
        DesktopModelRuntime.configureRunnerEnvironment(processBuilder, worker)
        val process = processBuilder.start()
        activeProcess.set(process)
        activeWorkerTrackId.set(trackId)
        var workerError: String? = null
        try {
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    when (event.optString("type")) {
                        "progress" -> if (publishState) {
                            val workerProgress = event.optDouble("value", 0.0).toFloat().coerceIn(0f, 1f)
                            _state.value = DesktopStemState(
                                trackId = trackId,
                                phase = DesktopStemPhase.SEPARATING,
                                progress = 0.06f + workerProgress * 0.88f,
                                message = "Separating stems… ${(workerProgress * 100).toInt()}%",
                            )
                        }

                        "loading" -> if (publishState) _state.value = DesktopStemState(
                            trackId = trackId,
                            phase = DesktopStemPhase.SEPARATING,
                            progress = 0.06f,
                            message = "Loading stem model…",
                        )

                        "loaded" -> if (publishState) _state.value = DesktopStemState(
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
        } catch (cancelled: CancellationException) {
            terminateProcess(process)
            throw cancelled
        } finally {
            activeProcess.compareAndSet(process, null)
            activeWorkerTrackId.compareAndSet(trackId, null)
        }
    }

    private suspend fun runRenderer(item: PlaybackItem, cache: Path, epoch: Long) {
        val trackId = item.metadata.id
        val worker = DesktopModelRuntime.findRunner()
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
                sendMixCommand(controlsFor(item).effectiveMix())
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

    private fun markReady(trackId: String) {
        if (trackId.isBlank() || trackId in _readyTrackIds.value) return
        _readyTrackIds.value = _readyTrackIds.value + trackId
    }

    private fun scanReadyTrackIds(): Set<String> {
        val root = DesktopStemStorage.stemsDirectory(runtime.paths)
        if (!Files.isDirectory(root)) return emptySet()
        val ready = linkedSetOf<String>()
        runCatching {
            Files.list(root).use { directories ->
                directories.filter(Files::isDirectory).forEach { directory ->
                    if (!isUsableCache(directory)) return@forEach
                    val manifestPath = directory.resolve("manifest.properties")
                    if (!Files.isRegularFile(manifestPath)) return@forEach
                    val manifest = Properties().also { properties ->
                        Files.newInputStream(manifestPath).use(properties::load)
                    }
                    if (manifest.getProperty("model") != MODEL_CACHE_VERSION) return@forEach
                    manifest.getProperty("trackId")?.trim()?.takeIf(String::isNotBlank)?.let(ready::add)
                }
            }
        }.onFailure { error ->
            runtime.logger.log(Level.FINE, "Could not index existing stem cache", error)
        }
        return ready
    }

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
            setProperty("format", "ogg-opus-6x-stereo")
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
        batchJob?.cancel()
        batchJob = null
        synchronized(batchLock) { batchQueue.clear() }
        activeProcess.getAndSet(null)?.let(::terminateProcess)
    }

    private companion object {
        const val MODEL_CACHE_VERSION = STEM_MODEL_VERSION
        const val RENDERER_READY_TIMEOUT_MS = 8_000L
        const val TRANSPORT_SEEK_RESYNC_THRESHOLD_MS = 650.0
        const val STEM_PIPELINE_PARALLELISM = 2
        val STEM_CACHE_NAMES = listOf("drums", "bass", "other", "vocals", "guitar", "piano")

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

private fun DesktopStemMix.isNeutral(): Boolean = workerGains().all { gain -> abs(gain - 1f) <= 0.001f }

private fun Float.isStemPlaybackRateSupported(): Boolean = isFinite() && abs(this - 1f) <= 0.001f

private fun formatModelBytes(bytes: Long): String =
    if (bytes >= 1024L * 1024L) {
        "%.1f MiB".format(java.util.Locale.ROOT, bytes.toDouble() / (1024.0 * 1024.0))
    } else {
        "${bytes.coerceAtLeast(0L) / 1024L} KiB"
    }

internal data class DesktopStemModelManifest(
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String,
)

internal fun parseDesktopStemModelManifest(json: JSONObject): DesktopStemModelManifest =
    DesktopStemModelManifest(
        version = json.getString("version").trim(),
        file = json.getString("file").trim(),
        size = json.getLong("size"),
        sha256 = json.getString("sha256").trim().lowercase(),
    )

internal fun validateDesktopStemModelManifest(manifest: DesktopStemModelManifest): DesktopStemModelManifest {
    check(manifest.version == STEM_MODEL_VERSION) {
        "Model manifest version ${manifest.version} does not match $STEM_MODEL_VERSION"
    }
    check(manifest.file == STEM_MODEL_NAME) {
        "Model manifest file ${manifest.file} does not match $STEM_MODEL_NAME"
    }
    check(manifest.size == STEM_MODEL_SIZE_BYTES) {
        "Model manifest size ${manifest.size} does not match $STEM_MODEL_SIZE_BYTES"
    }
    check(manifest.sha256 == STEM_MODEL_SHA256) {
        "Model manifest SHA-256 does not match the pinned ArchiveTune model"
    }
    return manifest
}
