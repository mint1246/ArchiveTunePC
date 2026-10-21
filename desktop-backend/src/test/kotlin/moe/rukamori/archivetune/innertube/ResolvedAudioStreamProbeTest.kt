package moe.rukamori.archivetune.innertube

import com.sun.net.httpserver.HttpServer
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class ResolvedAudioStreamProbeTest {
    @Test
    fun `probe follows the same redirects as libVLC`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/audio")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/audio") { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 0-3/4")
            exchange.sendResponseHeaders(206, 4)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        }
        server.start()
        try {
            val stream =
                ResolvedAudioStream(
                    videoId = "test",
                    url = "http://127.0.0.1:${server.address.port}/redirect",
                    format = audioFormat(),
                    videoDetails = null,
                    playbackTracking = null,
                    audioConfig = null,
                    clientName = "test",
                    clientVersion = "test",
                    requestHeaders = emptyMap(),
                    expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
                    authFingerprint = "test",
                )

            val result = probeResolvedAudioStream(stream).getOrThrow()

            assertEquals(206, result.httpStatus)
            assertEquals(4, result.bytesRead)
            assertTrue(result.rangedResponse)
        } finally {
            server.stop(0)
        }
    }

    private fun audioFormat() =
        PlayerResponse.StreamingData.Format(
            itag = 251,
            url = "https://example.test/audio",
            mimeType = "audio/webm",
            bitrate = 155_000,
            width = null,
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
            signatureCipher = null,
            cipher = null,
        )
}
