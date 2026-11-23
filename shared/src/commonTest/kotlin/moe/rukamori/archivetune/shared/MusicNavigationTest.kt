package moe.rukamori.archivetune.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicNavigationTest {
    @Test fun detailBackRestoresEarlierDataAndTabChangeClearsTheStack() = runTest {
        var calls = 0
        val catalog = object : MusicCatalog {
            override suspend fun browse(id: String, params: String?): MusicBrowsePage {
                calls++
                return MusicBrowsePage(id, tracks = listOf(MusicTrack("aa11bb22cc3", id)))
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
        controller.openBrowse(MusicCatalogItem.Browse("A", "Album")); runCurrent()
        val earlier = controller.browse.value
        controller.openBrowse(MusicCatalogItem.Browse("B", "Artist")); runCurrent()
        assertEquals(2, controller.browse.value.depth)
        controller.closeBrowse(); runCurrent()
        assertEquals(earlier, controller.browse.value)
        assertEquals(2, calls)
        controller.openBrowse(MusicCatalogItem.Browse("C", "Playlist")); runCurrent()
        controller.clearBrowse(); runCurrent()
        assertNull(controller.browse.value.item)
        controller.closeBrowse()
        assertNull(controller.browse.value.item)
        controller.close()
    }
}
