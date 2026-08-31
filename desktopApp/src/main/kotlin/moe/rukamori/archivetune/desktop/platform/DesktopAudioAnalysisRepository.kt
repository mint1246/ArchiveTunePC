package moe.rukamori.archivetune.desktop.platform

import moe.rukamori.archivetune.desktop.analysis.BeatKeyAnalysis
import moe.rukamori.archivetune.desktop.analysis.DesktopAudioAnalyzer
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.desktop.VlcjPlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Persistent desktop song-analysis cache shared by beat-driven features.
 *
 * Playback media is decoded once to deterministic 44.1 kHz mono PCM off the UI thread, then the
 * existing Essentia binding produces the beat grid. Subsequent requests use the compact binary
 * cache and never decode or analyze the song again.
 */
class DesktopAudioAnalysisRepository(
    paths: AppPaths,
    private val playback: VlcjPlaybackController,
    private val logger: Logger,
) {
    private val cacheDirectory = paths.cache.resolve("audio-analysis").resolve("v1")
    private val memory = ConcurrentHashMap<String, BeatKeyAnalysis>()
    @Volatile private var analyzer: DesktopAudioAnalyzer? = null

    suspend fun beatKeyAnalysis(item: PlaybackItem): BeatKeyAnalysis? = withContext(Dispatchers.IO) {
        val identity = analysisIdentity(item)
        memory[identity]?.let { return@withContext it }

        val cacheFile = cacheDirectory.resolve("${sha256(identity)}.bin")
        readCache(cacheFile)?.let { cached ->
            memory[identity] = cached
            return@withContext cached
        }

        Files.createDirectories(cacheDirectory)
        val input = Files.createTempFile(cacheDirectory, ".analysis-", ".wav")
        try {
            playback.decodeAnalysisInput(item, input, sampleRate = SAMPLE_RATE, channels = 1)
            val result = analyzePcm16MonoWav(input)
            memory[identity] = result
            writeCache(cacheFile, result)
            result
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            logger.fine("beat analysis unavailable for ${item.metadata.id}: ${error.message}")
            null
        } finally {
            runCatching { Files.deleteIfExists(input) }
        }
    }

    internal fun cachedAnalysis(item: PlaybackItem): BeatKeyAnalysis? = memory[analysisIdentity(item)]

    private fun analyzePcm16MonoWav(path: Path): BeatKeyAnalysis {
        val activeAnalyzer = analyzer ?: synchronized(this) {
            analyzer ?: DesktopAudioAnalyzer().also { analyzer = it }
        }
        return activeAnalyzer.createBeatKeySession().use { session ->
            BufferedInputStream(Files.newInputStream(path), WAV_BUFFER_BYTES).use { input ->
                val riffHeader = ByteArray(12)
                input.readFully(riffHeader)
                require(String(riffHeader, 0, 4, StandardCharsets.US_ASCII) == "RIFF") { "analysis WAV is not RIFF" }
                require(String(riffHeader, 8, 4, StandardCharsets.US_ASCII) == "WAVE") { "analysis WAV is not WAVE" }

                var formatSeen = false
                val chunkHeader = ByteArray(8)
                while (true) {
                    input.readFully(chunkHeader)
                    val chunkName = String(chunkHeader, 0, 4, StandardCharsets.US_ASCII)
                    val chunkSize = littleEndianInt(chunkHeader, 4).toLong() and 0xffff_ffffL
                    when (chunkName) {
                        "fmt " -> {
                            require(chunkSize in 16..1_024) { "unsupported WAV format chunk size: $chunkSize" }
                            val format = ByteArray(chunkSize.toInt())
                            input.readFully(format)
                            require(littleEndianShort(format, 0) == 1) { "analysis WAV must be PCM" }
                            require(littleEndianShort(format, 2) == 1) { "analysis WAV must be mono" }
                            require(littleEndianInt(format, 4) == SAMPLE_RATE) { "analysis WAV must be 44.1 kHz" }
                            require(littleEndianShort(format, 14) == 16) { "analysis WAV must be PCM16" }
                            formatSeen = true
                            if ((chunkSize and 1L) != 0L) input.skipFully(1)
                        }
                        "data" -> {
                            require(formatSeen) { "analysis WAV data precedes its format chunk" }
                            appendPcm16Data(input, chunkSize, session::append)
                            if ((chunkSize and 1L) != 0L) input.skipFully(1)
                            break
                        }
                        else -> input.skipFully(chunkSize + (chunkSize and 1L))
                    }
                }
            }
            session.analyze()
        }
    }

    private fun appendPcm16Data(
        input: BufferedInputStream,
        byteCount: Long,
        append: (FloatArray, Int, Int) -> Unit,
    ) {
        val bytes = ByteArray(PCM_CHUNK_SAMPLES * 2)
        val samples = FloatArray(PCM_CHUNK_SAMPLES)
        var remaining = byteCount
        while (remaining > 0L) {
            val wanted = minOf(bytes.size.toLong(), remaining).toInt()
            require(wanted % 2 == 0) { "analysis WAV contains a partial PCM16 sample" }
            input.readFully(bytes, 0, wanted)
            val sampleCount = wanted / 2
            var sampleIndex = 0
            var byteIndex = 0
            while (sampleIndex < sampleCount) {
                val value = ((bytes[byteIndex].toInt() and 0xff) or (bytes[byteIndex + 1].toInt() shl 8)).toShort()
                samples[sampleIndex] = value / 32768f
                sampleIndex++
                byteIndex += 2
            }
            append(samples, 0, sampleCount)
            remaining -= wanted
        }
    }

    private fun readCache(path: Path): BeatKeyAnalysis? = runCatching {
        if (!Files.isRegularFile(path)) return null
        DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { input ->
            if (input.readInt() != CACHE_MAGIC || input.readInt() != CACHE_VERSION) return null
            val bpm = input.readDouble()
            val key = input.readUTF()
            val mode = input.readUTF()
            val keyStrength = input.readFloat()
            val count = input.readInt()
            require(count in 0..MAX_CACHED_BEATS)
            val beats = FloatArray(count)
            var previous = -1f
            for (index in beats.indices) {
                val beat = input.readFloat()
                require(beat.isFinite() && beat >= 0f && beat > previous)
                beats[index] = beat
                previous = beat
            }
            BeatKeyAnalysis(
                bpm = bpm,
                beatsSeconds = beats,
                key = key,
                mode = mode,
                keyStrength = keyStrength,
            )
        }
    }.getOrNull()

    private fun writeCache(path: Path, result: BeatKeyAnalysis) {
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeInt(CACHE_VERSION)
                output.writeDouble(result.bpm)
                output.writeUTF(result.key)
                output.writeUTF(result.mode)
                output.writeFloat(result.keyStrength)
                output.writeInt(result.beatsSeconds.size)
                result.beatsSeconds.forEach(output::writeFloat)
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val WAV_BUFFER_BYTES = 128 * 1_024
        private const val PCM_CHUNK_SAMPLES = 32 * 1_024
        private const val CACHE_MAGIC = 0x41544241
        private const val CACHE_VERSION = 1
        private const val MAX_CACHED_BEATS = 1_000_000

        internal fun analysisIdentity(item: PlaybackItem): String = buildString {
            append(item.metadata.id)
            append('|')
            append(item.metadata.durationHintMs ?: -1L)
            append('|')
            when (val source = item.source) {
                is PlaybackSource.Deferred -> {
                    append("deferred:")
                    append(source.key)
                }
                is PlaybackSource.LocalFile -> {
                    append("local:")
                    val path = runCatching { Path.of(source.path).toAbsolutePath().normalize() }.getOrNull()
                    append(path ?: source.path)
                    if (path != null && Files.isRegularFile(path)) {
                        append(':')
                        append(runCatching { Files.size(path) }.getOrDefault(-1L))
                        append(':')
                        append(runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(-1L))
                    }
                }
                is PlaybackSource.Remote -> {
                    append("remote:")
                    append(source.url.substringBefore('?'))
                }
            }
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }

        private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
            ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

        private fun BufferedInputStream.readFully(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            var consumed = 0
            while (consumed < length) {
                val read = read(bytes, offset + consumed, length - consumed)
                if (read < 0) throw EOFException("unexpected end of analysis WAV")
                consumed += read
            }
        }

        private fun BufferedInputStream.skipFully(count: Long) {
            var remaining = count
            while (remaining > 0L) {
                val skipped = skip(remaining)
                if (skipped > 0L) {
                    remaining -= skipped
                } else if (read() >= 0) {
                    remaining--
                } else {
                    throw EOFException("unexpected end of analysis WAV")
                }
            }
        }
    }
}
