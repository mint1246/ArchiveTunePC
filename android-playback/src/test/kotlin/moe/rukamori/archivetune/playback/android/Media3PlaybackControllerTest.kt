/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackFailure
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.api.TransitionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Media3PlaybackControllerTest {
    @Test
    fun `replaceQueue populates snapshot queue, title, position, and emits transition`() = runTest {
        val fake = ControllerFakePlayer()
        val controller = Media3PlaybackController(player = fake.player)
        val events = mutableListOf<PlaybackEvent>()
        val collectJob = launch { controller.events.take(2).toList(events) }
        runCurrent()

        val items = listOf(remoteItem("a", "Alpha"), remoteItem("b", "Beta"))
        controller.replaceQueue(
            items = items,
            startIndex = 1,
            startPositionMs = 4_500L,
            title = "Mix",
            playWhenReady = true,
        )
        runCurrent()
        collectJob.cancel()

        val snap = controller.state.value
        assertEquals(items, snap.queue)
        assertEquals(1, snap.currentIndex)
        assertEquals("Mix", snap.queueTitle)
        assertEquals(4_500L, snap.positionMs)
        assertTrue(snap.playWhenReady)
        assertEquals(1, fake.prepareCount)
        assertTrue(events.any { it is PlaybackEvent.QueueChanged && it.currentIndex == 1 })
        assertTrue(
            events.any {
                it is PlaybackEvent.MediaTransition &&
                    it.current == items[1] &&
                    it.reason == TransitionReason.QUEUE_REPLACED
            },
        )
        controller.close()
    }

    @Test
    fun `duplicate tracks keep distinct entry tokens and single current row`() = runTest {
        val fake = ControllerFakePlayer()
        val controller = Media3PlaybackController(player = fake.player)

        val dup = remoteItem("same", "Repeated")
        val other = remoteItem("other", "Other")
        controller.replaceQueue(listOf(dup, other, dup), startIndex = 2, playWhenReady = false)

        val entries = controller.state.value.queueEntries
        assertEquals(3, entries.size)
        assertEquals(listOf(0, 1, 2), entries.map { it.logicalIndex })
        assertNotEquals(entries[0].id, entries[2].id)
        assertEquals(listOf(false, false, true), entries.map { it.isCurrent })
        controller.close()
    }

    @Test
    fun `shuffled moveQueueItem and moveQueueItemToNext use injected shuffle capability and fail intelligibly without it`() =
        runTest {
            val fake = ControllerFakePlayer()
            val noCapController = Media3PlaybackController(player = fake.player)
            val items = listOf(remoteItem("0"), remoteItem("1"), remoteItem("2"))
            noCapController.replaceQueue(items, startIndex = 0, playWhenReady = false)
            noCapController.setShuffleEnabled(true)
            fake.setShuffleOrder(intArrayOf(2, 0, 1))

            var moveFailed = false
            try {
                noCapController.moveQueueItem(0, 2)
            } catch (_: UnsupportedOperationException) {
                moveFailed = true
            }
            assertTrue(moveFailed)

            var moveToNextFailed = false
            try {
                noCapController.moveQueueItemToNext(1)
            } catch (_: UnsupportedOperationException) {
                moveToNextFailed = true
            }
            assertTrue(moveToNextFailed)
            noCapController.close()

            val capController = Media3PlaybackController(
                player = fake.player,
                shuffleOrderCapability = Media3ShuffleOrderCapability { fake.setShuffleOrder(it) },
            )
            // Current shuffle order: [2, 0(current), 1]
            assertEquals(listOf(2, 0, 1), capController.state.value.queueEntries.map { it.logicalIndex })

            // Move row 0 (logical 2) to row 2 -> [0(current), 1, 2]
            capController.moveQueueItem(0, 2)
            assertEquals(listOf(0, 1, 2), capController.state.value.queueEntries.map { it.logicalIndex })

            // Move logical 2 right after current (logical 0) -> [0(current), 2, 1]
            capController.moveQueueItemToNext(2)
            assertEquals(listOf(0, 2, 1), capController.state.value.queueEntries.map { it.logicalIndex })
            capController.close()
        }

    @Test
    fun `deferred items resolve via PlaybackSourceResolver and fail intelligibly when missing or failing`() = runTest {
        val fake = ControllerFakePlayer()
        val noResolverController = Media3PlaybackController(player = fake.player)
        val deferred = PlaybackItem(PlaybackMetadata(id = "yt-1", title = "Track"))

        noResolverController.replaceQueue(listOf(deferred), startIndex = 0, playWhenReady = true)
        assertEquals(PlaybackStatus.ERROR, noResolverController.state.value.status)
        assertEquals(PlaybackFailure.Kind.SOURCE_RESOLUTION, noResolverController.state.value.error?.kind)
        noResolverController.close()

        var shouldFail = true
        val resolver = PlaybackSourceResolver { item ->
            if (shouldFail) {
                Result.failure(IllegalStateException("network down"))
            } else {
                Result.success(ResolvedPlaybackSource(uri = "https://example.com/${item.metadata.id}.m4a"))
            }
        }
        val controller = Media3PlaybackController(player = fake.player, sourceResolver = resolver)
        controller.replaceQueue(listOf(deferred), startIndex = 0, playWhenReady = true)
        assertEquals(PlaybackStatus.ERROR, controller.state.value.status)
        assertEquals("network down", controller.state.value.error?.message)

        shouldFail = false
        controller.retry()
        assertNull(controller.state.value.error)
        assertTrue(fake.playWhenReady)
        controller.close()
    }

    @Test
    fun `useDataSourceDeferredResolution skips eager resolution and keeps mediaId and queue entries intact`() = runTest {
        val fake = ControllerFakePlayer()
        var resolveCalls = 0
        val resolver = PlaybackSourceResolver {
            resolveCalls++
            Result.success(ResolvedPlaybackSource(uri = "https://example.com/never-eager.m4a"))
        }
        val controller = Media3PlaybackController(
            player = fake.player,
            sourceResolver = resolver,
            useDataSourceDeferredResolution = true,
        )

        val first = PlaybackItem(
            metadata = PlaybackMetadata(id = "track-1", title = "First"),
            source = PlaybackSource.Deferred("key-1"),
        )
        val second = PlaybackItem(
            metadata = PlaybackMetadata(id = "track-2", title = "Second"),
            source = PlaybackSource.Deferred("key-2"),
        )

        controller.replaceQueue(listOf(first, second, first), startIndex = 0, playWhenReady = true)
        assertEquals(0, resolveCalls)
        assertNull(controller.state.value.error)
        assertEquals(listOf(first, second, first), controller.state.value.queue)
        assertEquals("track-1", fake.player.getMediaItemAt(0).mediaId)
        assertEquals("track-2", fake.player.getMediaItemAt(1).mediaId)
        assertEquals(3, controller.state.value.queueEntries.size)
        assertNotEquals(controller.state.value.queueEntries[0].id, controller.state.value.queueEntries[2].id)

        controller.skipNext()
        controller.seekToItem(2)
        controller.retry()
        assertEquals(0, resolveCalls)
        assertNull(controller.state.value.error)
        controller.close()
    }

    @Test
    fun `fallback mode resolves deferred item on automatic media item transition`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = ControllerFakePlayer()
        val resolvedIds = mutableListOf<String>()
        val resolver = PlaybackSourceResolver { item ->
            resolvedIds += item.metadata.id
            Result.success(ResolvedPlaybackSource(uri = "https://example.com/${item.metadata.id}.m4a"))
        }
        val controller = Media3PlaybackController(
            player = fake.player,
            sourceResolver = resolver,
            playerDispatcher = dispatcher,
            progressSamplingScope = this,
            useDataSourceDeferredResolution = false,
        )

        val first = remoteItem("0", "First")
        val second = PlaybackItem(
            metadata = PlaybackMetadata(id = "yt-auto", title = "Auto Next"),
            source = PlaybackSource.Deferred("yt-auto"),
        )
        controller.replaceQueue(listOf(first, second), startIndex = 0, playWhenReady = true)
        runCurrent()
        assertTrue(resolvedIds.isEmpty())

        fake.simulateAutoTransition(1)
        runCurrent()

        assertEquals(listOf("yt-auto"), resolvedIds)
        assertEquals(1, controller.state.value.currentIndex)
        assertNull(controller.state.value.error)
        assertTrue(fake.prepareCount >= 2)
        controller.close()
    }

    @Test
    fun `skipPrevious honors restartThresholdMs and speed pitch volume repeat update player`() = runTest {
        val fake = ControllerFakePlayer()
        val controller = Media3PlaybackController(player = fake.player)
        controller.replaceQueue(listOf(remoteItem("0"), remoteItem("1")), startIndex = 1, playWhenReady = true)

        fake.currentPosition = 5_000L
        controller.skipPrevious(restartThresholdMs = 3_000L)
        assertEquals(1, controller.state.value.currentIndex)
        assertEquals(0L, fake.currentPosition)

        fake.currentPosition = 1_000L
        controller.skipPrevious(restartThresholdMs = 3_000L)
        assertEquals(0, controller.state.value.currentIndex)

        controller.setRepeatMode(RepeatMode.ALL)
        controller.setVolume(0.5f)
        controller.setSpeed(1.5f)
        controller.setPitch(12f)

        val snap = controller.state.value
        assertEquals(RepeatMode.ALL, snap.repeatMode)
        assertEquals(0.5f, snap.volume, 0.001f)
        assertEquals(1.5f, snap.speed, 0.001f)
        assertEquals(12f, snap.pitchSemitones, 0.1f)
        controller.close()
    }

    @Test
    fun `clearError dismisses current player error and close detaches without releasing player`() = runTest {
        val fake = ControllerFakePlayer()
        val controller = Media3PlaybackController(player = fake.player)

        val error = PlaybackException("boom", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        fake.dispatchPlayerErrorChanged(error)
        assertEquals(PlaybackStatus.ERROR, controller.state.value.status)
        assertEquals(PlaybackFailure.Kind.NETWORK, controller.state.value.error?.kind)

        controller.clearError()
        assertNull(controller.state.value.error)
        assertNotEquals(PlaybackStatus.ERROR, controller.state.value.status)

        controller.close()
        assertTrue(fake.listeners.isEmpty())
        assertFalse(fake.released)

        var threwOnClosed = false
        try {
            controller.play()
        } catch (_: IllegalStateException) {
            threwOnClosed = true
        }
        assertTrue(threwOnClosed)
    }

    @Test
    fun `optional progressSamplingScope samples position while playing and stops on close`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fake = ControllerFakePlayer()
        val controller = Media3PlaybackController(
            player = fake.player,
            playerDispatcher = dispatcher,
            progressSamplingScope = this,
            progressSamplingIntervalMs = 100L,
        )

        controller.replaceQueue(listOf(remoteItem("0")), startIndex = 0, playWhenReady = true)
        fake.dispatchPlaybackStateChanged(Player.STATE_READY)
        fake.dispatchIsPlayingChanged(true)
        runCurrent()

        fake.currentPosition = 1_250L
        advanceTimeBy(120L)
        runCurrent()
        assertEquals(1_250L, controller.state.value.positionMs)

        controller.close()
        fake.currentPosition = 9_999L
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(1_250L, controller.state.value.positionMs)
    }

    private fun remoteItem(id: String, title: String = "Track $id"): PlaybackItem =
        PlaybackItem(
            metadata = PlaybackMetadata(id = id, title = title),
            source = PlaybackSource.Remote(url = "https://example.com/$id.m4a"),
        )
}

private class ControllerFakePlayer {
    private data class Entry(val uid: Any, val mediaItem: MediaItem)

    private val entries = mutableListOf<Entry>()
    private var shuffleOrder = listOf<Int>()

    var playbackState: Int = Player.STATE_IDLE
    var isPlaying: Boolean = false
    var playWhenReady: Boolean = false
    var repeatMode: Int = Player.REPEAT_MODE_OFF
    var shuffleModeEnabled: Boolean = false
    var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    var volume: Float = 1f
    var currentMediaItemIndex: Int = C.INDEX_UNSET
    var currentPosition: Long = 0L
    var bufferedPosition: Long = 0L
    var duration: Long = 180_000L
    var playerError: PlaybackException? = null
    var prepareCount: Int = 0
    var released: Boolean = false

    val listeners = mutableListOf<Player.Listener>()

    private var currentTimeline: Timeline = Timeline.EMPTY

    val player: Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, args ->
        when (method.name) {
            "addListener" -> {
                listeners.add(args[0] as Player.Listener)
                null
            }
            "removeListener" -> {
                listeners.remove(args[0] as Player.Listener)
                null
            }
            "release" -> {
                released = true
                null
            }
            "getPlaybackState" -> playbackState
            "isPlaying" -> isPlaying
            "getPlayWhenReady" -> playWhenReady
            "setPlayWhenReady" -> {
                playWhenReady = args[0] as Boolean
                listeners.toList().forEach {
                    it.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                }
                null
            }
            "prepare" -> {
                prepareCount++
                if (playbackState == Player.STATE_IDLE) {
                    playbackState = Player.STATE_BUFFERING
                }
                null
            }
            "stop" -> {
                playbackState = Player.STATE_IDLE
                isPlaying = false
                null
            }
            "getRepeatMode" -> repeatMode
            "setRepeatMode" -> {
                repeatMode = args[0] as Int
                listeners.toList().forEach { it.onRepeatModeChanged(repeatMode) }
                null
            }
            "getShuffleModeEnabled" -> shuffleModeEnabled
            "setShuffleModeEnabled" -> {
                shuffleModeEnabled = args[0] as Boolean
                rebuildTimeline(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                listeners.toList().forEach { it.onShuffleModeEnabledChanged(shuffleModeEnabled) }
                null
            }
            "getVolume" -> volume
            "setVolume" -> {
                volume = args[0] as Float
                listeners.toList().forEach { it.onVolumeChanged(volume) }
                null
            }
            "getPlaybackParameters" -> playbackParameters
            "setPlaybackParameters" -> {
                playbackParameters = args[0] as PlaybackParameters
                listeners.toList().forEach { it.onPlaybackParametersChanged(playbackParameters) }
                null
            }
            "getCurrentTimeline" -> currentTimeline
            "getCurrentMediaItemIndex" -> currentMediaItemIndex
            "getMediaItemCount" -> entries.size
            "getMediaItemAt" -> entries[args[0] as Int].mediaItem
            "getCurrentPosition" -> currentPosition
            "getBufferedPosition" -> bufferedPosition
            "getDuration" -> duration
            "getPlayerError" -> playerError
            "isCommandAvailable" -> true
            "setMediaItems" -> {
                @Suppress("UNCHECKED_CAST")
                val items = args[0] as List<MediaItem>
                val startIdx = if (args.size >= 2) args[1] as Int else 0
                val startPos = if (args.size >= 3) args[2] as Long else 0L
                entries.clear()
                entries += items.map { Entry(Any(), it) }
                shuffleOrder = entries.indices.toList()
                currentMediaItemIndex = if (entries.isEmpty()) C.INDEX_UNSET else startIdx.coerceIn(entries.indices)
                currentPosition = startPos
                rebuildTimeline(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                val current = entries.getOrNull(currentMediaItemIndex)?.mediaItem
                listeners.toList().forEach {
                    it.onMediaItemTransition(current, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                }
                null
            }
            "addMediaItems" -> {
                val index: Int
                @Suppress("UNCHECKED_CAST")
                val items: List<MediaItem>
                if (args.size == 2) {
                    index = args[0] as Int
                    items = args[1] as List<MediaItem>
                } else {
                    index = entries.size
                    items = args[0] as List<MediaItem>
                }
                entries.addAll(index, items.map { Entry(Any(), it) })
                shuffleOrder = entries.indices.toList()
                if (currentMediaItemIndex >= index && entries.isNotEmpty()) {
                    currentMediaItemIndex += items.size
                } else if (currentMediaItemIndex == C.INDEX_UNSET && entries.isNotEmpty()) {
                    currentMediaItemIndex = 0
                }
                rebuildTimeline(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                null
            }
            "replaceMediaItem" -> {
                val index = args[0] as Int
                val item = args[1] as MediaItem
                entries[index] = entries[index].copy(mediaItem = item)
                rebuildTimeline(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                null
            }
            "moveMediaItem" -> {
                val from = args[0] as Int
                val to = args[1] as Int
                val moved = entries.removeAt(from)
                entries.add(to, moved)
                if (currentMediaItemIndex == from) {
                    currentMediaItemIndex = to
                } else if (from < currentMediaItemIndex && to >= currentMediaItemIndex) {
                    currentMediaItemIndex--
                } else if (from > currentMediaItemIndex && to <= currentMediaItemIndex) {
                    currentMediaItemIndex++
                }
                rebuildTimeline(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                null
            }
            "removeMediaItem" -> {
                val index = args[0] as Int
                entries.removeAt(index)
                shuffleOrder = entries.indices.toList()
                if (entries.isEmpty()) {
                    currentMediaItemIndex = C.INDEX_UNSET
                } else if (currentMediaItemIndex >= entries.size) {
                    currentMediaItemIndex = entries.lastIndex
                }
                rebuildTimeline(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                null
            }
            "removeMediaItems" -> {
                val from = args[0] as Int
                val to = args[1] as Int
                entries.subList(from, to).clear()
                shuffleOrder = entries.indices.toList()
                if (entries.isEmpty()) {
                    currentMediaItemIndex = C.INDEX_UNSET
                } else if (currentMediaItemIndex >= to) {
                    currentMediaItemIndex -= (to - from)
                } else if (currentMediaItemIndex >= from) {
                    currentMediaItemIndex = from.coerceAtMost(entries.lastIndex)
                }
                rebuildTimeline(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                null
            }
            "clearMediaItems" -> {
                entries.clear()
                shuffleOrder = emptyList()
                currentMediaItemIndex = C.INDEX_UNSET
                currentPosition = 0L
                rebuildTimeline(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                null
            }
            "seekTo" -> {
                if (args.size == 1) {
                    currentPosition = args[0] as Long
                } else {
                    val idx = args[0] as Int
                    currentMediaItemIndex = idx
                    currentPosition = args[1] as Long
                    val item = entries.getOrNull(idx)?.mediaItem
                    listeners.toList().forEach {
                        it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                    }
                }
                null
            }
            "seekToDefaultPosition" -> {
                currentPosition = 0L
                null
            }
            "seekToNext", "seekToNextMediaItem" -> {
                val next = currentTimeline.getNextWindowIndex(currentMediaItemIndex, repeatMode, shuffleModeEnabled)
                if (next != C.INDEX_UNSET) {
                    currentMediaItemIndex = next
                    currentPosition = 0L
                    listeners.toList().forEach {
                        it.onMediaItemTransition(entries[next].mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                    }
                }
                null
            }
            "seekToPrevious", "seekToPreviousMediaItem" -> {
                val prev = currentTimeline.getPreviousWindowIndex(currentMediaItemIndex, repeatMode, shuffleModeEnabled)
                if (prev != C.INDEX_UNSET) {
                    currentMediaItemIndex = prev
                    currentPosition = 0L
                    listeners.toList().forEach {
                        it.onMediaItemTransition(entries[prev].mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                    }
                }
                null
            }
            else -> defaultReturn(method.returnType)
        }
    } as Player

    fun setShuffleOrder(order: IntArray) {
        shuffleOrder = order.toList()
        rebuildTimeline(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
    }

    fun simulateAutoTransition(index: Int) {
        currentMediaItemIndex = index
        currentPosition = 0L
        val item = entries.getOrNull(index)?.mediaItem
        listeners.toList().forEach {
            it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        }
    }

    fun dispatchPlaybackStateChanged(state: Int) {
        playbackState = state
        listeners.toList().forEach { it.onPlaybackStateChanged(state) }
    }

    fun dispatchIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
        listeners.toList().forEach { it.onIsPlayingChanged(playing) }
    }

    fun dispatchPlayerErrorChanged(error: PlaybackException?) {
        playerError = error
        listeners.toList().forEach { it.onPlayerErrorChanged(error) }
    }

    private fun rebuildTimeline(reason: Int) {
        currentTimeline = if (entries.isEmpty()) {
            Timeline.EMPTY
        } else {
            val snapshotEntries = entries.toList()
            val snapshotShuffle = shuffleOrder.toList()
            object : Timeline() {
                override fun getWindowCount(): Int = snapshotEntries.size

                override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
                    val entry = snapshotEntries[windowIndex]
                    window.uid = entry.uid
                    window.mediaItem = entry.mediaItem
                    window.firstPeriodIndex = windowIndex
                    window.lastPeriodIndex = windowIndex
                    return window
                }

                override fun getPeriodCount(): Int = snapshotEntries.size

                override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                    val uid = snapshotEntries[periodIndex].uid
                    return period.set(uid, uid, periodIndex, C.TIME_UNSET, 0L)
                }

                override fun getIndexOfPeriod(uid: Any): Int = snapshotEntries.indexOfFirst { it.uid == uid }

                override fun getUidOfPeriod(periodIndex: Int): Any = snapshotEntries[periodIndex].uid

                override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
                    if (shuffleModeEnabled) {
                        if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
                        val pos = snapshotShuffle.indexOf(windowIndex)
                        if (pos < 0) return C.INDEX_UNSET
                        return when {
                            pos + 1 < snapshotShuffle.size -> snapshotShuffle[pos + 1]
                            repeatMode == Player.REPEAT_MODE_ALL && snapshotShuffle.isNotEmpty() -> snapshotShuffle.first()
                            else -> C.INDEX_UNSET
                        }
                    }
                    return super.getNextWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
                }

                override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
                    if (shuffleModeEnabled) {
                        if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
                        val pos = snapshotShuffle.indexOf(windowIndex)
                        if (pos < 0) return C.INDEX_UNSET
                        return when {
                            pos - 1 >= 0 -> snapshotShuffle[pos - 1]
                            repeatMode == Player.REPEAT_MODE_ALL && snapshotShuffle.isNotEmpty() -> snapshotShuffle.last()
                            else -> C.INDEX_UNSET
                        }
                    }
                    return super.getPreviousWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
                }
            }
        }
        listeners.toList().forEach { it.onTimelineChanged(currentTimeline, reason) }
    }

    private fun defaultReturn(returnType: Class<*>): Any? =
        when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
}
