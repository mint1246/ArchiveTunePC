/*
 * ArchiveTune (2026)
 * Â© Rukamori â€” github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.session.QueuePersistencePolicy

class DesktopPlaybackSessionTest {
    @Test
    fun `persists queue to disk, strips remote headers, and restores in a new session`() = runBlocking {
        val tempDir = Files.createTempDirectory("archivetune-session-test")
        try {
            val queueFile = tempDir.resolve("persistent-queue.json")
            val remoteItem = PlaybackItem(
                metadata = PlaybackMetadata(
                    id = "remote-1",
                    title = "Remote Track",
                    artists = listOf(PlaybackArtist(id = "artist-1", name = "Artist One")),
                    album = PlaybackAlbum(id = "album-1", title = "Album One"),
                    durationHintMs = 240_000L,
                ),
                source = PlaybackSource.Remote(
                    url = "https://example.com/audio.m4a",
                    mimeType = "audio/mp4",
                    headers = mapOf(
                        "Authorization" to "Bearer top-secret-token",
                        "Cookie" to "SID=secret-cookie",
                    ),
                    expiresAtEpochMs = 1_900_000_000_000L,
                ),
            )
            val localItem = PlaybackItem(
                metadata = PlaybackMetadata(id = "local-2", title = "Local Track"),
                source = PlaybackSource.LocalFile("/tmp/local-track.flac"),
            )

            val firstController = FakePlaybackController()
            val firstSession = createDesktopPlaybackSession(
                controller = firstController,
                queueFile = queueFile,
                queuePersistencePolicy = QueuePersistencePolicy(
                    checkpointMs = 1_000L,
                    seekJumpMs = 1_000L,
                ),
                persistenceDispatcher = Dispatchers.Unconfined,
            )

            firstController.emitSnapshot(
                PlaybackSnapshot(
                    status = PlaybackStatus.READY,
                    playWhenReady = true,
                    queue = listOf(remoteItem, localItem),
                    queueTitle = "Evening Mix",
                    currentIndex = 1,
                    positionMs = 45_000L,
                ),
            )
            firstSession.close()
            assertTrue(firstController.closed)
            assertTrue(Files.isRegularFile(queueFile))

            val rawJson = Files.readString(queueFile)
            assertFalse(rawJson.contains("top-secret-token"))
            assertFalse(rawJson.contains("secret-cookie"))

            val secondController = FakePlaybackController()
            createDesktopPlaybackSession(
                controller = secondController,
                queueFile = queueFile,
                persistenceDispatcher = Dispatchers.Unconfined,
            ).use { secondSession ->
                val restored = secondSession.restorePersistedQueue(playWhenReady = false)
                assertTrue(restored)

                val replaceCall = secondController.lastReplaceQueueCall
                assertEquals("Evening Mix", replaceCall?.title)
                assertEquals(1, replaceCall?.startIndex)
                assertEquals(45_000L, replaceCall?.startPositionMs)
                assertEquals(false, replaceCall?.playWhenReady)
                assertEquals(2, replaceCall?.items?.size)

                val restoredRemoteSource = replaceCall?.items?.get(0)?.source as? PlaybackSource.Remote
                assertEquals("https://example.com/audio.m4a", restoredRemoteSource?.url)
                assertEquals("audio/mp4", restoredRemoteSource?.mimeType)
                assertEquals(emptyMap(), restoredRemoteSource?.headers)
                assertEquals(1_900_000_000_000L, restoredRemoteSource?.expiresAtEpochMs)
                assertEquals(
                    "/tmp/local-track.flac",
                    (replaceCall?.items?.get(1)?.source as? PlaybackSource.LocalFile)?.path,
                )
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `close flushes final checkpoint even when throttled and respects ownsController`() = runBlocking {
        val tempDir = Files.createTempDirectory("archivetune-session-flush")
        try {
            val queueFile = tempDir.resolve("persistent-queue.json")
            val controller = FakePlaybackController()
            val item = PlaybackItem(
                metadata = PlaybackMetadata(id = "track-1", title = "Track One"),
                source = PlaybackSource.Deferred("track-1"),
            )
            val session = createDesktopPlaybackSession(
                controller = controller,
                ownsController = false,
                queueFile = queueFile,
                queuePersistencePolicy = QueuePersistencePolicy(
                    checkpointMs = 60_000L,
                    seekJumpMs = 60_000L,
                ),
                persistenceDispatcher = Dispatchers.Unconfined,
            )

            controller.emitSnapshot(
                PlaybackSnapshot(
                    status = PlaybackStatus.READY,
                    playWhenReady = true,
                    queue = listOf(item),
                    queueTitle = "Focus",
                    currentIndex = 0,
                    positionMs = 12_345L,
                ),
            )
            controller.emitSnapshot(
                PlaybackSnapshot(
                    status = PlaybackStatus.READY,
                    playWhenReady = true,
                    queue = listOf(item),
                    queueTitle = "Focus",
                    currentIndex = 0,
                    positionMs = 15_678L,
                ),
            )

            session.close()
            assertFalse(controller.closed)

            val persisted = PlaybackQueueStore(queueFile).load()
            assertEquals("Focus", persisted?.title)
            assertEquals(0, persisted?.currentIndex)
            assertEquals(15_678L, persisted?.positionMs)
            assertEquals("track-1", persisted?.items?.singleOrNull()?.metadata?.id)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clearPersistedQueue removes queue file from disk`() = runBlocking {
        val tempDir = Files.createTempDirectory("archivetune-session-clear")
        try {
            val queueFile = tempDir.resolve("persistent-queue.json")
            val controller = FakePlaybackController()
            createDesktopPlaybackSession(
                controller = controller,
                queueFile = queueFile,
                persistenceDispatcher = Dispatchers.Unconfined,
            ).use { session ->
                controller.emitSnapshot(
                    PlaybackSnapshot(
                        queue = listOf(PlaybackItem(PlaybackMetadata(id = "a", title = "A"))),
                        currentIndex = 0,
                        positionMs = 5_000L,
                    ),
                )
                session.flushQueueCheckpoint()
                assertTrue(Files.isRegularFile(queueFile))

                session.clearPersistedQueue()
                assertFalse(Files.exists(queueFile))
                assertNull(PlaybackQueueStore(queueFile).load())
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private data class ReplaceQueueCall(
        val items: List<PlaybackItem>,
        val startIndex: Int,
        val startPositionMs: Long,
        val title: String?,
        val playWhenReady: Boolean,
    )

    private class FakePlaybackController : PlaybackController {
        private val _state = MutableStateFlow(PlaybackSnapshot())
        override val state = _state.asStateFlow()
        private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 16)
        override val events = _events.asSharedFlow()

        var closed = false
            private set
        var lastReplaceQueueCall: ReplaceQueueCall? = null
            private set

        fun emitSnapshot(snapshot: PlaybackSnapshot) {
            _state.value = snapshot
        }

        override suspend fun replaceQueue(
            items: List<PlaybackItem>,
            startIndex: Int,
            startPositionMs: Long,
            title: String?,
            playWhenReady: Boolean,
        ) {
            lastReplaceQueueCall = ReplaceQueueCall(items, startIndex, startPositionMs, title, playWhenReady)
            _state.value = _state.value.copy(
                queue = items,
                queueTitle = title,
                currentIndex = startIndex,
                positionMs = startPositionMs,
                playWhenReady = playWhenReady,
            )
        }

        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun stop() = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
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
        override fun close() {
            closed = true
        }
    }
}
