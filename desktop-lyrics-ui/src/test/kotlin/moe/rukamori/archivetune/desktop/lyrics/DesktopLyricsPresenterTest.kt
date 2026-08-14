package moe.rukamori.archivetune.desktop.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.lyrics.JvmLyricsClient
import moe.rukamori.archivetune.lyrics.JvmLyricsProvider
import moe.rukamori.archivetune.lyrics.NamedLyricsProvider
import moe.rukamori.archivetune.lyrics.LyricsRequest
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.RepeatMode

class DesktopLyricsPresenterTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `real provider document is cached and timeline follows playback position`() = runTest {
        var requests = 0
        val playback = FakePlaybackController()
        val client = JvmLyricsClient(listOf(NamedLyricsProvider("test", "Test", JvmLyricsProvider {
            requests++
            "[00:00]Start\n[00:02]Next"
        })))
        val presenter = DesktopLyricsPresenter(playback, client, this)
        val item = PlaybackItem(PlaybackMetadata(id = "id", title = "Song", artists = listOf(PlaybackArtist(name = "Artist"))))
        playback.state.value = PlaybackSnapshot(queue = listOf(item), currentIndex = 0, positionMs = 2_100)
        advanceUntilIdle()
        val ready = presenter.state.value as DesktopLyricsState.Ready
        assertEquals(1, ready.activeLineIndex)
        playback.state.value = playback.state.value.copy(positionMs = 100)
        advanceUntilIdle()
        assertEquals(0, (presenter.state.value as DesktopLyricsState.Ready).activeLineIndex)
        playback.state.value = PlaybackSnapshot(queue = listOf(item), currentIndex = 0)
        advanceUntilIdle()
        assertEquals(1, requests)
        presenter.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `search artist metadata is used when playback artist is absent`() = runTest {
        var requestedArtist: String? = null
        val playback = FakePlaybackController()
        val client = JvmLyricsClient(listOf(NamedLyricsProvider("test", "Test", JvmLyricsProvider {
            requestedArtist = it.artist
            "Lyrics"
        })))
        val presenter = DesktopLyricsPresenter(playback, client, this)
        val item = PlaybackItem(PlaybackMetadata(id = "id", title = "Song", extras = mapOf("searchArtist" to "Search Artist")))
        playback.state.value = PlaybackSnapshot(queue = listOf(item), currentIndex = 0)

        advanceUntilIdle()

        assertEquals("Search Artist", requestedArtist)
        presenter.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `hanging lyric provider leaves loading with an error`() = runTest {
        val playback = FakePlaybackController()
        val client = JvmLyricsClient(
            providers = listOf(NamedLyricsProvider("hanging", "Hanging", JvmLyricsProvider { delay(Long.MAX_VALUE); "" })),
            providerTimeoutMillis = 100,
            overallTimeoutMillis = 500,
        )
        val presenter = DesktopLyricsPresenter(playback, client, this)
        val item = PlaybackItem(PlaybackMetadata(id = "id", title = "Song", artists = listOf(PlaybackArtist(name = "Artist"))))
        playback.state.value = PlaybackSnapshot(queue = listOf(item), currentIndex = 0)

        advanceUntilIdle()

        assertTrue(presenter.state.value is DesktopLyricsState.Error)
        presenter.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `manual search exposes providers and selected result becomes active`() = runTest {
        val playback = FakePlaybackController()
        val client = JvmLyricsClient(
            listOf(
                NamedLyricsProvider("empty", "Empty", JvmLyricsProvider { "" }),
                NamedLyricsProvider("found", "Found", JvmLyricsProvider { "[00:00]Manual lyrics" }),
            ),
        )
        val presenter = DesktopLyricsPresenter(playback, client, this)
        val item = PlaybackItem(PlaybackMetadata(id = "id", title = "Song", artists = listOf(PlaybackArtist(name = "Artist"))))
        playback.state.value = PlaybackSnapshot(queue = listOf(item), currentIndex = 0)
        advanceUntilIdle()

        presenter.search(LyricsRequest("Edited song", "Edited artist"))
        advanceUntilIdle()

        val result = presenter.searchState.value.results.single { it.document != null }
        presenter.select(result)
        val ready = presenter.state.value as DesktopLyricsState.Ready
        assertEquals("Found", ready.providerName)
        assertEquals("Manual lyrics", ready.timeline.lines.single().text)
        presenter.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `late automatic miss cannot overwrite manually selected lyrics`() = runTest {
        val playback = FakePlaybackController()
        val client = JvmLyricsClient(
            listOf(NamedLyricsProvider("test", "Test", JvmLyricsProvider { request ->
                if (request.title == "Song") {
                    delay(1_000)
                    ""
                } else {
                    "[00:00]Manually selected"
                }
            })),
            providerTimeoutMillis = 2_000,
            overallTimeoutMillis = 3_000,
        )
        val presenter = DesktopLyricsPresenter(playback, client, this)
        val item = PlaybackItem(PlaybackMetadata(id = "id", title = "Song", artists = listOf(PlaybackArtist(name = "Artist"))))
        playback.state.value = PlaybackSnapshot(queue = listOf(item), currentIndex = 0)
        runCurrent()

        presenter.search(LyricsRequest("Edited song", "Artist"))
        runCurrent()
        presenter.select(presenter.searchState.value.results.single { it.document != null })
        advanceTimeBy(1_000)
        advanceUntilIdle()

        val ready = presenter.state.value as DesktopLyricsState.Ready
        assertEquals("Manually selected", ready.timeline.lines.single().text)
        presenter.close()
    }
}

private class FakePlaybackController : PlaybackController {
    override val state = MutableStateFlow(PlaybackSnapshot())
    override val events = MutableSharedFlow<PlaybackEvent>()
    override suspend fun replaceQueue(items: List<PlaybackItem>, startIndex: Int, startPositionMs: Long, title: String?, playWhenReady: Boolean) = Unit
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
    override suspend fun setRepeatMode(mode: RepeatMode) = Unit
    override suspend fun setShuffleEnabled(enabled: Boolean) = Unit
    override suspend fun setVolume(volume: Float) = Unit
    override suspend fun setSpeed(speed: Float) = Unit
    override suspend fun retry() = Unit
    override suspend fun clearError() = Unit
    override fun close() = Unit
}
