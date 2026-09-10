/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDemucsDspTest {
    @Test
    fun contract_matchesDesktopRustWorker() {
        assertEquals(44_100, AndroidDemucsContract.SAMPLE_RATE)
        assertEquals(4_096, AndroidDemucsContract.NFFT)
        assertEquals(1_024, AndroidDemucsContract.HOP)
        assertEquals(2_048, AndroidDemucsContract.FREQ_BINS)
        assertEquals(44, AndroidDemucsContract.FRAMES)
        assertEquals(39_690, AndroidDemucsContract.STRIDE)
        assertEquals(11_025, AndroidDemucsContract.SHIFT_CONTEXT)
        assertEquals(6, AndroidDemucsContract.SOURCES)
    }

    @Test
    fun reflectionAndTriangularOverlap_matchTorchWorkerSemantics() {
        val input = floatArrayOf(10f, 20f, 30f, 40f)
        assertEquals(30f, reflectedSample(input, -2))
        assertEquals(20f, reflectedSample(input, -1))
        assertEquals(10f, reflectedSample(input, 0))
        assertEquals(30f, reflectedSample(input, 4))
        assertEquals(20f, reflectedSample(input, 5))

        val weight = triangularDemucsWeight()
        assertEquals(1f / 22_050f, weight.first(), 1e-8f)
        assertEquals(1f, weight[22_049], 1e-7f)
        assertEquals(1f, weight[22_050], 1e-7f)
        assertEquals(1f / 22_050f, weight.last(), 1e-8f)
    }

    @Test
    fun stft_keepsLeftAndRightComplexPlanesIndependent() {
        val chunk = Array(2) { FloatArray(AndroidDemucsContract.SEGMENT) }
        chunk[0][AndroidDemucsContract.SEGMENT / 2] = 1f
        val magnitude = AndroidDemucsDsp().stftMagnitude(chunk)
        val planeSize = AndroidDemucsContract.FREQ_BINS * AndroidDemucsContract.FRAMES
        assertTrue(magnitude.copyOfRange(0, planeSize * 2).any { kotlin.math.abs(it) > 1e-8f })
        assertTrue(magnitude.copyOfRange(planeSize * 2, planeSize * 4).all { kotlin.math.abs(it) <= 1e-8f })
    }

    @Test
    fun zeroFrequencyBranch_reconstructsTimeBranchInWorkerOrder() {
        val freq = FloatArray(AndroidDemucsContract.FREQ_OUTPUT_SIZE)
        val time = FloatArray(AndroidDemucsContract.TIME_OUTPUT_SIZE)
        for (source in 0 until AndroidDemucsContract.SOURCES) {
            for (channel in 0 until AndroidDemucsContract.CHANNELS) {
                val marker = (source * 10 + channel + 1).toFloat()
                time[(source * 2 + channel) * AndroidDemucsContract.SEGMENT + 123] = marker
            }
        }
        val reconstructed = AndroidDemucsDsp().reconstruct(freq, time)
        for (source in 0 until AndroidDemucsContract.SOURCES) {
            for (channel in 0 until AndroidDemucsContract.CHANNELS) {
                assertEquals(
                    (source * 10 + channel + 1).toFloat(),
                    reconstructed[source * 2 + channel][123],
                    1e-6f,
                )
            }
        }
    }
}
