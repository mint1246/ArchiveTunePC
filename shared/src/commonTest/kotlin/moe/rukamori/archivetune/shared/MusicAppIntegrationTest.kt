package moe.rukamori.archivetune.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicAppIntegrationTest {
    private val saved = MusicTrack("saved", "Saved song")
    private val clicked = MusicTrack("clicked", "Clicked song")

    @Test
    fun sanitizingInvalidQueueEntriesPreservesTheSelectedSongIdentity() = runTest {
        val store = MemoryStore()
        val library = MusicLibrary(store, backgroundScope)
        library.saveQueue(listOf(MusicTrack("", "Invalid"), clicked, saved), 1)
        library.close()
        val reopened = MusicLibrary(store, backgroundScope)
        reopened.awaitLoaded()
        assertEquals(listOf(clicked, saved), reopened.state.value.queue)
        assertEquals(clicked, reopened.state.value.queue[reopened.state.value.queueIndex])
        reopened.close()

        val corrupt = MemoryStore("""{"queue":{"tracks":[{"id":""},{"id":"clicked","title":"Clicked song"},{"id":"saved","title":"Saved song"}],"index":1}}""")
        val restored = MusicLibrary(corrupt, backgroundScope)
        restored.awaitLoaded()
        assertEquals(clicked, restored.state.value.queue[restored.state.value.queueIndex])
        restored.close()
    }

    @Test
    fun restoringAQueuePreservesSelectedDuplicateWithoutStartingAudio() = runTest {
        val store = MemoryStore()
        val original = MusicLibrary(store, backgroundScope)
        original.saveQueue(listOf(saved, clicked, saved), 2)
        original.close()
        val library = MusicLibrary(store, backgroundScope)
        val player = Engine()
        val controller = MusicController(Catalog(), player, backgroundScope, libraryController = library)
        runCurrent()
        assertEquals(listOf(saved, clicked, saved), controller.state.value.queue)
        assertEquals(2, controller.state.value.queueIndex)
        assertTrue(player.loaded.isEmpty())
        assertFalse(player.state.value.isPlaying)
        controller.closeAndFlush()
    }

    @Test
    fun selectionDuringStartupWinsOverTheDelayedRestoredQueue() = runTest {
        val old = MemoryStore()
        val original = MusicLibrary(old, backgroundScope)
        original.saveQueue(listOf(saved), 0)
        original.close()
        val read = CompletableDeferred<String?>()
        val delayed = object : MusicStore {
            var stored: String? = null
            override suspend fun read() = read.await()
            override suspend fun write(json: String) { stored = json }
        }
        val library = MusicLibrary(delayed, backgroundScope)
        val player = Engine()
        val controller = MusicController(Catalog(), player, backgroundScope, libraryController = library)
        controller.playAt(1, listOf(clicked, clicked))
        runCurrent()
        read.complete(old.value)
        runCurrent()
        assertEquals(listOf(clicked, clicked), controller.state.value.queue)
        assertEquals(1, controller.state.value.queueIndex)
        assertEquals(listOf(clicked), player.loaded)
        controller.closeAndFlush()
        val reopened = MusicLibrary(MemoryStore(delayed.stored), backgroundScope)
        reopened.awaitLoaded()
        assertEquals(listOf(clicked, clicked), reopened.state.value.queue)
        assertEquals(1, reopened.state.value.queueIndex)
        reopened.close()
    }

    @Test
    fun lyricsFollowActualLoadedTrackAndSurviveCatalogNavigation() = runTest {
        val requests = mutableListOf<String>()
        var closed = false
        val repository = object : LyricsRepository {
            override suspend fun fetch(track: MusicTrack): List<LyricsLine> {
                requests += track.id
                return listOf(LyricsLine(0, "Fixture ${track.title}"))
            }
            override fun close() { closed = true }
        }
        val lyrics = LyricsController(repository, backgroundScope)
        val controller = MusicController(Catalog(), Engine(), backgroundScope, lyricsController = lyrics)
        controller.playAt(0, listOf(saved, clicked))
        runCurrent()
        controller.search("another page")
        runCurrent()
        assertEquals(saved.id, controller.lyrics.value.trackId)
        assertEquals(listOf(saved.id), requests)
        controller.next()
        runCurrent()
        assertEquals(clicked.id, controller.lyrics.value.trackId)
        assertEquals(listOf(saved.id, clicked.id), requests)
        controller.closeAndFlush()
        assertTrue(closed)
    }

    private class MemoryStore(var value: String? = null) : MusicStore {
        override suspend fun read() = value
        override suspend fun write(json: String) { value = json }
    }

    private class Catalog : MusicCatalog {
        override suspend fun search(query: String) = emptyList<MusicTrack>()
        override suspend fun resolve(track: MusicTrack) = ResolvedAudio(track.id, "https://example.invalid/audio", "audio/mp4")
        override fun close() {}
    }

    private class Engine : PlayerEngine {
        override val state = MutableStateFlow(PlaybackState())
        val loaded = mutableListOf<MusicTrack>()
        override fun load(track: MusicTrack, source: ResolvedAudio) {
            loaded += track
            state.value = PlaybackState(track = track)
        }
        override fun play() { state.value = state.value.copy(isPlaying = true) }
        override fun pause() { state.value = state.value.copy(isPlaying = false) }
        override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
        override fun stop() { state.value = PlaybackState() }
        override fun close() {}
    }
}
