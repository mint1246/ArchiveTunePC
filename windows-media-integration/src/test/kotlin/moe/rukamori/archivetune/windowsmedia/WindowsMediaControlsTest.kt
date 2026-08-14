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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsMediaControlsTest {
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
        override suspend fun setRepeatMode(mode: RepeatMode) = Unit
        override suspend fun setShuffleEnabled(enabled: Boolean) = Unit
        override suspend fun setVolume(volume: Float) = Unit
        override suspend fun setSpeed(speed: Float) = Unit
        override suspend fun retry() = Unit
        override suspend fun clearError() = Unit
        override fun close() = Unit
    }
}
