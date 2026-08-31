/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.logging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.chords.ChordEvent
import moe.rukamori.archivetune.chords.ChordRequest
import moe.rukamori.archivetune.chords.ChordTrack
import moe.rukamori.archivetune.chords.ChordTrackSource
import moe.rukamori.archivetune.chords.ChordUnavailableException
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.desktop.VlcjPlaybackController
import org.json.JSONObject

/**
 * Lazy whole-mix chord recognition through ArchiveTune's shared ONNX/DirectML sidecar.
 *
 * The heavy model is not bundled with the application. It is hash-pinned in the shared model
 * manifest and downloaded only when Songsterr has no authored chord annotations.
 */
internal class DesktopNeuralChordAnalyzer(
    private val runtime: DesktopRuntime,
    private val playback: VlcjPlaybackController,
) {
    suspend fun analyze(
        item: PlaybackItem,
        request: ChordRequest,
    ): Result<ChordTrack> =
        withContext(Dispatchers.IO) {
            try {
                readCached(item, request)?.let { return@withContext Result.success(it) }
                val runner =
                    DesktopModelRuntime.findRunner()
                        ?: throw ChordUnavailableException("Local chord model runner is unavailable")
                val bundle = DesktopModelRuntime.ensureModel(runtime.paths, DesktopModels.CHORDS)

                val directory = chordCacheDirectory()
                Files.createDirectories(directory)
                val key = cacheKey(item)
                val input = directory.resolve("." + key + ".input.wav")
                val output = directory.resolve("." + key + ".result.json")
                try {
                    Files.deleteIfExists(input)
                    Files.deleteIfExists(output)
                    playback.decodeAnalysisInput(
                        item = item,
                        output = input,
                        sampleRate = 22_050,
                        channels = 2,
                    )
                    currentCoroutineContext().ensureActive()
                    runModel(
                        runner = runner,
                        model = bundle.model,
                        plan = bundle.asset("cqtPlan"),
                        config = bundle.asset("config"),
                        input = input,
                        output = output,
                    )
                    currentCoroutineContext().ensureActive()
                    val track = parseResult(output, request)
                    writeCache(item, track)
                    Result.success(track)
                } finally {
                    runCatching { Files.deleteIfExists(input) }
                    runCatching { Files.deleteIfExists(output) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runtime.logger.log(Level.FINE, "Local neural chord analysis unavailable for " + item.metadata.id, error)
                Result.failure(error)
            }
        }

    private suspend fun runModel(
        runner: Path,
        model: Path,
        plan: Path,
        config: Path,
        input: Path,
        output: Path,
    ) {
        val builder =
            ProcessBuilder(
                runner.toString(),
                "chords",
                "--model", model.toString(),
                "--plan", plan.toString(),
                "--config", config.toString(),
                "--input", input.toString(),
                "--output", output.toString(),
            )
                .directory(runner.parent?.toFile())
                .redirectErrorStream(true)
        DesktopModelRuntime.configureRunnerEnvironment(builder, runner)
        val process = builder.start()
        val cancellationHandle =
            currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause is CancellationException && process.isAlive) {
                    runCatching { process.destroy() }
                    runCatching { if (process.isAlive) process.destroyForcibly() }
                }
            }
        var workerError: String? = null
        try {
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    if (event.optString("type") == "error") {
                        workerError = event.optString("message").takeIf(String::isNotBlank)
                    }
                }
            }
            val exit = process.waitFor()
            if (exit != 0 || !Files.isRegularFile(output)) {
                throw IllegalStateException(workerError ?: "Chord model runner exited with code " + exit)
            }
        } finally {
            cancellationHandle.dispose()
            if (process.isAlive) {
                runCatching { process.destroy() }
                runCatching { if (process.isAlive) process.destroyForcibly() }
            }
        }
    }

    private fun parseResult(
        path: Path,
        request: ChordRequest,
    ): ChordTrack =
        parseResult(JSONObject(Files.readString(path, StandardCharsets.UTF_8)), request)

    private fun parseResult(
        root: JSONObject,
        request: ChordRequest,
    ): ChordTrack {
        val eventsJson = root.getJSONArray("events")
        val events =
            buildList {
                for (index in 0 until eventsJson.length()) {
                    val event = eventsJson.getJSONObject(index)
                    val start = event.getLong("start_ms").coerceAtLeast(0L)
                    val end = event.getLong("end_ms").coerceAtLeast(start + 1L)
                    val chord = event.getString("chord").trim()
                    if (chord.isBlank()) continue
                    add(
                        ChordEvent(
                            startMs = start,
                            endMs = end,
                            name = chord,
                            confidence = event.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
                        ),
                    )
                }
            }
        if (events.isEmpty()) throw ChordUnavailableException("The local chord model found no reliable chords")
        return ChordTrack(
            songsterrSongId = 0,
            title = request.title,
            artist = request.artist,
            guitarTrackName = null,
            source = ChordTrackSource.LOCAL_NEURAL,
            events = events,
            tabDurationMs = root.optLong("duration_ms", request.durationMs ?: events.last().endMs),
        )
    }

    private fun readCached(
        item: PlaybackItem,
        request: ChordRequest,
    ): ChordTrack? {
        val path = cacheFile(item)
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val root = JSONObject(Files.readString(path, StandardCharsets.UTF_8))
            if (root.optString("modelVersion") != DesktopModels.CHORDS.version) return@runCatching null
            parseResult(root.getJSONObject("result"), request)
        }.getOrNull()
    }

    private fun writeCache(
        item: PlaybackItem,
        track: ChordTrack,
    ) {
        val path = cacheFile(item)
        Files.createDirectories(path.parent)
        val result =
            JSONObject()
                .put("duration_ms", track.tabDurationMs)
                .put(
                    "events",
                    track.events.map { event ->
                        JSONObject()
                            .put("start_ms", event.startMs)
                            .put("end_ms", event.endMs)
                            .put("chord", event.name)
                            .put("confidence", event.confidence)
                    },
                )
        val root =
            JSONObject()
                .put("modelVersion", DesktopModels.CHORDS.version)
                .put("result", result)
        val temporary = path.resolveSibling("." + path.fileName + ".part")
        Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun chordCacheDirectory(): Path = runtime.paths.cache.resolve("chords")

    private fun cacheFile(item: PlaybackItem): Path =
        chordCacheDirectory().resolve(cacheKey(item) + ".json")

    private fun cacheKey(item: PlaybackItem): String {
        val identity =
            DesktopModels.CHORDS.version + "\u0000" +
                item.metadata.id + "\u0000" +
                (item.metadata.durationHintMs ?: -1L)
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
