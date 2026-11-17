/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.stems

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Constants and DSP layout matching stem-worker/src/main.rs. */
object DemucsContract {
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    const val SOURCES = 6
    const val NFFT = 4096
    const val HOP = 1024
    const val FREQ_BINS = 2048
    const val FRAMES = 44
    const val SEGMENT = SAMPLE_RATE
    const val STRIDE = 39_690 // 90% of the one-second segment.
    const val SHIFT_CONTEXT = SAMPLE_RATE / 4

    val MAG_SHAPE = longArrayOf(1, 4, FREQ_BINS.toLong(), FRAMES.toLong())
    val MIX_SHAPE = longArrayOf(1, 2, SEGMENT.toLong())
    const val FREQ_OUTPUT_SIZE = SOURCES * 4 * FREQ_BINS * FRAMES
    const val TIME_OUTPUT_SIZE = SOURCES * CHANNELS * SEGMENT
}

/**
 * Pure-Kotlin port of the desktop Rust HTDemucs _spec/_ispec path. It deliberately keeps the same
 * periodic Hann window, torch-style reflection, sqrt(N) FFT normalization, dropped Nyquist row,
 * 44-frame crop and frequency+time-branch reconstruction.
 */
class DemucsDsp {
    private val fft = FloatComplexFft(DemucsContract.NFFT)
    private val hann =
        FloatArray(DemucsContract.NFFT) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / DemucsContract.NFFT)).toFloat()
        }
    private val fftScale = sqrt(DemucsContract.NFFT.toFloat())
    private val real = FloatArray(DemucsContract.NFFT)
    private val imag = FloatArray(DemucsContract.NFFT)

    fun stftMagnitude(mix: Array<FloatArray>): FloatArray {
        require(mix.size == DemucsContract.CHANNELS)
        require(mix.all { it.size == DemucsContract.SEGMENT })
        val le = (DemucsContract.SEGMENT + DemucsContract.HOP - 1) / DemucsContract.HOP
        check(le == DemucsContract.FRAMES)
        val manualLeft = DemucsContract.HOP / 2 * 3
        val manualRight = manualLeft + le * DemucsContract.HOP - DemucsContract.SEGMENT
        val output = FloatArray(4 * DemucsContract.FREQ_BINS * DemucsContract.FRAMES)

        for (channel in 0 until DemucsContract.CHANNELS) {
            val manual = reflectPad(mix[channel], manualLeft, manualRight)
            for (frame in 0 until DemucsContract.FRAMES) {
                val stftFrame = frame + 2
                val start = stftFrame * DemucsContract.HOP
                for (n in 0 until DemucsContract.NFFT) {
                    val centeredIndex = start + n - DemucsContract.NFFT / 2
                    real[n] = reflectedSample(manual, centeredIndex) * hann[n]
                    imag[n] = 0f
                }
                fft.transform(real, imag, inverse = false)
                val complexChannel = channel * 2
                for (freq in 0 until DemucsContract.FREQ_BINS) {
                    val base = (complexChannel * DemucsContract.FREQ_BINS + freq) * DemucsContract.FRAMES + frame
                    output[base] = real[freq] / fftScale
                    output[base + DemucsContract.FREQ_BINS * DemucsContract.FRAMES] = imag[freq] / fftScale
                }
            }
        }
        return output
    }

    fun reconstruct(freq: FloatArray, time: FloatArray): Array<FloatArray> {
        require(freq.size == DemucsContract.FREQ_OUTPUT_SIZE) { "Unexpected freq_stems size ${freq.size}" }
        require(time.size == DemucsContract.TIME_OUTPUT_SIZE) { "Unexpected time_stems size ${time.size}" }
        val paddedLength = DemucsContract.HOP * DemucsContract.FRAMES + 2 * (DemucsContract.HOP / 2 * 3)
        val centeredLength = paddedLength + DemucsContract.NFFT
        val cropStart = DemucsContract.NFFT / 2 + DemucsContract.HOP / 2 * 3
        val output = Array(DemucsContract.SOURCES * DemucsContract.CHANNELS) { FloatArray(DemucsContract.SEGMENT) }
        val ola = FloatArray(centeredLength)
        val envelope = FloatArray(centeredLength)

        for (source in 0 until DemucsContract.SOURCES) {
            for (channel in 0 until DemucsContract.CHANNELS) {
                ola.fill(0f)
                envelope.fill(0f)
                for (frame48 in 0 until DemucsContract.FRAMES + 4) {
                    real.fill(0f)
                    imag.fill(0f)
                    if (frame48 in 2 until DemucsContract.FRAMES + 2) {
                        val frame = frame48 - 2
                        val complexChannel = channel * 2
                        for (f in 0 until DemucsContract.FREQ_BINS) {
                            val reIndex = (((source * 4 + complexChannel) * DemucsContract.FREQ_BINS + f) * DemucsContract.FRAMES) + frame
                            val imIndex = (((source * 4 + complexChannel + 1) * DemucsContract.FREQ_BINS + f) * DemucsContract.FRAMES) + frame
                            real[f] = freq[reIndex]
                            imag[f] = freq[imIndex]
                            if (f > 0) {
                                real[DemucsContract.NFFT - f] = real[f]
                                imag[DemucsContract.NFFT - f] = -imag[f]
                            }
                        }
                        imag[0] = 0f
                        real[DemucsContract.NFFT / 2] = 0f
                        imag[DemucsContract.NFFT / 2] = 0f
                    }
                    fft.transform(real, imag, inverse = true)
                    val start = frame48 * DemucsContract.HOP
                    for (n in 0 until DemucsContract.NFFT) {
                        val synthesized = real[n] / fftScale
                        val window = hann[n]
                        ola[start + n] += synthesized * window
                        envelope[start + n] += window * window
                    }
                }
                val outputChannel = output[source * DemucsContract.CHANNELS + channel]
                for (i in 0 until DemucsContract.SEGMENT) {
                    val fullIndex = cropStart + i
                    val freqSample = if (envelope[fullIndex] > 1e-11f) ola[fullIndex] / envelope[fullIndex] else 0f
                    val timeSample = time[(source * DemucsContract.CHANNELS + channel) * DemucsContract.SEGMENT + i]
                    outputChannel[i] = freqSample + timeSample
                }
            }
        }
        return output
    }
}

fun interleavedDemucsMix(chunk: Array<FloatArray>): FloatArray {
    require(chunk.size == 2 && chunk.all { it.size == DemucsContract.SEGMENT })
    return FloatArray(DemucsContract.CHANNELS * DemucsContract.SEGMENT).also { output ->
        chunk[0].copyInto(output, 0)
        chunk[1].copyInto(output, DemucsContract.SEGMENT)
    }
}

fun triangularDemucsWeight(): FloatArray {
    val half = DemucsContract.SEGMENT / 2
    val output = FloatArray(DemucsContract.SEGMENT)
    for (index in 0 until half) output[index] = (index + 1).toFloat() / half
    for (index in half until DemucsContract.SEGMENT) {
        output[index] = (DemucsContract.SEGMENT - index).toFloat() / half
    }
    return output
}

fun reflectedSample(input: FloatArray, rawIndex: Int): Float {
    var index = rawIndex
    val length = input.size
    require(length > 1)
    while (index < 0 || index >= length) {
        if (index < 0) index = -index
        if (index >= length) index = 2 * length - 2 - index
    }
    return input[index]
}

private fun reflectPad(input: FloatArray, left: Int, right: Int): FloatArray =
    FloatArray(left + input.size + right) { offset -> reflectedSample(input, offset - left) }

/** Small allocation-free radix-2 FFT. inverse=true intentionally remains unscaled like realfft. */
private class FloatComplexFft(private val size: Int) {
    private val twiddleReal = FloatArray(size / 2) { index -> cos(2.0 * PI * index / size).toFloat() }
    private val twiddleImag = FloatArray(size / 2) { index -> (-kotlin.math.sin(2.0 * PI * index / size)).toFloat() }

    init {
        require(size > 0 && size and (size - 1) == 0)
    }

    fun transform(real: FloatArray, imag: FloatArray, inverse: Boolean) {
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
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var base = 0
            while (base < size) {
                for (offset in 0 until half) {
                    val twiddleIndex = offset * step
                    val wr = twiddleReal[twiddleIndex]
                    val wi = if (inverse) -twiddleImag[twiddleIndex] else twiddleImag[twiddleIndex]
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
