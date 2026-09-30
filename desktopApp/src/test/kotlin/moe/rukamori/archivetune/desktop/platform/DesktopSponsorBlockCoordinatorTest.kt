package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.desktop.settings.DesktopSettings
import moe.rukamori.archivetune.desktop.sponsorblock.DesktopSponsorBlockSegment
import moe.rukamori.archivetune.desktop.sponsorblock.DesktopSponsorBlockSegmentProvider
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackQueueEntry
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DesktopSponsorBlockCoordinatorTest {
    @Test
    fun skipsEnabledSegmentForRemoteTrack() = runBlocking {
        val id = "abcdefghijk"
        val playback = FakePlayback(snapshot(id))
        val fetchedId = AtomicReference<String?>(null)
        val provider = DesktopSponsorBlockSegmentProvider { videoId, _, _ ->
            fetchedId.set(videoId)
            listOf(DesktopSponsorBlockSegment(10_000, 15_000))
        }
        val coordinator = DesktopSponsorBlockCoordinator(playback, { DesktopSettings() }, { true }, provider)
        try {
            coordinator.start()
            waitUntil { fetchedId.get() == id }
            playback.set(snapshot(id, positionMs = 12_000))
            waitUntil { playback.seeks.isNotEmpty() }
            assertEquals(listOf(15_000L), playback.seeks)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun doesNotSeekForPartyGuestAndNeverRequestsLocalFiles() = runBlocking {
        val id = "abcdefghijk"
        val playback = FakePlayback(snapshot(id))
        val fetchCount = AtomicInteger()
        val provider = DesktopSponsorBlockSegmentProvider { _, _, _ ->
            fetchCount.incrementAndGet()
            listOf(DesktopSponsorBlockSegment(1_000, 3_000))
        }
        val coordinator = DesktopSponsorBlockCoordinator(playback, { DesktopSettings() }, { false }, provider)
        try {
            coordinator.start()
            waitUntil { fetchCount.get() == 1 }
            playback.set(snapshot(id, positionMs = 2_000))
            delay(100)
            assertTrue(playback.seeks.isEmpty())

            playback.set(snapshot(id, source = PlaybackSource.LocalFile("C:/Music/song.flac")))
            delay(100)
            assertEquals(1, fetchCount.get())
            assertTrue(playback.seeks.isEmpty())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun changingTracksCancelsObsoleteFetchBeforeApplyingItsSegments() = runBlocking {
        val first = "abcdefghijk"
        val second = "lmnopqrstuv"
        val playback = FakePlayback(snapshot(first))
        val firstStarted = CompletableDeferred<Unit>()
        val secondFetched = AtomicReference(false)
        val provider = DesktopSponsorBlockSegmentProvider { videoId, _, _ ->
            if (videoId == first) {
                firstStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            secondFetched.set(videoId == second)
            listOf(DesktopSponsorBlockSegment(1_000, 5_000))
        }
        val coordinator = DesktopSponsorBlockCoordinator(playback, { DesktopSettings() }, { true }, provider)
        try {
            coordinator.start()
            withTimeout(2_000) { firstStarted.await() }
            playback.set(snapshot(second, positionMs = 2_000))
            waitUntil { secondFetched.get() && playback.seeks.isNotEmpty() }
            assertEquals(listOf(5_000L), playback.seeks)
        } finally {
            coordinator.close()
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) delay(10)
        }
    }

    private fun snapshot(
        id: String,
        positionMs: Long = 0L,
        source: PlaybackSource = PlaybackSource.Deferred(id),
    ) = PlaybackSnapshot(
        queue = listOf(PlaybackItem(PlaybackMetadata(id = id, title = id), source)),
        queueEntries = listOf(PlaybackQueueEntry(id = 1L, item = PlaybackItem(PlaybackMetadata(id = id, title = id), source), logicalIndex = 0, isCurrent = true)),
        currentIndex = 0,
        status = PlaybackStatus.READY,
        playWhenReady = true,
        positionMs = positionMs,
    )

    private class FakePlayback(initial: PlaybackSnapshot) : PlaybackController {
        private val mutableState = MutableStateFlow(initial)
        override val state = mutableState.asStateFlow()
        override val events = MutableSharedFlow<PlaybackEvent>()
        val seeks = CopyOnWriteArrayList<Long>()

        fun set(value: PlaybackSnapshot) { mutableState.value = value }
        override suspend fun replaceQueue(items: List<PlaybackItem>, startIndex: Int, startPositionMs: Long, title: String?, playWhenReady: Boolean) = Unit
        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun stop() = Unit
        override suspend fun seekTo(positionMs: Long) { seeks += positionMs; mutableState.value = mutableState.value.copy(positionMs = positionMs) }
        override suspend fun seekToItem(index: Int, positionMs: Long) = Unit
        override suspend fun skipNext() = Unit
        override suspend fun skipPrevious(restartThresholdMs: Long) = Unit
        override suspend fun enqueue(items: List<PlaybackItem>) = Unit
        override suspend fun playNext(items: List<PlaybackItem>) = Unit
        override suspend fun removeQueueItem(index: Int) = Unit
        override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) = Unit
        override suspend fun moveQueueItemToNext(index: Int) = Unit
        override suspend fun clearQueue() = Unit
        override suspend fun setRepeatMode(mode: RepeatMode) = Unit
        override suspend fun setShuffleEnabled(enabled: Boolean) = Unit
        override suspend fun setVolume(volume: Float) = Unit
        override suspend fun setSpeed(speed: Float) = Unit
        override suspend fun retry() = Unit
        override suspend fun clearError() = Unit
        override fun close() = Unit
    }
}
