/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class AndroidBeatAnalysisInput(
    val uri: Uri,
    val requestHeaders: Map<String, String> = emptyMap(),
)

/** MediaExtractor/MediaCodec decode into a compact mono signal used only for beat tracking. */
internal class AndroidBeatAudioDecoder(private val context: Context) {
    suspend fun decode(input: AndroidBeatAnalysisInput): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            setDataSource(extractor, input)
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("Beat analysis input contains no decodable audio track")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = checkNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var sourceFrames = 0L
            var inputEnded = false
            var outputEnded = false
            var resampler: BeatLinearResampler? = null
            val output = FloatAccumulator()
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
                        check(sourceFrames == 0L || (newRate == sampleRate && newChannels == channelCount)) {
                            "Beat decoder changed PCM format mid-stream"
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
                            val buffer =
                                checkNotNull(codec.getOutputBuffer(outputIndex))
                                    .duplicate()
                                    .order(ByteOrder.nativeOrder())
                                    .apply {
                                        position(info.offset)
                                        limit(info.offset + info.size)
                                    }.slice()
                                    .order(ByteOrder.nativeOrder())
                            val activeResampler =
                                resampler ?: BeatLinearResampler(sampleRate, AndroidBeatTracker.SAMPLE_RATE).also { resampler = it }
                            sourceFrames +=
                                decodePcmToMono(buffer, pcmEncoding, channelCount) { mono ->
                                    activeResampler.push(mono, output::add)
                                }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            check(sourceFrames > 0L && output.size > 0) { "Beat decoder produced no PCM" }
            return output.toFloatArray()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun setDataSource(extractor: MediaExtractor, input: AndroidBeatAnalysisInput) {
        when (input.uri.scheme?.lowercase()) {
            "content", "file", "android.resource" -> extractor.setDataSource(context, input.uri, input.requestHeaders)
            else -> extractor.setDataSource(input.uri.toString(), input.requestHeaders)
        }
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
    }
}

private inline fun decodePcmToMono(
    buffer: ByteBuffer,
    encoding: Int,
    channels: Int,
    sink: (Float) -> Unit,
): Long {
    require(channels > 0)
    check(encoding == AudioFormat.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_16BIT) {
        "Unsupported beat decoder PCM encoding $encoding"
    }
    val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
    val frameBytes = bytesPerSample * channels
    val frames = buffer.remaining() / frameBytes
    repeat(frames) {
        var sum = 0f
        repeat(channels) {
            sum +=
                when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                    else -> buffer.short / 32768f
                }
        }
        sink((sum / channels).coerceIn(-1f, 1f))
    }
    return frames.toLong()
}

internal class BeatLinearResampler(
    sourceRate: Int,
    targetRate: Int,
) {
    private val step = sourceRate.toDouble() / targetRate.toDouble()
    private var previous = 0f
    private var sourceIndex = -1L
    private var nextOutputPosition = 0.0

    init {
        require(sourceRate > 0 && targetRate > 0)
    }

    fun push(sample: Float, sink: (Float) -> Unit) {
        sourceIndex++
        if (sourceIndex == 0L) {
            previous = sample
            sink(sample)
            nextOutputPosition = step
            return
        }
        val currentIndex = sourceIndex.toDouble()
        val previousIndex = currentIndex - 1.0
        while (nextOutputPosition <= currentIndex + 1e-12) {
            val fraction = (nextOutputPosition - previousIndex).coerceIn(0.0, 1.0).toFloat()
            sink(previous + (sample - previous) * fraction)
            nextOutputPosition += step
        }
        previous = sample
    }
}

private class FloatAccumulator(private val chunkSize: Int = 65_536) {
    private val chunks = mutableListOf<FloatArray>()
    private var current = FloatArray(chunkSize)
    private var inCurrent = 0
    var size: Int = 0
        private set

    fun add(value: Float) {
        check(size < Int.MAX_VALUE) { "Beat analysis PCM is too large" }
        if (inCurrent == current.size) {
            chunks += current
            current = FloatArray(chunkSize)
            inCurrent = 0
        }
        current[inCurrent++] = value
        size++
    }

    fun toFloatArray(): FloatArray {
        val result = FloatArray(size)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        current.copyInto(result, offset, 0, inCurrent)
        return result
    }
}
