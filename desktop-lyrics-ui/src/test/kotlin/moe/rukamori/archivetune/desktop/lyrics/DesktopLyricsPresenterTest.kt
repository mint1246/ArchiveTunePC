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
import moe.rukamori.archivetune.lyrics.LyricsDocument
import moe.rukamori.archivetune.lyrics.LyricsFormat
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchResult
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchStatus
import moe.rukamori.archivetune.chords.ChordEvent
import moe.rukamori.archivetune.chords.ChordTrack
import moe.rukamori.archivetune.chords.ChordTrackSource
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
        // The active line index is computed when the document loads; live playback
        // position streams through positionMsFlow for the per-frame animator.
        playback.state.value = playback.state.value.copy(positionMs = 100)
        advanceUntilIdle()
        assertEquals(100L, presenter.positionMsFlow.value)
        playback.state.value = PlaybackSnapshot(queue = listOf(item), currentIndex = 0)
        advanceUntilIdle()
        assertEquals(1, requests)
        presenter.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `inactive presenter defers provider lookup until lyrics surface activates`() = runTest {
        var requests = 0
        val playback = FakePlaybackController()
        val client = JvmLyricsClient(listOf(NamedLyricsProvider("test", "Test", JvmLyricsProvider {
            requests++
            "[00:00]Loaded on demand"
        })))
        val presenter = DesktopLyricsPresenter(playback, client, this, initiallyActive = false)
        val item = PlaybackItem(PlaybackMetadata(id = "id", title = "Song", artists = listOf(PlaybackArtist(name = "Artist"))))
        playback.state.value = PlaybackSnapshot(queue = listOf(item), currentIndex = 0)

        advanceUntilIdle()
        assertEquals(0, requests)
        assertTrue(presenter.state.value is DesktopLyricsState.Idle)

        presenter.setActive(true)
        advanceUntilIdle()
        assertEquals(1, requests)
        assertTrue(presenter.state.value is DesktopLyricsState.Ready)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `unrenderable selection remains an error and is not cached`() = runTest {
        val playback = FakePlaybackController()
        val client = JvmLyricsClient(listOf(NamedLyricsProvider("empty", "Empty", JvmLyricsProvider { "" })))
        val presenter = DesktopLyricsPresenter(playback, client, this)
        val item = PlaybackItem(PlaybackMetadata(id = "id", title = "Song", artists = listOf(PlaybackArtist(name = "Artist"))))
        playback.state.value = PlaybackSnapshot(queue = listOf(item), currentIndex = 0)
        advanceUntilIdle()

        val selected = presenter.select(
            LyricsProviderSearchResult(
                "broken",
                "Broken provider",
                LyricsProviderSearchStatus.FOUND,
                LyricsDocument("broken", "Broken provider", "<tt><body/></tt>", LyricsFormat.TTML),
            ),
        )

        assertEquals(false, selected)
        assertTrue(presenter.state.value is DesktopLyricsState.Error)
        presenter.retry()
        advanceUntilIdle()
        assertTrue(presenter.state.value is DesktopLyricsState.Empty)
        presenter.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `automatic lookup skips unrenderable provider and displays a renderable result`() = runTest {
        val playback = FakePlaybackController()
        val client = JvmLyricsClient(
            listOf(
                NamedLyricsProvider("broken", "Broken", JvmLyricsProvider { "<tt><body/></tt>" }),
                NamedLyricsProvider("working", "Working", JvmLyricsProvider { "[00:00]Displayed automatically" }),
            ),
        )
        val presenter = DesktopLyricsPresenter(playback, client, this)
        val item = PlaybackItem(PlaybackMetadata(id = "id", title = "Song", artists = listOf(PlaybackArtist(name = "Artist"))))
        playback.state.value = PlaybackSnapshot(queue = listOf(item), currentIndex = 0)

        advanceUntilIdle()

        val ready = presenter.state.value as DesktopLyricsState.Ready
        assertEquals("Working", ready.providerName)
        assertEquals("Displayed automatically", ready.timeline.lines.single().text)
        assertEquals(2, presenter.searchState.value.results.count { it.document != null })
        presenter.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `clicking a timed line seeks playback`() = runTest {
        val playback = FakePlaybackController()
        val client = JvmLyricsClient(listOf(NamedLyricsProvider("test", "Test", JvmLyricsProvider { "[00:03]Timed" })))
        val presenter = DesktopLyricsPresenter(playback, client, this)
        playback.state.value = PlaybackSnapshot(
            queue = listOf(PlaybackItem(PlaybackMetadata("id", "Song", artists = listOf(PlaybackArtist(name = "Artist"))))),
            currentIndex = 0,
        )
        advanceUntilIdle()
        presenter.seekToLine(0)
        advanceUntilIdle()
        assertEquals(3_000L, playback.lastSeekMs)
        presenter.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `local model action bypasses Songsterr and publishes neural chords`() = runTest {
        var modelCalls = 0
        val playback = FakePlaybackController()
        val client = JvmLyricsClient(listOf(NamedLyricsProvider("test", "Test", JvmLyricsProvider { "Lyrics" })))
        val presenter = DesktopLyricsPresenter(
            playback = playback,
            lyricsClient = client,
            scope = this,
            localChordAnalyzer = { _, request ->
                modelCalls++
                Result.success(
                    ChordTrack(
                        songsterrSongId = 0,
                        title = request.title,
                        artist = request.artist,
                        guitarTrackName = null,
                        source = ChordTrackSource.LOCAL_NEURAL,
                        events = listOf(ChordEvent(0, 1_000, "C")),
                        tabDurationMs = 1_000,
                    ),
                )
            },
        )
        val item = PlaybackItem(PlaybackMetadata(id = "id", title = "Song", artists = listOf(PlaybackArtist(name = "Artist"))))
        playback.state.value = PlaybackSnapshot(queue = listOf(item), currentIndex = 0, durationMs = 180_000)
        advanceUntilIdle()

        presenter.loadChordsFromLocalModel()
        advanceUntilIdle()

        assertEquals(1, modelCalls)
        val ready = presenter.chordState.value as DesktopChordState.Ready
        assertEquals(ChordTrackSource.LOCAL_NEURAL, ready.track.source)
        assertEquals("C", ready.track.events.single().name)
        presenter.close()
    }
}

private class FakePlaybackController : PlaybackController {
    var lastSeekMs: Long? = null
    override val state = MutableStateFlow(PlaybackSnapshot())
    override val events = MutableSharedFlow<PlaybackEvent>()
    override suspend fun replaceQueue(items: List<PlaybackItem>, startIndex: Int, startPositionMs: Long, title: String?, playWhenReady: Boolean) = Unit
    override suspend fun play() = Unit
    override suspend fun pause() = Unit
    override suspend fun stop() = Unit
    override suspend fun seekTo(positionMs: Long) { lastSeekMs = positionMs }
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
