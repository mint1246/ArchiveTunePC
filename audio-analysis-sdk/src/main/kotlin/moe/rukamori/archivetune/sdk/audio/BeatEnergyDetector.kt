/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.audio

import kotlin.math.sqrt

class BeatEnergyDetector(
    private val triggerRatio: Float = 1.72f,
    private val baselineAlpha: Float = 0.055f,
    private val minimumEnergy: Float = 3.5f,
    private val refractoryMs: Long = 210L,
) {
    private var baseline = 0f
    private var initialized = false
    private var lastBeatMs = Long.MIN_VALUE / 2

    fun acceptEnergy(energy: Float, nowMs: Long): Boolean {
        if (!energy.isFinite() || energy < 0f) return false
        if (!initialized) {
            baseline = energy.coerceAtLeast(0.001f)
            initialized = true
            return false
        }

        val reference = baseline.coerceAtLeast(0.001f)
        val isBeat =
            energy >= minimumEnergy &&
                energy >= reference * triggerRatio &&
                nowMs - lastBeatMs >= refractoryMs

        // Follow normal program level quickly enough to avoid permanently over-triggering, but do
        // not let a single transient drag the threshold all the way up to itself.
        val cappedForBaseline = energy.coerceAtMost(reference * 2.2f)
        baseline += (cappedForBaseline - baseline) * baselineAlpha

        if (isBeat) lastBeatMs = nowMs
        return isBeat
    }

    fun acceptFft(
        fft: ByteArray,
        samplingRateMilliHz: Int,
        nowMs: Long,
    ): Boolean {
        if (fft.size < 8 || samplingRateMilliHz <= 0) return false
        val sampleRateHz = samplingRateMilliHz / 1_000f
        val fftSize = fft.size
        val firstBin = ((45f * fftSize) / sampleRateHz).toInt().coerceAtLeast(1)
        val lastBin = ((180f * fftSize) / sampleRateHz).toInt().coerceAtMost((fft.size / 2) - 1)
        if (firstBin > lastBin) return false

        var sum = 0.0
        var count = 0
        for (bin in firstBin..lastBin) {
            val offset = bin * 2
            if (offset + 1 >= fft.size) break
            val real = fft[offset].toInt().toDouble()
            val imaginary = fft[offset + 1].toInt().toDouble()
            sum += real * real + imaginary * imaginary
            count++
        }
        if (count == 0) return false
        return acceptEnergy(sqrt(sum / count).toFloat(), nowMs)
    }
}
