/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audio

import android.content.Context
import androidx.media3.common.MediaItem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.sdk.audio.BeatAnalysis
import moe.rukamori.archivetune.sdk.audio.BeatTracker
import timber.log.Timber

internal data class AndroidBeatTrack(
    val mediaItem: MediaItem,
    val durationMs: Long?,
) {
    val identity: String = androidBeatAnalysisIdentity(mediaItem, durationMs)
}

internal data class AndroidBeatGrid(
    val identity: String,
    val analysis: BeatAnalysis,
)

internal class AndroidBeatAnalysisRepository(
    context: Context,
    private val resolveInput: suspend (MediaItem) -> AndroidBeatAnalysisInput?,
) {
    private val decoder = AndroidBeatAudioDecoder(context.applicationContext)
    private val cache = AndroidBeatAnalysisCache(File(context.applicationContext.cacheDir, CACHE_DIRECTORY))
    private val expensiveAnalysisMutex = Mutex()
    private val memoryLock = Any()
    private val memory =
        object : LinkedHashMap<String, BeatAnalysis>(MEMORY_ENTRIES + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BeatAnalysis>?): Boolean =
                size > MEMORY_ENTRIES
        }

    suspend fun analyze(track: AndroidBeatTrack): BeatAnalysis? {
        synchronized(memoryLock) { memory[track.identity] }?.let { return it }
        withContext(Dispatchers.IO) { cache.read(track.identity, track.durationMs) }?.let { cached ->
            synchronized(memoryLock) { memory[track.identity] = cached }
            return cached
        }

        return expensiveAnalysisMutex.withLock {
            currentCoroutineContext().ensureActive()
            synchronized(memoryLock) { memory[track.identity] }?.let { return@withLock it }
            withContext(Dispatchers.IO) { cache.read(track.identity, track.durationMs) }?.let { cached ->
                synchronized(memoryLock) { memory[track.identity] = cached }
                return@withLock cached
            }

            try {
                val input = resolveInput(track.mediaItem) ?: return@withLock null
                currentCoroutineContext().ensureActive()
                val pcm = withContext(Dispatchers.IO) { decoder.decode(input) }
                currentCoroutineContext().ensureActive()
                val analysis = withContext(Dispatchers.Default) { BeatTracker.analyze(pcm) } ?: return@withLock null
                currentCoroutineContext().ensureActive()
                synchronized(memoryLock) { memory[track.identity] = analysis }
                withContext(Dispatchers.IO) { cache.write(track.identity, track.durationMs, analysis) }
                analysis
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Timber.tag(LOG_TAG).d(error, "Beat analysis unavailable for %s", track.mediaItem.mediaId)
                null
            }
        }
    }

    private companion object {
        const val CACHE_DIRECTORY = "audio-analysis/android-beats-v1"
        const val MEMORY_ENTRIES = 24
        const val LOG_TAG = "BeatAnalysis"
    }
}

internal class AndroidBeatAnalysisCache(private val directory: File) {
    fun read(identity: String, expectedDurationMs: Long?): BeatAnalysis? =
        runCatching {
            val file = fileFor(identity)
            if (!file.isFile) return null
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != CACHE_MAGIC || input.readInt() != CACHE_VERSION) return null
                val storedDuration = input.readLong()
                val expected = expectedDurationMs ?: UNKNOWN_DURATION
                if (storedDuration != expected) return null
                val analysisDuration = input.readLong()
                val bpm = input.readFloat()
                val count = input.readInt()
                if (count !in 2..MAX_CACHED_BEATS || !bpm.isFinite() || bpm <= 0f || analysisDuration <= 0L) return null
                val beats = FloatArray(count)
                var previous = -1f
                for (index in beats.indices) {
                    val beat = input.readFloat()
                    if (!beat.isFinite() || beat < 0f || beat <= previous) return null
                    beats[index] = beat
                    previous = beat
                }
                BeatAnalysis(beats, bpm, analysisDuration)
            }
        }.getOrNull()

    fun write(identity: String, expectedDurationMs: Long?, analysis: BeatAnalysis) {
        if (analysis.beatsSeconds.size < 2) return
        directory.mkdirs()
        val target = fileFor(identity)
        val temporary = File(directory, ".${target.name}.${System.nanoTime()}.part")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeInt(CACHE_VERSION)
                output.writeLong(expectedDurationMs ?: UNKNOWN_DURATION)
                output.writeLong(analysis.durationMs)
                output.writeFloat(analysis.bpm)
                output.writeInt(analysis.beatsSeconds.size)
                analysis.beatsSeconds.forEach(output::writeFloat)
            }
            if (target.exists() && !target.delete()) error("Could not replace beat analysis cache")
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } finally {
            temporary.delete()
        }
    }

    private fun fileFor(identity: String): File {
        val versioned = "$ANALYZER_VERSION\u0000$identity"
        val digest = MessageDigest.getInstance("SHA-256").digest(versioned.toByteArray(StandardCharsets.UTF_8))
        val key = digest.take(20).joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$key.bin")
    }

    private companion object {
        const val ANALYZER_VERSION = 1
        const val CACHE_MAGIC = 0x41544254
        const val CACHE_VERSION = 1
        const val MAX_CACHED_BEATS = 1_000_000
        const val UNKNOWN_DURATION = -1L
    }
}

internal fun androidBeatAnalysisIdentity(mediaItem: MediaItem, durationMs: Long?): String {
    val configuration = mediaItem.localConfiguration
    val stableId = configuration?.customCacheKey?.takeIf(String::isNotBlank) ?: mediaItem.mediaId
    val uri = configuration?.uri
    val localSource =
        uri?.scheme?.lowercase()
            ?.takeIf { it == "content" || it == "file" || it == "android.resource" }
            ?.let { uri.toString() }
    return buildString {
        append(stableId)
        append('|')
        append(durationMs ?: -1L)
        if (localSource != null) {
            append('|')
            append(localSource)
        }
    }
}
