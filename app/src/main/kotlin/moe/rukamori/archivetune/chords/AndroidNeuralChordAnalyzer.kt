/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.chords

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.media3.common.MediaItem
import java.io.File
import java.nio.FloatBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.modelruntime.AndroidOnnxModelContract
import moe.rukamori.archivetune.modelruntime.AndroidOnnxRuntime
import moe.rukamori.archivetune.modelruntime.AndroidOnnxSession
import moe.rukamori.archivetune.utils.StreamClientUtils
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.retryWithoutPlaybackLoginContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** Whole-mix ChordMini analysis using ArchiveTune's accelerated-only Android ONNX runtime. */
internal class AndroidNeuralChordAnalyzer(
    context: Context,
    private val modelStore: AndroidChordModelStore =
        AndroidChordModelStore(File(context.applicationContext.noBackupFilesDir, "models")),
    private val runtime: AndroidOnnxRuntime = AndroidOnnxRuntime(),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val decoder = AndroidChordAudioDecoder(appContext)
    private val analysisMutex = Mutex()
    @Volatile private var session: AndroidOnnxSession? = null
    @Volatile private var closed = false

    suspend fun analyze(
        mediaItem: MediaItem?,
        request: ChordRequest,
        forceRecompute: Boolean = false,
    ): Result<ChordTrack> =
        try {
            analysisMutex.withLock {
                check(!closed) { "Local chord analyzer is closed" }
                val trackId = analysisTrackId(mediaItem, request)
                if (!forceRecompute) {
                    withContext(Dispatchers.IO) { readCache(trackId, request) }
                        ?.let { return@withLock Result.success(it) }
                }

                val bundle = modelStore.ensureBundle()
                currentCoroutineContext().ensureActive()
                val config =
                    withContext(Dispatchers.IO) {
                        AndroidChordConfig.parseAndValidate(bundle.config.readText(Charsets.UTF_8))
                    }
                val plan =
                    withContext(Dispatchers.IO) {
                        AndroidChordCqtPlan.decode(bundle.cqtPlan.readBytes()).validate()
                    }
                val activeSession = withContext(Dispatchers.Default) { ensureSession(bundle.model) }
                val input = resolveAudioInput(mediaItem, request)
                val audio = withContext(Dispatchers.IO) { decoder.decode(input) }
                currentCoroutineContext().ensureActive()
                val durationMs = audio.size.toLong() * 1_000L / AndroidChordModelSpec.SAMPLE_RATE
                val coroutineContext = currentCoroutineContext()
                val features =
                    withContext(Dispatchers.Default) {
                        computeAndroidChordCqt(audio, plan) { coroutineContext.ensureActive() }
                    }
                val frameCount = features.size / AndroidChordModelSpec.INPUT_BINS
                if (frameCount == 0) throw ChordUnavailableException("Local chord analysis produced no CQT frames")
                val windowCount =
                    (frameCount + AndroidChordModelSpec.SEQUENCE_LENGTH - 1) / AndroidChordModelSpec.SEQUENCE_LENGTH
                val padded =
                    FloatArray(
                        windowCount * AndroidChordModelSpec.SEQUENCE_LENGTH * AndroidChordModelSpec.INPUT_BINS,
                    )
                features.copyInto(padded)
                currentCoroutineContext().ensureActive()
                val logits = withContext(Dispatchers.Default) { runModel(activeSession, padded, windowCount) }
                currentCoroutineContext().ensureActive()
                val events =
                    withContext(Dispatchers.Default) {
                        val predictions = smoothAndroidChordArgmax(logits, frameCount, windowCount)
                        androidChordPredictionsToEvents(
                            predictions = predictions,
                            logits = logits,
                            frameCount = frameCount,
                            vocabulary = config.chordVocab,
                            durationMs = durationMs,
                        )
                    }
                if (events.isEmpty()) throw ChordUnavailableException("The local chord model found no reliable chords")
                val track =
                    ChordTrack(
                        songsterrSongId = 0,
                        title = request.title,
                        artist = request.artist,
                        guitarTrackName = null,
                        source = ChordTrackSource.LOCAL_NEURAL,
                        events = events,
                        tabDurationMs = durationMs,
                    )
                withContext(Dispatchers.IO) { writeCache(trackId, request, track) }
                Result.success(track)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Timber.tag(LOG_TAG).w(error, "Local ChordMini analysis unavailable")
            Result.failure(error)
        } finally {
            if (closed && !analysisMutex.isLocked) closeSession()
        }

    override fun close() {
        if (closed) return
        closed = true
        // Never tear down an ORT session while a cancelled Compose effect is still unwinding from
        // inference. The analyze() finally block closes it immediately after the lease drains.
        if (!analysisMutex.isLocked) closeSession()
    }

    @Synchronized
    private fun closeSession() {
        val active = session ?: return
        session = null
        active.close()
    }

    private fun ensureSession(model: File): AndroidOnnxSession {
        session?.let { return it }
        return runtime
            .createAcceleratedSession(
                model = model,
                contract =
                    AndroidOnnxModelContract(
                        expectedInputs = setOf("features"),
                        expectedOutputs = setOf("logits"),
                    ),
            ).also { session = it }
    }

    private fun runModel(
        session: AndroidOnnxSession,
        padded: FloatArray,
        windowCount: Int,
    ): FloatArray {
        val environment = OrtEnvironment.getEnvironment()
        val inputPerWindow = AndroidChordModelSpec.SEQUENCE_LENGTH * AndroidChordModelSpec.INPUT_BINS
        val outputPerWindow = AndroidChordModelSpec.SEQUENCE_LENGTH * AndroidChordModelSpec.OUTPUT_CLASSES
        check(padded.size == windowCount * inputPerWindow) {
            "Unexpected padded chord feature size ${padded.size} for $windowCount windows"
        }
        val logits = FloatArray(windowCount * outputPerWindow)
        val shape =
            longArrayOf(
                1L,
                AndroidChordModelSpec.SEQUENCE_LENGTH.toLong(),
                AndroidChordModelSpec.INPUT_BINS.toLong(),
            )
        for (windowIndex in 0 until windowCount) {
            val inputOffset = windowIndex * inputPerWindow
            val input = FloatBuffer.wrap(padded, inputOffset, inputPerWindow).slice()
            OnnxTensor.createTensor(environment, input, shape).use { tensor ->
                session.run(mapOf("features" to tensor)).use { result ->
                    val value = result.get("logits").orElseThrow { IllegalStateException("ChordMini did not return logits") }
                    val output = value as? OnnxTensor ?: error("ChordMini logits output is not a tensor")
                    val buffer = output.floatBuffer.duplicate().apply { rewind() }
                    check(buffer.remaining() == outputPerWindow) {
                        "Unexpected chord model output size ${buffer.remaining()} (expected $outputPerWindow)"
                    }
                    buffer.get(logits, windowIndex * outputPerWindow, outputPerWindow)
                }
            }
        }
        return logits
    }

    private suspend fun resolveAudioInput(
        mediaItem: MediaItem?,
        request: ChordRequest,
    ): AndroidChordAudioInput {
        val configured = mediaItem?.localConfiguration?.uri
        if (configured != null && configured.scheme != null) {
            return AndroidChordAudioInput(configured)
        }
        val videoId =
            mediaItem?.mediaId?.trim()?.takeIf(String::isNotEmpty)
                ?: request.videoId?.trim()?.takeIf(String::isNotEmpty)
                ?: throw ChordUnavailableException("No playable audio source is available for local chord analysis")
        val connectivity = appContext.getSystemService<ConnectivityManager>()
            ?: throw ChordUnavailableException("Network service is unavailable")
        val playback =
            appContext
                .retryWithoutPlaybackLoginContext {
                    YTPlayerUtils.playerResponseForDownload(
                        videoId = videoId,
                        audioQuality = AudioQuality.LOW,
                        connectivityManager = connectivity,
                    )
                }.getOrThrow()
        val profile = StreamClientUtils.resolveRequestProfile(playback.streamUrl)
        val headers = buildMap {
            put("User-Agent", profile.userAgent)
            profile.origin?.let { put("Origin", it) }
            profile.referer?.let { put("Referer", it) }
        }
        return AndroidChordAudioInput(Uri.parse(playback.streamUrl), headers)
    }

    private fun analysisTrackId(mediaItem: MediaItem?, request: ChordRequest): String =
        mediaItem?.mediaId?.takeIf(String::isNotBlank)
            ?: request.videoId?.takeIf(String::isNotBlank)
            ?: "${request.artist}\u0000${request.title}"

    private fun cacheFile(trackId: String, durationMs: Long?): File {
        val identity =
            AndroidChordModelSpec.VERSION + "\u0000" + trackId + "\u0000" + (durationMs ?: -1L)
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        val key = digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(File(appContext.cacheDir, "chords"), "$key.json")
    }

    private fun readCache(trackId: String, request: ChordRequest): ChordTrack? {
        val file = cacheFile(trackId, request.durationMs)
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optString("modelVersion") != AndroidChordModelSpec.VERSION) return@runCatching null
            parseCachedResult(root.getJSONObject("result"), request)
        }.getOrNull()
    }

    private fun writeCache(
        trackId: String,
        request: ChordRequest,
        track: ChordTrack,
    ) {
        val file = cacheFile(trackId, request.durationMs)
        file.parentFile?.mkdirs()
        val events = JSONArray()
        track.events.forEach { event ->
            events.put(
                JSONObject()
                    .put("start_ms", event.startMs)
                    .put("end_ms", event.endMs)
                    .put("chord", event.name)
                    .put("confidence", event.confidence.toDouble()),
            )
        }
        val result = JSONObject().put("duration_ms", track.tabDurationMs).put("events", events)
        val root = JSONObject().put("modelVersion", AndroidChordModelSpec.VERSION).put("result", result)
        val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.part")
        temporary.writeText(root.toString(), Charsets.UTF_8)
        if (file.exists() && !file.delete()) error("Could not replace chord cache")
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun parseCachedResult(root: JSONObject, request: ChordRequest): ChordTrack {
        val eventsJson = root.getJSONArray("events")
        val events = buildList {
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
        if (events.isEmpty()) throw ChordUnavailableException("Cached local chord result is empty")
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

    private companion object {
        const val LOG_TAG = "OnnxRuntime"
    }
}

internal suspend fun resolveAndroidChordPriority(
    authored: suspend () -> Result<ChordTrack>,
    localNeural: suspend () -> Result<ChordTrack>,
    derived: suspend () -> Result<ChordTrack>,
): Result<ChordTrack> {
    val authoredResult = authored()
    authoredResult.getOrNull()?.let { return Result.success(it) }
    val localResult = localNeural()
    localResult.getOrNull()?.let { return Result.success(it) }
    val derivedResult = derived()
    derivedResult.getOrNull()?.let { return Result.success(it) }
    val failures =
        listOfNotNull(
            authoredResult.exceptionOrNull()?.message,
            localResult.exceptionOrNull()?.message,
            derivedResult.exceptionOrNull()?.message,
        ).distinct()
    return Result.failure(
        ChordUnavailableException(
            failures.lastOrNull() ?: "No authored, local neural, or derived chords were available",
        ),
    )
}
