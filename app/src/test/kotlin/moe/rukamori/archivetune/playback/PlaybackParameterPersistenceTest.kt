/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackParameterPersistenceTest {
    @Test
    fun `missing and non finite values use desktop defaults`() {
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
    fun `values use desktop persistence bounds`() {
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
    fun `pitch multiplier conversion matches semitone model`() {
        assertEquals(-12f, PlaybackParameterPersistence.pitchMultiplierToSemitones(0.5f), 0.0001f)
        assertEquals(0f, PlaybackParameterPersistence.pitchMultiplierToSemitones(1f), 0.0001f)
        assertEquals(12f, PlaybackParameterPersistence.pitchMultiplierToSemitones(2f), 0.0001f)

        assertEquals(0.5f, PlaybackParameterPersistence.semitonesToPitchMultiplier(-12f), 0.0001f)
        assertEquals(1f, PlaybackParameterPersistence.semitonesToPitchMultiplier(0f), 0.0001f)
        assertEquals(2f, PlaybackParameterPersistence.semitonesToPitchMultiplier(12f), 0.0001f)
    }
}
