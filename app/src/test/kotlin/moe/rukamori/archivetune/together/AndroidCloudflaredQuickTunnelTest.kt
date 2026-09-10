/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidCloudflaredQuickTunnelTest {
    @Test
    fun bootstrapApiUrlIsIgnoredUntilRealQuickTunnelAppears() {
        assertNull(
            AndroidCloudflaredQuickTunnel.publishedQuickTunnelUrl(
                "Requesting new quick Tunnel on https://api.trycloudflare.com/tunnel",
            ),
        )
        assertEquals(
            "https://sofa-commentary-counters-moore.trycloudflare.com",
            AndroidCloudflaredQuickTunnel.publishedQuickTunnelUrl(
                "Your quick Tunnel has been created! Visit it at https://sofa-commentary-counters-moore.trycloudflare.com",
            ),
        )
    }
}
