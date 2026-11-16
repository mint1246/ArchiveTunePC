package moe.rukamori.archivetune.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicSearchParityTest {
    @Test fun searchKeepsAlbumsArtistsAndSongsInBackendOrder() {
        val json = Json.parseToJsonElement("""{"contents":[
          {"musicTwoRowItemRenderer":{"title":{"simpleText":"Album"},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREalbum"}}}},
          {"videoRenderer":{"videoId":"aa11bb22cc3","title":{"simpleText":"Song"}}},
          {"musicTwoRowItemRenderer":{"title":{"simpleText":"Artist"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}}}
        ]}""")
        val items = CatalogParsing.searchItems(json)
        assertEquals(3, items.size)
        assertEquals(MusicBrowseKind.ALBUM, (items[0] as MusicCatalogItem.Browse).kind)
        assertEquals("aa11bb22cc3", (items[1] as MusicCatalogItem.Track).track.id)
        assertEquals(MusicBrowseKind.ARTIST, (items[2] as MusicCatalogItem.Browse).kind)
    }

    @Test fun searchHistoryEditsBeforeRestorePersistWithoutResurrectingDeletedQueries() = runTest {
        val read = CompletableDeferred<String?>()
        var saved: String? = null
        val store = object : MusicStore {
            override suspend fun read() = read.await()
            override suspend fun write(json: String) { saved = json }
        }
        val library = MusicLibrary(store, backgroundScope)
        library.recordSearch("Björk")
        library.removeSearch("Old query")
        read.complete("""{"version":1,"searchHistory":["Old query","Existing"]}""")
        library.awaitLoaded()
        assertEquals(listOf("Björk", "Existing"), library.state.value.searchHistory)
        library.close()
        val restored = MusicLibrary(object : MusicStore {
            override suspend fun read() = saved
            override suspend fun write(json: String) {}
        }, backgroundScope)
        restored.awaitLoaded()
        assertEquals(listOf("Björk", "Existing"), restored.state.value.searchHistory)
        restored.close()
    }

    @Test fun obsoleteSuggestionsCannotReplaceNewerInputAndFiltersHaveSeparateCaches() = runTest {
        val old = CompletableDeferred<List<String>>()
        var calls = 0
        val catalog = object : MusicCatalog {
            override suspend fun search(query: String) = emptyList<MusicTrack>()
            override suspend fun searchItems(query: String, params: String?): List<MusicCatalogItem> {
                calls++
                return listOf(MusicCatalogItem.Browse(params ?: "all", "Result"))
            }
            override suspend fun searchSuggestions(query: String): List<String> =
                if (query == "old") withContext(NonCancellable) { old.await() } else listOf("New suggestion")
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
        controller.suggest("old"); runCurrent()
        controller.suggest("new"); runCurrent()
        old.complete(listOf("Old suggestion")); runCurrent()
        assertEquals(listOf("New suggestion"), controller.state.value.suggestions)
        controller.search("Music"); runCurrent()
        controller.search("Music", "albums"); runCurrent()
        assertEquals("albums", (controller.state.value.searchItems.single() as MusicCatalogItem.Browse).id)
        controller.search("Music"); runCurrent()
        assertEquals("all", (controller.state.value.searchItems.single() as MusicCatalogItem.Browse).id)
        assertEquals(2, calls)
        controller.close()
    }
}
