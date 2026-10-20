package moe.rukamori.archivetune.innertube

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.stream.YtDlpConfiguration
import moe.rukamori.archivetune.stream.YtDlpException
import moe.rukamori.archivetune.stream.YtDlpFailure
import moe.rukamori.archivetune.stream.YtDlpStreamResolver
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class YtDlpDesktopStreamExtractorTest {
    @After fun resetAuth() { YouTube.authState = PlaybackAuthState(); NetworkGatekeeper.setConnectionBlocked(true) }

    @Test fun `public backend contract carries session quality URL headers expiry and format`() = runBlocking {
        val auth = PlaybackAuthState(cookie = "SAPISID=private-value", visitorData = "visitor", dataSyncId = "channel").normalized()
        var requestAuth: PlaybackAuthState? = null
        var requestQuality: JvmAudioQuality? = null
        val client = ArchiveTuneJvmClient(Files.createTempDirectory("backend-contract-").toFile(),
            sessionProvider = DesktopSessionProvider { auth },
            audioStreamExtractor = DesktopAudioStreamExtractor { id, quality, state ->
                requestAuth = state; requestQuality = quality
                sampleStream().copy(videoId = id, authFingerprint = state.fingerprint)
            })
        val result = client.resolveAudioStream("jNQXAC9IVRw", playlistId = "PLignored", quality = JvmAudioQuality.LOW).getOrThrow()
        assertEquals(auth, requestAuth)
        assertEquals(JvmAudioQuality.LOW, requestQuality)
        assertEquals(auth.fingerprint, result.authFingerprint)
        assertEquals("https://media.example/audio", result.url)
        assertEquals("unit-agent", result.requestHeaders["User-Agent"])
        assertTrue(result.expiresAtEpochMillis > System.currentTimeMillis())
        assertEquals(251, result.format.itag)
    }

    @Test fun `backend preserves caller cancellation`() = runBlocking {
        val client = ArchiveTuneJvmClient(Files.createTempDirectory("backend-cancel-").toFile(),
            audioStreamExtractor = DesktopAudioStreamExtractor { _, _, _ -> delay(Long.MAX_VALUE); error("unreachable") })
        try {
            withTimeout(100) { client.resolveAudioStream("jNQXAC9IVRw") }
            fail("caller cancellation was swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun `yt-dlp failure classification only enables metadata replacement for explicit unavailable`() {
        assertEquals(StreamResolutionFailureKind.EXTRACTOR_INCOMPATIBLE, classifyStreamResolutionFailure(YtDlpException(YtDlpFailure.TOOLCHAIN)))
        assertEquals(StreamResolutionFailureKind.AUTH_REQUIRED, classifyStreamResolutionFailure(YtDlpException(YtDlpFailure.AUTH_REQUIRED)))
        assertEquals(StreamResolutionFailureKind.NETWORK_TIMEOUT, classifyStreamResolutionFailure(YtDlpException(YtDlpFailure.TIMEOUT)))
        assertTrue(isExplicitlyUnavailableStreamFailure(YtDlpException(YtDlpFailure.UNAVAILABLE)))
        assertFalse(isExplicitlyUnavailableStreamFailure(YtDlpException(YtDlpFailure.AUTH_REQUIRED)))
        assertFalse(isExplicitlyUnavailableStreamFailure(YtDlpException(YtDlpFailure.NETWORK)))
    }

    @Test fun `proxy bridge follows application route and escapes credentials`() {
        assertEquals("", streamProxyUrl(null, null, null))
        assertEquals("", streamProxyUrl(Proxy.NO_PROXY, null, null))
        assertEquals("http://user%40name:p%3Aa%20ss@localhost:8080",
            streamProxyUrl(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("localhost", 8080)), "user@name", "p:a ss"))
        assertEquals("socks5://localhost:1080",
            streamProxyUrl(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("localhost", 1080)), null, null))
    }

    @Test fun `new extractor validates first and tail CDN ranges using returned headers`() = runBlocking {
        if (!Files.isExecutable(Path.of("/bin/sh"))) return@runBlocking
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio") { exchange ->
            requests += "${exchange.requestHeaders.getFirst("Range")}:${exchange.requestHeaders.getFirst("User-Agent")}:${exchange.requestHeaders.getFirst("Cookie")}"
            exchange.responseHeaders.add("Content-Range", "bytes 0-1023/8192")
            exchange.sendResponseHeaders(206, 1024)
            exchange.responseBody.use { it.write(ByteArray(1024) { 1 }) }
        }
        server.start()
        val root = Files.createTempDirectory("extractor-e2e-")
        try {
            val fixture = root.resolve("yt-dlp")
            val response = JSONObject().put("id", "jNQXAC9IVRw")
                .put("url", "http://127.0.0.1:${server.address.port}/audio")
                .put("protocol", "http").put("vcodec", "none").put("acodec", "opus").put("ext", "webm")
                .put("format_id", "251").put("abr", 128).put("filesize", 8192)
                .put("http_headers", JSONObject().put("User-Agent", "returned-agent").put("Cookie", "do-not-forward"))
            Files.writeString(root.resolve("response.json"), response.toString())
            Files.writeString(fixture, """
                #!/bin/sh
                case "${'$'}*" in
                  *archivetune-runtime-check:*)
                    printf '[debug] yt-dlp version stable@2026.08.19 from fixture\n[debug] Optional libraries: yt_dlp_ejs-0.8.0\n[debug] JS runtimes: quickjs-ng-0.17.0\n' >&2
                    exit 1 ;;
                esac
                cat "${'$'}(dirname "${'$'}0")/response.json"
            """.trimIndent())
            fixture.toFile().setExecutable(true)
            val extractor = YtDlpDesktopStreamExtractor(YtDlpStreamResolver(YtDlpConfiguration(fixture.toString(), "qjs", temporaryRoot = root)))
            val result = extractor.resolve("jNQXAC9IVRw", JvmAudioQuality.HIGHEST, PlaybackAuthState())
            assertEquals("YT_DLP", result.clientName)
            assertEquals("2026.08.19", result.clientVersion)
            assertEquals(listOf("bytes=0-1023:returned-agent:null", "bytes=7168-8191:returned-agent:null"), requests.toList())
            assertEquals("audio/webm; codecs=\"opus\"", result.mimeType)
        } finally { root.toFile().deleteRecursively(); server.stop(0) }
    }

    @Test fun `cancellation stops a hanging CDN probe`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hang") { exchange -> entered.countDown(); release.await(5, TimeUnit.SECONDS); exchange.close() }
        server.start()
        try {
            val probe = async { probeResolvedAudioStreamCancellable(sampleStream().copy(url = "http://127.0.0.1:${server.address.port}/hang")) }
            withTimeout(2000) { while (entered.count > 0) delay(10) }
            withTimeout(1000) { probe.cancelAndJoin() }
            assertTrue(probe.isCancelled)
        } finally { release.countDown(); server.stop(0) }
    }

    private fun sampleStream(): ResolvedAudioStream {
        val format = moe.rukamori.archivetune.innertube.models.response.PlayerResponse.StreamingData.Format(
            251, "https://media.example/audio", "audio/webm", 128000, null, null, 8192, "audio", null, null,
            128000, null, "10000", 48000, 2, null, null, null, null)
        return ResolvedAudioStream("jNQXAC9IVRw", format.url!!, format, null, null, null, "YT_DLP", "2026.08.19",
            mapOf("User-Agent" to "unit-agent"), System.currentTimeMillis() + 3600000, "anonymous")
    }
}
