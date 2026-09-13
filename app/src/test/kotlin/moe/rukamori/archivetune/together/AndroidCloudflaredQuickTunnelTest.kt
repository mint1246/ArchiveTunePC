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

    @Test
    fun resolverConfigUsesAndroidDnsServersAndStripsIpv6Scope() {
        assertEquals(
            "nameserver 192.168.1.1\nnameserver fe80::1\noptions timeout:2 attempts:2\n",
            AndroidCloudflaredQuickTunnel.resolverConfig(
                listOf("192.168.1.1", "fe80::1%wlan0", "192.168.1.1"),
            ),
        )
    }

    @Test
    fun resolverConfigFallsBackWhenAndroidReportsNoDnsServers() {
        assertEquals(
            "nameserver 1.1.1.1\nnameserver 1.0.0.1\noptions timeout:2 attempts:2\n",
            AndroidCloudflaredQuickTunnel.resolverConfig(emptyList()),
        )
    }

    @Test
    fun cloudflaredUsesAndroidSystemCertificateDirectories() {
        assertEquals(
            "/apex/com.android.conscrypt/cacerts${java.io.File.pathSeparator}/system/etc/security/cacerts",
            AndroidCloudflaredQuickTunnel.androidCaDirectories(),
        )
    }
}
