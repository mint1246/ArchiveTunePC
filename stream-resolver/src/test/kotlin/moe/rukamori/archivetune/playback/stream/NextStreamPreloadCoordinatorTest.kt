/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NextStreamPreloadCoordinatorTest {
    @Test
    fun `updateTarget deduplicates identical requests while in flight and after completion`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val preloadCalls = AtomicInteger()

        val preloader =
            NextStreamPreloadCoordinator(
                peekResolved = { null },
                preloadResolved = {
                    preloadCalls.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    completed.complete(Unit)
                },
                scope = scope,
            )

        val req = request("track-1")
        try {
            preloader.updateTarget(req)
            withTimeout(5_000) { started.await() }

            // Repeated update while in flight should be ignored.
            preloader.updateTarget(req)
            release.complete(Unit)
            withTimeout(5_000) { completed.await() }

            // Repeated update after successful completion should also be ignored.
            preloader.updateTarget(req)
            assertEquals(1, preloadCalls.get())
        } finally {
            preloader.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `resolver peek hit short-circuits local cache check and preload`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val localCacheCalls = AtomicInteger()
        val preloadCalls = AtomicInteger()
        val peekCalls = AtomicInteger()

        val preloader =
            NextStreamPreloadCoordinator(
                peekResolved = { req ->
                    peekCalls.incrementAndGet()
                    stream(req)
                },
                preloadResolved = { preloadCalls.incrementAndGet() },
                localCacheProbe = PreloadCacheProbe {
                    localCacheCalls.incrementAndGet()
                    false
                },
                scope = scope,
            )

        val req = request("cached-in-resolver")
        try {
            preloader.updateTarget(req)
            // Yield until the launched coroutine finishes.
            withTimeout(5_000) {
                while (peekCalls.get() == 0) {
                    kotlinx.coroutines.yield()
                }
            }
            preloader.updateTarget(req)
            assertEquals(1, peekCalls.get())
            assertEquals(0, localCacheCalls.get())
            assertEquals(0, preloadCalls.get())
        } finally {
            preloader.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `local player or download cache hit short-circuits preload`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val localCacheCalls = AtomicInteger()
        val preloadCalls = AtomicInteger()

        val preloader =
            NextStreamPreloadCoordinator(
                peekResolved = { null },
                preloadResolved = { preloadCalls.incrementAndGet() },
                localCacheProbe = PreloadCacheProbe { mediaId ->
                    localCacheCalls.incrementAndGet()
                    mediaId == "cached-locally"
                },
                scope = scope,
            )

        val req = request("cached-locally")
        try {
            preloader.updateTarget(req)
            withTimeout(5_000) {
                while (localCacheCalls.get() == 0) {
                    kotlinx.coroutines.yield()
                }
            }
            preloader.updateTarget(req)
            assertEquals(1, localCacheCalls.get())
            assertEquals(0, preloadCalls.get())
        } finally {
            preloader.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `changing target cancels superseded preload and ignores stale completion`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val secondCompleted = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<String>()

        val preloader =
            NextStreamPreloadCoordinator(
                peekResolved = { null },
                preloadResolved = { req ->
                    calls += req.mediaId
                    if (req.mediaId == "first") {
                        firstStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            firstCancelled.complete(Unit)
                        }
                    } else {
                        secondStarted.complete(Unit)
                        secondRelease.await()
                        secondCompleted.complete(Unit)
                    }
                },
                scope = scope,
            )

        try {
            preloader.updateTarget(request("first"))
            withTimeout(5_000) { firstStarted.await() }

            preloader.updateTarget(request("second"))
            withTimeout(5_000) {
                firstCancelled.await()
                secondStarted.await()
            }

            // Duplicate update for "second" must still be deduplicated after first job cancelled.
            preloader.updateTarget(request("second"))
            secondRelease.complete(Unit)
            withTimeout(5_000) { secondCompleted.await() }

            assertEquals(listOf("first", "second"), calls.toList())
        } finally {
            preloader.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `cancel cancels active job and allows same target to preload again without stale clobber`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondCompleted = CompletableDeferred<Unit>()
        val attempt = AtomicInteger()

        val preloader =
            NextStreamPreloadCoordinator(
                peekResolved = { null },
                preloadResolved = {
                    val index = attempt.incrementAndGet()
                    if (index == 1) {
                        firstStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            firstCancelled.complete(Unit)
                        }
                    } else {
                        secondStarted.complete(Unit)
                        secondCompleted.complete(Unit)
                    }
                },
                scope = scope,
            )

        val req = request("same-track")
        try {
            preloader.updateTarget(req)
            withTimeout(5_000) { firstStarted.await() }

            preloader.cancel()
            preloader.updateTarget(req)

            withTimeout(5_000) {
                firstCancelled.await()
                secondStarted.await()
                secondCompleted.await()
            }
            // Target must remain deduplicated after second attempt succeeds.
            preloader.updateTarget(req)
            assertEquals(2, attempt.get())
        } finally {
            preloader.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `non-cancellation failure reports diagnostics and clears target so next update retries`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val attempt = AtomicInteger()
        val firstFailed = CompletableDeferred<Unit>()
        val secondSucceeded = CompletableDeferred<Unit>()
        val reportedFailures = CopyOnWriteArrayList<Pair<String, String?>>()

        val preloader =
            NextStreamPreloadCoordinator(
                peekResolved = { null },
                preloadResolved = {
                    if (attempt.incrementAndGet() == 1) {
                        throw IllegalStateException("network boom")
                    }
                    secondSucceeded.complete(Unit)
                },
                diagnostics = PreloadDiagnostics { req, throwable ->
                    reportedFailures += req.mediaId to throwable.message
                    firstFailed.complete(Unit)
                },
                scope = scope,
            )

        val req = request("flaky-track")
        try {
            preloader.updateTarget(req)
            withTimeout(5_000) { firstFailed.await() }

            // Retry same request after failure.
            preloader.updateTarget(req)
            withTimeout(5_000) { secondSucceeded.await() }

            assertEquals(2, attempt.get())
            assertEquals(listOf("flaky-track" to "network boom"), reportedFailures.toList())
        } finally {
            preloader.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `integrates with StreamResolutionCoordinator and reuses resolved cache`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val resolveCalls = AtomicInteger()
        val resolvedSignal = CompletableDeferred<Unit>()
        val coordinator =
            StreamResolutionCoordinator { req, priority ->
                assertEquals(StreamResolutionPriority.BACKGROUND, priority)
                resolveCalls.incrementAndGet()
                stream(req)
            }
        val preloader =
            NextStreamPreloadCoordinator(
                coordinator = coordinator,
                scope = scope,
            )

        val req = request("coord-track")
        try {
            preloader.updateTarget(req)
            withTimeout(5_000) {
                while (coordinator.peek(req) == null) {
                    kotlinx.coroutines.yield()
                }
                resolvedSignal.complete(Unit)
            }
            assertTrue(resolvedSignal.isCompleted)
            assertEquals(1, resolveCalls.get())

            // Cancelling and re-targeting should hit coordinator.peek() without re-resolving.
            preloader.cancel()
            preloader.updateTarget(req)
            kotlinx.coroutines.yield()
            assertEquals(1, resolveCalls.get())
        } finally {
            preloader.cancel()
            coordinator.close()
            scope.cancel()
        }
    }

    private fun request(id: String) = AudioStreamRequest(
        mediaId = id,
        quality = AudioQuality.HIGH,
        networkMetered = false,
        purpose = StreamPurpose.PLAYBACK,
        authState = PlaybackAuthState(),
    )

    private fun stream(request: AudioStreamRequest) = ResolvedAudioStream(
        url = "https://example.invalid/audio",
        requestHeaders = emptyMap(),
        formatId = 140,
        mimeType = "audio/mp4",
        codecs = "mp4a",
        bitrate = 128_000,
        sampleRate = 44_100,
        contentLength = 1_000,
        expiresAtMs = System.currentTimeMillis() + 600_000,
        authFingerprint = request.authState.streamCacheFingerprint,
        source = StreamSource.YOUTUBEI,
        title = request.mediaId,
    )
}
