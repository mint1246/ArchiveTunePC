package moe.rukamori.archivetune.windowsmedia

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class WindowsMediaControlsTest {
    @Test
    fun `position ticks share one windows media update key`() {
        val base = presentationSnapshot()
        val tenSecondsOfTicks = List(200) { tick -> base.copy(positionMs = tick * 50L) }

        assertEquals(1, tenSecondsOfTicks.map(PlaybackSnapshot::windowsMediaUpdateKey).distinct().size)
    }

    @Test
    fun `presentation relevant playback changes produce new update keys`() {
        val base = presentationSnapshot()
        val baseKey = base.windowsMediaUpdateKey()

        assertNotEquals(baseKey, base.copy(playWhenReady = false).windowsMediaUpdateKey())
        assertNotEquals(
            baseKey,
            base.copy(
                queue = listOf(
                    base.currentItem!!.copy(metadata = base.currentItem!!.metadata.copy(title = "Changed title")),
                ),
            ).windowsMediaUpdateKey(),
        )
        assertNotEquals(baseKey, base.copy(hasNextItem = true).windowsMediaUpdateKey())
        assertNotEquals(baseKey, base.copy(hasPreviousItem = true).windowsMediaUpdateKey())
        assertNotEquals(baseKey, base.copy(volume = 0.5f).windowsMediaUpdateKey())
        assertNotEquals(baseKey, base.copy(durationMs = base.durationMs + 1_000L).windowsMediaUpdateKey())
    }

    @Test
    fun `metadata is sanitized and no item falls back cleanly`() {
        val controls = WindowsMediaControls(FakeHotKeys())
        controls.update(
            PlaybackSnapshot(
                queue = listOf(PlaybackItem(PlaybackMetadata("id", "", listOf(PlaybackArtist(name = "")), PlaybackAlbum(title = ""), durationHintMs = -1))),
                currentIndex = 0,
                positionMs = -1,
                durationMs = -2,
            ),
        )
        val metadata = requireNotNull(controls.presentation.value.metadata)
        assertEquals("Unknown title", metadata.title)
        assertEquals("Unknown artist", metadata.artist)
        assertNull(metadata.album)
        assertEquals(0, metadata.durationMs)
        controls.update(PlaybackSnapshot())
        assertNull(controls.presentation.value.metadata)
        controls.close()
    }

    @Test
    fun `presentation carries local album credits and release ordering`() {
        val controls = WindowsMediaControls(FakeHotKeys())

        controls.update(presentationSnapshot())

        val metadata = requireNotNull(controls.presentation.value.metadata)
        assertEquals("Album Artist", metadata.albumArtist)
        assertEquals(7, metadata.trackNumber)
        assertEquals(2, metadata.discNumber)
        assertEquals(2024, metadata.year)
        controls.close()
    }

    @Test
    fun `start and close are idempotent and cleanup registration`() {
        val keys = FakeHotKeys()
        val controls = WindowsMediaControls(keys)
        controls.start()
        controls.start()
        assertEquals(1, keys.starts)
        controls.close()
        controls.close()
        assertEquals(1, keys.closes)
        assertEquals(WindowsMediaAvailability.CLOSED, controls.availability.value)
    }

    @Test
    fun `command callback is emitted without native dependency`() = runTest {
        val keys = FakeHotKeys()
        val controls = WindowsMediaControls(keys)
        controls.start()
        val command = async { withTimeout(1_000) { controls.commands.first() } }
        yield()
        keys.emit(MediaCommand.NEXT)
        assertEquals(MediaCommand.NEXT, command.await())
        controls.close()
    }

    @Test
    fun `router maps seek commands against the current position`() = runTest {
        val controller = RecordingController(positionMs = 4_000)
        val router = PlaybackMediaCommandRouter(controller, seekStepMs = 10_000)
        router.handle(MediaCommand.SEEK_BACKWARD)
        router.handle(MediaCommand.SEEK_FORWARD)
        router.handle(MediaCommand.NEXT)
        router.handle(MediaCommand.PREVIOUS)
        assertEquals(listOf("seek:0", "seek:14000", "next", "previous"), controller.calls)
    }

    @Test
    fun `router ignores local media commands while command gate is closed`() = runTest {
        val controller = RecordingController(positionMs = 4_000)
        val router = PlaybackMediaCommandRouter(controller, commandAllowed = { false })

        MediaCommand.entries.forEach { router.handle(it) }

        assertEquals(emptyList(), controller.calls)
    }

    private class FakeHotKeys : WindowsHotKeySource {
        var starts = 0
        var closes = 0
        private var callback: ((MediaCommand) -> Unit)? = null
        override fun start(onCommand: (MediaCommand) -> Unit): AutoCloseable {
            starts++
            callback = onCommand
            return AutoCloseable { closes++ }
        }
        fun emit(command: MediaCommand) = callback?.invoke(command)
    }

    private fun presentationSnapshot(): PlaybackSnapshot =
        PlaybackSnapshot(
            queue = listOf(
                PlaybackItem(
                    PlaybackMetadata(
                        id = "id",
                        title = "Title",
                        artists = listOf(PlaybackArtist(name = "Artist")),
                        album = PlaybackAlbum(title = "Album"),
                        durationHintMs = 180_000L,
                        albumArtist = "Album Artist",
                        trackNumber = 7,
                        discNumber = 2,
                        year = 2024,
                    ),
                ),
            ),
            currentIndex = 0,
            status = PlaybackStatus.READY,
            playWhenReady = true,
            positionMs = 5_000L,
            durationMs = 180_000L,
            volume = 1f,
        )

    private class RecordingController(positionMs: Long) : PlaybackController {
        override val state: StateFlow<PlaybackSnapshot> = MutableStateFlow(PlaybackSnapshot(positionMs = positionMs))
        override val events: SharedFlow<PlaybackEvent> = MutableSharedFlow()
        val calls = mutableListOf<String>()
        override suspend fun replaceQueue(items: List<PlaybackItem>, startIndex: Int, startPositionMs: Long, title: String?, playWhenReady: Boolean) = Unit
        override suspend fun play() { calls += "play" }
        override suspend fun pause() { calls += "pause" }
        override suspend fun stop() = Unit
        override suspend fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
        override suspend fun seekToItem(index: Int, positionMs: Long) = Unit
        override suspend fun skipNext() { calls += "next" }
        override suspend fun skipPrevious(restartThresholdMs: Long) { calls += "previous" }
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
