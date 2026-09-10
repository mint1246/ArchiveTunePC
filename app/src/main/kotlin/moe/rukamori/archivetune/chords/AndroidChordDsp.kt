/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.chords

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject

internal data class AndroidChordConfig(val chordVocab: List<String>) {
    companion object {
        fun parseAndValidate(json: String): AndroidChordConfig {
            val root = JSONObject(json)
            val input = root.getJSONObject("input")
            val output = root.getJSONObject("output")
            val inputShape = input.getJSONArray("shape")
            val outputShape = output.getJSONArray("shape")
            check(input.getString("name") == "features")
            check(output.getString("name") == "logits")
            check(inputShape.getInt(1) == AndroidChordModelSpec.SEQUENCE_LENGTH)
            check(inputShape.getInt(2) == AndroidChordModelSpec.INPUT_BINS)
            check(outputShape.getInt(1) == AndroidChordModelSpec.SEQUENCE_LENGTH)
            check(outputShape.getInt(2) == AndroidChordModelSpec.OUTPUT_CLASSES)
            check(root.getInt("sampleRate") == AndroidChordModelSpec.SAMPLE_RATE)
            check(root.getInt("hopLength") == AndroidChordContract.HOP_LENGTH)
            check(root.optBoolean("normalizationInGraph", false))
            val vocabulary = root.getJSONArray("chordVocab")
            check(vocabulary.length() == AndroidChordModelSpec.OUTPUT_CLASSES)
            return AndroidChordConfig(List(vocabulary.length()) { index -> vocabulary.getString(index) })
        }
    }
}

internal object AndroidChordContract {
    const val HOP_LENGTH = 2_048
    const val FFT_SIZE = 512
    const val CQT_MAGIC = 0x5451434d
}

internal data class AndroidChordOctavePlan(
    val index: Int,
    val hopLength: Int,
    val fftSize: Int,
    val binStart: Int,
    val binCount: Int,
)

internal data class AndroidChordCqtPlan(
    val sampleRate: Double,
    val hopLength: Int,
    val nBins: Int,
    val earlyDownsampleCount: Int,
    val tapCount: Int,
    val rowOffsets: IntArray,
    val fftBins: IntArray,
    val coefficients: FloatArray,
    val downsampleHalfCoefficients: FloatArray,
    val octaves: List<AndroidChordOctavePlan>,
) {
    fun validate(): AndroidChordCqtPlan = also {
        check(abs(sampleRate - AndroidChordModelSpec.SAMPLE_RATE) <= 0.001)
        check(hopLength == AndroidChordContract.HOP_LENGTH)
        check(nBins == AndroidChordModelSpec.INPUT_BINS)
        check(earlyDownsampleCount == 1)
        check(tapCount == 255)
        check(octaves.size == 6)
        check(octaves.all { octave -> octave.fftSize == AndroidChordContract.FFT_SIZE })
        check(rowOffsets.size == nBins + 1)
        check(coefficients.size == fftBins.size * 2)
    }

    companion object {
        fun decode(bytes: ByteArray): AndroidChordCqtPlan {
            check(bytes.size >= 128) { "Invalid CQT plan artifact" }
            val reader = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun intAt(offset: Int): Int {
                check(offset >= 0 && offset + 4 <= bytes.size) { "CQT plan range is outside artifact" }
                return reader.getInt(offset)
            }
            fun doubleAt(offset: Int): Double {
                check(offset >= 0 && offset + 8 <= bytes.size) { "CQT plan range is outside artifact" }
                return reader.getDouble(offset)
            }
            fun floatAt(offset: Int): Float {
                check(offset >= 0 && offset + 4 <= bytes.size) { "CQT plan range is outside artifact" }
                return reader.getFloat(offset)
            }

            check(intAt(0) == AndroidChordContract.CQT_MAGIC) { "Invalid CQT plan artifact" }
            val headerSize = intAt(8)
            check(headerSize == 128) { "Unsupported CQT plan header size $headerSize" }
            val sampleRate = doubleAt(16)
            val hopLength = intAt(24)
            val nBins = intAt(28)
            val earlyDownsampleCount = intAt(36)
            val octaveCount = intAt(48)
            val tapCount = intAt(52)
            val coefficientCount = intAt(56)
            val payloadSize = intAt(60)
            check(nBins >= 0 && octaveCount >= 0 && tapCount >= 0 && coefficientCount >= 0 && payloadSize >= 0)
            check(headerSize.toLong() + payloadSize.toLong() == bytes.size.toLong()) { "CQT plan payload size mismatch" }
            val octavesOffset = intAt(72)
            val rowOffsetsOffset = intAt(76)
            val fftBinsOffset = intAt(80)
            val coefficientsOffset = intAt(84)
            val downsampleOffset = intAt(92)

            fun checkedOffset(base: Int, index: Int, stride: Int, width: Int): Int {
                val offset = base.toLong() + index.toLong() * stride.toLong()
                check(offset >= 0 && offset + width <= bytes.size.toLong()) { "CQT plan range is outside artifact" }
                return offset.toInt()
            }
            val octaves =
                List(octaveCount) { index ->
                    val base = checkedOffset(octavesOffset, index, 32, 32)
                    AndroidChordOctavePlan(
                        index = intAt(base),
                        hopLength = intAt(base + 12),
                        fftSize = intAt(base + 16),
                        binStart = intAt(base + 20),
                        binCount = intAt(base + 24),
                    )
                }
            val rowOffsets = IntArray(nBins + 1) { index -> intAt(checkedOffset(rowOffsetsOffset, index, 4, 4)) }
            val fftBins = IntArray(coefficientCount) { index -> intAt(checkedOffset(fftBinsOffset, index, 4, 4)) }
            val coefficients = FloatArray(coefficientCount * 2) { index -> floatAt(checkedOffset(coefficientsOffset, index, 4, 4)) }
            val halfCount = (tapCount + 1) / 2
            val half = FloatArray(halfCount) { index -> floatAt(checkedOffset(downsampleOffset, index, 4, 4)) }
            return AndroidChordCqtPlan(
                sampleRate = sampleRate,
                hopLength = hopLength,
                nBins = nBins,
                earlyDownsampleCount = earlyDownsampleCount,
                tapCount = tapCount,
                rowOffsets = rowOffsets,
                fftBins = fftBins,
                coefficients = coefficients,
                downsampleHalfCoefficients = half,
                octaves = octaves,
            )
        }
    }
}

internal fun computeAndroidChordCqt(
    audio: FloatArray,
    plan: AndroidChordCqtPlan,
    cancellationCheck: () -> Unit = {},
): FloatArray {
    plan.validate()
    val levels = ArrayList<FloatArray>(plan.earlyDownsampleCount + plan.octaves.size + 1)
    levels += audio
    repeat(plan.earlyDownsampleCount + plan.octaves.size) {
        cancellationCheck()
        levels += downsampleAndroidChord(levels.last(), plan)
    }
    val frameCount =
        plan.octaves.minOfOrNull { octave ->
            val level = plan.earlyDownsampleCount + octave.index
            check(level in levels.indices) { "CQT octave references unavailable downsample level" }
            check(octave.hopLength > 0) { "Invalid CQT octave hop length" }
            1 + levels[level].size / octave.hopLength
        } ?: 0
    if (frameCount == 0) return FloatArray(0)
    val output = FloatArray(frameCount * plan.nBins)
    val fft = AndroidChordFft(AndroidChordContract.FFT_SIZE)
    val real = FloatArray(AndroidChordContract.FFT_SIZE)
    val imag = FloatArray(AndroidChordContract.FFT_SIZE)
    val spectrumBins = AndroidChordContract.FFT_SIZE / 2 + 1

    for (octave in plan.octaves) {
        val level = levels[plan.earlyDownsampleCount + octave.index]
        check(octave.binStart >= 0 && octave.binCount >= 0 && octave.binStart + octave.binCount <= plan.nBins)
        for (frameIndex in 0 until frameCount) {
            if (frameIndex and 0x1f == 0) cancellationCheck()
            for (sample in 0 until AndroidChordContract.FFT_SIZE) {
                val offset = frameIndex * octave.hopLength + sample - AndroidChordContract.FFT_SIZE / 2
                real[sample] = if (offset in level.indices) level[offset] else 0f
                imag[sample] = 0f
            }
            fft.forward(real, imag)
            for (localBin in 0 until octave.binCount) {
                val globalBin = octave.binStart + localBin
                val start = plan.rowOffsets[globalBin]
                val end = plan.rowOffsets[globalBin + 1]
                check(start in 0..end && end <= plan.fftBins.size) { "Invalid CQT coefficient row" }
                var sumRe = 0f
                var sumIm = 0f
                for (coefficientIndex in start until end) {
                    val fftBin = plan.fftBins[coefficientIndex]
                    check(fftBin in 0 until spectrumBins) { "CQT plan references FFT bin $fftBin outside spectrum" }
                    val coefficientRe = plan.coefficients[coefficientIndex * 2]
                    val coefficientIm = plan.coefficients[coefficientIndex * 2 + 1]
                    val spectrumRe = real[fftBin]
                    val spectrumIm = imag[fftBin]
                    sumRe += coefficientRe * spectrumRe - coefficientIm * spectrumIm
                    sumIm += coefficientRe * spectrumIm + coefficientIm * spectrumRe
                }
                val magnitude = sqrt(sumRe * sumRe + sumIm * sumIm)
                output[frameIndex * plan.nBins + globalBin] = ln((magnitude + 1e-6f).toDouble()).toFloat()
            }
        }
    }
    return output
}

internal fun downsampleAndroidChord(input: FloatArray, plan: AndroidChordCqtPlan): FloatArray {
    val outputCount = (input.size + 1) / 2
    val delay = (plan.tapCount - 1) / 2
    val gain = sqrt(2f)
    return FloatArray(outputCount) { outputIndex ->
        val center = outputIndex * 2
        var sum = 0f
        for (tap in 0 until plan.tapCount) {
            val source = center + tap - delay
            if (source !in input.indices) continue
            val distance = abs(tap - delay)
            sum += input[source] * plan.downsampleHalfCoefficients[distance]
        }
        sum * gain
    }
}

internal fun smoothAndroidChordArgmax(
    logits: FloatArray,
    frameCount: Int,
    windowCount: Int,
): IntArray {
    val sequenceLength = AndroidChordModelSpec.SEQUENCE_LENGTH
    val classes = AndroidChordModelSpec.OUTPUT_CLASSES
    require(logits.size == windowCount * sequenceLength * classes) { "Invalid chord logits buffer" }
    return IntArray(frameCount) { frameIndex ->
        val window = frameIndex / sequenceLength
        val local = frameIndex % sequenceLength
        var bestClass = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (clazz in 0 until classes) {
            var total = 0f
            for (offset in -4..4) {
                val neighbor = local + offset
                if (neighbor !in 0 until sequenceLength) continue
                total += logits[(window * sequenceLength + neighbor) * classes + clazz]
            }
            if (total > bestValue) {
                bestValue = total
                bestClass = clazz
            }
        }
        bestClass
    }
}

internal fun androidChordPredictionsToEvents(
    predictions: IntArray,
    logits: FloatArray,
    frameCount: Int,
    vocabulary: List<String>,
    durationMs: Long,
): List<ChordEvent> {
    if (predictions.isEmpty()) return emptyList()
    val result = mutableListOf<ChordEvent>()
    var start = 0
    while (start < predictions.size) {
        val clazz = predictions[start]
        var end = start + 1
        while (end < predictions.size && predictions[end] == clazz) end++
        val raw = vocabulary.getOrNull(clazz) ?: "N"
        if (raw != "N" && raw != "X") {
            val startMs = androidChordFrameToMs(start)
            val endMs = androidChordFrameToMs(end).coerceAtMost(durationMs).coerceAtLeast(startMs + 1L)
            result +=
                ChordEvent(
                    startMs = startMs,
                    endMs = endMs,
                    name = prettyAndroidChord(raw),
                    confidence = androidChordSegmentConfidence(logits, clazz, start, end, frameCount),
                )
        }
        start = end
    }
    return result
}

internal fun androidChordSegmentConfidence(
    logits: FloatArray,
    clazz: Int,
    start: Int,
    end: Int,
    frameCount: Int,
): Float {
    val sequenceLength = AndroidChordModelSpec.SEQUENCE_LENGTH
    val classes = AndroidChordModelSpec.OUTPUT_CLASSES
    var accumulated = 0f
    var count = 0
    for (frameIndex in start until minOf(end, frameCount)) {
        val window = frameIndex / sequenceLength
        val local = frameIndex % sequenceLength
        val base = (window * sequenceLength + local) * classes
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until classes) max = maxOf(max, logits[base + i])
        var denom = 0f
        for (i in 0 until classes) denom += exp((logits[base + i] - max).toDouble()).toFloat()
        if (denom > 0f) {
            accumulated += exp((logits[base + clazz] - max).toDouble()).toFloat() / denom
            count++
        }
    }
    return if (count == 0) 0f else accumulated / count
}

internal fun prettyAndroidChord(raw: String): String {
    val separator = raw.indexOf(':')
    if (separator < 0) return raw
    val root = raw.substring(0, separator)
    val quality = raw.substring(separator + 1)
    val suffix =
        when (quality) {
            "min" -> "m"
            "dim" -> "dim"
            "aug" -> "aug"
            "min6" -> "m6"
            "maj6" -> "6"
            "min7" -> "m7"
            "minmaj7" -> "mMaj7"
            "maj7" -> "maj7"
            "7" -> "7"
            "dim7" -> "dim7"
            "hdim7" -> "m7b5"
            "sus2" -> "sus2"
            "sus4" -> "sus4"
            else -> quality
        }
    return root + suffix
}

internal fun androidChordFrameToMs(frame: Int): Long =
    frame.toLong() * AndroidChordContract.HOP_LENGTH * 1_000L / AndroidChordModelSpec.SAMPLE_RATE

private class AndroidChordFft(private val size: Int) {
    private val twiddleReal = FloatArray(size / 2) { index -> cos(2.0 * PI * index / size).toFloat() }
    private val twiddleImag = FloatArray(size / 2) { index -> (-sin(2.0 * PI * index / size)).toFloat() }

    init {
        require(size > 0 && size and (size - 1) == 0)
    }

    fun forward(real: FloatArray, imag: FloatArray) {
        require(real.size == size && imag.size == size)
        var j = 0
        for (i in 1 until size) {
            var bit = size ushr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit ushr 1
            }
            j = j xor bit
            if (i < j) {
                val re = real[i]
                real[i] = real[j]
                real[j] = re
                val im = imag[i]
                imag[i] = imag[j]
                imag[j] = im
            }
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var base = 0
            while (base < size) {
                for (offset in 0 until half) {
                    val twiddle = offset * step
                    val wr = twiddleReal[twiddle]
                    val wi = twiddleImag[twiddle]
                    val odd = base + offset + half
                    val even = base + offset
                    val tr = wr * real[odd] - wi * imag[odd]
                    val ti = wr * imag[odd] + wi * real[odd]
                    real[odd] = real[even] - tr
                    imag[odd] = imag[even] - ti
                    real[even] += tr
                    imag[even] += ti
                }
                base += length
            }
            length = length shl 1
        }
    }
}
