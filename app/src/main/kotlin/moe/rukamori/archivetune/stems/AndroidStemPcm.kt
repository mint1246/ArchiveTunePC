/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class AndroidStemInput(
    val uri: Uri,
    val requestHeaders: Map<String, String> = emptyMap(),
)

internal data class AndroidCanonicalPcm(
    val file: File,
    val frames: Int,
)

/** Android MediaExtractor/MediaCodec decode followed by deterministic stereo 44.1 kHz resampling. */
internal class AndroidStemPcmDecoder(private val context: Context) {
    suspend fun decode(
        input: AndroidStemInput,
        outputWav: File,
    ): AndroidCanonicalPcm {
        outputWav.parentFile?.mkdirs()
        val raw = File(outputWav.parentFile, ".${outputWav.name}.source-pcm.part")
        raw.delete()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            setExtractorDataSource(extractor, input)
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("Input contains no decodable audio track")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = checkNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            var wroteFrames = 0L
            FileOutputStream(raw).buffered(256 * 1024).use { rawOutput ->
                val info = MediaCodec.BufferInfo()
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = checkNotNull(codec.getInputBuffer(inputIndex))
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val newChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            check(wroteFrames == 0L || (newRate == sampleRate && newChannels == channelCount)) {
                                "Decoder changed PCM format mid-stream"
                            }
                            sampleRate = newRate
                            channelCount = newChannels
                            pcmEncoding =
                                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                } else {
                                    AudioFormat.ENCODING_PCM_16BIT
                                }
                        }

                        else -> if (outputIndex >= 0) {
                            if (info.size > 0) {
                                val buffer = checkNotNull(codec.getOutputBuffer(outputIndex)).duplicate().order(ByteOrder.nativeOrder())
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                wroteFrames += writeStereoPcm16(buffer.slice().order(ByteOrder.nativeOrder()), pcmEncoding, channelCount, rawOutput)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            check(wroteFrames > 0) { "Audio decoder produced no PCM" }
            val targetFrames = resampleStereoPcm16ToWav(raw, sampleRate, wroteFrames, outputWav)
            return AndroidCanonicalPcm(outputWav, targetFrames)
        } finally {
            raw.delete()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun setExtractorDataSource(extractor: MediaExtractor, input: AndroidStemInput) {
        val scheme = input.uri.scheme?.lowercase()
        if (scheme == "content" || scheme == "file" || scheme == "android.resource") {
            extractor.setDataSource(context, input.uri, input.requestHeaders)
        } else {
            extractor.setDataSource(input.uri.toString(), input.requestHeaders)
        }
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
    }
}

private fun writeStereoPcm16(
    buffer: ByteBuffer,
    encoding: Int,
    channels: Int,
    output: java.io.OutputStream,
): Int {
    require(channels > 0)
    val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
    val frameBytes = bytesPerSample * channels
    val frames = buffer.remaining() / frameBytes
    val encoded = ByteArray(frames * 4)
    var write = 0
    repeat(frames) {
        var left = 0f
        var right = 0f
        repeat(channels) { channel ->
            val sample =
                when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                    else -> error("Unsupported decoder PCM encoding $encoding")
                }
            if (channel == 0) left = sample
            if (channel == 1) right = sample
        }
        if (channels == 1) right = left
        val l = floatToPcm16(left)
        val r = floatToPcm16(right)
        encoded[write++] = l.toByte()
        encoded[write++] = (l.toInt() ushr 8).toByte()
        encoded[write++] = r.toByte()
        encoded[write++] = (r.toInt() ushr 8).toByte()
    }
    output.write(encoded)
    return frames
}

private suspend fun resampleStereoPcm16ToWav(
    raw: File,
    sourceRate: Int,
    sourceFrames: Long,
    destination: File,
): Int {
    require(sourceRate > 0 && sourceFrames > 0)
    val targetFrames = ((sourceFrames.toDouble() * AndroidDemucsContract.SAMPLE_RATE / sourceRate).roundToInt()).coerceAtLeast(1)
    Pcm16WavOutput(destination, AndroidDemucsContract.SAMPLE_RATE, 2).use { wav ->
        RandomAccessFile(raw, "r").use { input ->
            fun readFrame(): IntArray {
                val b0 = input.read()
                val b1 = input.read()
                val b2 = input.read()
                val b3 = input.read()
                if (b3 < 0) return intArrayOf(0, 0)
                return intArrayOf(((b1 shl 8) or b0).toShort().toInt(), ((b3 shl 8) or b2).toShort().toInt())
            }
            var sourceIndex = 0L
            var current = readFrame()
            var next = if (sourceFrames > 1) readFrame() else current
            for (targetIndex in 0 until targetFrames) {
                if (targetIndex and 0x3fff == 0) currentCoroutineContext().ensureActive()
                val sourcePosition = targetIndex.toDouble() * sourceRate / AndroidDemucsContract.SAMPLE_RATE
                val wanted = sourcePosition.toLong().coerceAtMost(sourceFrames - 1)
                while (sourceIndex < wanted) {
                    current = next
                    sourceIndex++
                    next = if (sourceIndex + 1 < sourceFrames) readFrame() else current
                }
                val fraction = (sourcePosition - wanted).toFloat().coerceIn(0f, 1f)
                val left = current[0] + (next[0] - current[0]) * fraction
                val right = current[1] + (next[1] - current[1]) * fraction
                wav.writePcm16(left.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(), right.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
    }
    return targetFrames
}

internal class CanonicalPcm16WavReader(private val file: File) : AutoCloseable {
    private val input = RandomAccessFile(file, "r")
    val frames: Int

    init {
        val header = ByteArray(44)
        input.readFully(header)
        check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a canonical WAV" }
        check(readLe16(header, 20) == 1 && readLe16(header, 22) == 2) { "Canonical WAV must be stereo PCM" }
        check(readLe32(header, 24) == AndroidDemucsContract.SAMPLE_RATE) { "Canonical WAV must be 44.1 kHz" }
        check(readLe16(header, 34) == 16 && String(header, 36, 4, Charsets.US_ASCII) == "data") { "Canonical WAV must be 16-bit PCM with a 44-byte header" }
        frames = readLe32(header, 40) / 4
        check(frames > 0 && file.length() >= 44L + frames * 4L) { "Canonical WAV is truncated" }
    }

    fun normalization(): Pair<Float, Float> {
        var sum = 0.0
        forEachFrame { left, right -> sum += (left + right) * 0.5 }
        val mean = sum / frames
        var variance = 0.0
        forEachFrame { left, right ->
            val mono = (left + right) * 0.5
            variance += (mono - mean) * (mono - mean)
        }
        val std = kotlin.math.sqrt(variance / (frames - 1).coerceAtLeast(1)).coerceAtLeast(1e-12)
        return mean.toFloat() to std.toFloat()
    }

    fun fillNormalized(
        startFrame: Int,
        endFrame: Int,
        mean: Float,
        std: Float,
        destination: Array<FloatArray>,
        destinationStart: Int,
    ) {
        require(startFrame in 0..frames && endFrame in startFrame..frames)
        input.seek(44L + startFrame * 4L)
        for (frame in 0 until endFrame - startFrame) {
            val left = readPcm16(input) / 32768f
            val right = readPcm16(input) / 32768f
            destination[0][destinationStart + frame] = (left - mean) / std
            destination[1][destinationStart + frame] = (right - mean) / std
        }
    }

    private fun forEachFrame(block: (Double, Double) -> Unit) {
        input.seek(44L)
        repeat(frames) {
            block(readPcm16(input) / 32768.0, readPcm16(input) / 32768.0)
        }
    }

    override fun close() = input.close()
}

internal class RollingStemPcm16WavWriter(
    directory: File,
    private val originalLength: Int,
    private val outerMean: Float,
    private val outerStd: Float,
) : AutoCloseable {
    private val writers = AndroidStemSource.workerOrder.map { source -> Pcm16WavOutput(File(directory, "${source.cacheName}.wav"), AndroidDemucsContract.SAMPLE_RATE, 2) }
    private val sums = Array(AndroidDemucsContract.SOURCES * 2) { FloatArray(AndroidDemucsContract.SEGMENT) }
    private val weights = FloatArray(AndroidDemucsContract.SEGMENT)
    private var cursor = 0
    private var finished = false

    fun addChunk(offset: Int, chunkLength: Int, trimStart: Int, stems: Array<FloatArray>, blend: FloatArray) {
        for (local in 0 until chunkLength) {
            val slot = (offset + local) % AndroidDemucsContract.SEGMENT
            val weight = blend[local]
            weights[slot] += weight
            for (source in 0 until AndroidDemucsContract.SOURCES) {
                for (channel in 0 until 2) {
                    sums[source * 2 + channel][slot] += stems[source * 2 + channel][trimStart + local] * weight
                }
            }
        }
    }

    fun flushUntil(end: Int) {
        while (cursor < end) {
            val slot = cursor % AndroidDemucsContract.SEGMENT
            val divisor = weights[slot].takeIf { it > 0f } ?: 1f
            if (cursor in AndroidDemucsContract.SHIFT_CONTEXT until AndroidDemucsContract.SHIFT_CONTEXT + originalLength) {
                for (source in 0 until AndroidDemucsContract.SOURCES) {
                    val left = sums[source * 2][slot] / divisor * outerStd + outerMean
                    val right = sums[source * 2 + 1][slot] / divisor * outerStd + outerMean
                    writers[source].writeFloat(left, right)
                }
            }
            weights[slot] = 0f
            sums.forEach { it[slot] = 0f }
            cursor++
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        writers.forEach(Pcm16WavOutput::close)
    }

    override fun close() = finish()
}

private class Pcm16WavOutput(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
) : AutoCloseable {
    private val output: BufferedOutputStream
    private var frames = 0L
    private var closed = false

    init {
        file.parentFile?.mkdirs()
        output = FileOutputStream(file).buffered(256 * 1024)
        output.write(ByteArray(44))
    }

    fun writeFloat(left: Float, right: Float) = writePcm16(floatToPcm16(left), floatToPcm16(right))

    fun writePcm16(left: Short, right: Short) {
        writeShort(left)
        if (channels == 2) writeShort(right)
        frames++
    }

    private fun writeShort(value: Short) {
        val int = value.toInt()
        output.write(int and 0xff)
        output.write((int ushr 8) and 0xff)
    }

    override fun close() {
        if (closed) return
        closed = true
        output.flush()
        output.close()
        RandomAccessFile(file, "rw").use { wav ->
            val dataBytes = frames * channels * 2L
            val header = wavHeader(sampleRate, channels, dataBytes)
            wav.seek(0)
            wav.write(header)
            wav.fd.sync()
        }
    }
}

private fun wavHeader(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
    require(dataBytes <= Int.MAX_VALUE.toLong()) { "WAV output exceeds RIFF32 limit" }
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt((36L + dataBytes).toInt())
    header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1)
    header.putShort(channels.toShort())
    header.putInt(sampleRate)
    header.putInt(sampleRate * channels * 2)
    header.putShort((channels * 2).toShort())
    header.putShort(16)
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(dataBytes.toInt())
    return header.array()
}

private fun floatToPcm16(sample: Float): Short =
    (sample.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

private fun readPcm16(input: RandomAccessFile): Short {
    val low = input.read()
    val high = input.read()
    if (high < 0) error("Unexpected end of canonical PCM")
    return ((high shl 8) or low).toShort()
}

private fun readLe16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun readLe32(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)
