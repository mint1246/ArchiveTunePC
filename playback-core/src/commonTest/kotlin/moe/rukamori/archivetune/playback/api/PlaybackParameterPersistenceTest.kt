/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackParameterPersistenceTest {
    @Test
    fun missing_and_non_finite_values_use_desktop_defaults() {
        assertEquals(
            PersistedPlaybackParameters(speed = 1f, pitchSemitones = 0f),
            PlaybackParameterPersistence.sanitize(null, null),
        )
        assertEquals(
            PersistedPlaybackParameters(speed = 1f, pitchSemitones = 0f),
            PlaybackParameterPersistence.sanitize(Float.NaN, Float.POSITIVE_INFINITY),
        )
    }

    @Test
    fun values_use_desktop_persistence_bounds() {
        assertEquals(
            PersistedPlaybackParameters(speed = 0.25f, pitchSemitones = -12f),
            PlaybackParameterPersistence.sanitize(0.01f, -99f),
        )
        assertEquals(
            PersistedPlaybackParameters(speed = 4f, pitchSemitones = 12f),
            PlaybackParameterPersistence.sanitize(99f, 99f),
        )
    }

    @Test
    fun pitch_multiplier_conversion_matches_semitone_model() {
        assertFloatEquals(-12f, PlaybackParameterPersistence.pitchMultiplierToSemitones(0.5f), 0.0001f)
        assertFloatEquals(0f, PlaybackParameterPersistence.pitchMultiplierToSemitones(1f), 0.0001f)
        assertFloatEquals(12f, PlaybackParameterPersistence.pitchMultiplierToSemitones(2f), 0.0001f)

        assertFloatEquals(0.5f, PlaybackParameterPersistence.semitonesToPitchMultiplier(-12f), 0.0001f)
        assertFloatEquals(1f, PlaybackParameterPersistence.semitonesToPitchMultiplier(0f), 0.0001f)
        assertFloatEquals(2f, PlaybackParameterPersistence.semitonesToPitchMultiplier(12f), 0.0001f)
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
