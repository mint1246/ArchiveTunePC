package moe.rukamori.archivetune.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicHomeTest {
    @Test
    fun featuredPlaylistRetainsItsPreviewOrderInBothAndroidShelfForms() {
        val card = """{"musicCardShelfRenderer":{
          "header":{"musicCardShelfHeaderBasicRenderer":{"title":{"simpleText":"Featured"}}},
          "title":{"simpleText":"A playlist"},"subtitle":{"simpleText":"YouTube Music"},
          "onTap":{"browseEndpoint":{"browseId":"VLfixture"}},
          "contents":[
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"aa11bb22cc3"},"title":{"simpleText":"First"}}},
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"dd44ee55ff6"},"title":{"simpleText":"Second"}}},
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"aa11bb22cc3"},"title":{"simpleText":"Duplicate"}}}
          ]}}"""
        val standalone = CatalogParsing.home(Json.parseToJsonElement(card)).single()
        val carousel = CatalogParsing.home(Json.parseToJsonElement("""{"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"simpleText":"For you"}}},
            "contents":[$card]}}""")).single()
        assertEquals("Featured", standalone.title)
        assertEquals("For you", carousel.title)
        for (shelf in listOf(standalone, carousel)) {
            val featured = shelf.featuredCards.single()
            assertEquals("VLfixture", featured.item.id)
            assertEquals("A playlist", featured.item.title)
            assertEquals(listOf("aa11bb22cc3", "dd44ee55ff6", "aa11bb22cc3"), featured.tracks.map { it.id })
            assertEquals(featured.tracks, shelf.tracks)
        }
    }

    @Test
    fun namedShelvesRetainBackendTitlesAndTrackIdentity() {
        val response = Json.parseToJsonElement("""{
          "contents":{"sectionListRenderer":{"contents":[{"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Listen again"}]}}},
            "contents":[{"musicTwoRowItemRenderer":{
              "title":{"runs":[{"text":"A song"}]},"subtitle":{"runs":[{"text":"An artist"}]},
              "navigationEndpoint":{"watchEndpoint":{"videoId":"aa11bb22cc3"}}
            }}]
          }}]}}
        }""")
        val shelves = CatalogParsing.home(response)
        assertEquals(listOf("Listen again"), shelves.map { it.title })
        assertEquals(listOf("aa11bb22cc3"), shelves.single().tracks.map { it.id })
    }

    @Test
    fun mixedShelfPreservesBrowseCardsAndCategoryEndpoints() {
        val response = Json.parseToJsonElement("""{
          "header":{"chipCloudChipRenderer":{"text":{"runs":[{"text":"Workout"}]},
            "navigationEndpoint":{"browseEndpoint":{"browseId":"FEmusic_home","params":"workout"}}}},
          "contents":[{"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"simpleText":"For you"},"strapline":{"simpleText":"Recommended"}}},
            "contents":[
              {"musicTwoRowItemRenderer":{"title":{"simpleText":"Mix"},"subtitle":{"simpleText":"A playlist"},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"VL123","params":"p"}},
                "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/a.jpg"}]}}}}},
              {"musicTwoRowItemRenderer":{"title":{"simpleText":"Song"},"navigationEndpoint":{"watchEndpoint":{"videoId":"aa11bb22cc3"}}}},
              {"musicTwoRowItemRenderer":{"title":{"simpleText":"Artist"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UC123"}}}}
            ]}}]}
        """)
        val shelf = CatalogParsing.home(response).single()
        assertEquals("Recommended", shelf.subtitle)
        assertEquals(3, shelf.items.size)
        val playlist = shelf.items.first() as MusicCatalogItem.Browse
        assertEquals(MusicBrowseKind.PLAYLIST, playlist.kind)
        assertEquals("VL123", playlist.id)
        assertEquals("p", playlist.params)
        assertEquals("https://i.ytimg.com/a.jpg", playlist.artworkUrl)
        assertEquals(MusicBrowseKind.ARTIST, (shelf.items.last() as MusicCatalogItem.Browse).kind)
        assertEquals(listOf("aa11bb22cc3"), shelf.tracks.map { it.id })
        assertEquals(listOf(MusicHomeChip("Workout", "FEmusic_home", "workout")), CatalogParsing.homeChips(response))
    }

    @Test
    fun browsePreservesDuplicateTracksInOrder() {
        val response = Json.parseToJsonElement("""{
          "header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"My playlist"},"subtitle":{"simpleText":"Owner"}}},
          "contents":{"musicPlaylistShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"aa11bb22cc3"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"First"}}}]}},
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"dd44ee55ff6"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Second"}}}]}},
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"aa11bb22cc3"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"First"}}}]}}
          ]}}}
        """)
        val page = CatalogParsing.browse(response)
        assertEquals("My playlist", page.title)
        assertEquals("Owner", page.subtitle)
        assertEquals(listOf("aa11bb22cc3", "dd44ee55ff6", "aa11bb22cc3"), page.tracks.map { it.id })
    }

    @Test
    fun repeatedHomeEntryCoalescesAndFailedRefreshKeepsUsableContent() = runTest {
        var calls = 0
        var fail = false
        val ready = CompletableDeferred<Unit>()
        val shelf = MusicShelf("Music", listOf(MusicTrack("aa11bb22cc3", "A song")))
        val catalog = object : MusicCatalog {
            override suspend fun home(): List<MusicShelf> {
                calls++
                ready.await()
                if (fail) error("offline")
                return listOf(shelf)
            }
            override suspend fun search(query: String) = emptyList<MusicTrack>()
            override suspend fun resolve(track: MusicTrack): ResolvedAudio = error("unused")
            override fun close() {}
        }
        val player = object : PlayerEngine {
            override val state = MutableStateFlow(PlaybackState())
            override fun load(track: MusicTrack, source: ResolvedAudio) {}
            override fun play() {}
            override fun pause() {}
            override fun seekTo(positionMs: Long) {}
            override fun stop() {}
            override fun close() {}
        }
        val controller = MusicController(catalog, player, backgroundScope)
        controller.loadHome()
        controller.loadHome()
        runCurrent()
        assertEquals(1, calls)
        ready.complete(Unit)
        runCurrent()
        assertEquals(listOf(shelf), controller.home.value.shelves)
        controller.loadHome()
        runCurrent()
        assertEquals(1, calls)
        fail = true
        controller.loadHome(force = true)
        assertTrue(controller.home.value.loading)
        assertEquals(listOf(shelf), controller.home.value.shelves)
        runCurrent()
        assertFalse(controller.home.value.loading)
        assertTrue(controller.home.value.error != null)
        assertEquals(listOf(shelf), controller.home.value.shelves)
        controller.close()
    }
}
