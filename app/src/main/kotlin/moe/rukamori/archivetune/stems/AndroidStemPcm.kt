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
import androidx.media3.datasource.cache.Cache
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.YouTube
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

data class AndroidStemInput(
    val uri: Uri,
    val requestHeaders: Map<String, String> = emptyMap(),
    val cache: Cache? = null,
    val cacheKey: String? = null,
    val cachedContentLength: Long? = null,
)

internal data class AndroidCanonicalPcm(
    val file: File,
    val frames: Int,
)

/** Android MediaExtractor/MediaCodec decode followed by deterministic stereo 44.1 kHz resampling. */
internal class AndroidStemPcmDecoder(private val context: Context) {
    private val streamHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.streamOkHttpProxy)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun decode(
        input: AndroidStemInput,
        outputWav: File,
        onStatus: (String) -> Unit = {},
    ): AndroidCanonicalPcm {
        outputWav.parentFile?.mkdirs()
        val raw = File(outputWav.parentFile, ".${outputWav.name}.source-pcm.part")
        val materializedSource = File(outputWav.parentFile, ".${outputWav.name}.source-media.part")
        raw.delete()
        materializedSource.delete()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val decodeStarted = System.nanoTime()
            val decoderInput = materializeInput(input, materializedSource, onStatus)
            onStatus("Decoding source audio")
            setExtractorDataSource(extractor, decoderInput)
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
            val codecElapsedMs = elapsedMs(decodeStarted)
            Timber.tag(LOG_TAG).i(
                "stem decode produced %d source frames at %d Hz in %d ms",
                wroteFrames,
                sampleRate,
                codecElapsedMs,
            )
            onStatus("Resampling audio to 44.1 kHz")
            val resampleStarted = System.nanoTime()
            val targetFrames = resampleStereoPcm16ToWav(raw, sampleRate, wroteFrames, outputWav) { done, total ->
                onStatus("Resampling audio to 44.1 kHz · ${done / 44100}s / ${total / 44100}s")
            }
            Timber.tag(LOG_TAG).i(
                "stem resample produced %d frames in %d ms",
                targetFrames,
                elapsedMs(resampleStarted),
            )
            return AndroidCanonicalPcm(outputWav, targetFrames)
        } finally {
            raw.delete()
            materializedSource.delete()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private suspend fun materializeInput(
        input: AndroidStemInput,
        destination: File,
        onStatus: (String) -> Unit,
    ): AndroidStemInput {
        val cache = input.cache
        val cacheKey = input.cacheKey
        val cachedLength = input.cachedContentLength
        if (cache != null && !cacheKey.isNullOrBlank() && cachedLength != null && cachedLength > 0L && cache.isCached(cacheKey, 0L, cachedLength)) {
            return withContext(Dispatchers.IO) {
                onStatus("Reading downloaded audio")
                materializeCachedInput(cache, cacheKey, cachedLength, destination)
                Timber.tag(LOG_TAG).i("materialized downloaded stem source key=%s bytes=%d", cacheKey, cachedLength)
                AndroidStemInput(Uri.fromFile(destination))
            }
        }

        val scheme = input.uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return input

        return withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val requestBuilder = Request.Builder().url(input.uri.toString())
            input.requestHeaders.forEach { (name, value) ->
                runCatching { requestBuilder.header(name, value) }
            }
            val call = streamHttpClient.newCall(requestBuilder.build())
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Stem source request failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Stem source response body is empty")
                val totalBytes = body.contentLength().takeIf { it > 0L }
                destination.parentFile?.mkdirs()
                FileOutputStream(destination).buffered(256 * 1024).use { output ->
                    body.byteStream().use { source ->
                        val buffer = ByteArray(256 * 1024)
                        var downloaded = 0L
                        var lastReportedMiB = -1L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val downloadedMiB = downloaded / (1024L * 1024L)
                            if (downloadedMiB != lastReportedMiB) {
                                lastReportedMiB = downloadedMiB
                                onStatus(
                                    if (totalBytes != null) {
                                        "Fetching source audio · ${formatMiB(downloaded)} / ${formatMiB(totalBytes)}"
                                    } else {
                                        "Fetching source audio · ${formatMiB(downloaded)}"
                                    },
                                )
                            }
                        }
                    }
                }
            }
            check(destination.length() > 0L) { "Stem source download produced an empty file" }
            Timber.tag(LOG_TAG).i(
                "stem source materialized %.2f MiB in %d ms",
                destination.length() / (1024.0 * 1024.0),
                elapsedMs(started),
            )
            AndroidStemInput(Uri.fromFile(destination))
        }
    }

    private suspend fun materializeCachedInput(
        cache: Cache,
        key: String,
        contentLength: Long,
        destination: File,
    ) {
        val spans = cache.getCachedSpans(key).sortedBy { it.position }
        var cursor = 0L
        val copyBuffer = ByteArray(256 * 1024)
        FileOutputStream(destination).buffered(256 * 1024).use { output ->
            for (span in spans) {
                currentCoroutineContext().ensureActive()
                if (cursor >= contentLength) break
                val spanEnd = span.position + span.length
                if (spanEnd <= cursor) continue
                check(span.position <= cursor) { "Downloaded audio cache has a gap at byte $cursor" }
                val file = span.file ?: error("Downloaded audio cache span has no backing file")
                val sourceOffset = (cursor - span.position).coerceAtLeast(0L)
                var remaining = minOf(span.length - sourceOffset, contentLength - cursor)
                FileInputStream(file).use { input ->
                    input.channel.position(sourceOffset)
                    while (remaining > 0L) {
                        currentCoroutineContext().ensureActive()
                        val wanted = minOf(copyBuffer.size.toLong(), remaining).toInt()
                        val read = input.read(copyBuffer, 0, wanted)
                        check(read > 0) { "Downloaded audio cache span ended early" }
                        output.write(copyBuffer, 0, read)
                        cursor += read
                        remaining -= read
                    }
                }
            }
        }
        check(cursor == contentLength) { "Downloaded audio cache is incomplete ($cursor / $contentLength bytes)" }
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
        const val LOG_TAG = "StemDecode"
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
    onProgress: (Long, Long) -> Unit,
): Int {
    require(sourceRate > 0 && sourceFrames > 0)
    val targetFrames = ((sourceFrames.toDouble() * AndroidDemucsContract.SAMPLE_RATE / sourceRate).roundToInt()).coerceAtLeast(1)
    Pcm16WavOutput(destination, AndroidDemucsContract.SAMPLE_RATE, 2).use { wav ->
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
                val sourcePosition = targetIndex.toDouble() * sourceRate / AndroidDemucsContract.SAMPLE_RATE
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

private fun elapsedMs(startedNanos: Long): Long =
    ((System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L)

private fun formatMiB(bytes: Long): String = "%.1f MB".format(bytes.coerceAtLeast(0L) / (1024.0 * 1024.0))

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
