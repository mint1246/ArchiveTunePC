/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.session.PlaybackSessionHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class Media3PlaybackSessionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `restorePersistedQueue, flushQueueCheckpoint, and clearPersistedQueue round-trip through store`() = runTest {
        val queueFile = File(tempFolder.root, "session-queue.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = AndroidPlaybackQueueStore(queueFile)

        val itemA = PlaybackItem(PlaybackMetadata(id = "a", title = "Alpha"), PlaybackSource.Deferred("key-a"))
        val itemB = PlaybackItem(PlaybackMetadata(id = "b", title = "Beta"), PlaybackSource.Deferred("key-b"))
        store.save(
            PlaybackSnapshot(
                queue = listOf(itemA, itemB),
                queueTitle = "Saved Mix",
                currentIndex = 1,
                positionMs = 12_345L,
            ),
        )

        val fake = SessionFakeExoPlayer()
        val controller = Media3PlaybackController(
            player = fake.exoPlayer,
            playerDispatcher = dispatcher,
            useDataSourceDeferredResolution = true,
        )
        val host = PlaybackSessionHost(
            controller = controller,
            ownsController = true,
            parentContext = dispatcher,
            queuePersistence = store,
            persistenceDispatcher = dispatcher,
        )
        val session = Media3PlaybackSession(
            exoPlayer = fake.exoPlayer,
            controller = controller,
            host = host,
            ownsPlayer = true,
        )

        assertTrue(session.restorePersistedQueue(playWhenReady = false))
        assertEquals(listOf(itemA, itemB), session.state.value.queue)
        assertEquals(1, session.state.value.currentIndex)
        assertEquals(12_345L, session.state.value.positionMs)
        assertEquals("Saved Mix", session.state.value.queueTitle)
        assertFalse(session.state.value.playWhenReady)

        fake.currentPosition = 33_000L
        session.refreshProgress()
        session.flushQueueCheckpoint()
        assertEquals(33_000L, store.load()?.positionMs)

        session.clearPersistedQueue()
        assertNull(store.load())

        session.close()
        assertTrue(session.isClosed)
        assertEquals(1, fake.releaseCount)
        assertTrue(fake.listeners.isEmpty())

        // Idempotent close
        session.close()
        assertEquals(1, fake.releaseCount)
    }

    @Test
    fun `close flushes checkpoint and respects ownsPlayer flag`() = runTest {
        val queueFile = File(tempFolder.root, "owned-check.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = AndroidPlaybackQueueStore(queueFile)

        val fake = SessionFakeExoPlayer()
        val controller = Media3PlaybackController(
            player = fake.exoPlayer,
            playerDispatcher = dispatcher,
            useDataSourceDeferredResolution = true,
        )
        val host = PlaybackSessionHost(
            controller = controller,
            ownsController = true,
            parentContext = dispatcher,
            queuePersistence = store,
            persistenceDispatcher = dispatcher,
        )
        val unownedSession = Media3PlaybackSession(
            exoPlayer = fake.exoPlayer,
            controller = controller,
            host = host,
            ownsPlayer = false,
        )

        val track = PlaybackItem(PlaybackMetadata(id = "t-1", title = "Track 1"), PlaybackSource.Deferred("t-1"))
        unownedSession.replaceQueue(listOf(track), startIndex = 0, startPositionMs = 4_000L, playWhenReady = false)
        unownedSession.close()

        assertEquals(0, fake.releaseCount)
        assertTrue(fake.listeners.isEmpty())
        val persisted = store.load()
        assertNotNull(persisted)
        assertEquals(listOf(track), persisted!!.items)
    }
}

private class SessionFakeExoPlayer {
    private data class Entry(val uid: Any, val mediaItem: MediaItem)

    private val entries = mutableListOf<Entry>()
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
    var duration: Long = 120_000L
    var releaseCount: Int = 0
    val listeners = mutableListOf<Player.Listener>()
    private var currentTimeline: Timeline = Timeline.EMPTY

    val exoPlayer: ExoPlayer = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java),
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
                releaseCount++
                null
            }
            "getPlaybackState" -> playbackState
            "isPlaying" -> isPlaying
            "getPlayWhenReady" -> playWhenReady
            "setPlayWhenReady" -> {
                playWhenReady = args[0] as Boolean
                null
            }
            "prepare" -> {
                if (playbackState == Player.STATE_IDLE) {
                    playbackState = Player.STATE_BUFFERING
                }
                null
            }
            "getRepeatMode" -> repeatMode
            "getShuffleModeEnabled" -> shuffleModeEnabled
            "getVolume" -> volume
            "getPlaybackParameters" -> playbackParameters
            "getCurrentTimeline" -> currentTimeline
            "getCurrentMediaItemIndex" -> currentMediaItemIndex
            "getMediaItemCount" -> entries.size
            "getMediaItemAt" -> entries[args[0] as Int].mediaItem
            "getCurrentPosition" -> currentPosition
            "getBufferedPosition" -> bufferedPosition
            "getDuration" -> duration
            "getPlayerError" -> null
            "isCommandAvailable" -> true
            "setMediaItems" -> {
                @Suppress("UNCHECKED_CAST")
                val items = args[0] as List<MediaItem>
                val startIdx = if (args.size >= 2) args[1] as Int else 0
                val startPos = if (args.size >= 3) args[2] as Long else 0L
                entries.clear()
                entries += items.map { Entry(Any(), it) }
                currentMediaItemIndex = if (entries.isEmpty()) C.INDEX_UNSET else startIdx.coerceIn(entries.indices)
                currentPosition = startPos
                rebuildTimeline()
                val current = entries.getOrNull(currentMediaItemIndex)?.mediaItem
                listeners.toList().forEach {
                    it.onMediaItemTransition(current, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                }
                null
            }
            "clearMediaItems" -> {
                entries.clear()
                currentMediaItemIndex = C.INDEX_UNSET
                currentPosition = 0L
                rebuildTimeline()
                null
            }
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
        }
    } as ExoPlayer

    private fun rebuildTimeline() {
        currentTimeline = if (entries.isEmpty()) {
            Timeline.EMPTY
        } else {
            val snapshotEntries = entries.toList()
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
            }
        }
        listeners.toList().forEach { it.onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
    }
}
