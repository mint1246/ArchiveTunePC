/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StemModelsTest {
    @Test
    fun workerOrder_containsAllSixStemsInExpectedOrder() {
        assertEquals(
            listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
            StemSource.workerOrder.map { it.cacheName },
        )
    }

    @Test
    fun effectiveGains_respectSoloMuteAndSanitizationAcrossAllSixStems() {
        val controls =
            StemControls()
                .withControl(StemSource.DRUMS, StemChannelControl(gain = 0.4f))
                .withControl(StemSource.BASS, StemChannelControl(gain = 0.7f, muted = true))
                .withControl(StemSource.VOCALS, StemChannelControl(gain = 0.8f, solo = true))
                .withControl(StemSource.GUITAR, StemChannelControl(gain = 0.5f, solo = true))
                .withControl(StemSource.PIANO, StemChannelControl(gain = 0.9f))

        assertEquals(
            listOf(0f, 0f, 0f, 0.8f, 0.5f, 0f),
            controls.effectiveGains(),
        )
        assertFalse(controls.isEffectiveNeutral())
        assertFalse(controls.isDefault())
    }

    @Test
    fun pianoSoloAndGuitarMute_behaveIndependently() {
        val controls =
            StemControls()
                .withControl(StemSource.GUITAR, StemChannelControl(gain = 0.6f, muted = true, solo = true))
                .withControl(StemSource.PIANO, StemChannelControl(gain = 0.75f, solo = true))

        assertEquals(
            listOf(0f, 0f, 0f, 0f, 0f, 0.75f),
            controls.effectiveGains(),
        )
    }

    @Test
    fun sanitizeGain_clampsOutOfRangeAndNonFiniteValues() {
        val sanitized =
            StemControls()
                .withControl(StemSource.DRUMS, StemChannelControl(gain = Float.NaN))
                .withControl(StemSource.BASS, StemChannelControl(gain = -4f))
                .withControl(StemSource.PIANO, StemChannelControl(gain = 5f))
                .sanitized()

        assertEquals(1f, sanitized.drums.gain)
        assertEquals(0f, sanitized.bass.gain)
        assertEquals(1f, sanitized.piano.gain)
        assertTrue(StemControls().isEffectiveNeutral())
        assertTrue(StemControls().isDefault())
    }
}
