package moe.rukamori.archivetune.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicControllerTest {
    private val first = MusicTrack("first", "First")
    private val second = MusicTrack("second", "Second")

    @Test
    fun sourceDescriptionNeverContainsPlaybackUrlOrHeaders() {
        val source = ResolvedAudio("id", "https://example.invalid/private?signature=secret", "audio/mp4",
            mapOf("Authorization" to "private-token"))
        assertFalse(source.toString().contains("secret"))
        assertFalse(source.toString().contains("private-token"))
        assertFalse(source.toString().contains("https://"))
    }

    @Test
    fun clickedQueueIndexPreservesDuplicateEntries() = runTest {
        val player = FakePlayer()
        val controller = MusicController(FakeCatalog(), player, backgroundScope)
        val songs = listOf(first, second, first)
        controller.playAt(2, songs)
        runCurrent()
        assertEquals(songs, controller.state.value.queue)
        assertEquals(2, controller.state.value.queueIndex)
        assertEquals(first, player.state.value.track)
        controller.close()
    }

    @Test
    fun mismatchedSourceNeverReachesPlayer() = runTest {
        val player = FakePlayer()
        val catalog = FakeCatalog().apply { resolveBlock = { audio(second) } }
        val controller = MusicController(catalog, player, backgroundScope)
        controller.playAt(0, listOf(first))
        runCurrent()
        assertTrue(player.loaded.isEmpty())
        assertFalse(player.state.value.isPlaying)
        assertTrue(controller.state.value.error != null)
        assertNull(controller.state.value.resolvingTrackId)
        controller.close()
    }

    @Test
    fun obsoleteNonCooperativeResolutionCannotStartWrongSong() = runTest {
        val pending = CompletableDeferred<ResolvedAudio>()
        val player = FakePlayer()
        val catalog = FakeCatalog().apply {
            resolveBlock = { track -> if (track.id == first.id) withContext(NonCancellable) { pending.await() } else audio(track) }
        }
        val controller = MusicController(catalog, player, backgroundScope)
        controller.playAt(0, listOf(first, second))
        runCurrent()
        controller.next()
        runCurrent()
        pending.complete(audio(first))
        runCurrent()
        assertEquals(listOf(second.id), player.loaded.map { it.id })
        assertEquals(second, player.state.value.track)
        controller.close()
    }

    @Test
    fun replacingSelectionCancelsOldNetworkWork() = runTest {
        var cancelled = false
        val catalog = FakeCatalog().apply {
            resolveBlock = { track ->
                if (track.id == first.id) try { awaitCancellation() } finally { cancelled = true }
                audio(track)
            }
        }
        val controller = MusicController(catalog, FakePlayer(), backgroundScope)
        controller.playAt(0, listOf(first, second))
        runCurrent()
        controller.next()
        runCurrent()
        assertTrue(cancelled)
        controller.close()
    }

    @Test
    fun transportEndAdvancesExactlyOnce() = runTest {
        val player = FakePlayer()
        val controller = MusicController(FakeCatalog(), player, backgroundScope)
        controller.playAt(0, listOf(first, second))
        runCurrent()
        player.state.value = player.state.value.copy(ended = true, isPlaying = false)
        runCurrent()
        assertEquals(listOf(first.id, second.id), player.loaded.map { it.id })
        assertEquals(1, controller.state.value.queueIndex)
        player.state.value = player.state.value.copy(ended = true, isPlaying = false)
        runCurrent()
        assertEquals(2, player.loaded.size)
        controller.close()
    }

    @Test
    fun previousRestartsCurrentTrackAfterThreeSeconds() = runTest {
        val player = FakePlayer()
        val controller = MusicController(FakeCatalog(), player, backgroundScope)
        controller.playAt(1, listOf(first, second))
        runCurrent()
        player.state.value = player.state.value.copy(positionMs = 9_000)
        controller.previous()
        assertEquals(0, player.state.value.positionMs)
        assertEquals(1, controller.state.value.queueIndex)
        controller.close()
    }

    @Test
    fun staleSearchCannotReplaceNewerResults() = runTest {
        val oldSearch = CompletableDeferred<List<MusicTrack>>()
        val catalog = FakeCatalog().apply {
            searchBlock = { query -> if (query == "old") withContext(NonCancellable) { oldSearch.await() } else listOf(second) }
        }
        val controller = MusicController(catalog, FakePlayer(), backgroundScope)
        controller.search("old")
        runCurrent()
        controller.search("new")
        runCurrent()
        oldSearch.complete(listOf(first))
        runCurrent()
        assertEquals(listOf(second), controller.state.value.results)
        assertFalse(controller.state.value.searching)
        controller.close()
    }

    @Test
    fun recentSearchesAreReusedAndCacheIsBounded() = runTest {
        val catalog = FakeCatalog()
        val controller = MusicController(catalog, FakePlayer(), backgroundScope)
        controller.search(" first ")
        runCurrent()
        controller.search("first")
        runCurrent()
        assertEquals(1, catalog.searchCalls.size)
        repeat(12) { controller.search("song$it"); runCurrent() }
        controller.search("first")
        runCurrent()
        assertEquals(14, catalog.searchCalls.size)
        controller.close()
    }

    @Test
    fun retryResolvesFreshSourceWithoutChangingQueueIdentity() = runTest {
        var attempts = 0
        val player = FakePlayer()
        val catalog = FakeCatalog().apply {
            resolveBlock = { track ->
                attempts++
                if (attempts == 1) error("expired source")
                audio(track)
            }
        }
        val controller = MusicController(catalog, player, backgroundScope)
        controller.playAt(1, listOf(first, second, first))
        runCurrent()
        assertTrue(controller.state.value.failedPlayback)
        controller.retryPlayback()
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, controller.state.value.queueIndex)
        assertEquals(second, player.state.value.track)
        assertNull(controller.state.value.error)
        controller.close()
    }

    @Test
    fun playbackTogglePausesBufferingAndRestartsAnEndedSong() = runTest {
        val player = FakePlayer()
        val controller = MusicController(FakeCatalog(), player, backgroundScope)
        controller.playAt(0, listOf(first))
        runCurrent()
        player.state.value = player.state.value.copy(isPlaying = false, isBuffering = true)
        controller.togglePlayback()
        assertEquals(2, player.pauseCount) // selecting a song, then pausing buffering
        player.state.value = player.state.value.copy(ended = true, isPlaying = false, isBuffering = false, positionMs = 99_000)
        runCurrent()
        controller.togglePlayback()
        assertEquals(0, player.state.value.positionMs)
        assertTrue(player.state.value.isPlaying)
        controller.close()
    }

    @Test
    fun closeCancelsWorkAndPreventsFurtherPlayerCommands() = runTest {
        val player = FakePlayer()
        val catalog = FakeCatalog()
        val controller = MusicController(catalog, player, backgroundScope)
        controller.close()
        controller.close()
        controller.search("anything")
        controller.playAt(0, listOf(first))
        controller.togglePlayback()
        runCurrent()
        assertTrue(catalog.closed)
        assertEquals(1, player.closeCount)
        assertTrue(catalog.searchCalls.isEmpty())
        assertTrue(player.loaded.isEmpty())
    }

    private class FakeCatalog : MusicCatalog {
        var searchBlock: suspend (String) -> List<MusicTrack> = { emptyList() }
        var resolveBlock: suspend (MusicTrack) -> ResolvedAudio = { audio(it) }
        val searchCalls = mutableListOf<String>()
        var closed = false
        override suspend fun search(query: String): List<MusicTrack> { searchCalls += query; return searchBlock(query) }
        override suspend fun resolve(track: MusicTrack) = resolveBlock(track)
        override fun close() { closed = true }
    }

    private class FakePlayer : PlayerEngine {
        override val state = MutableStateFlow(PlaybackState())
        val loaded = mutableListOf<MusicTrack>()
        var closeCount = 0
        var pauseCount = 0
        override fun load(track: MusicTrack, source: ResolvedAudio) {
            loaded += track
            state.value = PlaybackState(track = track, durationMs = track.durationMs ?: 0)
        }
        override fun play() { state.value = state.value.copy(isPlaying = true) }
        override fun pause() { pauseCount++; state.value = state.value.copy(isPlaying = false) }
        override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
        override fun stop() { state.value = PlaybackState() }
        override fun close() { closeCount++ }
    }

    companion object {
        private fun audio(track: MusicTrack) = ResolvedAudio(track.id, "https://example.invalid/audio.m4a", "audio/mp4")
    }
}
