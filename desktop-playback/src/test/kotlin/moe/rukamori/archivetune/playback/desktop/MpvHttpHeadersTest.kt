/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MpvHttpHeadersTest {
    @Test
    fun `preserves youtube client headers as separate mpv fields`() {
        val headers =
            linkedMapOf(
                "User-Agent" to "ArchiveTune test client",
                "Referer" to "https://music.youtube.com/",
                "Origin" to "https://music.youtube.com",
                "Cookie" to "session=private",
            )

        assertEquals(
            listOf(
                "User-Agent: ArchiveTune test client",
                "Referer: https://music.youtube.com/",
                "Origin: https://music.youtube.com",
                "Cookie: session=private",
            ),
            headers.toMpvHttpHeaders(),
        )
    }

    @Test
    fun `rejects CRLF injection without exposing the value`() {
        assertFailsWith<IllegalArgumentException> {
            mapOf("Cookie" to "private\r\nInjected: value").toMpvHttpHeaders()
        }
    }

    @Test
    fun `rejects malformed header names`() {
        assertFailsWith<IllegalArgumentException> {
            mapOf("Bad Header" to "value").toMpvHttpHeaders()
        }
    }
}
