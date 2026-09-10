/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class AndroidBeatAnalysis(
    val beatsSeconds: FloatArray,
    val bpm: Float,
    val durationMs: Long,
)

/**
 * Pure Kotlin beat tracker used on Android when the desktop Essentia binding is unavailable.
 *
 * It derives a spectral-flux onset envelope, estimates tempo through normalized autocorrelation,
 * chooses the strongest grid phase, then locally snaps/adapts each beat to nearby onsets. The
 * output is a real timestamp grid; playback visuals sample those timestamps directly rather than
 * advancing a synthetic UI metronome.
 */
internal object AndroidBeatTracker {
    const val SAMPLE_RATE = 11_025

    fun analyze(samples: FloatArray): AndroidBeatAnalysis? {
        if (samples.size < SAMPLE_RATE * MIN_AUDIO_SECONDS) return null
        val novelty = spectralFlux(samples)
        if (novelty.size < 8) return null
        val periodFrames = estimateBeatPeriod(novelty) ?: return null
        val beatFrames = traceBeatGrid(novelty, periodFrames)
        if (beatFrames.size < 2) return null

        val beatsSeconds = FloatArray(beatFrames.size) { index -> (beatFrames[index] * HOP_SIZE.toDouble() / SAMPLE_RATE.toDouble()).toFloat() }
        val intervals =
            FloatArray(beatsSeconds.size - 1) { index -> beatsSeconds[index + 1] - beatsSeconds[index] }
                .filter { it.isFinite() && it > 0f }
                .sorted()
        if (intervals.isEmpty()) return null
        val medianInterval = intervals[intervals.size / 2].coerceAtLeast(0.05f)
        return AndroidBeatAnalysis(
            beatsSeconds = beatsSeconds,
            bpm = (60f / medianInterval).coerceIn(MIN_BPM.toFloat(), MAX_BPM.toFloat()),
            durationMs = samples.size.toLong() * 1_000L / SAMPLE_RATE,
        )
    }

    private fun spectralFlux(samples: FloatArray): FloatArray {
        val frameCount = ((samples.size - 1) / HOP_SIZE) + 1
        val raw = FloatArray(frameCount)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        val previousSpectrum = DoubleArray((FFT_SIZE / 2) + 1)
        val hann = DoubleArray(FFT_SIZE) { index -> 0.5 - 0.5 * cos((2.0 * PI * index) / (FFT_SIZE - 1)) }
        val firstBin = max(1, (MIN_ANALYSIS_HZ * FFT_SIZE / SAMPLE_RATE).roundToInt())
        val lastBin = (MAX_ANALYSIS_HZ * FFT_SIZE / SAMPLE_RATE).roundToInt().coerceAtMost(FFT_SIZE / 2)
        var previousLogRms = 0.0

        for (frameIndex in 0 until frameCount) {
            val start = frameIndex * HOP_SIZE
            var mean = 0.0
            var actual = 0
            var i = 0
            while (i < FFT_SIZE && start + i < samples.size) {
                mean += samples[start + i]
                actual++
                i++
            }
            if (actual == 0) break
            mean /= actual

            var sumSquares = 0.0
            for (sampleIndex in 0 until FFT_SIZE) {
                val sourceIndex = start + sampleIndex
                val sample = if (sourceIndex < samples.size) samples[sourceIndex].toDouble() - mean else 0.0
                sumSquares += sample * sample
                real[sampleIndex] = sample * hann[sampleIndex]
                imaginary[sampleIndex] = 0.0
            }
            fft(real, imaginary)

            var flux = 0.0
            for (bin in firstBin..lastBin) {
                val magnitude = ln1p(hypot(real[bin], imaginary[bin]))
                val delta = magnitude - previousSpectrum[bin]
                if (delta > 0.0) {
                    val hz = bin.toDouble() * SAMPLE_RATE / FFT_SIZE
                    val weight = if (hz <= BASS_WEIGHT_MAX_HZ) BASS_WEIGHT else 1.0
                    flux += delta * weight
                }
                previousSpectrum[bin] = magnitude
            }
            flux /= (lastBin - firstBin + 1).coerceAtLeast(1)

            val rms = sqrt(sumSquares / FFT_SIZE)
            val logRms = ln1p(rms * 32.0)
            val transient = max(0.0, logRms - previousLogRms)
            previousLogRms = logRms
            raw[frameIndex] = if (frameIndex == 0) 0f else (flux + transient * RMS_TRANSIENT_WEIGHT).toFloat()
        }

        val prefix = DoubleArray(raw.size + 1)
        val prefixSquares = DoubleArray(raw.size + 1)
        for (index in raw.indices) {
            prefix[index + 1] = prefix[index] + raw[index]
            prefixSquares[index + 1] = prefixSquares[index] + raw[index] * raw[index]
        }
        val normalized = FloatArray(raw.size)
        for (index in raw.indices) {
            val from = (index - LOCAL_NORMALIZE_RADIUS).coerceAtLeast(0)
            val until = (index + LOCAL_NORMALIZE_RADIUS + 1).coerceAtMost(raw.size)
            val count = (until - from).coerceAtLeast(1)
            val mean = (prefix[until] - prefix[from]) / count
            val meanSquare = (prefixSquares[until] - prefixSquares[from]) / count
            val std = sqrt(max(0.0, meanSquare - mean * mean)).coerceAtLeast(MIN_LOCAL_STD)
            normalized[index] = max(0.0, (raw[index] - mean) / std).toFloat()
        }

        val smoothed = FloatArray(normalized.size)
        for (index in normalized.indices) {
            val previous = normalized[(index - 1).coerceAtLeast(0)]
            val current = normalized[index]
            val next = normalized[(index + 1).coerceAtMost(normalized.lastIndex)]
            smoothed[index] = (previous + current * 2f + next) * 0.25f
        }
        return smoothed
    }

    private fun estimateBeatPeriod(novelty: FloatArray): Double? {
        val framesPerSecond = SAMPLE_RATE.toDouble() / HOP_SIZE
        val minLag = (framesPerSecond * 60.0 / MAX_BPM).roundToInt().coerceAtLeast(2)
        val maxLag = (framesPerSecond * 60.0 / MIN_BPM).roundToInt().coerceAtMost(novelty.size / 2)
        if (minLag > maxLag) return null

        val correlations = DoubleArray(maxLag + 1)
        fun correlation(lag: Int): Double {
            if (lag !in 1..maxLag || lag >= novelty.size) return 0.0
            var product = 0.0
            var leftEnergy = 0.0
            var rightEnergy = 0.0
            for (index in lag until novelty.size) {
                val left = novelty[index].toDouble()
                val right = novelty[index - lag].toDouble()
                product += left * right
                leftEnergy += left * left
                rightEnergy += right * right
            }
            if (leftEnergy <= 1e-12 || rightEnergy <= 1e-12) return 0.0
            return product / sqrt(leftEnergy * rightEnergy)
        }

        for (lag in minLag..maxLag) correlations[lag] = correlation(lag)
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val bpm = framesPerSecond * 60.0 / lag
            val octaveSupport = if (lag * 2 <= maxLag) correlations[lag * 2] else correlation(lag * 2)
            val tripleSupport = if (lag * 3 < novelty.size) correlation(lag * 3) else 0.0
            val logDistance = ln(bpm / TEMPO_PRIOR_BPM) / LN_2
            val broadPrior = exp(-0.5 * (logDistance / TEMPO_PRIOR_OCTAVES) * (logDistance / TEMPO_PRIOR_OCTAVES))
            val score = (correlations[lag] + 0.42 * octaveSupport + 0.12 * tripleSupport) * (0.82 + 0.18 * broadPrior)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0 || bestScore < MIN_TEMPO_SCORE) return null

        val center = correlations[bestLag]
        val left = if (bestLag > minLag) correlations[bestLag - 1] else center
        val right = if (bestLag < maxLag) correlations[bestLag + 1] else center
        val denominator = left - 2.0 * center + right
        val adjustment =
            if (abs(denominator) > 1e-9) {
                (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5)
            } else {
                0.0
            }
        return bestLag + adjustment
    }

    private fun traceBeatGrid(novelty: FloatArray, estimatedPeriod: Double): DoubleArray {
        val period = estimatedPeriod.coerceAtLeast(2.0)
        val roundedPeriod = period.roundToInt().coerceAtLeast(2)
        var bestPhase = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (phase in 0 until roundedPeriod) {
            var expected = phase.toDouble()
            var score = 0.0
            var slots = 0
            while (expected < novelty.size) {
                val center = expected.roundToInt()
                var localBest = 0f
                for (offset in -PHASE_SEARCH_RADIUS..PHASE_SEARCH_RADIUS) {
                    val index = center + offset
                    if (index in novelty.indices) {
                        localBest = max(localBest, novelty[index] / (1f + abs(offset) * 0.16f))
                    }
                }
                score += localBest
                slots++
                expected += period
            }
            if (slots >= 2) score /= sqrt(slots.toDouble())
            if (score > bestScore) {
                bestScore = score
                bestPhase = phase
            }
        }

        val peakFloor = adaptivePeakFloor(novelty)
        val firstActive = novelty.indices.firstOrNull { index -> isLocalPeak(novelty, index, peakFloor) } ?: return DoubleArray(0)
        val lastActive = novelty.indices.reversed().firstOrNull { index -> isLocalPeak(novelty, index, peakFloor) } ?: return DoubleArray(0)

        var currentPeriod = period
        var predicted = bestPhase.toDouble()
        while (predicted + currentPeriod * 0.55 < firstActive) predicted += currentPeriod
        while (predicted - currentPeriod * 0.55 > firstActive) predicted -= currentPeriod

        val output = ArrayList<Double>()
        var lastBeat = Double.NEGATIVE_INFINITY
        var previousSnapped = false
        while (predicted <= lastActive + currentPeriod * 0.65 && predicted < novelty.size + currentPeriod) {
            val radius = max(2, (currentPeriod * LOCAL_SNAP_FRACTION).roundToInt())
            val center = predicted.roundToInt()
            var selected = center.coerceIn(0, novelty.lastIndex)
            var selectedValue = -1f
            for (index in (center - radius)..(center + radius)) {
                if (index !in novelty.indices) continue
                val distancePenalty = 1f + (abs(index - predicted) / radius.coerceAtLeast(1)) * 0.22f
                val value = novelty[index] / distancePenalty.toFloat()
                if (value > selectedValue) {
                    selectedValue = value
                    selected = index
                }
            }
            val snapped = selectedValue >= peakFloor * SNAP_FLOOR_MULTIPLIER
            var beat = if (snapped) selected.toDouble() else predicted
            val minimumSpacing = currentPeriod * MIN_SPACING_FRACTION
            if (lastBeat.isFinite() && beat - lastBeat < minimumSpacing) beat = predicted.coerceAtLeast(lastBeat + minimumSpacing)
            if (beat >= 0.0 && beat < novelty.size + currentPeriod * 0.5) output += beat

            if (lastBeat.isFinite() && snapped && previousSnapped) {
                val observed = beat - lastBeat
                val ratio = observed / currentPeriod
                if (ratio in ADAPT_MIN_RATIO..ADAPT_MAX_RATIO) {
                    currentPeriod = currentPeriod * (1.0 - PERIOD_ADAPT_ALPHA) + observed * PERIOD_ADAPT_ALPHA
                }
            }
            lastBeat = beat
            previousSnapped = snapped
            predicted = beat + currentPeriod
        }
        return output.toDoubleArray()
    }

    private fun adaptivePeakFloor(novelty: FloatArray): Float {
        val positive = novelty.filter { it > 0f }.sorted()
        if (positive.isEmpty()) return Float.POSITIVE_INFINITY
        val q70 = positive[((positive.size - 1) * 0.70).roundToInt().coerceIn(0, positive.lastIndex)]
        return max(MIN_PEAK_FLOOR, q70 * PEAK_FLOOR_QUANTILE_SCALE)
    }

    private fun isLocalPeak(novelty: FloatArray, index: Int, floor: Float): Boolean {
        if (novelty[index] < floor) return false
        val left = novelty[(index - 1).coerceAtLeast(0)]
        val right = novelty[(index + 1).coerceAtMost(novelty.lastIndex)]
        return novelty[index] >= left && novelty[index] >= right
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        require(n > 0 && n and (n - 1) == 0)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realSwap = real[i]
                real[i] = real[j]
                real[j] = realSwap
                val imaginarySwap = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = imaginarySwap
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            var block = 0
            while (block < n) {
                var weightReal = 1.0
                var weightImaginary = 0.0
                val half = length shr 1
                for (offset in 0 until half) {
                    val even = block + offset
                    val odd = even + half
                    val oddReal = real[odd] * weightReal - imaginary[odd] * weightImaginary
                    val oddImaginary = real[odd] * weightImaginary + imaginary[odd] * weightReal
                    val evenReal = real[even]
                    val evenImaginary = imaginary[even]
                    real[even] = evenReal + oddReal
                    imaginary[even] = evenImaginary + oddImaginary
                    real[odd] = evenReal - oddReal
                    imaginary[odd] = evenImaginary - oddImaginary

                    val nextWeightReal = weightReal * stepReal - weightImaginary * stepImaginary
                    weightImaginary = weightReal * stepImaginary + weightImaginary * stepReal
                    weightReal = nextWeightReal
                }
                block += length
            }
            length = length shl 1
        }
    }

    private const val FFT_SIZE = 1_024
    private const val HOP_SIZE = 256
    private const val MIN_AUDIO_SECONDS = 2
    private const val MIN_BPM = 55
    private const val MAX_BPM = 210
    private const val MIN_ANALYSIS_HZ = 45.0
    private const val MAX_ANALYSIS_HZ = 5_000.0
    private const val BASS_WEIGHT_MAX_HZ = 260.0
    private const val BASS_WEIGHT = 1.28
    private const val RMS_TRANSIENT_WEIGHT = 0.32
    private const val LOCAL_NORMALIZE_RADIUS = 16
    private const val MIN_LOCAL_STD = 1e-4
    private const val MIN_TEMPO_SCORE = 0.035
    private const val TEMPO_PRIOR_BPM = 120.0
    private const val TEMPO_PRIOR_OCTAVES = 1.45
    private const val LN_2 = 0.6931471805599453
    private const val PHASE_SEARCH_RADIUS = 2
    private const val LOCAL_SNAP_FRACTION = 0.20
    private const val MIN_PEAK_FLOOR = 0.22f
    private const val PEAK_FLOOR_QUANTILE_SCALE = 0.62f
    private const val SNAP_FLOOR_MULTIPLIER = 0.70f
    private const val MIN_SPACING_FRACTION = 0.48
    private const val PERIOD_ADAPT_ALPHA = 0.10
    private const val ADAPT_MIN_RATIO = 0.72
    private const val ADAPT_MAX_RATIO = 1.28
}
