/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.innertube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.stream.YtDlpException
import moe.rukamori.archivetune.stream.YtDlpFailure
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.innertube.models.ResponseContext
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class DesktopStreamResolutionPolicyTest {
    @After fun resetSession() { YouTube.authState = PlaybackAuthState(); NetworkGatekeeper.setConnectionBlocked(true) }
    private val directFormat = PlayerResponse.StreamingData.Format(
        251, "https://media.example/audio?expire=9999999999", "audio/webm", 128000,
        null, null, 8192, "audio", null, null, 128000, null, "10000", 48000, 2,
        null, null, null, null)

    @Test fun `direct fast path never deciphers signatures or throttling challenges`() {
        assertEquals(directFormat.url, directAudioFastPathUrl(directFormat))
        for (url in listOf("https://media.example/audio?n=challenge", "https://media.example/audio?n=",
            "https://media.example/audio?%6e=challenge", "file:///tmp/audio", "https://user:secret@media.example/audio")) {
            assertNull(directAudioFastPathUrl(directFormat.copy(url = url)))
        }
        assertNull(directAudioFastPathUrl(directFormat.copy(signatureCipher = "s=challenge")))
        assertNull(directAudioFastPathUrl(directFormat.copy(cipher = "s=challenge")))
        assertNull(directAudioFastPathUrl(directFormat.copy(mimeType = "video/mp4", width = 320)))
    }

    @Test fun `signed in highest quality goes upstream to retain account formats`() {
        val auth = PlaybackAuthState(cookie = "SAPISID=fixture").normalized()
        assertFalse(allowsDirectAudioFastPath(JvmAudioQuality.HIGHEST, auth))
        assertTrue(allowsDirectAudioFastPath(JvmAudioQuality.HIGH, auth))
        assertTrue(allowsDirectAudioFastPath(JvmAudioQuality.HIGHEST, PlaybackAuthState()))
    }

    @Test fun `backend fast path preserves metadata and probes without Web proof or deciphering`() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio") { exchange ->
            requests += exchange.requestHeaders.getFirst("Range")
            assertNull(exchange.requestHeaders.getFirst("Cookie"))
            exchange.responseHeaders.add("Content-Range", "bytes 0-1023/8192")
            exchange.sendResponseHeaders(206, 1024)
            exchange.responseBody.use { it.write(ByteArray(1024) { 1 }) }
        }
        server.start()
        val root = Files.createTempDirectory("direct-audio-contract-")
        try {
            val format = directFormat.copy(url = "http://127.0.0.1:${server.address.port}/audio")
            val response = playerResponse(format)
            val client = backend(root, DesktopAudioStreamExtractor { _, _, _ -> error("Unexpected upstream") })
            client.streamPlayerRequest = { selected, _, _, timestamp, _ ->
                assertEquals(YouTubeClient.VISIONOS, selected); assertNull(timestamp); response
            }
            val result = client.resolveAudioStream("jNQXAC9IVRw").getOrThrow()
            assertEquals("VISIONOS", result.clientName)
            assertEquals(format.url, result.url)
            assertEquals(response.playerConfig!!.audioConfig, result.audioConfig)
            assertTrue(result.requestHeaders.isNotEmpty())
            assertEquals(listOf("bytes=0-1023", "bytes=7168-8191"), requests.toList())
        } finally { server.stop(0); root.toFile().deleteRecursively() }
    }

    @Test fun `backend cipher throttling PO rejection and CDN 403 use the unchanged session upstream`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rejected") { it.sendResponseHeaders(403, -1); it.close() }
        server.start()
        val root = Files.createTempDirectory("direct-audio-fallback-")
        try {
            var calls = 0
            val client = backend(root, DesktopAudioStreamExtractor { id, _, auth ->
                assertEquals(session, auth); calls++
                ResolvedAudioStream(id, directFormat.url!!, directFormat, null, null, null, "YT_DLP", "fixture",
                    mapOf("User-Agent" to "upstream-agent"), System.currentTimeMillis() + 300_000, auth.fingerprint)
            })
            val responses = listOf(
                playerResponse(directFormat.copy(url = null, signatureCipher = "s=fixture")),
                playerResponse(directFormat.copy(url = "https://media.example/audio?n=fixture")),
                playerResponse(directFormat).copy(playabilityStatus = PlayerResponse.PlayabilityStatus("LOGIN_REQUIRED", "proof fixture")),
                playerResponse(directFormat.copy(url = "http://127.0.0.1:${server.address.port}/rejected")),
            )
            for ((index, response) in responses.withIndex()) {
                client.streamPlayerRequest = { _, _, _, _, _ -> response }
                val stream = client.resolveAudioStream("jNQXAC9IVRw").getOrThrow()
                assertEquals("YT_DLP", stream.clientName)
                assertEquals("upstream-agent", stream.requestHeaders["User-Agent"])
                assertEquals(index + 1, calls)
            }
            client.streamPlayerRequest = { _, _, _, _, _ -> error("403 backoff must bypass this client") }
            assertEquals("YT_DLP", client.resolveAudioStream("jNQXAC9IVRw").getOrThrow().clientName)
            assertEquals(5, calls)
            client.streamPlayerRequest = { _, _, _, _, _ -> error("Highest quality must go straight upstream") }
            assertEquals("YT_DLP", client.resolveAudioStream("jNQXAC9IVRw", quality = JvmAudioQuality.HIGHEST).getOrThrow().clientName)
            assertEquals(6, calls)
        } finally { server.stop(0); root.toFile().deleteRecursively() }
    }

    private val session = PlaybackAuthState(cookie = "SAPISID=fixture", visitorData = "visitor", dataSyncId = "123||456").normalized()

    private fun backend(root: java.nio.file.Path, upstream: DesktopAudioStreamExtractor) = ArchiveTuneJvmClient(
        root.toFile(), streamUrlResolver = StreamUrlResolver { _, _, _, _ -> error("Native deciphering must not run") },
        sessionProvider = object : DesktopSessionProvider {
            override fun currentAuthState() = session
            override suspend fun ensurePlaybackAuth(videoId: String, authState: PlaybackAuthState): PlaybackAuthState =
                error("Eager Web proof must not run")
        }, audioStreamExtractor = upstream, streamResolutionMode = DesktopStreamResolutionMode.HYBRID)

    private fun playerResponse(format: PlayerResponse.StreamingData.Format) = PlayerResponse(
        ResponseContext(visitorData = null, serviceTrackingParams = null), PlayerResponse.PlayabilityStatus("OK", null),
        PlayerResponse.PlayerConfig(PlayerResponse.PlayerConfig.AudioConfig(6.0, 7.0)),
        PlayerResponse.StreamingData(null, listOf(format)), null, null)

    @Test fun `explicit modes invoke only their selected implementation`() = runBlocking {
        assertEquals("native", resolveDesktopStream(DesktopStreamResolutionMode.LEGACY,
            native = { "native" }, upstream = { error("Unexpected subprocess") }))
        assertEquals("upstream", resolveDesktopStream(DesktopStreamResolutionMode.YT_DLP,
            native = { error("Unexpected native call") }, upstream = { "upstream" }))
    }

    @Test fun `successful fast path starts no fallback work`() = runBlocking {
        assertEquals("native URL and headers", resolveDesktopStream(DesktopStreamResolutionMode.HYBRID,
            native = { "native URL and headers" }, upstream = { error("Unexpected subprocess") }))
    }

    @Test fun `signature probe and proof failures each reach upstream once`() = runBlocking {
        val failures = listOf(
            StreamAudioFormatUnavailableException("jNQXAC9IVRw", "fixture"),
            AudioStreamProbeHttpException(403),
            IOException("Signature fixture failure"),
            StreamPlaybackUnavailableException("jNQXAC9IVRw", "fixture", "LOGIN_REQUIRED", "proof fixture"),
        )
        for (failure in failures) {
            var calls = 0
            val result = resolveDesktopStream(DesktopStreamResolutionMode.HYBRID,
                native = { throw failure }, upstream = { calls++; "upstream URL and headers" })
            assertEquals("upstream URL and headers", result)
            assertEquals(1, calls)
        }
    }

    @Test fun `timeout finishes native cleanup before starting the fallback`() = runBlocking {
        var nativeStopped = false
        val result = resolveDesktopStream(DesktopStreamResolutionMode.HYBRID, nativeTimeoutMillis = 20,
            native = {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { delay(10); nativeStopped = true } }
            },
            upstream = { assertTrue(nativeStopped); "upstream" })
        assertEquals("upstream", result)
    }

    @Test fun `caller cancellation never starts an upstream extraction`() = runBlocking {
        var upstreamStarted = false
        try {
            withTimeout(20) {
                resolveDesktopStream(DesktopStreamResolutionMode.HYBRID,
                    native = { awaitCancellation() },
                    upstream = { upstreamStarted = true; "unexpected" })
            }
            fail("Expected caller cancellation")
        } catch (_: CancellationException) { /* expected */ }
        assertFalse(upstreamStarted)
    }

    @Test fun `upstream failure remains classifiable and does not retain native secrets`() = runBlocking {
        val upstreamFailure = YtDlpException(YtDlpFailure.AUTH_REQUIRED)
        try {
            resolveDesktopStream(DesktopStreamResolutionMode.HYBRID,
                native = { throw IOException("signed-url-cookie-marker") },
                upstream = { throw upstreamFailure })
            fail("Expected upstream failure")
        } catch (failure: YtDlpException) {
            assertSame(upstreamFailure, failure)
            assertEquals(StreamResolutionFailureKind.AUTH_REQUIRED, classifyStreamResolutionFailure(failure))
            assertFalse(failure.stackTraceToString().contains("signed-url-cookie-marker"))
        }
    }

    @Test fun `live upstream fallback resolves historical problem videos after controlled native failures`() = runBlocking {
        org.junit.Assume.assumeTrue("Opt-in public-video fallback check", System.getenv("ARCHIVETUNE_LIVE_YTDLP") == "1")
        val root = Files.createTempDirectory("hybrid-live-fallback-")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rejected") { it.sendResponseHeaders(403, -1); it.close() }
        server.start()
        try {
            val client = ArchiveTuneJvmClient(root.toFile(),
                sessionProvider = DesktopSessionProvider { PlaybackAuthState() },
                audioStreamExtractor = YtDlpDesktopStreamExtractor(root.toFile()),
                streamResolutionMode = DesktopStreamResolutionMode.HYBRID)
            val scenarios = listOf(
                "dQw4w9WgXcQ" to playerResponse(directFormat.copy(url = "https://media.example/audio?n=fixture")),
                "aqz-KE-bpKQ" to playerResponse(directFormat.copy(url = null, signatureCipher = "s=fixture")),
                "jNQXAC9IVRw" to playerResponse(directFormat.copy(url = "http://127.0.0.1:${server.address.port}/rejected")),
                "9bZkp7q19f0" to playerResponse(directFormat).copy(playabilityStatus = PlayerResponse.PlayabilityStatus("LOGIN_REQUIRED", "proof fixture")),
            )
            for ((id, response) in scenarios) {
                client.streamPlayerRequest = { _, _, _, _, _ -> response }
                // The failures above are controlled regression fixtures, not a claim that these
                // videos still naturally fail VISIONOS today. The fallback and CDN are real.
                val result = client.resolveAudioStream(id).getOrElse { throw AssertionError("Public fallback failed (${it.javaClass.simpleName})") }
                assertEquals("YT_DLP", result.clientName)
                assertTrue(result.mimeType.startsWith("audio/"))
                assertTrue(result.requestHeaders.isNotEmpty())
                assertTrue(result.expiresAtEpochMillis > System.currentTimeMillis() + 60_000)
            }
        } finally { server.stop(0); root.toFile().deleteRecursively() }
    }
}
