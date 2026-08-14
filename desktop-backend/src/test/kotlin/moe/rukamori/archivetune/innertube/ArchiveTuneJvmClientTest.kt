package moe.rukamori.archivetune.innertube

import moe.rukamori.archivetune.innertube.models.ResponseContext
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class ArchiveTuneJvmClientTest {
    @After
    fun restoreNetworkGatekeeper() {
        NetworkGatekeeper.setConnectionBlocked(true)
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
    fun signedInResolutionPrefersCookieCapableThenLoginCapableClients() {
        val client = ArchiveTuneJvmClient(Files.createTempDirectory("archivetune-test-").toFile())
        val auth = PlaybackAuthState(cookie = "SAPISID=secret", dataSyncId = "channel").normalized()

        val ordered = client.orderedStreamClientsForTest(auth)

        assertEquals(YouTubeClient.WEB_PRIMARY.clientName, ordered.first().clientName)
        assertTrue(ordered.take(2).all { it.supportsCookieAuthentication })
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
    fun muxedAudioOnlyFormatsRemainPlayableFallbacks() {
        val muxedAudio = format(itag = 18, bitrate = 96_000, mimeType = "video/mp4; codecs=\"avc1, mp4a.40.2\"", direct = true, width = 640)

        val response = playerResponse().copy(streamingData = PlayerResponse.StreamingData(formats = listOf(muxedAudio), adaptiveFormats = emptyList()))
        assertEquals(listOf(muxedAudio), selectAudioFormatCandidates(response, JvmAudioQuality.HIGHEST))
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
