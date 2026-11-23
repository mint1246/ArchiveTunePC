/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class PlaybackOutputGainTest {
    @Test
    fun dbMapping_matchesDesktopNormalizationCaps() {
        assertFloatEquals(1f, playbackLoudnessNormalizationFactor(false, -12.0, -12.0), 0f)
        assertFloatEquals(1f, playbackLoudnessNormalizationFactor(true, 0.0, null), 0f)
        assertFloatEquals(0.5f, playbackLoudnessNormalizationFactor(true, 6.020599913, null), 0.001f)
        assertFloatEquals(1.414f, playbackLoudnessNormalizationFactor(true, -20.0, null), 0.0001f)
        assertFloatEquals(0.25f, playbackLoudnessNormalizationFactor(true, 20.0, null), 0.0001f)
    }

    @Test
    fun perceptualLoudnessWins_andInvalidMetadataFallsBackSafely() {
        assertFloatEquals(0.5f, playbackLoudnessNormalizationFactor(true, 6.020599913, -20.0), 0.001f)
        assertFloatEquals(0.5f, playbackLoudnessNormalizationFactor(true, Double.NaN, 6.020599913), 0.001f)
        assertFloatEquals(1f, playbackLoudnessNormalizationFactor(true, Double.POSITIVE_INFINITY, null), 0f)
        assertFloatEquals(1f, playbackLoudnessNormalizationFactor(true, -500.0, 500.0), 0f)
        assertFloatEquals(1f, playbackLoudnessNormalizationFactor(true, null, null), 0f)
    }

    private fun assertFloatEquals(
        expected: Float,
        actual: Float,
        tolerance: Float,
    ) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "Expected $expected but was $actual (tolerance $tolerance)",
        )
    }
}
