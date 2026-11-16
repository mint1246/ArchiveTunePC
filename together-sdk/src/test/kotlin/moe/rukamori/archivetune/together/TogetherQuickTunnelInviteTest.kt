/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherQuickTunnelInviteTest {
    @Test
    fun generatedTokenMatchesDesktopUrlSafeShape() {
        val token = TogetherQuickTunnelInvite.randomSessionKey()

        assertEquals(32, token.length)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{32}")))
    }

    @Test
    fun encodeProducesCanonicalCloudflarePartyUrlAndRoundTrips() {
        val invite =
            TogetherQuickTunnelInvite.encode(
                publicBaseUrl = "https://small-cloud-name.trycloudflare.com",
                sessionKey = "0123456789abcdef",
            )

        assertEquals("https://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef", invite)
        val decoded = TogetherQuickTunnelInvite.decode(invite)!!
        assertEquals(TogetherQuickTunnelInvite.sessionIdForKey("0123456789abcdef"), decoded.sessionId)
        assertEquals("0123456789abcdef", decoded.sessionKey)
        assertEquals(
            "wss://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef",
            decoded.wsUrl,
        )
    }

    @Test
    fun decoderRejectsOldOnlineCodesAndNonQuickTunnelUrls() {
        assertNull(TogetherQuickTunnelInvite.decode("archivetune://together?code=AB_cd-12"))
        assertNull(TogetherQuickTunnelInvite.decode("https://example.com/party?token=0123456789abcdef&sid=session"))
        assertNull(TogetherQuickTunnelInvite.decode("https://api.trycloudflare.com/party?token=0123456789abcdef"))
        assertNull(TogetherQuickTunnelInvite.decode("http://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef&sid=session"))
    }

    @Test
    fun decoderCanonicalizesWssInviteToHttpsForSharing() {
        val decoded =
            TogetherQuickTunnelInvite.decode(
                "wss://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef&sid=session",
            )!!

        assertEquals(
            "https://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef",
            decoded.inviteUrl,
        )
    }

    @Test
    fun oldSidInviteStillDecodesButCanonicalizesToDesktopShape() {
        val decoded =
            TogetherQuickTunnelInvite.decode(
                "https://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef&sid=session",
            )!!

        assertEquals("session", decoded.sessionId)
        assertEquals(
            "https://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef",
            decoded.inviteUrl,
        )
    }
}
