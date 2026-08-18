/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistLinkTest {
    private val id = "37i9dQZF1DXcBWIGoYBM5M"

    @Test
    fun `parses public playlist url with query parameters`() {
        val parsed = SpotifyPlaylistLink.parse("https://open.spotify.com/playlist/$id?si=deadbeef")
        assertEquals(id, parsed.getOrThrow())
    }

    @Test
    fun `parses localized playlist url`() {
        val parsed = SpotifyPlaylistLink.parse("https://open.spotify.com/intl-de/playlist/$id")
        assertEquals(id, parsed.getOrThrow())
    }

    @Test
    fun `parses spotify playlist uri`() {
        val parsed = SpotifyPlaylistLink.parse("spotify:playlist:$id")
        assertEquals(id, parsed.getOrThrow())
    }

    @Test
    fun `rejects non-playlist spotify links`() {
        assertTrue(SpotifyPlaylistLink.parse("https://open.spotify.com/album/$id").isFailure)
    }

    @Test
    fun `rejects non-spotify hosts`() {
        assertTrue(SpotifyPlaylistLink.parse("https://example.com/playlist/$id").isFailure)
    }
}
