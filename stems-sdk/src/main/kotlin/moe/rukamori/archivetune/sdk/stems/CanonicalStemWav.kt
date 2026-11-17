/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.stems

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

suspend fun resampleStereoPcm16ToWav(
    raw: File,
    sourceRate: Int,
    sourceFrames: Long,
    destination: File,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
): Int {
    require(sourceRate > 0 && sourceFrames > 0)
    val targetFrames = ((sourceFrames.toDouble() * DemucsContract.SAMPLE_RATE / sourceRate).roundToInt()).coerceAtLeast(1)
    Pcm16WavOutput(destination, DemucsContract.SAMPLE_RATE, 2).use { wav ->
        BufferedStereoPcm16Reader(raw).use { input ->
            var sourceIndex = 0L
            var current = input.readFrame()
            var next = if (sourceFrames > 1) input.readFrame() else current
            val outputBlock = ByteArray(RESAMPLE_OUTPUT_FRAMES * 4)
            var outputFrames = 0

            fun flushOutputBlock() {
                if (outputFrames <= 0) return
                wav.writeStereoPcm16Bytes(outputBlock, outputFrames)
                outputFrames = 0
            }

            for (targetIndex in 0 until targetFrames) {
                if (targetIndex and 0x3fff == 0) {
                    currentCoroutineContext().ensureActive()
                    if (targetIndex % (RESAMPLE_OUTPUT_FRAMES * 16) == 0) {
                        onProgress(targetIndex.toLong(), targetFrames.toLong())
                    }
                }
                val sourcePosition = targetIndex.toDouble() * sourceRate / DemucsContract.SAMPLE_RATE
                val wanted = sourcePosition.toLong().coerceAtMost(sourceFrames - 1)
                while (sourceIndex < wanted) {
                    current = next
                    sourceIndex++
                    next = if (sourceIndex + 1 < sourceFrames) input.readFrame() else current
                }
                val fraction = (sourcePosition - wanted).toFloat().coerceIn(0f, 1f)
                val left = current[0] + (next[0] - current[0]) * fraction
                val right = current[1] + (next[1] - current[1]) * fraction
                val leftShort = left.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                val rightShort = right.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                val offset = outputFrames * 4
                outputBlock[offset] = leftShort.toByte()
                outputBlock[offset + 1] = (leftShort.toInt() ushr 8).toByte()
                outputBlock[offset + 2] = rightShort.toByte()
                outputBlock[offset + 3] = (rightShort.toInt() ushr 8).toByte()
                outputFrames++
                if (outputFrames == RESAMPLE_OUTPUT_FRAMES) flushOutputBlock()
            }
            flushOutputBlock()
        }
    }
    return targetFrames
}

private class BufferedStereoPcm16Reader(file: File) : AutoCloseable {
    private val input = FileInputStream(file)
    private val buffer = ByteArray(256 * 1024)
    private var position = 0
    private var limit = 0

    fun readFrame(): IntArray {
        ensureAvailable(4)
        val b0 = buffer[position++].toInt() and 0xff
        val b1 = buffer[position++].toInt() and 0xff
        val b2 = buffer[position++].toInt() and 0xff
        val b3 = buffer[position++].toInt() and 0xff
        return intArrayOf(((b1 shl 8) or b0).toShort().toInt(), ((b3 shl 8) or b2).toShort().toInt())
    }

    private fun ensureAvailable(bytes: Int) {
        if (limit - position >= bytes) return
        val remaining = limit - position
        if (remaining > 0) {
            System.arraycopy(buffer, position, buffer, 0, remaining)
        }
        position = 0
        limit = remaining
        while (limit < bytes) {
            val read = input.read(buffer, limit, buffer.size - limit)
            if (read < 0) error("Unexpected end of source PCM")
            limit += read
        }
    }

    override fun close() = input.close()
}

private const val RESAMPLE_OUTPUT_FRAMES = 16 * 1024

class CanonicalPcm16WavReader(private val file: File) : AutoCloseable {
    private val input = RandomAccessFile(file, "r")
    val frames: Int

    init {
        val header = ByteArray(44)
        input.readFully(header)
        check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
            "Not a canonical WAV"
        }
        check(readLe16(header, 20) == 1 && readLe16(header, 22) == 2) { "Canonical WAV must be stereo PCM" }
        check(readLe32(header, 24) == DemucsContract.SAMPLE_RATE) { "Canonical WAV must be 44.1 kHz" }
        check(readLe16(header, 34) == 16 && String(header, 36, 4, Charsets.US_ASCII) == "data") {
            "Canonical WAV must be 16-bit PCM with a 44-byte header"
        }
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

class RollingStemPcm16WavWriter(
    directory: File,
    private val originalLength: Int,
    private val outerMean: Float,
    private val outerStd: Float,
) : AutoCloseable {
    private val writers = StemSource.workerOrder.map { source ->
        Pcm16WavOutput(File(directory, "${source.cacheName}.wav"), DemucsContract.SAMPLE_RATE, 2)
    }
    private val sums = Array(DemucsContract.SOURCES * 2) { FloatArray(DemucsContract.SEGMENT) }
    private val weights = FloatArray(DemucsContract.SEGMENT)
    private var cursor = 0
    private var finished = false

    fun addChunk(offset: Int, chunkLength: Int, trimStart: Int, stems: Array<FloatArray>, blend: FloatArray) {
        for (local in 0 until chunkLength) {
            val slot = (offset + local) % DemucsContract.SEGMENT
            val weight = blend[local]
            weights[slot] += weight
            for (source in 0 until DemucsContract.SOURCES) {
                for (channel in 0 until 2) {
                    sums[source * 2 + channel][slot] += stems[source * 2 + channel][trimStart + local] * weight
                }
            }
        }
    }

    fun flushUntil(end: Int) {
        while (cursor < end) {
            val slot = cursor % DemucsContract.SEGMENT
            val divisor = weights[slot].takeIf { it > 0f } ?: 1f
            if (cursor in DemucsContract.SHIFT_CONTEXT until DemucsContract.SHIFT_CONTEXT + originalLength) {
                for (source in 0 until DemucsContract.SOURCES) {
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

class Pcm16WavOutput(
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

    fun writeStereoPcm16Bytes(bytes: ByteArray, frameCount: Int) {
        require(channels == 2)
        require(frameCount >= 0 && frameCount * 4 <= bytes.size)
        output.write(bytes, 0, frameCount * 4)
        frames += frameCount
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

fun wavHeader(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
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

fun floatToPcm16(sample: Float): Short =
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
