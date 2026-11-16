package moe.rukamori.archivetune.playback.stream

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveAudioStreamUseCaseTest {
    @Test
    fun `identical consumers share resolution and one cancellation preserves the other`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val resolver = ResolveAudioStreamUseCase { request, _ ->
            calls.incrementAndGet()
            started.complete(Unit)
            try {
                release.await()
                stream(request)
            } catch (error: kotlinx.coroutines.CancellationException) {
                cancelled.complete(Unit)
                throw error
            }
        }
        val request = request("same-track")
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { resolver(request) }
            withTimeout(5_000) { started.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { resolver(request) }
            first.cancelAndJoin()
            assertFalse(cancelled.isCompleted)
            release.complete(Unit)
            assertEquals("same-track", second.await().title)
            assertEquals(1, calls.get())
            assertEquals("same-track", resolver(request).title)
            assertEquals(1, calls.get())
        } finally {
            resolver.clear()
        }
    }

    @Test
    fun `foreground request cancels unrelated preload and resolves with foreground priority`() = runBlocking {
        val preloadStarted = CompletableDeferred<Unit>()
        val preloadCancelled = CompletableDeferred<Unit>()
        val resolver = ResolveAudioStreamUseCase { request, priority ->
            if (request.mediaId == "preload") {
                preloadStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    preloadCancelled.complete(Unit)
                }
            }
            assertEquals(StreamResolutionPriority.FOREGROUND, priority)
            stream(request)
        }
        try {
            val preload = async { resolver.preload(request("preload")) }
            withTimeout(5_000) { preloadStarted.await() }
            assertEquals("current", resolver(request("current")).title)
            withTimeout(5_000) {
                preloadCancelled.await()
                preload.join()
            }
            assertTrue(preload.isCancelled)
        } finally {
            resolver.clear()
        }
    }

    @Test
    fun `invalidation removes cached stream`() = runBlocking {
        val calls = AtomicInteger()
        val resolver = ResolveAudioStreamUseCase { request, _ ->
            calls.incrementAndGet()
            stream(request)
        }
        val request = request("track")
        try {
            resolver(request)
            assertEquals(1, calls.get())
            resolver.invalidate(request.mediaId)
            assertNull(resolver.peek(request))
            resolver(request)
            assertEquals(2, calls.get())
        } finally {
            resolver.clear()
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
