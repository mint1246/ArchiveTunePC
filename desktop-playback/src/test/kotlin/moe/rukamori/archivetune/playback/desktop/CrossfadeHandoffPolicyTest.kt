/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossfadeHandoffPolicyTest {
    @Test
    fun `corrective seek is skipped while drift stays within tolerance`() {
        assertFalse(
            needsCorrectiveCrossfadeSeek(
                primaryPositionMs = 10_040L,
                secondaryPositionMs = 10_000L,
                maximumDriftMs = 75L,
            ),
        )
        assertFalse(needsCorrectiveCrossfadeSeek(10_075L, 10_000L, maximumDriftMs = 75L))
    }

    @Test
    fun `corrective seek is required when drift exceeds tolerance`() {
        assertTrue(needsCorrectiveCrossfadeSeek(9_900L, 10_000L, maximumDriftMs = 75L))
        assertTrue(needsCorrectiveCrossfadeSeek(10_100L, 10_000L, maximumDriftMs = 75L))
    }

    @Test
    fun `playback position must move forward after a seek`() {
        assertFalse(hasPlaybackPositionAdvanced(positionAfterSeekMs = 10_000L, currentPositionMs = 10_000L))
        assertFalse(hasPlaybackPositionAdvanced(positionAfterSeekMs = 10_000L, currentPositionMs = 9_999L))
        assertTrue(hasPlaybackPositionAdvanced(positionAfterSeekMs = 10_000L, currentPositionMs = 10_001L))
    }

    @Test
    fun `equal power gains preserve power across the fade`() {
        val start = equalPowerGains(0f)
        val midpoint = equalPowerGains(0.5f)
        val end = equalPowerGains(1f)

        assertEquals(1f, start.outgoing, 0.0001f)
        assertEquals(0f, start.incoming, 0.0001f)
        assertEquals(1f, end.incoming, 0.0001f)
        assertEquals(0f, end.outgoing, 0.0001f)
        assertEquals(
            1f,
            midpoint.outgoing * midpoint.outgoing + midpoint.incoming * midpoint.incoming,
            0.0001f,
        )
    }
}
