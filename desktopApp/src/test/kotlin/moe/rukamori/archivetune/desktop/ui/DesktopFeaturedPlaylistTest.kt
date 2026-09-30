package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.pages.HomePage
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFeaturedPlaylistTest {
    @Test fun `server playlist identity wins over carousel card identity`() {
        val card = HomePage.Section.FeaturedCard(
            "carousel-card", "Playlist", null, null, BrowseEndpoint("VLbrowse-id"),
            WatchEndpoint(playlistId = "explicit-playlist"), null, null, emptyList(),
        )
        assertEquals("explicit-playlist", card.asDesktopPlaylist().id)
        assertEquals("browse-id", card.copy(playEndpoint = null).asDesktopPlaylist().id)
    }
}
