/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.ios

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackFailure
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource

@OptIn(ExperimentalCoroutinesApi::class)
class IosPlaybackControllerTest {
    private class FakeIosEngine : IosPlaybackEngine {
        private val mutableState = MutableStateFlow(IosEngineState())
        override val state: StateFlow<IosEngineState> = mutableState.asStateFlow()

        var remoteHandlers: IosRemoteCommandHandlers? = null
        var lastHasPrevious: Boolean = false
        var lastHasNext: Boolean = false
        var loadedItems = mutableListOf<String>()
        var lastSpeed: Float = 1f
        var lastVolume: Float = 1f
        var seekHistory = mutableListOf<Long>()
        var closed = false

        override fun bindRemoteCommands(handlers: IosRemoteCommandHandlers?) {
            remoteHandlers = handlers
        }

        override fun updateNavigationAvailability(hasPrevious: Boolean, hasNext: Boolean) {
            lastHasPrevious = hasPrevious
            lastHasNext = hasNext
        }

        override fun load(item: PlaybackItem, source: ResolvedPlaybackSource, startPositionMs: Long) {
            loadedItems += item.metadata.id
            mutableState.value = IosEngineState(
                itemId = item.metadata.id,
                isPlaying = false,
                isBuffering = false,
                positionMs = startPositionMs,
                bufferedPositionMs = startPositionMs,
                durationMs = item.metadata.durationHintMs ?: 180_000L,
            )
        }

        override fun play() {
            mutableState.value = mutableState.value.copy(isPlaying = true, isBuffering = false, ended = false)
        }

        override fun pause() {
            mutableState.value = mutableState.value.copy(isPlaying = false, isBuffering = false)
        }

        override fun seekTo(positionMs: Long) {
            seekHistory += positionMs
            mutableState.value = mutableState.value.copy(positionMs = positionMs, ended = false)
        }

        override fun setSpeed(speed: Float) {
            lastSpeed = speed
        }

        override fun setVolume(volume: Float) {
            lastVolume = volume
        }

        override fun stop() {
            mutableState.value = IosEngineState()
        }

        override fun close() {
            closed = true
        }

        fun emitPosition(positionMs: Long) {
            mutableState.value = mutableState.value.copy(positionMs = positionMs)
        }

        fun emitEnded() {
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                isBuffering = false,
                ended = true,
                positionMs = mutableState.value.durationMs,
            )
        }
    }

    private fun track(id: String, durationMs: Long = 180_000L): PlaybackItem = PlaybackItem(
        metadata = PlaybackMetadata(
            id = id,
            title = "Track $id",
            artists = listOf(PlaybackArtist(name = "Artist $id")),
            durationHintMs = durationMs,
        ),
        source = PlaybackSource.Deferred(id),
    )

    private fun validSource(id: String) = ResolvedPlaybackSource(
        uri = "https://audio.example.com/$id.m4a",
        mimeType = "audio/mp4",
    )

    @Test
    fun replaceQueueWithPlayWhenReadyFalseStaysIdleWithoutResolving() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeIosEngine()
        var resolveCount = 0
        val controller = IosPlaybackController(
            engine = engine,
            sourceResolver = PlaybackSourceResolver { item ->
                resolveCount++
                Result.success(validSource(item.metadata.id))
            },
            parentContext = dispatcher,
        )

        controller.replaceQueue(listOf(track("a"), track("b")), startIndex = 1, playWhenReady = false)
        advanceUntilIdle()

        assertEquals(0, resolveCount)
        assertEquals(1, controller.state.value.currentIndex)
        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
        assertFalse(controller.state.value.isPlaying)
        assertTrue(engine.lastHasPrevious)
        assertFalse(engine.lastHasNext)

        controller.play()
        advanceUntilIdle()
        assertEquals(1, resolveCount)
        assertEquals(listOf("b"), engine.loadedItems)
        assertTrue(controller.state.value.isPlaying)

        controller.close()
        assertTrue(engine.closed)
        assertNull(engine.remoteHandlers)
    }

    @Test
    fun nonCancellableResolverIsGenerationGuardedWhenTrackChanges() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeIosEngine()
        val firstGate = CompletableDeferred<Unit>()
        val controller = IosPlaybackController(
            engine = engine,
            sourceResolver = PlaybackSourceResolver { item ->
                if (item.metadata.id == "first") {
                    withContext(NonCancellable) { firstGate.await() }
                }
                Result.success(validSource(item.metadata.id))
            },
            parentContext = dispatcher,
        )

        controller.replaceQueue(listOf(track("first"), track("second")), startIndex = 0, playWhenReady = true)
        advanceUntilIdle()
        assertEquals(PlaybackStatus.RESOLVING, controller.state.value.status)

        controller.seekToItem(1)
        advanceUntilIdle()
        assertEquals(listOf("second"), engine.loadedItems)
        assertEquals("second", controller.state.value.currentItem?.metadata?.id)

        firstGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("second"), engine.loadedItems)
        assertEquals("second", controller.state.value.currentItem?.metadata?.id)

        controller.close()
    }

    @Test
    fun endedTrackAutoAdvancesAndHonorsRepeatOneAndRepeatAll() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeIosEngine()
        val controller = IosPlaybackController(
            engine = engine,
            sourceResolver = PlaybackSourceResolver { Result.success(validSource(it.metadata.id)) },
            parentContext = dispatcher,
            random = Random(42),
        )

        controller.replaceQueue(listOf(track("a"), track("b")), startIndex = 0, playWhenReady = true)
        advanceUntilIdle()
        assertEquals("a", controller.state.value.currentItem?.metadata?.id)

        // Repeat ONE restarts current without re-resolving.
        controller.setRepeatMode(RepeatMode.ONE)
        engine.emitEnded()
        advanceUntilIdle()
        assertEquals("a", controller.state.value.currentItem?.metadata?.id)
        assertEquals(listOf("a"), engine.loadedItems)
        assertEquals(listOf(0L), engine.seekHistory)

        // Repeat OFF advances a -> b, then stops at end.
        controller.setRepeatMode(RepeatMode.OFF)
        engine.emitEnded()
        advanceUntilIdle()
        assertEquals("b", controller.state.value.currentItem?.metadata?.id)
        assertEquals(listOf("a", "b"), engine.loadedItems)

        engine.emitEnded()
        advanceUntilIdle()
        assertEquals("b", controller.state.value.currentItem?.metadata?.id)
        assertEquals(PlaybackStatus.ENDED, controller.state.value.status)

        // Repeat ALL wraps b -> a.
        controller.setRepeatMode(RepeatMode.ALL)
        controller.play()
        advanceUntilIdle()
        engine.emitEnded()
        advanceUntilIdle()
        assertEquals("a", controller.state.value.currentItem?.metadata?.id)

        controller.close()
    }

    @Test
    fun skipPreviousHonorsThreeSecondThresholdAndClampsSpeedAndVolume() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeIosEngine()
        val controller = IosPlaybackController(
            engine = engine,
            sourceResolver = PlaybackSourceResolver { Result.success(validSource(it.metadata.id)) },
            parentContext = dispatcher,
        )

        controller.replaceQueue(listOf(track("a"), track("b")), startIndex = 1, playWhenReady = true)
        advanceUntilIdle()

        engine.emitPosition(5_000L)
        advanceUntilIdle()
        controller.skipPrevious(restartThresholdMs = 3_000L)
        advanceUntilIdle()
        assertEquals("b", controller.state.value.currentItem?.metadata?.id)
        assertEquals(0L, controller.state.value.positionMs)

        controller.skipPrevious(restartThresholdMs = 3_000L)
        advanceUntilIdle()
        assertEquals("a", controller.state.value.currentItem?.metadata?.id)

        controller.setSpeed(9.0f)
        controller.setVolume(-2.0f)
        assertEquals(IosPlaybackController.MAX_IOS_SPEED, controller.state.value.speed)
        assertEquals(0f, controller.state.value.volume)
        assertEquals(IosPlaybackController.MAX_IOS_SPEED, engine.lastSpeed)
        assertEquals(0f, engine.lastVolume)

        controller.close()
    }

    @Test
    fun sourceValidationRejectsHeadersBadMimeAndNonHttpsWithSanitizedError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = FakeIosEngine()
        var nextSource = ResolvedPlaybackSource(
            uri = "https://audio.example.com/a.m4a",
            mimeType = "audio/mp4",
            headers = mapOf("Authorization" to "Bearer secret"),
        )
        val controller = IosPlaybackController(
            engine = engine,
            sourceResolver = PlaybackSourceResolver { Result.success(nextSource) },
            parentContext = dispatcher,
        )

        controller.replaceQueue(listOf(track("a")), playWhenReady = true)
        advanceUntilIdle()
        assertEquals(PlaybackStatus.ERROR, controller.state.value.status)
        assertEquals(PlaybackFailure.Kind.UNSUPPORTED_FORMAT, controller.state.value.error?.kind)
        assertTrue(engine.loadedItems.isEmpty())

        nextSource = ResolvedPlaybackSource(uri = "http://insecure.example.com/a.m4a", mimeType = "audio/mp4")
        controller.retry()
        advanceUntilIdle()
        assertEquals(PlaybackFailure.Kind.UNSUPPORTED_FORMAT, controller.state.value.error?.kind)

        nextSource = ResolvedPlaybackSource(uri = "https://audio.example.com/a.m4a", mimeType = "audio/webm")
        controller.retry()
        advanceUntilIdle()
        assertEquals(PlaybackFailure.Kind.UNSUPPORTED_FORMAT, controller.state.value.error?.kind)

        nextSource = ResolvedPlaybackSource(uri = "https://audio.example.com/a.m3u8", mimeType = "application/vnd.apple.mpegurl")
        controller.retry()
        advanceUntilIdle()
        assertNull(controller.state.value.error)
        assertEquals(PlaybackStatus.READY, controller.state.value.status)
        assertEquals(listOf("a"), engine.loadedItems)

        controller.close()
    }

    @Test
    fun sanitizePlaybackErrorMessageRedactsUrlsAndSecrets() {
        val redacted = sanitizePlaybackErrorMessage("Failed fetching https://rr1---sn.googlevideo.com/videoplayback?sig=123")
        assertFalse(redacted.contains("https://"))
        assertFalse(redacted.contains("googlevideo"))
        assertNotNull(redacted)
    }
}
