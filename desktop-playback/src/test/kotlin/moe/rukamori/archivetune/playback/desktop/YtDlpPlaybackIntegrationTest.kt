/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.JvmAudioQuality
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.probeResolvedAudioStream
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.jupiter.api.Assumptions.assumeTrue
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live integration test for the yt-dlp playback path.
 *
 * The test proves that the real Kotlin backend resolver ([ArchiveTuneJvmClient.resolveAudioStream])
 * hands a direct audio URL plus the headers the CDN requires to the existing ranged callback
 * transport ([VlcjRangeHttpTransport] / [VlcjRangeCallbackMedia]) and that real libVLC plays the
 * resulting stream: it reaches the Playing state, advances its clock, seeks deep into the music
 * video, pauses and resumes, and finally tears everything down.
 *
 * The test is opt-in. It only runs when the resolver toolchain is available:
 *
 * ```
 * ARCHIVETUNE_LIVE_YTDLP=1 \
 * ARCHIVETUNE_YTDLP=/path/to/yt-dlp \
 * ARCHIVETUNE_BUN=/path/to/bun \
 * ./gradlew :desktop-playback:test --tests '*YtDlpPlaybackIntegrationTest*'
 * ```
 *
 * `archivetune.ytdlp` / `archivetune.bun` system properties are accepted as an alternative to
 * the environment variables. Without the opt-in the live test is skipped through a JUnit assumption
 * instead of silently passing.
 *
 * The run stays anonymous: no account cookie is read, stored or forwarded, and neither the signed
 * URL, the header values nor the raw resolver diagnostics are written to the test log.
 */
class YtDlpPlaybackIntegrationTest {

    @Test
    fun `yt-dlp resolved audio plays through the ranged callback transport`() = runBlocking {
        val environment = assumeLiveResolverEnvironment()
        // The production resolver looks the toolchain up through these very properties, so pinning
        // them keeps the test on the configured binaries no matter how the opt-in was provided.
        System.setProperty("archivetune.ytdlp", environment.ytDlpExecutable.toAbsolutePath().toString())
        System.setProperty("archivetune.bun", environment.bunExecutable.toAbsolutePath().toString())

        val cacheDirectory = Files.createTempDirectory("archivetune-live-ytdlp-")
        val client = ArchiveTuneJvmClient(cacheDirectory.toFile())
        // Never reuse an interactive login session: this integration run resolves anonymously.
        client.authState = PlaybackAuthState()

        try {
            val resolved = client.resolveAudioStream(environment.videoId, quality = JvmAudioQuality.HIGH)
                .getOrElse { failure ->
                    throw AssertionError(
                        "the live yt-dlp resolver returned no stream (${failure::class.simpleName})",
                    )
                }

            assertEquals("YT_DLP", resolved.clientName)
            assertTrue(
                resolved.mimeType.startsWith("audio/"),
                "yt-dlp did not resolve a direct audio-only format",
            )
            assertTrue(
                resolved.url.startsWith("https://") || resolved.url.startsWith("http://"),
                "the resolved stream is not an HTTP(S) URL",
            )
            assertTrue(
                resolved.expiresAtEpochMillis > System.currentTimeMillis(),
                "the resolved stream URL is already expired",
            )
            val host = runCatching { URI(resolved.url).host?.lowercase() }.getOrNull().orEmpty()
            assertTrue(
                host.isNotBlank() && !host.endsWith("youtube.com"),
                "the resolver did not hand back a direct CDN audio URL",
            )

            val requiredHeaders = resolved.requestHeaders.filter { (name, value) ->
                name.isNotBlank() && value.isNotBlank() && !name.equals("Range", ignoreCase = true)
            }
            assertTrue(requiredHeaders.isNotEmpty(), "yt-dlp returned no headers for the CDN request")

            // The production extractor already probes the first bytes and the tail of the object
            // with the returned headers. Repeat that probe so the assertion covers both ranges.
            val probe = probeResolvedAudioStream(resolved).getOrElse { failure ->
                throw AssertionError(
                    "the CDN rejected the first/tail range probe (${failure::class.simpleName})",
                )
            }
            assertEquals(206, probe.httpStatus, "the CDN did not answer the range probe with a partial response")
            assertTrue(probe.bytesRead > 0, "the range probe returned no audio bytes")
            assertTrue(probe.rangedResponse, "the CDN ignored the range probe")

            val capture = RangeRequestCapture(requiredHeaders)
            val transport = VlcjRangeHttpTransport(capturingHttpClient(capture))
            val media = transport.media(resolved.url, resolved.requestHeaders)
            val factory = MediaPlayerFactory(*VLC_FACTORY_OPTIONS)
            val player = factory.mediaPlayers().newMediaPlayer()
            try {
                val events = PlaybackEvents()
                player.events().addMediaPlayerEventListener(events)

                val started = player.media().play(media, *callbackMediaOptions(resolved.mimeType).toTypedArray())
                assertTrue(started, "libVLC rejected the ranged callback media")
                assertTrue(
                    awaitPlayingState(events, PLAYBACK_START_TIMEOUT_MILLIS),
                    "libVLC never reached the Playing state for the resolved stream",
                )
                assertTrue(player.status().isPlaying(), "libVLC is not playing the resolved stream")

                val playedTo = awaitAdvancingTime(player, MINIMUM_PLAYBACK_TIME_MS, PLAYBACK_PROGRESS_TIMEOUT_MILLIS)
                assertTrue(playedTo >= MINIMUM_PLAYBACK_TIME_MS, "the resolved stream did not advance its clock")

                val duration = player.status().length()
                assertTrue(duration > 0L, "libVLC did not report a duration for the resolved stream")

                val seekTarget = seekTargetFor(duration)
                player.controls().setTime(seekTarget)
                val seekedTo = awaitTimeAtLeast(player, seekTarget - SEEK_TOLERANCE_MS, SEEK_TIMEOUT_MILLIS)
                assertTrue(
                    seekedTo >= seekTarget - SEEK_TOLERANCE_MS,
                    "the seek did not land inside the music video",
                )
                // libVLC prefetches the stream, so the transport serves the music video through
                // byte ranges. The captured ranges are the observable proof that libVLC received
                // real ranges of the track over HTTP instead of one single full download.
                val contentLength = media.openSize()
                assertTrue(contentLength > 0L, "the transport did not learn the size of the music video")
                val servedRanges = capture.requests.mapNotNull { request ->
                    request.rangeStart?.let { start -> start to (request.rangeEnd ?: start) }
                }
                assertTrue(servedRanges.isNotEmpty(), "the transport did not serve any byte range of the music video")
                assertTrue(
                    servedRanges.any { it.first == 0L },
                    "the transport never requested the first byte range",
                )
                val servedEnd = servedRanges.maxOf { it.second }
                assertTrue(
                    servedEnd + 1L >= contentLength,
                    "the transport did not serve the music video up to its end " +
                        "(served up to $servedEnd of $contentLength bytes)",
                )
                if (contentLength > DEEP_RANGE_THRESHOLD_BYTES) {
                    assertTrue(
                        capture.requests.any { (it.rangeStart ?: 0L) >= DEEP_RANGE_THRESHOLD_BYTES },
                        "the transport did not serve a byte range deep inside the music video " +
                            "(observed range starts: ${capture.requests.map { it.rangeStart }})",
                    )
                }

                player.controls().setPause(true)
                assertTrue(
                    awaitCondition(PAUSE_TIMEOUT_MILLIS) { !player.status().isPlaying() },
                    "libVLC did not pause the resolved stream",
                )
                val pausedAt = player.status().time()
                Thread.sleep(PAUSED_SETTLE_MILLIS)
                assertTrue(
                    kotlin.math.abs(player.status().time() - pausedAt) <= PAUSED_DRIFT_TOLERANCE_MS,
                    "the paused stream kept advancing its clock",
                )
                player.controls().setPause(false)
                assertTrue(
                    awaitCondition(RESUME_TIMEOUT_MILLIS) {
                        player.status().isPlaying() && player.status().time() > pausedAt
                    },
                    "libVLC did not resume the resolved stream",
                )

                val droppedHeaders = capture.requests.flatMap { it.missingHeaders }.distinct()
                assertTrue(
                    droppedHeaders.isEmpty(),
                    "the transport dropped required request headers: ${droppedHeaders.joinToString()}",
                )
            } finally {
                runCatching { player.controls().stop() }
                runCatching { player.release() }
                runCatching { factory.release() }
                runCatching { media.close() }
                runCatching { transport.close() }
            }
        } finally {
            runCatching { cacheDirectory.toFile().deleteRecursively() }
        }
    }

    /**
     * Hermetic companion of the live test. It proves that the existing ranged callback transport
     * really hands the requested headers and byte ranges to a server and that real libVLC decodes
     * and plays the result, without depending on YouTube or on the network.
     */
    @Test
    fun `the ranged callback transport plays local ranged audio through libVLC`() {
        assumeTrue(
            systemLibVlcPath() != null,
            "a system libVLC (libvlc.so) is required for the ranged callback transport test",
        )
        val wave = createWaveFile(durationSeconds = 20)
        val received = CopyOnWriteArrayList<LocalRangeRequest>()
        val server = startRangeServer(wave, received)
        val url = "http://127.0.0.1:${server.address.port}/audio.wav"
        val transport = VlcjRangeHttpTransport()
        val media = transport.media(url = url, headers = SYNTHETIC_HEADERS)
        val factory = MediaPlayerFactory(*VLC_FACTORY_OPTIONS)
        val player = factory.mediaPlayers().newMediaPlayer()
        try {
            val events = PlaybackEvents()
            player.events().addMediaPlayerEventListener(events)

            assertTrue(player.media().play(media), "libVLC rejected the local ranged callback media")
            assertTrue(
                awaitPlayingState(events, PLAYBACK_START_TIMEOUT_MILLIS),
                "libVLC never reached the Playing state for the local ranged media",
            )
            awaitAdvancingTime(player, MINIMUM_PLAYBACK_TIME_MS, PLAYBACK_PROGRESS_TIMEOUT_MILLIS)

            player.controls().setPause(true)
            assertTrue(
                awaitCondition(PAUSE_TIMEOUT_MILLIS) { !player.status().isPlaying() },
                "libVLC did not pause the local ranged media",
            )
            val pausedAt = player.status().time()
            Thread.sleep(PAUSED_SETTLE_MILLIS)
            assertTrue(
                kotlin.math.abs(player.status().time() - pausedAt) <= PAUSED_DRIFT_TOLERANCE_MS,
                "the paused local media kept advancing its clock",
            )
            player.controls().setPause(false)
            assertTrue(
                awaitCondition(RESUME_TIMEOUT_MILLIS) {
                    player.status().isPlaying() && player.status().time() > pausedAt
                },
                "libVLC did not resume the local ranged media",
            )
        } finally {
            runCatching { player.controls().stop() }
            runCatching { player.release() }
            runCatching { factory.release() }
            runCatching { media.close() }
            runCatching { transport.close() }
        }

        // The WAV demuxer of libVLC cannot seek, so the ranged seek is verified on the transport
        // itself: a byte offset beyond the first window must be fetched with its own HTTP range.
        val seekTransport = VlcjRangeHttpTransport()
        val seekMedia = seekTransport.media(url = url, headers = SYNTHETIC_HEADERS)
        try {
            assertTrue(
                seekMedia.readInto(ByteArray(4096), 4096) > 0,
                "the transport did not read the first byte range",
            )
            assertTrue(seekMedia.seekTo(SYNTHETIC_SEEK_BYTE_OFFSET), "the transport refused a ranged seek")
            assertTrue(
                seekMedia.readInto(ByteArray(4096), 4096) > 0,
                "the transport did not read the seeked byte range",
            )
        } finally {
            runCatching { seekMedia.close() }
            runCatching { seekTransport.close() }
            server.stop(0)
            runCatching { Files.deleteIfExists(wave) }
        }

        assertTrue(received.isNotEmpty(), "the local server never received a range request")
        assertTrue(received.any { it.rangeStart == 0L }, "the transport never requested the first byte range")
        assertTrue(
            received.any { it.rangeStart == SYNTHETIC_SEEK_BYTE_OFFSET },
            "the transport never requested the seeked byte range (observed starts: ${received.map { it.rangeStart }})",
        )
        assertTrue(
            received.all { it.userAgent == SYNTHETIC_HEADERS["User-Agent"] },
            "the transport dropped the User-Agent header",
        )
        assertTrue(
            received.all { it.referer == SYNTHETIC_HEADERS["Referer"] },
            "the transport dropped the Referer header",
        )
        assertTrue(
            received.all { it.customHeader == SYNTHETIC_HEADERS["X-ArchiveTune-Test"] },
            "the transport dropped a custom request header",
        )
    }

    private fun seekTargetFor(durationMs: Long): Long {
        val upperBound = (durationMs - MINIMUM_SEEK_TARGET_MS).coerceAtLeast(MINIMUM_SEEK_TARGET_MS)
        return ((durationMs * 3) / 4).coerceIn(MINIMUM_SEEK_TARGET_MS, upperBound)
    }

    /** Mirrors the production callback-media demuxer selection without touching production code. */
    private fun callbackMediaOptions(mimeType: String?): List<String> =
        when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/mp4" -> listOf(":demux=mp4")
            "audio/webm" -> listOf(":demux=mkv")
            else -> emptyList()
        }

    private fun capturingHttpClient(capture: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .addInterceptor(capture)
            .build()

    private fun createWaveFile(durationSeconds: Int, sampleRate: Int = 44_100): Path {
        val samples = sampleRate * durationSeconds
        val pcm = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val value = (kotlin.math.sin(index * 2.0 * Math.PI * 440.0 / sampleRate) * 8_000.0).toInt()
            pcm[index * 2] = (value and 0xff).toByte()
            pcm[index * 2 + 1] = (value shr 8).toByte()
        }
        val path = Files.createTempFile("archivetune-range-transport-", ".wav")
        Files.newOutputStream(path).use { output ->
            output.write("RIFF".toByteArray())
            output.write(intLittleEndian(36 + pcm.size))
            output.write("WAVEfmt ".toByteArray())
            output.write(intLittleEndian(16))
            output.write(shortLittleEndian(1))
            output.write(shortLittleEndian(1))
            output.write(intLittleEndian(sampleRate))
            output.write(intLittleEndian(sampleRate * 2))
            output.write(shortLittleEndian(2))
            output.write(shortLittleEndian(16))
            output.write("data".toByteArray())
            output.write(intLittleEndian(pcm.size))
            output.write(pcm)
        }
        return path
    }

    private fun startRangeServer(file: Path, received: MutableList<LocalRangeRequest>): HttpServer {
        val bytes = Files.readAllBytes(file)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/audio.wav") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val match = Regex("""bytes=(\d+)-(\d*)""").matchEntire(range.orEmpty())
            val start = match?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(0L, bytes.size.toLong()) ?: 0L
            val requestedEnd = match?.groupValues?.get(2)?.toLongOrNull()
            val end = (requestedEnd ?: (bytes.size - 1L)).coerceIn(start, bytes.size - 1L)
            val length = (end - start + 1L).coerceAtLeast(0L)
            received += LocalRangeRequest(
                rangeStart = match?.groupValues?.get(1)?.toLongOrNull(),
                userAgent = exchange.requestHeaders.getFirst("User-Agent"),
                referer = exchange.requestHeaders.getFirst("Referer"),
                customHeader = exchange.requestHeaders.getFirst("X-ArchiveTune-Test"),
            )
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Type", "audio/wav")
            if (match != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                exchange.sendResponseHeaders(206, length)
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
            }
            exchange.responseBody.use { output ->
                if (match != null) {
                    output.write(bytes, start.toInt(), length.toInt())
                } else {
                    output.write(bytes)
                }
            }
        }
        server.start()
        return server
    }

    private fun awaitPlayingState(events: PlaybackEvents, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (events.playing.await(100, TimeUnit.MILLISECONDS)) return true
            if (events.errored.get()) return false
        }
        return false
    }

    private fun awaitAdvancingTime(player: MediaPlayer, minimumTimeMs: Long, timeoutMillis: Long): Long {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var last = Long.MIN_VALUE
        while (System.nanoTime() < deadline) {
            val current = player.status().time()
            if (current > last) {
                last = current
                if (current >= minimumTimeMs) return current
            }
            Thread.sleep(50L)
        }
        throw AssertionError("libVLC playback did not advance past $minimumTimeMs ms")
    }

    private fun awaitTimeAtLeast(player: MediaPlayer, minimumTimeMs: Long, timeoutMillis: Long): Long {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var current = -1L
        while (System.nanoTime() < deadline) {
            current = player.status().time()
            if (current >= minimumTimeMs) return current
            Thread.sleep(50L)
        }
        throw AssertionError("libVLC did not reach $minimumTimeMs ms (last observed $current)")
    }

    private fun awaitCondition(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(50L)
        }
        return predicate()
    }

    private fun intLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private data class LiveResolverEnvironment(
        val videoId: String,
        val ytDlpExecutable: Path,
        val bunExecutable: Path,
    )

    private data class LocalRangeRequest(
        val rangeStart: Long?,
        val userAgent: String?,
        val referer: String?,
        val customHeader: String?,
    )

    /** Records what the transport actually sent without ever keeping the URL or a header value. */
    private class RangeRequestCapture(
        private val expectedHeaders: Map<String, String>,
    ) : Interceptor {
        val requests = CopyOnWriteArrayList<CapturedRangeRequest>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val range = request.header("Range")
            requests += CapturedRangeRequest(
                rangeStart = range
                    ?.substringAfter("bytes=", "")
                    ?.substringBefore('-')
                    ?.trim()
                    ?.toLongOrNull(),
                rangeEnd = range
                    ?.substringAfter("bytes=", "")
                    ?.substringAfter('-', "")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.toLongOrNull(),
                missingHeaders = expectedHeaders
                    .filter { (name, value) -> request.header(name) != value }
                    .keys
                    .toList(),
            )
            return chain.proceed(request)
        }
    }

    private data class CapturedRangeRequest(
        val rangeStart: Long?,
        val rangeEnd: Long?,
        val missingHeaders: List<String>,
    )

    private class PlaybackEvents : MediaPlayerEventAdapter() {
        val playing = CountDownLatch(1)
        val errored = AtomicBoolean(false)

        override fun playing(mediaPlayer: MediaPlayer) {
            playing.countDown()
        }

        override fun error(mediaPlayer: MediaPlayer) {
            errored.set(true)
        }
    }

    private companion object {
        const val DEFAULT_LIVE_VIDEO_ID = "dQw4w9WgXcQ"
        const val PLAYBACK_START_TIMEOUT_MILLIS = 45_000L
        const val PLAYBACK_PROGRESS_TIMEOUT_MILLIS = 45_000L
        const val SEEK_TIMEOUT_MILLIS = 45_000L
        const val MINIMUM_PLAYBACK_TIME_MS = 1_000L
        const val MINIMUM_SEEK_TARGET_MS = 10_000L
        const val SEEK_TOLERANCE_MS = 5_000L
        const val PAUSE_TIMEOUT_MILLIS = 15_000L
        const val RESUME_TIMEOUT_MILLIS = 15_000L
        const val PAUSED_SETTLE_MILLIS = 1_000L
        const val PAUSED_DRIFT_TOLERANCE_MS = 750L
        const val SYNTHETIC_SEEK_BYTE_OFFSET = 1_000_000L
        const val DEEP_RANGE_THRESHOLD_BYTES = 1_000_000L

        val VLC_FACTORY_OPTIONS = arrayOf("--no-video", "--quiet", "--aout=dummy")

        val SYNTHETIC_HEADERS = mapOf(
            "User-Agent" to "archivetune-range-transport-test/1.0",
            "Referer" to "https://archive.tune.invalid/",
            "X-ArchiveTune-Test" to "range-transport",
        )

        fun systemLibVlcPath(): Path? =
            listOf(
                "/usr/lib/libvlc.so",
                "/usr/lib64/libvlc.so",
                "/usr/lib/x86_64-linux-gnu/libvlc.so",
                "/usr/local/lib/libvlc.so",
                "/lib/libvlc.so",
            ).map(Path::of).firstOrNull { Files.exists(it) }

        fun liveOptInEnabled(): Boolean =
            (System.getenv("ARCHIVETUNE_LIVE_YTDLP") ?: System.getProperty("archivetune.liveYtdlp"))
                ?.trim()
                ?.equals("1", ignoreCase = true) == true

        fun liveVideoId(): String =
            (System.getenv("ARCHIVETUNE_LIVE_YTDLP_VIDEO")
                ?: System.getProperty("archivetune.liveYtdlpVideo"))
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_LIVE_VIDEO_ID

        fun requireExecutable(environmentName: String, propertyName: String): Path {
            val configured = System.getenv(environmentName)?.trim()?.takeIf { it.isNotBlank() }
                ?: System.getProperty(propertyName)?.trim()?.takeIf { it.isNotBlank() }
            val path = Path.of(configured ?: throw AssertionError("$environmentName must point at a resolver executable"))
            assertTrue(
                Files.isRegularFile(path) && Files.isExecutable(path),
                "$environmentName does not point at an executable file",
            )
            return path
        }

        fun assumeLiveResolverEnvironment(): LiveResolverEnvironment {
            assumeTrue(
                liveOptInEnabled(),
                "ARCHIVETUNE_LIVE_YTDLP=1 is required for the live yt-dlp playback integration test",
            )
            return LiveResolverEnvironment(
                videoId = liveVideoId(),
                ytDlpExecutable = requireExecutable("ARCHIVETUNE_YTDLP", "archivetune.ytdlp"),
                bunExecutable = requireExecutable("ARCHIVETUNE_BUN", "archivetune.bun"),
            )
        }
    }
}
