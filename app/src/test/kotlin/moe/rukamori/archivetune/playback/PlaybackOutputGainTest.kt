/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackOutputGainTest {
    @Test
    fun dbMapping_matchesDesktopNormalizationCaps() {
        assertEquals(1f, playbackLoudnessNormalizationFactor(false, -12.0, -12.0), 0f)
        assertEquals(1f, playbackLoudnessNormalizationFactor(true, 0.0, null), 0f)
        assertEquals(0.5f, playbackLoudnessNormalizationFactor(true, 6.020599913, null), 0.001f)
        assertEquals(1.414f, playbackLoudnessNormalizationFactor(true, -20.0, null), 0.0001f)
        assertEquals(0.25f, playbackLoudnessNormalizationFactor(true, 20.0, null), 0.0001f)
    }

    @Test
    fun perceptualLoudnessWins_andInvalidMetadataFallsBackSafely() {
        assertEquals(0.5f, playbackLoudnessNormalizationFactor(true, 6.020599913, -20.0), 0.001f)
        assertEquals(0.5f, playbackLoudnessNormalizationFactor(true, Double.NaN, 6.020599913), 0.001f)
        assertEquals(1f, playbackLoudnessNormalizationFactor(true, Double.POSITIVE_INFINITY, null), 0f)
        assertEquals(1f, playbackLoudnessNormalizationFactor(true, -500.0, 500.0), 0f)
        assertEquals(1f, playbackLoudnessNormalizationFactor(true, null, null), 0f)
    }
}
