/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStemModelsTest {
    @Test
    fun workerOrder_matchesDesktopHtdemucsSixSourceOrder() {
        assertEquals(
            listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
            AndroidStemSource.workerOrder.map { it.cacheName },
        )
    }

    @Test
    fun effectiveGains_applyMuteSoloAndGainExactlyOnce() {
        val controls =
            AndroidStemControls()
                .withControl(AndroidStemSource.DRUMS, AndroidStemChannelControl(gain = 0.4f))
                .withControl(AndroidStemSource.BASS, AndroidStemChannelControl(gain = 0.7f, muted = true))
                .withControl(AndroidStemSource.VOCALS, AndroidStemChannelControl(gain = 0.8f, solo = true))
                .withControl(AndroidStemSource.GUITAR, AndroidStemChannelControl(gain = 0.5f, solo = true))

        assertEquals(listOf(0f, 0f, 0f, 0.8f, 0.5f, 0f), controls.effectiveGains())
        assertFalse(controls.isEffectiveNeutral())
    }

    @Test
    fun nonFiniteAndOutOfRangeGains_areSanitized() {
        val controls =
            AndroidStemControls()
                .withControl(AndroidStemSource.DRUMS, AndroidStemChannelControl(gain = Float.NaN))
                .withControl(AndroidStemSource.BASS, AndroidStemChannelControl(gain = -4f))
                .withControl(AndroidStemSource.PIANO, AndroidStemChannelControl(gain = 5f))

        assertEquals(1f, controls.drums.gain, 0f)
        assertEquals(0f, controls.bass.gain, 0f)
        assertEquals(1f, controls.piano.gain, 0f)
        assertFalse(controls.isEffectiveNeutral())
        assertTrue(AndroidStemControls().isEffectiveNeutral())
    }
}
