package moe.rukamori.archivetune.innertube

import moe.rukamori.archivetune.innertube.models.AccountInfo
import moe.rukamori.archivetune.innertube.models.ResponseContext
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.YouTubeLocale
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class ArchiveTuneJvmClientTest {
    private val originalLocale = YouTube.locale
    @After
    fun restoreNetworkGatekeeper() {
        NetworkGatekeeper.setConnectionBlocked(true)
        YouTube.authState = PlaybackAuthState.EMPTY
        YouTube.locale = originalLocale
    }

    @Test
    fun defaultNetworkPolicyMatchesOfficialBuildAndUnblocksBackend() {
        NetworkGatekeeper.setConnectionBlocked(true)

        ArchiveTuneJvmClient(Files.createTempDirectory("archivetune-test-").toFile())

        assertFalse(NetworkGatekeeper.isConnectionBlocked)
    }

    @Test
    fun enabledNetworkPolicyKeepsBackendBlockedUntilExternalVerification() {
        NetworkGatekeeper.setConnectionBlocked(false)

        ArchiveTuneJvmClient(
            cacheDirectory = Files.createTempDirectory("archivetune-test-").toFile(),
            networkPolicy = DesktopNetworkPolicy(gatekeeperEnabled = true),
        )

        assertTrue(NetworkGatekeeper.isConnectionBlocked)
    }

    @Test
    fun sessionProviderUpdatesBackendFingerprintWithoutLeakingCookieToMediaHeaders() {
        val provided = PlaybackAuthState(cookie = "SAPISID=secret; SID=another", dataSyncId = "sync").normalized()
        val client =
            ArchiveTuneJvmClient(
                cacheDirectory = Files.createTempDirectory("archivetune-test-").toFile(),
                sessionProvider = DesktopSessionProvider { provided },
            )

        assertEquals(provided.fingerprint, client.authState.fingerprint)
        val headers = requestHeaders(YouTubeClient.WEB_REMIX)
        assertTrue(headers.containsKey("User-Agent"))
        assertTrue(headers.containsKey("Origin"))
        assertFalse(headers.keys.any { it.contains("cookie", ignoreCase = true) || it.contains("authorization", ignoreCase = true) })
        assertFalse(headers.values.any { it.contains("secret") })
    }

    @Test
    fun signedInResolutionMatchesAndroidWebThenNativeFallbackOrder() {
        val client = ArchiveTuneJvmClient(Files.createTempDirectory("archivetune-test-").toFile())
        val auth = PlaybackAuthState(cookie = "SAPISID=secret", dataSyncId = "channel").normalized()

        val ordered = client.orderedStreamClientsForTest(auth)

        assertEquals(YouTubeClient.WEB_REMIX.clientName, ordered[0].clientName)
        assertEquals(YouTubeClient.VISIONOS.clientName, ordered[1].clientName)
        assertEquals(YouTubeClient.VISIONOS.clientVersion, ordered[1].clientVersion)
    }

    @Test
    fun signedInHttp403BackoffMakesVisionOsTheRetryClient() {
        val client = ArchiveTuneJvmClient(Files.createTempDirectory("archivetune-test-").toFile())
        val auth = PlaybackAuthState(cookie = "SAPISID=secret", dataSyncId = "channel").normalized()
        val videoId = "fresh-release"
        val first = client.orderedStreamClientsForTest(auth, videoId).first()

        assertEquals(YouTubeClient.WEB_REMIX.clientName, first.clientName)
        assertTrue(
            client.markStreamClientFailed(
                videoId,
                "${first.clientName}@${first.clientVersion}",
                403,
                auth.fingerprint,
            ),
        )

        val retry = client.orderedStreamClientsForTest(auth, videoId).first()
        assertEquals(YouTubeClient.VISIONOS.clientName, retry.clientName)
        assertEquals(YouTubeClient.VISIONOS.clientVersion, retry.clientVersion)
    }

    @Test
    fun signedInWeb403WithoutProofRefreshesProofBeforeClientBackoff() = runBlocking {
        val initial = PlaybackAuthState(cookie = "SAPISID=secret", dataSyncId = "channel").normalized()
        var current = initial
        val provider =
            object : DesktopSessionProvider {
                override fun currentAuthState(): PlaybackAuthState = current

                override suspend fun ensurePlaybackAuth(
                    videoId: String,
                    authState: PlaybackAuthState,
                ): PlaybackAuthState =
                    authState
                        .copy(
                            poTokenGvs = "video-proof",
                            poTokenGvsSession = "session-proof",
                            poTokenGvsVideoId = videoId,
                            webClientPoTokenEnabled = true,
                        ).normalized()

                override suspend fun persistAuthRepair(
                    initialAuthState: PlaybackAuthState,
                    repairedAuthState: PlaybackAuthState,
                ) {
                    current = repairedAuthState
                }
            }
        val client =
            ArchiveTuneJvmClient(
                cacheDirectory = Files.createTempDirectory("archivetune-test-").toFile(),
                sessionProvider = provider,
            )
        val videoId = "needs-proof"
        val web = client.orderedStreamClientsForTest(initial, videoId).first()

        val action =
            client.prepareStreamHttpRecovery(
                videoId = videoId,
                clientKey = "${web.clientName}@${web.clientVersion}",
                httpStatusCode = 403,
                authFingerprint = initial.fingerprint,
                failedUrlHadPoToken = false,
            )

        assertEquals(StreamHttpRecoveryAction.PROOF_REFRESHED, action)
        assertEquals("video-proof", current.resolveGvsPoToken(YouTubeClient.WEB_REMIX, videoId))
        assertEquals(YouTubeClient.WEB_REMIX.clientName, client.orderedStreamClientsForTest(current, videoId).first().clientName)
    }

    @Test
    fun retryableMediaHttpFailureTemporarilyRemovesThatClientForTheVideo() {
        val client = ArchiveTuneJvmClient(Files.createTempDirectory("archivetune-test-").toFile())
        val auth = PlaybackAuthState().normalized()
        val videoId = "video"
        val first = client.orderedStreamClientsForTest(auth, videoId).first()
        val firstKey = "${first.clientName}@${first.clientVersion}"

        assertTrue(client.markStreamClientFailed(videoId, firstKey, 403, auth.fingerprint))

        val retriedOrder = client.orderedStreamClientsForTest(auth, videoId)
        assertFalse(retriedOrder.any { it.clientName == first.clientName && it.clientVersion == first.clientVersion })
        assertTrue(retriedOrder.isNotEmpty())
    }

    @Test
    fun retryableMediaHttpFailureAfterAuthRefreshAlsoBlocksCurrentGeneration() = runBlocking {
        val failedAuth = PlaybackAuthState(visitorData = "before").normalized()
        val currentAuth = failedAuth.copy(visitorData = "after").normalized()
        val client =
            ArchiveTuneJvmClient(
                cacheDirectory = Files.createTempDirectory("archivetune-test-").toFile(),
                sessionProvider = DesktopSessionProvider { currentAuth },
            )
        val videoId = "video-after-auth-refresh"
        val failedClient = client.orderedStreamClientsForTest(currentAuth, videoId).first()
        val failedClientKey = "${failedClient.clientName}@${failedClient.clientVersion}"

        val action =
            client.prepareStreamHttpRecovery(
                videoId = videoId,
                clientKey = failedClientKey,
                httpStatusCode = 403,
                authFingerprint = failedAuth.fingerprint,
                failedUrlHadPoToken = false,
            )

        assertEquals(StreamHttpRecoveryAction.CLIENT_BACKOFF, action)
        assertFalse(
            client.orderedStreamClientsForTest(currentAuth, videoId).any {
                it.clientName == failedClient.clientName && it.clientVersion == failedClient.clientVersion
            },
        )
    }

    @Test
    fun nonRetryableMediaHttpFailureDoesNotRemoveTheClient() {
        val client = ArchiveTuneJvmClient(Files.createTempDirectory("archivetune-test-").toFile())
        val auth = PlaybackAuthState().normalized()
        val videoId = "video"
        val first = client.orderedStreamClientsForTest(auth, videoId).first()
        val firstKey = "${first.clientName}@${first.clientVersion}"

        assertFalse(client.markStreamClientFailed(videoId, firstKey, 500, auth.fingerprint))

        val retriedOrder = client.orderedStreamClientsForTest(auth, videoId)
        assertEquals(first.clientName, retriedOrder.first().clientName)
        assertEquals(first.clientVersion, retriedOrder.first().clientVersion)
    }

    @Test
    fun highQualityPrefersBestDirectFormatAtOrBelowTarget() {
        val response =
            playerResponse(
                format(itag = 251, bitrate = 155_000, mimeType = "audio/webm; codecs=\"opus\"", direct = true),
                format(itag = 140, bitrate = 128_000, mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", direct = true),
                format(itag = 141, bitrate = 256_000, mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", direct = true),
            )

        assertEquals(251, selectAudioFormatCandidates(response, JvmAudioQuality.HIGH).first().itag)
    }

    @Test
    fun directUrlWinsOverCipherAtSameQuality() {
        val response =
            playerResponse(
                format(itag = 1, bitrate = 160_000, mimeType = "audio/webm; codecs=\"opus\"", direct = false),
                format(itag = 2, bitrate = 160_000, mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", direct = true),
            )

        assertEquals(2, selectAudioFormatCandidates(response, JvmAudioQuality.HIGH).first().itag)
    }

    @Test
    fun videoAndUnresolvableFormatsAreExcluded() {
        val audio = format(itag = 1, bitrate = 70_000, mimeType = "audio/webm; codecs=\"opus\"", direct = true)
        val video = format(itag = 2, bitrate = 500_000, mimeType = "video/mp4", direct = true, width = 1920)
        val missingUrl = format(itag = 3, bitrate = 320_000, mimeType = "audio/webm", direct = false, cipher = false)

        assertEquals(listOf(audio), selectAudioFormatCandidates(playerResponse(audio, video, missingUrl), JvmAudioQuality.HIGHEST))
    }

    @Test
    fun muxedVideoFormatsAreNotDesktopAudioFallbacks() {
        val muxedAudio = format(itag = 18, bitrate = 96_000, mimeType = "video/mp4; codecs=\"avc1, mp4a.40.2\"", direct = true, width = 640)

        val response = playerResponse().copy(streamingData = PlayerResponse.StreamingData(formats = listOf(muxedAudio), adaptiveFormats = emptyList()))
        assertTrue(selectAudioFormatCandidates(response, JvmAudioQuality.HIGHEST).isEmpty())
    }

    @Test
    fun streamProbeUsesTheExactPlayerHeadersAndSmallRangeRequest() {
        val rangeHeader = AtomicReference<String>()
        val playerHeader = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio") { exchange ->
            rangeHeader.set(exchange.requestHeaders.getFirst("Range"))
            playerHeader.set(exchange.requestHeaders.getFirst("X-ArchiveTune-Player"))
            exchange.responseHeaders.add("Content-Range", "bytes 0-3/4")
            exchange.sendResponseHeaders(206, 4)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        }
        server.start()
        try {
            val stream =
                ResolvedAudioStream(
                    videoId = "test",
                    url = "http://127.0.0.1:${server.address.port}/audio",
                    format = format(251, 155_000, "audio/webm", direct = true),
                    videoDetails = null,
                    playbackTracking = null,
                    audioConfig = null,
                    clientName = "test",
                    clientVersion = "test",
                    requestHeaders = mapOf("X-ArchiveTune-Player" to "desktop"),
                    expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
                    authFingerprint = "test",
                )

            val result = probeResolvedAudioStream(stream).getOrThrow()

            assertEquals("bytes=0-1023", rangeHeader.get())
            assertEquals("desktop", playerHeader.get())
            assertEquals(206, result.httpStatus)
            assertEquals(4, result.bytesRead)
            assertTrue(result.rangedResponse)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamProbeRejectsUrlThatFailsLibVlcStyleLaterSeek() {
        val rangeHeaders = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/seek-sensitive") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range").orEmpty()
            rangeHeaders += range
            if (range == "bytes=0-1023") {
                exchange.responseHeaders.add("Content-Range", "bytes 0-1023/8192")
                exchange.sendResponseHeaders(206, 1024)
                exchange.responseBody.use { it.write(ByteArray(1024) { 1 }) }
            } else {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val stream =
                ResolvedAudioStream(
                    videoId = "test",
                    url = "http://127.0.0.1:${server.address.port}/seek-sensitive",
                    format = format(251, 155_000, "audio/webm", direct = true),
                    videoDetails = null,
                    playbackTracking = null,
                    audioConfig = null,
                    clientName = "test",
                    clientVersion = "test",
                    requestHeaders = emptyMap(),
                    expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
                    authFingerprint = "test",
                )

            val failure = probeResolvedAudioStream(stream).exceptionOrNull()

            assertTrue(failure is AudioStreamProbeHttpException)
            assertEquals(403, (failure as AudioStreamProbeHttpException).httpStatus)
            assertEquals(listOf("bytes=0-1023", "bytes=7168-8191"), rangeHeaders.toList())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamResolutionDeadlineCancelsAHangingResolver() = runBlocking {
        val failure = runCatching {
            resolveStreamWithinTimeout(20) { delay(Long.MAX_VALUE) }
        }.exceptionOrNull()

        assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(StreamResolutionFailureKind.NETWORK_TIMEOUT, classifyStreamResolutionFailure(StreamResolutionTimeoutException()))
    }

    @Test
    fun unavailableWebFailureWinsOverLaterNativeAuthRequired() {
        val failure = StreamResolutionException(
            "video",
            listOf(
                StreamPlaybackUnavailableException("video", "WEB", "UNPLAYABLE", null),
                StreamPlaybackUnavailableException("video", "ANDROID", "LOGIN_REQUIRED", null),
            ),
        )

        assertEquals(StreamResolutionFailureKind.UNAVAILABLE, classifyStreamResolutionFailure(failure))
    }

    @Test
    fun explicitUnavailableFailureWinsOverParallelClientTimeout() {
        val failure =
            StreamResolutionException(
                "video",
                listOf(
                    StreamClientTimeoutException("WEB_REMIX"),
                    StreamPlaybackUnavailableException("video", "ANDROID_VR", "ERROR", "This video is unavailable"),
                ),
            )

        assertEquals(StreamResolutionFailureKind.UNAVAILABLE, classifyStreamResolutionFailure(failure))
        assertTrue(isExplicitlyUnavailableStreamFailure(failure))
    }

    @Test
    fun streamProbeReturnsTypedHttpFailureWithoutUrl() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rejected") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        server.start()
        try {
            val stream =
                ResolvedAudioStream(
                    videoId = "test",
                    url = "http://127.0.0.1:${server.address.port}/rejected",
                    format = format(251, 155_000, "audio/webm", direct = true),
                    videoDetails = null,
                    playbackTracking = null,
                    audioConfig = null,
                    clientName = "test",
                    clientVersion = "test",
                    requestHeaders = emptyMap(),
                    expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
                    authFingerprint = "test",
                )

            val failure = probeResolvedAudioStream(stream).exceptionOrNull()

            assertTrue(failure is AudioStreamProbeHttpException)
            assertEquals(403, (failure as AudioStreamProbeHttpException).httpStatus)
            assertFalse(failure.message.orEmpty().contains("127.0.0.1"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun candidateSessionIsValidatedWithoutPublishingItToTheSharedSession() = runBlocking {
        val candidate = PlaybackAuthState(cookie = "SAPISID=candidate").normalized()
        val endpoint =
            FakeAccountEndpoint(
                accountInfoResult = { Result.success(accountInfo()) },
            )
        val client = client(accountEndpoint = endpoint)
        YouTube.authState = PlaybackAuthState.EMPTY
        val sharedBefore = YouTube.authState.fingerprint

        val verified = client.verifyAccountSession(candidate)

        assertTrue(verified.isSuccess)
        assertEquals("SAPISID=candidate", verified.getOrThrow().authState.cookie)
        // The candidate is never published to the process-wide session: it becomes active only
        // after the desktop session owner has persisted it.
        assertEquals(sharedBefore, YouTube.authState.fingerprint)
        assertEquals("SAPISID=candidate", endpoint.observedAuthStates.single().cookie)
    }

    @Test
    fun concurrentSessionChangeCannotReplaceTheValidatedCookie() = runBlocking {
        val candidate = PlaybackAuthState(cookie = "SAPISID=candidate").normalized()
        val endpoint =
            FakeAccountEndpoint(
                accountInfoResult = {
                    // Simulate a browse request that refreshes the shared session while the
                    // validation request is still in flight.
                    YouTube.authState = PlaybackAuthState(cookie = "SAPISID=other-session")
                    Result.success(accountInfo())
                },
            )
        val client = client(accountEndpoint = endpoint)

        val verified = client.verifyAccountSession(candidate)

        assertTrue(verified.isSuccess)
        assertEquals("SAPISID=candidate", verified.getOrThrow().authState.cookie)
        assertEquals("SAPISID=candidate", endpoint.observedAuthStates.single().cookie)
        assertEquals("SAPISID=candidate", endpoint.observedChannelAuthStates.single().cookie)
        assertEquals("SAPISID=candidate", endpoint.observedVisitorAuthStates.single().cookie)
        assertEquals("SAPISID=other-session", YouTube.authState.cookie)
    }

    @Test
    fun unavailableChannelListDoesNotRejectAnAuthenticatedSession() = runBlocking {
        val candidate = PlaybackAuthState(cookie = "SAPISID=candidate").normalized()
        val endpoint =
            FakeAccountEndpoint(
                accountInfoResult = { Result.success(accountInfo()) },
                accountDataSyncIdResult = { Result.failure(IllegalStateException("channel list unavailable")) },
                visitorContextResult = {
                    Result.success(
                        ResolvedVisitorContext(
                            visitorData = "visitor",
                            locale = YouTubeLocale(gl = "IL", hl = "en"),
                            dataSyncId = "V19126125||Cgt2ZXN0ZW1faWQ",
                        ),
                    )
                },
            )
        val client = client(accountEndpoint = endpoint)

        val verified = client.verifyAccountSession(candidate)

        assertTrue(verified.isSuccess)
        val authState = verified.getOrThrow().authState
        assertEquals("visitor", authState.visitorData)
        assertEquals("V19126125||Cgt2ZXN0ZW1faWQ", authState.dataSyncId)
        assertTrue(authState.hasPlaybackLoginContext)
    }

    @Test
    fun anonymousBootstrapDataSyncIdIsNotUsedAsChannelContext() = runBlocking {
        val candidate = PlaybackAuthState(cookie = "SAPISID=candidate").normalized()
        val endpoint =
            FakeAccountEndpoint(
                accountInfoResult = { Result.success(accountInfo()) },
                accountDataSyncIdResult = { Result.failure(IllegalStateException("channel list unavailable")) },
                visitorContextResult = {
                    Result.success(
                        ResolvedVisitorContext(
                            visitorData = "visitor",
                            locale = YouTubeLocale(gl = "IL", hl = "en"),
                            // The anonymous bootstrap page reports a delegated-only id.
                            dataSyncId = "V19126125",
                        ),
                    )
                },
            )
        val client = client(accountEndpoint = endpoint)

        val verified = client.verifyAccountSession(candidate)

        assertTrue(verified.isSuccess)
        val authState = verified.getOrThrow().authState
        assertNull(authState.dataSyncId)
        assertFalse(authState.hasPlaybackLoginContext)
    }

    @Test
    fun slowChannelEndpointCannotRejectAnAuthenticatedSessionOrBlockBootstrapFallback() = runBlocking {
        val endpoint = FakeAccountEndpoint(
            accountInfoResult = { Result.success(accountInfo()) },
            accountDataSyncIdResult = {
                delay(5_000)
                Result.success("unused-channel")
            },
            visitorContextResult = { Result.success(ResolvedVisitorContext("visitor", originalLocale, "channel||user")) },
        )
        val client = client(endpoint).also { it.accountContextTimeoutMillis = 50 }

        val result = client.verifyAccountSession(PlaybackAuthState(cookie = "SAPISID=candidate"))

        assertTrue(result.isSuccess)
        assertEquals("channel||user", result.getOrThrow().authState.dataSyncId)
    }

    @Test
    fun rejectedAccountMenuFailsValidation() = runBlocking {
        val candidate = PlaybackAuthState(cookie = "SAPISID=candidate").normalized()
        val endpoint =
            FakeAccountEndpoint(
                accountInfoResult = { Result.failure(IllegalStateException("user may not be logged in")) },
            )
        val client = client(accountEndpoint = endpoint)
        val previous = PlaybackAuthState(cookie = "SAPISID=active-session")
        YouTube.authState = previous

        val verified = client.verifyAccountSession(candidate)

        assertTrue(verified.isFailure)
        assertEquals(previous, YouTube.authState)
    }

    @Test
    fun validationDeadlineReturnsATimeoutWithoutPublishingTheCandidate() = runBlocking {
        val endpoint = FakeAccountEndpoint(accountInfoResult = {
            delay(5_000)
            Result.success(accountInfo())
        })
        val client = client(endpoint).also { it.accountValidationTimeoutMillis = 50 }
        val previous = PlaybackAuthState(cookie = "SAPISID=active-session")
        YouTube.authState = previous

        val result = client.verifyAccountSession(PlaybackAuthState(cookie = "SAPISID=candidate"))

        assertTrue(result.exceptionOrNull() is AccountValidationTimeoutException)
        assertEquals(previous, YouTube.authState)
    }

    @Test
    fun enrichmentDeadlineRetainsTheSuccessfullyAuthenticatedAccount() = runBlocking {
        val endpoint = FakeAccountEndpoint(
            accountInfoResult = { Result.success(accountInfo()) },
            accountDataSyncIdResult = {
                delay(5_000)
                Result.success("unused-channel")
            },
        )
        val client = client(endpoint).also { it.accountValidationTimeoutMillis = 50 }
        val previous = PlaybackAuthState(cookie = "SAPISID=active-session")
        YouTube.authState = previous

        val result = client.verifyAccountSession(PlaybackAuthState(cookie = "SAPISID=candidate"))

        assertTrue(result.isSuccess)
        assertEquals("SAPISID=candidate", result.getOrThrow().authState.cookie)
        assertEquals(previous, YouTube.authState)
    }

    @Test
    fun cancellingValidationDoesNotPublishOrReplaceTheActiveSession() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val endpoint = FakeAccountEndpoint(accountInfoResult = {
            entered.complete(Unit)
            delay(5_000)
            Result.success(accountInfo())
        })
        val client = client(endpoint)
        val previous = PlaybackAuthState(cookie = "SAPISID=active-session")
        YouTube.authState = previous
        val validation = async { client.verifyAccountSession(PlaybackAuthState(cookie = "SAPISID=candidate")) }
        entered.await()

        validation.cancelAndJoin()

        assertTrue(validation.isCancelled)
        assertEquals(previous, YouTube.authState)
    }

    @Test
    fun restoredSessionRepairsChannelContextFromTheAuthenticatedBootstrap() = runBlocking {
        val initial = PlaybackAuthState(cookie = "SAPISID=restored", visitorData = "visitor")
        var current = initial
        val endpoint = FakeAccountEndpoint(
            accountInfoResult = { error("Repair must not revalidate the account") },
            visitorContextResult = { Result.success(ResolvedVisitorContext("visitor", originalLocale, "channel||user")) },
        )
        val client = client(endpoint)
        client.bindSessionProvider(object : DesktopSessionProvider {
            override fun currentAuthState(): PlaybackAuthState = current
            override suspend fun persistAuthRepair(initialAuthState: PlaybackAuthState, repairedAuthState: PlaybackAuthState) {
                current = repairedAuthState
            }
        })

        // Preparation happens before the empty query is rejected, so no external search runs.
        client.searchSummary("")

        assertEquals("channel||user", current.dataSyncId)
        assertTrue(current.hasPlaybackLoginContext)
        assertEquals(initial.cookie, endpoint.observedVisitorAuthStates.single().cookie)
    }

    @Test
    fun candidateWithoutLoginCookieIsRejectedBeforeAnyRequest() = runBlocking {
        val endpoint =
            FakeAccountEndpoint(
                accountInfoResult = { Result.success(accountInfo()) },
            )
        val client = client(accountEndpoint = endpoint)

        val verified = client.verifyAccountSession(PlaybackAuthState(visitorData = "visitor"))

        assertTrue(verified.isFailure)
        assertTrue(endpoint.observedAuthStates.isEmpty())
    }

    private fun client(accountEndpoint: DesktopAccountEndpoint): ArchiveTuneJvmClient =
        ArchiveTuneJvmClient(
            cacheDirectory = Files.createTempDirectory("archivetune-test-").toFile(),
        ).also { it.accountEndpoint = accountEndpoint }

    private fun accountInfo(): AccountInfo =
        AccountInfo(
            name = "Example",
            email = "example@example.test",
            channelHandle = "@example",
            thumbnailUrl = null,
        )

    private class FakeAccountEndpoint(
        private val accountInfoResult: suspend (PlaybackAuthState) -> Result<AccountInfo>,
        private val accountDataSyncIdResult: suspend (PlaybackAuthState) -> Result<String> = {
            Result.failure(IllegalStateException("channel list unavailable"))
        },
        private val visitorContextResult: (PlaybackAuthState) -> Result<ResolvedVisitorContext> = {
            Result.failure(IllegalStateException("bootstrap page unavailable"))
        },
        private val visitorDataResult: () -> Result<String> = {
            Result.failure(IllegalStateException("visitor data unavailable"))
        },
    ) : DesktopAccountEndpoint {
        val observedAuthStates = CopyOnWriteArrayList<PlaybackAuthState>()
        val observedChannelAuthStates = CopyOnWriteArrayList<PlaybackAuthState>()
        val observedVisitorAuthStates = CopyOnWriteArrayList<PlaybackAuthState>()

        override suspend fun accountInfo(authState: PlaybackAuthState): Result<AccountInfo> {
            observedAuthStates += authState
            return accountInfoResult(authState)
        }

        override suspend fun accountDataSyncId(authState: PlaybackAuthState): Result<String> {
            observedChannelAuthStates += authState
            return accountDataSyncIdResult(authState)
        }

        override suspend fun visitorContext(authState: PlaybackAuthState): Result<ResolvedVisitorContext> {
            observedVisitorAuthStates += authState
            return visitorContextResult(authState)
        }

        override suspend fun visitorData(): Result<String> = visitorDataResult()
    }

    private fun playerResponse(vararg formats: PlayerResponse.StreamingData.Format) =
        PlayerResponse(
            responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
            playabilityStatus = PlayerResponse.PlayabilityStatus(status = "OK", reason = null),
            playerConfig = null,
            streamingData = PlayerResponse.StreamingData(formats = null, adaptiveFormats = formats.toList()),
            videoDetails = null,
            playbackTracking = null,
        )

    private fun format(
        itag: Int,
        bitrate: Int,
        mimeType: String,
        direct: Boolean,
        cipher: Boolean = !direct,
        width: Int? = null,
    ) =
        PlayerResponse.StreamingData.Format(
            itag = itag,
            url = if (direct) "https://example.test/audio" else null,
            mimeType = mimeType,
            bitrate = bitrate,
            width = width,
            height = null,
            contentLength = null,
            quality = "tiny",
            fps = null,
            qualityLabel = null,
            averageBitrate = null,
            audioQuality = "AUDIO_QUALITY_MEDIUM",
            approxDurationMs = null,
            audioSampleRate = 48_000,
            audioChannels = 2,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = if (cipher) "url=x&s=y" else null,
            cipher = null,
        )
}
