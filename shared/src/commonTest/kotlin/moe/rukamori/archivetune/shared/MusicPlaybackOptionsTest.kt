package moe.rukamori.archivetune.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MusicPlaybackOptionsTest {
    @Test fun thumbnailNeighborsFollowShuffleAndNeverWrapAtRepeatBoundary() = runTest {
        val player = Player()
        val controller = MusicController(catalog(), player, backgroundScope)
        controller.playAt(0, songs); runCurrent()
        controller.cycleRepeat()
        assertNull(controller.thumbnailNeighbor(-1))
        assertEquals(1, controller.thumbnailNeighbor(1))
        controller.toggleShuffle()
        val next = controller.thumbnailNeighbor(1)!!
        controller.playAt(next); runCurrent()
        assertEquals(0, controller.thumbnailNeighbor(-1))
        player.seekTo(25_000)
        controller.skipToPreviousItem(); runCurrent()
        assertEquals(0, controller.state.value.queueIndex)
        assertEquals(0, player.state.value.positionMs)
        controller.close()
    }

    @Test fun playAfterNativeFailureResolvesAgainInsteadOfReusingFailedItem() = runTest {
        val player = Player()
        var resolutions = 0
        val catalog = object : MusicCatalog {
            override suspend fun search(query: String) = songs
            override suspend fun resolve(track: MusicTrack): ResolvedAudio {
                resolutions++
                return ResolvedAudio(track.id, "https://example.invalid/audio", "audio/mp4")
            }
            override fun close() {}
        }
        val controller = MusicController(catalog, player, backgroundScope)
        controller.playAt(0, songs); runCurrent()
        player.state.value = player.state.value.copy(error = "Native decoder failure", isPlaying = false)
        controller.togglePlayback(); runCurrent()
        assertEquals(2, resolutions)
        assertNull(player.state.value.error)
        assertTrue(player.state.value.isPlaying)
        controller.close()
    }

    @Test fun movingActiveDuplicatePreservesItsOccurrenceAndPlaybackPosition() = runTest {
        val player = Player()
        val controller = MusicController(catalog(), player, backgroundScope)
        controller.playAt(2, songs); runCurrent(); player.seekTo(25_000)
        controller.moveQueueItem(2, 0)
        assertEquals(listOf(songs[2], songs[0], songs[1]), controller.state.value.queue)
        assertEquals(0, controller.state.value.queueIndex)
        assertEquals("Duplicate", player.state.value.track?.title)
        assertEquals(25_000, player.state.value.positionMs)
        controller.close()
    }

    @Test fun endOfSongTimerOverridesRepeatAndDismissClearsActualTransport() = runTest {
        val player = Player()
        val controller = MusicController(catalog(), player, backgroundScope)
        controller.playAt(0, songs); runCurrent()
        controller.cycleRepeat(); controller.cycleRepeat()
        controller.setSleepAfterCurrent()
        player.state.value = player.state.value.copy(ended = true, isPlaying = false)
        runCurrent()
        assertEquals(0, controller.state.value.queueIndex)
        assertFalse(player.state.value.isPlaying)
        assertFalse(controller.playbackOptions.value.sleepAfterCurrent)
        controller.dismissPlayback(); runCurrent()
        assertNull(player.state.value.track)
        assertTrue(controller.state.value.queue.isEmpty())
        assertEquals(-1, controller.state.value.queueIndex)
        controller.close()
    }

    private val songs = listOf(MusicTrack("aa11bb22cc3", "First"), MusicTrack("dd44ee55ff6", "Second"), MusicTrack("aa11bb22cc3", "Duplicate"))
    private class Player : PlayerEngine {
        override val state = MutableStateFlow(PlaybackState())
        var appliedSpeed = 1f
        override fun load(track: MusicTrack, source: ResolvedAudio) { state.value = PlaybackState(track = track, durationMs = 120_000) }
        override fun play() { state.value = state.value.copy(isPlaying = true, ended = false) }
        override fun pause() { state.value = state.value.copy(isPlaying = false) }
        override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs, ended = false) }
        override fun setSpeed(speed: Float) { this.appliedSpeed = speed }
        override fun stop() { state.value = PlaybackState() }
        override fun close() {}
    }
    private fun catalog() = object : MusicCatalog {
        override suspend fun search(query: String) = songs
        override suspend fun resolve(track: MusicTrack) = ResolvedAudio(track.id, "https://example.invalid/audio", "audio/mp4")
        override fun close() {}
    }
    @Test fun shuffledQueueVisitsEveryOccurrenceWithoutChangingOriginalOrder() = runTest {
        val player = Player()
        val controller = MusicController(catalog(), player, backgroundScope)
        controller.playAt(0, songs); runCurrent(); controller.toggleShuffle()
        val visited = mutableSetOf(controller.state.value.queueIndex)
        repeat(2) { controller.next(); runCurrent(); visited += controller.state.value.queueIndex }
        assertEquals(setOf(0, 1, 2), visited)
        assertEquals(songs, controller.state.value.queue)
        val last = controller.state.value.queueIndex
        controller.next(); runCurrent()
        assertEquals(last, controller.state.value.queueIndex)
        controller.close()
    }
    @Test fun repeatOneRestartsEndedSongButManualNextStillChangesTrack() = runTest {
        val player = Player()
        val controller = MusicController(catalog(), player, backgroundScope)
        controller.playAt(0, songs); runCurrent()
        controller.cycleRepeat(); controller.cycleRepeat()
        player.state.value = player.state.value.copy(ended = true, isPlaying = false, positionMs = 120_000)
        runCurrent()
        assertEquals(0, controller.state.value.queueIndex)
        assertEquals(0, player.state.value.positionMs)
        assertTrue(player.state.value.isPlaying)
        controller.next(); runCurrent()
        assertEquals(1, controller.state.value.queueIndex)
        controller.close()
    }
    @Test fun sleepTimerPausesActualPlayerAndCancelledTimerDoesNotFire() = runTest {
        val player = Player()
        val controller = MusicController(catalog(), player, this)
        controller.playAt(0, songs); runCurrent()
        controller.setSleepTimer(1)
        advanceTimeBy(60_000); runCurrent()
        assertFalse(player.state.value.isPlaying)
        assertNull(controller.playbackOptions.value.sleepTimerMinutes)
        controller.togglePlayback()
        controller.setSleepTimer(1); controller.setSleepTimer(null)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(player.state.value.isPlaying)
        controller.setSpeed(1.5f)
        assertEquals(1.5f, player.appliedSpeed)
        controller.next(); runCurrent()
        assertEquals(1.5f, player.appliedSpeed)
        controller.close()
    }
    @Test fun removingOccurrenceBeforeActiveDuplicatePreservesSelectedIdentityAndPosition() = runTest {
        val player = Player()
        val controller = MusicController(catalog(), player, backgroundScope)
        controller.playAt(2, songs); runCurrent(); player.seekTo(25_000)
        controller.removeQueueItem(0)
        assertEquals(1, controller.state.value.queueIndex)
        assertEquals("Duplicate", player.state.value.track?.title)
        assertEquals(25_000, player.state.value.positionMs)
        controller.close()
    }
}
