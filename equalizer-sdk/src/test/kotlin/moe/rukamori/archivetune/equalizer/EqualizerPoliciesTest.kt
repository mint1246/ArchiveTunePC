/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.equalizer

import moe.rukamori.archivetune.playback.EqCapabilities
import moe.rukamori.archivetune.playback.EqSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class EqualizerPoliciesTest {
    @Test
    fun `resampling interpolates to the device band count`() {
        assertEquals(emptyList(), resampleLevels(listOf(1, 2, 3), 0))
        assertEquals(listOf(0, 0, 0), resampleLevels(emptyList(), 3))
        assertEquals(listOf(2), resampleLevels(listOf(1, 3), 1))
        assertEquals(listOf(1, 3), resampleLevels(listOf(1, 3), 2))
        assertEquals(listOf(-1_000, -500, 0, 500, 1_000), resampleLevels(listOf(-1_000, 1_000), 5))
    }

    @Test
    fun `tone indices use centre frequencies when available`() {
        val frequencies = listOf(60, 200, 1_000, 3_000, 8_000, 16_000)

        assertEquals(listOf(0, 1), equalizerToneIndices(EqualizerTone.BASS, frequencies, 6))
        assertEquals(listOf(2, 3), equalizerToneIndices(EqualizerTone.MIDRANGE, frequencies, 6))
        assertEquals(listOf(4, 5), equalizerToneIndices(EqualizerTone.TREBLE, frequencies, 6))
    }

    @Test
    fun `tone indices fall back to band thirds`() {
        val missingFrequencies = List(3) { 0 }

        assertEquals(listOf(0), equalizerToneIndices(EqualizerTone.BASS, missingFrequencies, 3))
        assertEquals(listOf(1), equalizerToneIndices(EqualizerTone.MIDRANGE, missingFrequencies, 3))
        assertEquals(listOf(2), equalizerToneIndices(EqualizerTone.TREBLE, missingFrequencies, 3))
    }

    @Test
    fun `tone adjustment moves only selected bands within device limits`() {
        val configuration =
            EqualizerConfiguration(
                controlMode = EqualizerControlMode.BASIC,
                selectedProfileId = "manual",
                settings = settings(listOf(0, 0, 0, 0, 0, 0)),
                capabilities =
                    EqCapabilities(
                        bandCount = 6,
                        minBandLevelMb = -1_000,
                        maxBandLevelMb = 1_000,
                        centerFreqHz = listOf(60, 200, 1_000, 3_000, 8_000, 16_000),
                        systemPresets = emptyList(),
                    ),
                profiles = emptyList(),
            )

        assertEquals(listOf(500, 500, 0, 0, 0, 0), adjustToneBands(EqualizerTone.BASS, 500, configuration))
        assertEquals(listOf(-1_000, -1_000, 0, 0, 0, 0), adjustToneBands(EqualizerTone.BASS, -5_000, configuration))
    }

    @Test
    fun `tone adjustment without capabilities leaves stored levels untouched`() {
        val levels = listOf(1, 2, 3)
        val configuration =
            EqualizerConfiguration(
                controlMode = EqualizerControlMode.BASIC,
                selectedProfileId = "manual",
                settings = settings(levels),
                capabilities = null,
                profiles = emptyList(),
            )

        assertEquals(levels, adjustToneBands(EqualizerTone.MIDRANGE, 1_000, configuration))
    }

    private fun settings(levels: List<Int>) =
        EqSettings(
            enabled = true,
            bandLevelsMb = levels,
            outputGainEnabled = false,
            outputGainMb = 0,
            bassBoostEnabled = false,
            bassBoostStrength = 0,
            virtualizerEnabled = false,
            virtualizerStrength = 0,
            autoHeadroomEnabled = false,
        )
}
