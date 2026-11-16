/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import moe.rukamori.archivetune.playback.api.equalPowerGains

import kotlin.test.Test
import kotlin.test.assertEquals

class CrossfadeHandoffPolicyTest {
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
