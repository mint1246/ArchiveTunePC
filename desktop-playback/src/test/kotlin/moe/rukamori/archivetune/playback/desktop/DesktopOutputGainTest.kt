package moe.rukamori.archivetune.playback.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource

class DesktopOutputGainTest {
    @Test
    fun `db mapping matches android formula and conservative caps`() {
        assertEquals(1f, loudnessNormalizationFactor(false, -12.0, -12.0))
        assertEquals(1f, loudnessNormalizationFactor(true, 0.0, null))
        assertEquals(0.5f, loudnessNormalizationFactor(true, 6.020599913, null), 0.001f)
        assertEquals(MAX_AUDIO_NORMALIZATION_FACTOR, loudnessNormalizationFactor(true, -20.0, null), 0.0001f)
        assertEquals(MIN_AUDIO_NORMALIZATION_FACTOR, loudnessNormalizationFactor(true, 20.0, null), 0.0001f)
    }

    @Test
    fun `perceptual loudness is preferred and invalid values fall back safely`() {
        val preferred = loudnessNormalizationFactor(true, 6.020599913, -20.0)
        assertEquals(0.5f, preferred, 0.001f)

        val fallback = loudnessNormalizationFactor(true, Double.NaN, 6.020599913)
        assertEquals(0.5f, fallback, 0.001f)

        assertEquals(1f, loudnessNormalizationFactor(true, Double.POSITIVE_INFINITY, null))
        assertEquals(1f, loudnessNormalizationFactor(true, -500.0, 500.0))
        assertEquals(1f, loudnessNormalizationFactor(true, null, null))
    }

    @Test
    fun `user volume stays separate while backend gain follows track metadata and toggle`() {
        val backend = RecordingVolumeBackend()
        val gain = DesktopOutputGain()
        gain.attach(backend)
        gain.setUserVolume(0.8f)
        gain.setSource(ResolvedPlaybackSource("https://example", perceptualLoudnessDb = -20.0))

        assertEquals(0.8f, gain.userVolumeForTest())
        assertEquals(80, backend.last)

        gain.setNormalizationEnabled(true)
        assertEquals(MAX_AUDIO_NORMALIZATION_FACTOR, gain.effectiveFactorForTest())
        assertEquals(113, backend.last)

        gain.setSource(ResolvedPlaybackSource("https://example2", perceptualLoudnessDb = 6.020599913))
        assertEquals(0.8f, gain.userVolumeForTest())
        assertEquals(40, backend.last)

        gain.setSource(ResolvedPlaybackSource("file:///local"))
        assertEquals(80, backend.last)
    }

    @Test
    fun `backend replacement reapplies desired normalized volume`() {
        val first = RecordingVolumeBackend()
        val replacement = RecordingVolumeBackend()
        val gain = DesktopOutputGain()

        gain.setUserVolume(1f)
        gain.setSource(ResolvedPlaybackSource("https://example", perceptualLoudnessDb = -20.0))
        gain.setNormalizationEnabled(true)
        gain.attach(first)
        assertEquals(141, first.last)

        gain.detach(first)
        gain.attach(replacement)
        assertEquals(141, replacement.last)
        assertTrue(first.values.isNotEmpty())
    }

    @Test
    fun `crossfade multiplier scales primary and secondary without changing user volume`() {
        val backend = RecordingVolumeBackend()
        val gain = DesktopOutputGain()
        gain.attach(backend)
        gain.setUserVolume(0.8f)
        gain.setFadeMultiplier(0.25f)

        assertEquals(20, backend.last)
        assertEquals(0.8f, gain.userVolumeForTest())

        val quieterSource = ResolvedPlaybackSource(
            "https://example",
            perceptualLoudnessDb = 6.020599913,
        )
        gain.setNormalizationEnabled(true)
        assertEquals(10, gain.volumePercentForSource(quieterSource, 0.25f))
    }

    private class RecordingVolumeBackend : OutputVolumeBackend {
        val values = mutableListOf<Int>()
        val last: Int get() = values.last()

        override fun setVolumePercent(volumePercent: Int) {
            values += volumePercent
        }
    }
}
