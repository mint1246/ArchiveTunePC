/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TogetherLinkTest {
    @Test
    fun onlineCodeRoundTripsThroughShareableDeepLink() {
        val link = TogetherLink.encodeOnlineCode("AB_cd-12")

        assertEquals("AB_cd-12", TogetherLink.decodeOnlineCode(link))
        assertNull(TogetherLink.decode(link))
    }

    @Test
    fun lanAndOnlineLinksDoNotCrossDecode() {
        val lan =
            TogetherLink.encode(
                TogetherJoinInfo(
                    host = "192.168.1.20",
                    port = 42117,
                    sessionId = "session",
                    sessionKey = "secret",
                ),
            )

        assertNull(TogetherLink.decodeOnlineCode(lan))
        assertEquals("192.168.1.20", TogetherLink.decode(lan)?.host)
    }
}
