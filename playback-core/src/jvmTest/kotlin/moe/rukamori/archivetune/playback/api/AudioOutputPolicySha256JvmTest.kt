/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import java.security.MessageDigest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioOutputPolicySha256JvmTest {
    @Test
    fun persistentIdMatchesJvmMessageDigestSha256Exactly() {
        val samples = listOf(
            AudioOutputIdentity(1, 8, "AA:BB:CC:DD:EE:FF", "AirPods Pro", "Bluetooth"),
            AudioOutputIdentity(2, 22, null, "  USB-C   DAC  ", "USB"),
            AudioOutputIdentity(3, 2, "", "", "Built-in Speaker"),
            AudioOutputIdentity(4, 7, "long-address-" + "x".repeat(120), "Headset", "Fallback"),
        )
        for (sample in samples) {
            val address = sample.address?.trim()?.replace(Regex("\\s+"), " ")?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
            val product = sample.productName?.trim()?.replace(Regex("\\s+"), " ")?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
            val fallback = sample.fallbackName.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            val discriminator = if (address != null) "address:$address" else "name:${product ?: fallback}"
            val expectedHash = MessageDigest.getInstance("SHA-256")
                .digest(discriminator.toByteArray(Charsets.UTF_8))
                .take(16)
                .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
            assertEquals("android-audio-v1|${sample.type}|$expectedHash", AudioOutputPolicy.persistentId(sample))
        }
    }
}
