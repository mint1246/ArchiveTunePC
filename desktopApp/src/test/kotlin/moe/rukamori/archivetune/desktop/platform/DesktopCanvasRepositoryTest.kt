package moe.rukamori.archivetune.desktop.platform

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.canvas.CanvasSource
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.desktop.settings.DesktopCanvasSource
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopCanvasRepositoryTest {
    @Test
    fun downloadedVideoWarmsLocalCacheWithoutPersistingRemoteUrl() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val payload = ByteArray(16_384) { (it % 251).toByte() }
        server.createContext("/canvas.mp4") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        val root = Files.createTempDirectory("desktop-canvas-test")
        val settings = DesktopSettingsStore(root.resolve("settings.properties"))
        settings.update { it.copy(desktopCanvasEnabled = true, desktopCanvasCacheLimitMb = 64) }
        val cache = root.resolve("canvas-cache")
        val remoteUrl = "http://127.0.0.1:${server.address.port}/canvas.mp4"
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val firstRepository = DesktopCanvasRepository(
                settings = settings,
                cacheDirectory = cache,
                scope = firstScope,
                artworkResolver = { title, artist, _ ->
                    CanvasArtwork(
                        source = CanvasSource.BETTER_LYRICS,
                        name = title,
                        artist = artist,
                        static = "https://art.example/poster.jpg",
                        animated = remoteUrl,
                    )
                },
            )
            firstRepository.load("media-id", "Song", "Artist")
            val cached = withTimeout(5_000) { firstRepository.state.first { it.videoUri?.startsWith("file:") == true } }
            assertNotNull(cached.videoUri)
            assertTrue(Files.isRegularFile(java.nio.file.Path.of(java.net.URI(cached.videoUri))))
            firstRepository.close()

            val metadata = Files.list(cache).use { paths -> paths.filter { it.toString().endsWith(".properties") }.findFirst().orElseThrow() }
            val metadataText = Files.readString(metadata)
            assertFalse(metadataText.contains(remoteUrl))
            assertFalse(metadataText.contains("videoUrl"))
            assertFalse(metadataText.contains("art.example"))

            val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                var providerCalls = 0
                val warmRepository = DesktopCanvasRepository(
                    settings = settings,
                    cacheDirectory = cache,
                    scope = warmScope,
                    artworkResolver = { _, _, _ ->
                        providerCalls++
                        null
                    },
                )
                warmRepository.load("media-id", "Song", "Artist")
                val warm = withTimeout(1_000) { warmRepository.state.first { it.videoUri?.startsWith("file:") == true } }
                assertNotNull(warm.videoUri)
                assertEquals(0, providerCalls, "a valid local Canvas cache should avoid provider/network lookup")
                warmRepository.close()
            } finally {
                warmScope.cancel()
            }
        } finally {
            firstScope.cancel()
            server.stop(0)
            settings.close()
        }
    }
}
