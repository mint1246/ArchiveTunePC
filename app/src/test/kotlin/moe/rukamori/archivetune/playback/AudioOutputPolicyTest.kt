/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioOutputPolicyTest {
    @Test
    fun `address identity survives runtime device id changes`() {
        val first = identity(runtimeId = 4, address = "AA:BB:CC", productName = "Headphones")
        val reconnected = identity(runtimeId = 27, address = "aa:bb:cc", productName = "Headphones")

        val persistedId = AudioOutputPolicy.persistentId(first)
        val choices = AudioOutputPolicy.normalize(listOf(reconnected))

        assertEquals(27, AudioOutputPolicy.resolveRuntimeId(persistedId, choices))
    }

    @Test
    fun `name fallback is normalized and stable when address is unavailable`() {
        val first = identity(runtimeId = 1, address = null, productName = "  USB   DAC ")
        val reconnected = identity(runtimeId = 9, address = null, productName = "usb dac")

        assertEquals(
            AudioOutputPolicy.persistentId(first),
            AudioOutputPolicy.persistentId(reconnected),
        )
    }

    @Test
    fun `duplicate stable identities are collapsed`() {
        val choices =
            AudioOutputPolicy.normalize(
                listOf(
                    identity(runtimeId = 3, address = "usb:1", productName = "DAC"),
                    identity(runtimeId = 8, address = "USB:1", productName = "DAC duplicate"),
                ),
            )

        assertEquals(1, choices.size)
        assertEquals(3, choices.single().runtimeId)
    }

    @Test
    fun `missing saved device falls back to system default`() {
        val choices = AudioOutputPolicy.normalize(listOf(identity(runtimeId = 2, address = "wired:2")))

        assertNull(AudioOutputPolicy.resolveRuntimeId("android-audio-v1|8|address:missing", choices))
        assertNull(AudioOutputPolicy.resolveRuntimeId(null, choices))
    }

    @Test
    fun `product name wins for display and blank product uses fallback`() {
        assertEquals(
            "Pixel Buds",
            AudioOutputPolicy.displayName(identity(runtimeId = 1, productName = " Pixel Buds ")),
        )
        assertEquals(
            "Speaker",
            AudioOutputPolicy.displayName(identity(runtimeId = 1, productName = "  ", fallbackName = "Speaker")),
        )
    }

    private fun identity(
        runtimeId: Int,
        type: Int = 8,
        address: String? = null,
        productName: String? = null,
        fallbackName: String = "Audio output",
    ) =
        AudioOutputIdentity(
            runtimeId = runtimeId,
            type = type,
            address = address,
            productName = productName,
            fallbackName = fallbackName,
        )
}
