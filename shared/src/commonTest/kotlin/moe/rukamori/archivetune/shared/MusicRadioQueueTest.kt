package moe.rukamori.archivetune.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicRadioQueueTest {
    @Test fun radioArrivingAfterSleepAtEndCannotRestartOrAdvanceAudio() = runTest {
        val ready = CompletableDeferred<MusicRadioQueue>()
        val player = Player()
        val controller = MusicController(catalog { ready.await() }, player, backgroundScope)
        controller.playTrack(clicked); runCurrent()
        controller.setSleepAfterCurrent()
        player.state.value = player.state.value.copy(ended = true, isPlaying = false)
        runCurrent()
        ready.complete(MusicRadioQueue(listOf(clicked, other))); runCurrent()
        assertEquals(clicked, player.state.value.track)
        assertFalse(player.state.value.isPlaying)
        assertEquals(0, controller.state.value.queueIndex)
        assertEquals(1, player.loads)
        controller.close()
    }

    private val clicked = MusicTrack("aa11bb22cc3", "Clicked song")
    private val other = MusicTrack("dd44ee55ff6", "Other song")
    private class Player : PlayerEngine {
        override val state = MutableStateFlow(PlaybackState())
        var loads = 0
        override fun load(track: MusicTrack, source: ResolvedAudio) {
            loads++
            state.value = PlaybackState(track = track)
        }
        override fun play() { state.value = state.value.copy(isPlaying = true) }
        override fun pause() { state.value = state.value.copy(isPlaying = false) }
        override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
        override fun stop() { state.value = PlaybackState() }
        override fun close() {}
    }

    @Test fun serverSelectionCannotReplaceClickedIdentityAndMissingClickFallsBack() {
        val response = Json.parseToJsonElement("""{
          "currentIndex":0,"contents":[
            {"musicQueueHeaderRenderer":{"subtitle":{"simpleText":"Clicked song Mix"}}},
            {"playlistPanelVideoRenderer":{"videoId":"dd44ee55ff6","title":{"simpleText":"Other"},"selected":true}},
            {"playlistPanelVideoRenderer":{"videoId":"aa11bb22cc3","title":{"simpleText":"Clicked"}}}
          ]} """)
        val queue = CatalogParsing.radioQueue(response, clicked)
        assertEquals(listOf(other.id, clicked.id), queue.tracks.map { it.id })
        assertEquals("Clicked song Mix", queue.title)
        val absent = MusicTrack("gg77hh88ii9", "Absent")
        assertEquals(listOf(absent), CatalogParsing.radioQueue(response, absent).tracks)
    }

    @Test fun radioLoadsAfterAudioStartsWithoutReloadingOrLosingPosition() = runTest {
        val ready = CompletableDeferred<MusicRadioQueue>()
        val player = Player()
        val catalog = catalog { ready.await() }
        val controller = MusicController(catalog, player, backgroundScope)
        controller.playTrack(clicked)
        runCurrent()
        assertEquals(clicked, player.state.value.track)
        assertTrue(player.state.value.isPlaying)
        player.seekTo(25_000)
        ready.complete(MusicRadioQueue(listOf(other, clicked, other), "Song Mix"))
        runCurrent()
        assertEquals(1, player.loads)
        assertEquals(25_000, player.state.value.positionMs)
        assertEquals(1, controller.state.value.queueIndex)
        assertEquals("Song Mix", controller.state.value.queueTitle)
        controller.next(); runCurrent()
        assertEquals(other, player.state.value.track)
        controller.close()
    }

    @Test fun lateUncancellableRadioCannotOverwriteANewerClick() = runTest {
        val ready = CompletableDeferred<MusicRadioQueue>()
        val player = Player()
        val catalog = catalog { track ->
            if (track == clicked) withContext(NonCancellable) { ready.await() }
            else MusicRadioQueue(listOf(other), "New Mix")
        }
        val controller = MusicController(catalog, player, backgroundScope)
        controller.playTrack(clicked); runCurrent()
        controller.playTrack(other); runCurrent()
        ready.complete(MusicRadioQueue(listOf(clicked, other), "Old Mix")); runCurrent()
        assertEquals(other, player.state.value.track)
        assertEquals(listOf(other), controller.state.value.queue)
        assertEquals("New Mix", controller.state.value.queueTitle)
        controller.close()
    }

    private fun catalog(queue: suspend (MusicTrack) -> MusicRadioQueue) = object : MusicCatalog {
        override suspend fun radioQueue(clicked: MusicTrack) = queue(clicked)
        override suspend fun search(query: String) = emptyList<MusicTrack>()
        override suspend fun resolve(track: MusicTrack) = ResolvedAudio(track.id, "https://example.invalid/audio", "audio/mp4")
        override fun close() {}
    }
}
