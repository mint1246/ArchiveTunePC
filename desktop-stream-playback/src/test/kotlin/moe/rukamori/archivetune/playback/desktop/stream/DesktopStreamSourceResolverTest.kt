package moe.rukamori.archivetune.playback.desktop.stream

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.innertube.DesktopSessionProvider
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.ResolvedAudioStream
import moe.rukamori.archivetune.innertube.StreamHttpRecoveryAction
import moe.rukamori.archivetune.innertube.StreamResolutionFailureKind
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopStreamSourceResolverTest {
    @Test
    fun `resolves concrete remote and local sources without touching backend`() = runTest {
        val backend = FakeStreamBackend()
        val resolver = DesktopStreamSourceResolver(
            backend = backend,
            sessionProvider = DesktopSessionProvider { PlaybackAuthState.EMPTY },
        )
        val localFile = Files.createTempFile("archivetune-local-", ".m4a")

        val remote = resolver.resolve(
            PlaybackItem(
                metadata = PlaybackMetadata(id = "remote", title = "Remote"),
                source = PlaybackSource.Remote(
                    url = "https://example.invalid/audio.m4a",
                    mimeType = "audio/mp4",
                    headers = mapOf("X-Test" to "1"),
                    expiresAtEpochMs = 999_999L,
                ),
            ),
        ).getOrThrow()
        val local = resolver.resolve(
            PlaybackItem(
                metadata = PlaybackMetadata(id = "local", title = "Local"),
                source = PlaybackSource.LocalFile(localFile.toString()),
            ),
        ).getOrThrow()

        assertEquals("https://example.invalid/audio.m4a", remote.uri)
        assertEquals(localFile.toUri().toASCIIString(), local.uri)
        assertEquals(0, backend.resolveCalls.size)
    }

    @Test
    fun `caches deferred resolution within expiry lead and serves cache hit without backend`() = runTest {
        var now = 1_000_000L
        val backend = FakeStreamBackend().apply {
            nextResolveResult = { videoId ->
                Result.success(
                    sampleResolvedAudioStream(
                        videoId = "actual-$videoId",
                        url = "https://signed.example.invalid/videoplayback?id=$videoId&pot=secret-pot&n=sig-n",
                        expiresAtEpochMillis = now + DesktopStreamSourceResolver.STREAM_CACHE_EXPIRY_LEAD_MILLIS + 30_000L,
                    ),
                )
            }
        }
        val diagnostics = RecordingDiagnostics()
        val resolver = DesktopStreamSourceResolver(
            backend = backend,
            sessionProvider = DesktopSessionProvider { PlaybackAuthState.EMPTY },
            diagnostics = diagnostics,
            clockMillis = { now },
        )
        val item = deferredItem("clicked-id")

        val first = resolver.resolve(item).getOrThrow()
        now += 10_000L
        val second = resolver.resolve(item).getOrThrow()

        assertEquals(first, second)
        assertEquals(1, backend.resolveCalls.size)
        assertEquals("clicked-id", backend.resolveCalls.single().videoId)

        // Advance into the 60s expiry lead window: cache entry must be evicted and re-resolved.
        now += 25_000L
        resolver.resolve(item).getOrThrow()
        assertEquals(2, backend.resolveCalls.size)
    }

    @Test
    fun `account fingerprint change invalidates cached source and blocks mid-flight cache write`() = runTest {
        var now = 1_000_000L
        var auth = PlaybackAuthState(
            cookie = "SAPISID=account-a-secret",
            visitorData = "visitor-a",
            dataSyncId = "channel-a",
        ).normalized()
        val backend = FakeStreamBackend().apply {
            onResolveStart = {
                // Simulate account switching while the first resolve is in flight.
                if (resolveCalls.size == 1) {
                    auth = PlaybackAuthState(
                        cookie = "SAPISID=account-b-secret",
                        visitorData = "visitor-b",
                        dataSyncId = "channel-b",
                    ).normalized()
                }
            }
            nextResolveResult = { videoId ->
                Result.success(
                    sampleResolvedAudioStream(
                        videoId = videoId,
                        url = "https://signed.example.invalid/stream-$videoId-${resolveCalls.size}",
                        expiresAtEpochMillis = now + 600_000L,
                    ),
                )
            }
        }
        val resolver = DesktopStreamSourceResolver(
            backend = backend,
            sessionProvider = DesktopSessionProvider { auth },
            clockMillis = { now },
        )
        val item = deferredItem("track-1")

        // First call completes after account switched -> must NOT be cached under account B.
        resolver.resolve(item).getOrThrow()
        // Second call under account B must miss cache and resolve again, then cache under account B.
        resolver.resolve(item).getOrThrow()
        // Third call under account B hits cache.
        resolver.resolve(item).getOrThrow()
        assertEquals(2, backend.resolveCalls.size)

        // Switching back to account A must also miss account B's cached entry.
        auth = PlaybackAuthState(
            cookie = "SAPISID=account-a-secret",
            visitorData = "visitor-a",
            dataSyncId = "channel-a",
        ).normalized()
        resolver.resolve(item).getOrThrow()
        assertEquals(3, backend.resolveCalls.size)
    }

    @Test
    fun `cacheRespectsMaxEntriesAndPrefersEvictingExpiredBeforeClearing`() = runTest {
        var now = 1_000_000L
        val backend = FakeStreamBackend().apply {
            nextResolveResult = { videoId ->
                Result.success(
                    sampleResolvedAudioStream(
                        videoId = videoId,
                        url = "https://signed.example.invalid/$videoId",
                        expiresAtEpochMillis = now + 120_000L,
                    ),
                )
            }
        }
        val resolver = DesktopStreamSourceResolver(
            backend = backend,
            sessionProvider = DesktopSessionProvider { PlaybackAuthState.EMPTY },
            clockMillis = { now },
        )

        repeat(DesktopStreamSourceResolver.MAX_RESOLVED_SOURCE_CACHE_ENTRIES) { index ->
            resolver.resolve(deferredItem("track-$index")).getOrThrow()
        }
        assertEquals(DesktopStreamSourceResolver.MAX_RESOLVED_SOURCE_CACHE_ENTRIES, backend.resolveCalls.size)

        // All 128 entries are still valid: inserting entry 129 triggers clear() before insert,
        // so track-0 is no longer cached.
        resolver.resolve(deferredItem("track-overflow")).getOrThrow()
        resolver.resolve(deferredItem("track-0")).getOrThrow()
        assertEquals(DesktopStreamSourceResolver.MAX_RESOLVED_SOURCE_CACHE_ENTRIES + 2, backend.resolveCalls.size)
    }

    @Test
    fun `cacheResolvedRemote false skips caching`() = runTest {
        val now = 1_000_000L
        val backend = FakeStreamBackend().apply {
            nextResolveResult = { videoId ->
                Result.success(
                    sampleResolvedAudioStream(
                        videoId = videoId,
                        url = "https://signed.example.invalid/$videoId",
                        expiresAtEpochMillis = now + 600_000L,
                    ),
                )
            }
        }
        val resolver = DesktopStreamSourceResolver(
            backend = backend,
            sessionProvider = DesktopSessionProvider { PlaybackAuthState.EMPTY },
            clockMillis = { now },
        )
        val item = deferredItem("prefetch-track")

        resolver.resolve(item, cacheResolvedRemote = { false }).getOrThrow()
        resolver.resolve(item).getOrThrow()

        assertEquals(2, backend.resolveCalls.size)
    }

    @Test
    fun `recoverFromStreamHttpFailure evicts cache and delegates current profile with resolvedVideoId`() = runTest {
        var now = 1_000_000L
        val auth = PlaybackAuthState(cookie = "SAPISID=secret", visitorData = "v", dataSyncId = "d").normalized()
        val backend = FakeStreamBackend().apply {
            nextResolveResult = { _ ->
                Result.success(
                    sampleResolvedAudioStream(
                        videoId = "replacement-id",
                        url = "https://signed.example.invalid/play?pot=tok123&n=abc",
                        expiresAtEpochMillis = now + 600_000L,
                        clientName = "WEB_REMIX",
                        clientVersion = "1.20260101",
                        authFingerprint = "stream-fp",
                    ),
                )
            }
            nextRecoveryAction = StreamHttpRecoveryAction.PROOF_REFRESHED
        }
        val diagnostics = RecordingDiagnostics()
        val resolver = DesktopStreamSourceResolver(
            backend = backend,
            sessionProvider = DesktopSessionProvider { auth },
            diagnostics = diagnostics,
            clockMillis = { now },
        )
        val item = deferredItem("clicked-old-id")
        val resolved = resolver.resolve(item).getOrThrow()

        // Non-retryable status returns false and keeps cache intact.
        assertFalse(resolver.recoverFromStreamHttpFailure(item, resolved, 500))
        resolver.resolve(item).getOrThrow()
        assertEquals(1, backend.resolveCalls.size)

        // Retryable 403 on current matching source calls prepareStreamHttpRecovery with resolvedVideoId.
        assertTrue(resolver.recoverFromStreamHttpFailure(item, resolved, 403))
        val recovery = backend.recoveryCalls.single()
        assertEquals("replacement-id", recovery.videoId)
        assertEquals("WEB_REMIX@1.20260101", recovery.clientKey)
        assertEquals(403, recovery.httpStatusCode)
        assertEquals("stream-fp", recovery.authFingerprint)
        assertTrue(recovery.failedUrlHadPoToken)

        // Cache was evicted, so next resolve hits backend again.
        resolver.resolve(item).getOrThrow()
        assertEquals(2, backend.resolveCalls.size)

        // Near-expiry source still returns true for retry but does not call prepareStreamHttpRecovery.
        val nearExpirySource = resolved.copy(expiresAtEpochMs = now + 10_000L)
        assertTrue(resolver.recoverFromStreamHttpFailure(item, nearExpirySource, 403))
        assertEquals(1, backend.recoveryCalls.size)
        assertTrue(diagnostics.infos.any { it.contains("recovery=RETRY_ONLY") && it.contains("expired=true") })
    }

    @Test
    fun `failure classification and diagnostics never leak signed URLs or auth secrets`() = runTest {
        val auth = PlaybackAuthState(
            cookie = "SAPISID=super-secret-cookie",
            visitorData = "secret-visitor",
            dataSyncId = "secret-channel",
        ).normalized()
        val backend = FakeStreamBackend().apply {
            nextResolveResult = { _ ->
                Result.success(
                    sampleResolvedAudioStream(
                        videoId = "actual-id",
                        url = "https://rr1---sn.googlevideo.com/videoplayback?pot=secret-pot&n=secret-n&sig=secret-sig",
                        expiresAtEpochMillis = 2_000_000L,
                        requestHeaders = mapOf("Cookie" to "SAPISID=super-secret-cookie"),
                    ),
                )
            }
        }
        val diagnostics = RecordingDiagnostics()
        val resolver = DesktopStreamSourceResolver(
            backend = backend,
            sessionProvider = DesktopSessionProvider { auth },
            diagnostics = diagnostics,
            clockMillis = { 1_000_000L },
        )

        resolver.resolve(deferredItem("clicked-id")).getOrThrow()
        val successLog = diagnostics.infos.single()
        assertTrue(successLog.contains("videoId=clicked-id"))
        assertTrue(successLog.contains("resolvedVideoId=actual-id"))
        assertTrue(successLog.contains("hasPot=true"))
        assertTrue(successLog.contains("hasN=true"))
        assertFalse(successLog.contains("googlevideo.com"))
        assertFalse(successLog.contains("secret-pot"))
        assertFalse(successLog.contains("secret-n"))
        assertFalse(successLog.contains("super-secret-cookie"))
        assertFalse(successLog.contains("secret-visitor"))
        assertFalse(successLog.contains("secret-channel"))

        backend.nextResolveResult = { _ -> Result.failure(IOException("https://secret.example.invalid/?pot=leak")) }
        val suppressedFailure = resolver.resolve(deferredItem("fail-silent"), reportFailure = false)
        assertTrue(suppressedFailure.isFailure)
        assertEquals(0, diagnostics.warnings.size)

        val reportedFailure = resolver.resolve(deferredItem("fail-loud"), reportFailure = true)
        val exception = assertIs<DesktopStreamResolutionException>(reportedFailure.exceptionOrNull())
        assertEquals(StreamResolutionFailureKind.NETWORK, exception.classification)
        assertEquals(1, diagnostics.warnings.size)
        assertFalse(diagnostics.warnings.single().contains("secret.example.invalid"))
    }

    private fun deferredItem(id: String) = PlaybackItem(
        metadata = PlaybackMetadata(
            id = id,
            title = "Track $id",
            artists = listOf(PlaybackArtist(id = "artist-1", name = "Artist")),
        ),
        source = PlaybackSource.Deferred(id),
    )

    private fun sampleResolvedAudioStream(
        videoId: String,
        url: String,
        expiresAtEpochMillis: Long,
        clientName: String = "WEB_REMIX",
        clientVersion: String = "1.0",
        authFingerprint: String = "auth-fp",
        requestHeaders: Map<String, String> = emptyMap(),
    ) = ResolvedAudioStream(
        videoId = videoId,
        url = url,
        format = PlayerResponse.StreamingData.Format(
            itag = 251,
            url = url,
            mimeType = "audio/webm; codecs=\"opus\"",
            bitrate = 128_000,
            width = null,
            height = null,
            contentLength = 1_024L,
            quality = "tiny",
            fps = null,
            qualityLabel = null,
            averageBitrate = 128_000,
            audioQuality = "AUDIO_QUALITY_MEDIUM",
            approxDurationMs = "180000",
            audioSampleRate = 48_000,
            audioChannels = 2,
            loudnessDb = -4.2,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
        ),
        videoDetails = null,
        playbackTracking = null,
        audioConfig = PlayerResponse.PlayerConfig.AudioConfig(
            loudnessDb = -4.2,
            perceptualLoudnessDb = -14.0,
        ),
        clientName = clientName,
        clientVersion = clientVersion,
        requestHeaders = requestHeaders,
        expiresAtEpochMillis = expiresAtEpochMillis,
        authFingerprint = authFingerprint,
    )

    private class FakeStreamBackend : DesktopStreamBackend {
        data class ResolveCall(val videoId: String)
        data class RecoveryCall(
            val videoId: String,
            val clientKey: String?,
            val httpStatusCode: Int,
            val authFingerprint: String,
            val failedUrlHadPoToken: Boolean,
        )

        val resolveCalls = CopyOnWriteArrayList<ResolveCall>()
        val recoveryCalls = CopyOnWriteArrayList<RecoveryCall>()
        var onResolveStart: (() -> Unit)? = null
        var nextResolveResult: (String) -> Result<ResolvedAudioStream> = {
            Result.failure(IllegalStateException("Unconfigured fake"))
        }
        var nextRecoveryAction: StreamHttpRecoveryAction = StreamHttpRecoveryAction.RETRY_ONLY

        override suspend fun resolveAudioStreamWithMetadataFallback(
            videoId: String,
            title: String,
            artistNames: List<String>,
            albumTitle: String?,
            durationHintMs: Long?,
        ): Result<ResolvedAudioStream> {
            resolveCalls += ResolveCall(videoId)
            onResolveStart?.invoke()
            return nextResolveResult(videoId)
        }

        override suspend fun prepareStreamHttpRecovery(
            videoId: String,
            clientKey: String?,
            httpStatusCode: Int,
            authFingerprint: String,
            failedUrlHadPoToken: Boolean,
        ): StreamHttpRecoveryAction {
            recoveryCalls += RecoveryCall(
                videoId = videoId,
                clientKey = clientKey,
                httpStatusCode = httpStatusCode,
                authFingerprint = authFingerprint,
                failedUrlHadPoToken = failedUrlHadPoToken,
            )
            return nextRecoveryAction
        }
    }

    private class RecordingDiagnostics : DesktopStreamDiagnostics {
        val infos = CopyOnWriteArrayList<String>()
        val warnings = CopyOnWriteArrayList<String>()

        override fun info(message: String) {
            infos += message
        }

        override fun warning(message: String) {
            warnings += message
        }
    }
}
