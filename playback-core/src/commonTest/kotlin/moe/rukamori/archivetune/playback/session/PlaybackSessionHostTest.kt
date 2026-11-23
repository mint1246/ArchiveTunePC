/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackQueueEntry
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionHostTest {
    @Test
    fun restorePersistedQueue_clamps_index_and_position_persists_updates_and_flushes_on_close() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = InMemoryPlaybackController()
        val store = InMemoryQueueStore(
            PersistedPlaybackQueue(
                title = "Saved Mix",
                items = listOf(item("a"), item("b")),
                currentIndex = 99,
                positionMs = 12_345L,
            ),
        )
        val prefetched = mutableListOf<String>()

        val session = PlaybackSessionHost(
            controller = controller,
            ownsController = true,
            parentContext = dispatcher,
            queuePersistence = store,
            persistenceDispatcher = dispatcher,
            prefetchResolver = { next, _ ->
                prefetched += next.metadata.id
                Result.success(ResolvedPlaybackSource(uri = "https://example.com/${next.metadata.id}.m4a"))
            },
        )
        runCurrent()
        // Initial IDLE empty state must not clobber the stored queue before restore runs.
        assertEquals(0, store.saveCount)

        assertTrue(session.restorePersistedQueue(playWhenReady = false))
        assertEquals(1, session.state.value.currentIndex)
        assertEquals(12_345L, session.state.value.positionMs)
        assertEquals("Saved Mix", session.state.value.queueTitle)
        assertFalse(session.state.value.playWhenReady)

        // Move back to index 0 while playing so track "b" is prefetched and checkpointed.
        session.seekToItem(0, 0L)
        controller.advancePosition(16_000L)
        runCurrent()

        assertEquals(listOf("b"), prefetched)
        assertTrue(store.saveCount >= 1)

        controller.advancePosition(19_500L)
        session.close()

        assertTrue(session.isClosed)
        assertTrue(controller.closed)
        assertEquals(19_500L, store.current?.positionMs)
    }

    @Test
    fun clearPersistedQueue_removes_stored_state_and_unowned_controller_stays_open_on_close() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = InMemoryPlaybackController()
        val store = InMemoryQueueStore(
            PersistedPlaybackQueue(
                title = "Temp",
                items = listOf(item("x")),
                currentIndex = 0,
                positionMs = 0L,
            ),
        )
        val session = PlaybackSessionHost(
            controller = controller,
            ownsController = false,
            parentContext = dispatcher,
            queuePersistence = store,
            persistenceDispatcher = dispatcher,
        )

        session.clearPersistedQueue()
        assertNull(store.load())

        session.close()
        assertFalse(controller.closed)
    }

    private fun item(id: String) = PlaybackItem(
        metadata = PlaybackMetadata(id = id, title = "Song $id", durationHintMs = 30_000L),
    )
}

private class InMemoryQueueStore(initial: PersistedPlaybackQueue? = null) : PlaybackQueuePersistence {
    var current: PersistedPlaybackQueue? = initial
    var saveCount: Int = 0

    override fun load(): PersistedPlaybackQueue? = current

    override fun save(snapshot: PlaybackSnapshot) {
        saveCount++
        current = if (snapshot.queue.isEmpty()) {
            null
        } else {
            PersistedPlaybackQueue(
                title = snapshot.queueTitle,
                items = snapshot.queue,
                currentIndex = snapshot.currentIndex,
                positionMs = snapshot.positionMs,
            )
        }
    }

    override fun clear() {
        current = null
    }
}

private class InMemoryPlaybackController : PlaybackController {
    private val _state = MutableStateFlow(PlaybackSnapshot())
    override val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    override val events = _events.asSharedFlow()
    var closed = false

    override suspend fun replaceQueue(
        items: List<PlaybackItem>,
        startIndex: Int,
        startPositionMs: Long,
        title: String?,
        playWhenReady: Boolean,
    ) {
        val idx = if (items.isEmpty()) -1 else startIndex.coerceIn(items.indices)
        val duration = items.getOrNull(idx)?.metadata?.durationHintMs ?: 0L
        _state.value = PlaybackSnapshot(
            queue = items,
            queueEntries = items.mapIndexed { i, item ->
                PlaybackQueueEntry(id = (i + 1).toLong(), item = item, logicalIndex = i, isCurrent = i == idx)
            },
            queueTitle = title,
            currentIndex = idx,
            status = if (items.isEmpty()) PlaybackStatus.IDLE else PlaybackStatus.READY,
            playWhenReady = playWhenReady,
            positionMs = startPositionMs,
            durationMs = duration,
            hasPreviousItem = idx > 0,
            hasNextItem = idx >= 0 && idx < items.lastIndex,
        )
    }

    override suspend fun play() {
        _state.value = _state.value.copy(playWhenReady = true, status = PlaybackStatus.READY)
    }

    override suspend fun pause() {
        _state.value = _state.value.copy(playWhenReady = false)
    }

    override suspend fun stop() {
        _state.value = _state.value.copy(playWhenReady = false, status = PlaybackStatus.IDLE)
    }

    override suspend fun seekTo(positionMs: Long) {
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    override suspend fun seekToItem(index: Int, positionMs: Long) {
        val current = _state.value
        val duration = current.queue.getOrNull(index)?.metadata?.durationHintMs ?: 0L
        _state.value = current.copy(
            currentIndex = index,
            positionMs = positionMs,
            durationMs = duration,
            playWhenReady = true,
            status = PlaybackStatus.READY,
            queueEntries = current.queue.mapIndexed { i, item ->
                PlaybackQueueEntry(id = (i + 1).toLong(), item = item, logicalIndex = i, isCurrent = i == index)
            },
            hasPreviousItem = index > 0,
            hasNextItem = index >= 0 && index < current.queue.lastIndex,
        )
    }

    fun advancePosition(positionMs: Long) {
        _state.value = _state.value.copy(positionMs = positionMs)
    }

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

    override fun close() {
        closed = true
    }
}
