/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Constants and DSP layout copied from stem-worker/src/main.rs. */
internal object AndroidDemucsContract {
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
 * Kotlin port of the desktop Rust HTDemucs _spec/_ispec path. It deliberately keeps the same
 * periodic Hann window, torch-style reflection, sqrt(N) FFT normalization, dropped Nyquist row,
 * 44-frame crop and frequency+time-branch reconstruction.
 */
internal class AndroidDemucsDsp {
    private val fft = FloatComplexFft(AndroidDemucsContract.NFFT)
    private val hann =
        FloatArray(AndroidDemucsContract.NFFT) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / AndroidDemucsContract.NFFT)).toFloat()
        }
    private val fftScale = sqrt(AndroidDemucsContract.NFFT.toFloat())
    private val real = FloatArray(AndroidDemucsContract.NFFT)
    private val imag = FloatArray(AndroidDemucsContract.NFFT)

    fun stftMagnitude(mix: Array<FloatArray>): FloatArray {
        require(mix.size == AndroidDemucsContract.CHANNELS)
        require(mix.all { it.size == AndroidDemucsContract.SEGMENT })
        val le = (AndroidDemucsContract.SEGMENT + AndroidDemucsContract.HOP - 1) / AndroidDemucsContract.HOP
        check(le == AndroidDemucsContract.FRAMES)
        val manualLeft = AndroidDemucsContract.HOP / 2 * 3
        val manualRight = manualLeft + le * AndroidDemucsContract.HOP - AndroidDemucsContract.SEGMENT
        val output = FloatArray(4 * AndroidDemucsContract.FREQ_BINS * AndroidDemucsContract.FRAMES)

        for (channel in 0 until AndroidDemucsContract.CHANNELS) {
            val manual = reflectPad(mix[channel], manualLeft, manualRight)
            for (frame in 0 until AndroidDemucsContract.FRAMES) {
                val stftFrame = frame + 2
                val start = stftFrame * AndroidDemucsContract.HOP
                for (n in 0 until AndroidDemucsContract.NFFT) {
                    val centeredIndex = start + n - AndroidDemucsContract.NFFT / 2
                    real[n] = reflectedSample(manual, centeredIndex) * hann[n]
                    imag[n] = 0f
                }
                fft.transform(real, imag, inverse = false)
                val complexChannel = channel * 2
                for (freq in 0 until AndroidDemucsContract.FREQ_BINS) {
                    val base = (complexChannel * AndroidDemucsContract.FREQ_BINS + freq) * AndroidDemucsContract.FRAMES + frame
                    output[base] = real[freq] / fftScale
                    output[base + AndroidDemucsContract.FREQ_BINS * AndroidDemucsContract.FRAMES] = imag[freq] / fftScale
                }
            }
        }
        return output
    }

    fun reconstruct(freq: FloatArray, time: FloatArray): Array<FloatArray> {
        require(freq.size == AndroidDemucsContract.FREQ_OUTPUT_SIZE) { "Unexpected freq_stems size ${freq.size}" }
        require(time.size == AndroidDemucsContract.TIME_OUTPUT_SIZE) { "Unexpected time_stems size ${time.size}" }
        val paddedLength = AndroidDemucsContract.HOP * AndroidDemucsContract.FRAMES + 2 * (AndroidDemucsContract.HOP / 2 * 3)
        val centeredLength = paddedLength + AndroidDemucsContract.NFFT
        val cropStart = AndroidDemucsContract.NFFT / 2 + AndroidDemucsContract.HOP / 2 * 3
        val output = Array(AndroidDemucsContract.SOURCES * AndroidDemucsContract.CHANNELS) { FloatArray(AndroidDemucsContract.SEGMENT) }
        val ola = FloatArray(centeredLength)
        val envelope = FloatArray(centeredLength)

        for (source in 0 until AndroidDemucsContract.SOURCES) {
            for (channel in 0 until AndroidDemucsContract.CHANNELS) {
                ola.fill(0f)
                envelope.fill(0f)
                for (frame48 in 0 until AndroidDemucsContract.FRAMES + 4) {
                    real.fill(0f)
                    imag.fill(0f)
                    if (frame48 in 2 until AndroidDemucsContract.FRAMES + 2) {
                        val frame = frame48 - 2
                        val complexChannel = channel * 2
                        for (f in 0 until AndroidDemucsContract.FREQ_BINS) {
                            val reIndex = (((source * 4 + complexChannel) * AndroidDemucsContract.FREQ_BINS + f) * AndroidDemucsContract.FRAMES) + frame
                            val imIndex = (((source * 4 + complexChannel + 1) * AndroidDemucsContract.FREQ_BINS + f) * AndroidDemucsContract.FRAMES) + frame
                            real[f] = freq[reIndex]
                            imag[f] = freq[imIndex]
                            if (f > 0) {
                                real[AndroidDemucsContract.NFFT - f] = real[f]
                                imag[AndroidDemucsContract.NFFT - f] = -imag[f]
                            }
                        }
                        imag[0] = 0f
                        real[AndroidDemucsContract.NFFT / 2] = 0f
                        imag[AndroidDemucsContract.NFFT / 2] = 0f
                    }
                    fft.transform(real, imag, inverse = true)
                    val start = frame48 * AndroidDemucsContract.HOP
                    for (n in 0 until AndroidDemucsContract.NFFT) {
                        val synthesized = real[n] / fftScale
                        val window = hann[n]
                        ola[start + n] += synthesized * window
                        envelope[start + n] += window * window
                    }
                }
                val outputChannel = output[source * AndroidDemucsContract.CHANNELS + channel]
                for (i in 0 until AndroidDemucsContract.SEGMENT) {
                    val fullIndex = cropStart + i
                    val freqSample = if (envelope[fullIndex] > 1e-11f) ola[fullIndex] / envelope[fullIndex] else 0f
                    val timeSample = time[(source * AndroidDemucsContract.CHANNELS + channel) * AndroidDemucsContract.SEGMENT + i]
                    outputChannel[i] = freqSample + timeSample
                }
            }
        }
        return output
    }
}

internal fun interleavedDemucsMix(chunk: Array<FloatArray>): FloatArray {
    require(chunk.size == 2 && chunk.all { it.size == AndroidDemucsContract.SEGMENT })
    return FloatArray(AndroidDemucsContract.CHANNELS * AndroidDemucsContract.SEGMENT).also { output ->
        chunk[0].copyInto(output, 0)
        chunk[1].copyInto(output, AndroidDemucsContract.SEGMENT)
    }
}

internal fun triangularDemucsWeight(): FloatArray {
    val half = AndroidDemucsContract.SEGMENT / 2
    val output = FloatArray(AndroidDemucsContract.SEGMENT)
    for (index in 0 until half) output[index] = (index + 1).toFloat() / half
    for (index in half until AndroidDemucsContract.SEGMENT) {
        output[index] = (AndroidDemucsContract.SEGMENT - index).toFloat() / half
    }
    return output
}

internal fun reflectedSample(input: FloatArray, rawIndex: Int): Float {
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
