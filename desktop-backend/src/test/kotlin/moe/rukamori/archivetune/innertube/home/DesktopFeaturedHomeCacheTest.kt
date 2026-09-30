package moe.rukamori.archivetune.innertube.home

import moe.rukamori.archivetune.innertube.cache.ytItemFromJson
import moe.rukamori.archivetune.innertube.cache.ytItemToJson
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.pages.HomePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DesktopFeaturedHomeCacheTest {
    @Test fun `featured only shelves survive persistent cache with playlist identity`() {
        val card = HomePage.Section.FeaturedCard(
            "playlist", "Community", "Curated by a listener", "https://example.com/art.jpg",
            BrowseEndpoint("VLplaylist"), WatchEndpoint(playlistId = "playlist"),
            WatchEndpoint(playlistId = "playlist", params = "shuffle"), null, listOf("episode"),
        )
        val section = HomePage.Section("Community playlists", null, null, null, emptyList(), featuredCards = listOf(card))
        assertEquals(section, sectionFromJson(sectionToJson(section)))
    }

    @Test fun `podcasts and episodes retain their endpoint and descriptive metadata`() {
        val show = PodcastItem("MPSPshow", "PLshow", "Show", Artist("Host", "host"), null)
        val episode = EpisodeItem(
            "episode", null, "Episode", Artist("Show", "MPSPshow"), "Description", "Today", "1:00", 60,
            "", WatchEndpoint(videoId = "episode", playlistId = "PLshow", index = 3),
        )
        listOf(show, episode).forEach { assertEquals(it, ytItemFromJson(ytItemToJson(it))) }
    }

    @Test fun `old shelves without featured card fields remain readable`() {
        val section = sectionFromJson(org.json.JSONObject("""{"title":"Shelf","items":[{"type":"song","id":"song","title":"Song","thumbnail":""}]}"""))
        assertNotNull(section)
        assertEquals(emptyList<HomePage.Section.FeaturedCard>(), section!!.featuredCards)
    }
}
