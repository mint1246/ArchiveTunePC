/* Copyright (C) 2026 ArchiveTune contributors. GPL-3.0-or-later. */
package moe.rukamori.archivetune.downloads

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.Properties
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DesktopDownloadManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: java.nio.file.Path

    @Before fun setUp() { server = MockWebServer().also { it.start() }; directory = Files.createTempDirectory("archivetune-downloads") }
    @After fun tearDown() { server.shutdown(); directory.toFile().deleteRecursively() }

    @Test fun `streams progress forwards headers and never persists credentials`() = runBlocking {
        server.enqueue(MockResponse().setBody("streamed-audio").setHeader("Content-Length", 14))
        DesktopDownloadManager(directory).use { manager ->
            val id = manager.enqueue(DownloadRequest(server.url("/audio").toString(), mapOf("Authorization" to "secret-token"), "A / unsafe : title"))
            val done = manager.downloads.filter { it.any { item -> item.id == id && item.status == DownloadStatus.COMPLETED } }.first()
            assertTrue(done.single { it.id == id }.bytesDownloaded > 0)
            assertEquals("secret-token", server.takeRequest().getHeader("Authorization"))
            assertFalse(Files.readString(directory.resolve("downloads.properties")).contains("secret-token"))
            assertFalse(Files.readString(directory.resolve("downloads.properties")).contains(server.url("/audio").toString()))
            assertTrue(Files.exists(directory.resolve(done.single { it.id == id }.fileName)))
        }
    }

    @Test fun `retries transient server failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("ok"))
        DesktopDownloadManager(directory, retryCount = 1).use { manager ->
            val id = manager.enqueue(DownloadRequest(server.url("/retry").toString(), emptyMap(), "retry"))
            manager.downloads.filter { it.any { item -> item.id == id && item.status == DownloadStatus.COMPLETED } }.first()
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `cancel deletes partial by default`() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(256 * 1024)).throttleBody(1024, 100, TimeUnit.MILLISECONDS))
        DesktopDownloadManager(directory).use { manager ->
            val id = manager.enqueue(DownloadRequest(server.url("/slow").toString(), emptyMap(), "slow"))
            manager.downloads.filter { it.any { item -> item.id == id && item.bytesDownloaded > 0 } }.first()
            assertTrue(manager.cancel(id))
            manager.downloads.filter { it.any { item -> item.id == id && item.status == DownloadStatus.CANCELLED } }.first()
            assertFalse(Files.list(directory).use { it.anyMatch { path -> path.fileName.toString().endsWith(".part") } })
        }
    }

    @Test fun `restart recovers completed state and marks unresolved queued work failed`() = runBlocking {
        server.enqueue(MockResponse().setBody("complete"))
        val completedId: String
        DesktopDownloadManager(directory).use { manager ->
            completedId = manager.enqueue(
                DownloadRequest(
                    server.url("/complete").toString(),
                    emptyMap(),
                    "complete",
                    artist = "Artist",
                    mediaId = "video-id",
                    albumTitle = "Album",
                    albumId = "album-id",
                    artworkUrl = "https://i.ytimg.com/example.jpg",
                    durationMs = 123_000,
                    explicit = true,
                ),
            )
            manager.downloads.filter { it.any { item -> item.id == completedId && item.status == DownloadStatus.COMPLETED } }.first()
        }
        DesktopDownloadManager(directory).use { recovered ->
            val item = recovered.downloads.value.single { it.id == completedId }
            assertEquals(DownloadStatus.COMPLETED, item.status)
            assertNotNull(item.fileName)
            assertEquals("video-id", item.mediaId)
            assertEquals("Album", item.albumTitle)
            assertEquals("album-id", item.albumId)
            assertEquals("https://i.ytimg.com/example.jpg", item.artworkUrl)
            assertEquals(123_000L, item.durationMs)
            assertTrue(item.explicit)
        }
    }

    @Test fun `legacy journal without extended metadata still opens`() {
        val properties = Properties().apply {
            setProperty("download.0.id", "legacy-id")
            setProperty("download.0.title", "Legacy song")
            setProperty("download.0.artist", "Legacy artist")
            setProperty("download.0.fileName", "legacy.mp4")
            setProperty("download.0.status", DownloadStatus.COMPLETED.name)
            setProperty("download.0.bytes", "42")
            setProperty("download.0.total", "42")
            setProperty("download.0.updatedAt", "1")
        }
        Files.newOutputStream(directory.resolve("downloads.properties")).use { properties.store(it, null) }

        DesktopDownloadManager(directory).use { manager ->
            val item = manager.downloads.value.single()
            assertEquals("legacy-id", item.id)
            assertEquals(null, item.durationMs)
            assertFalse(item.explicit)
        }
    }

    @Test fun `safe filenames exclude Windows-reserved separators`() {
        DesktopDownloadManager(directory).use { manager ->
            val id = manager.enqueue(DownloadRequest(server.url("/name").toString(), emptyMap(), "../bad:*?title", "a/b"))
            val name = manager.downloads.value.single { it.id == id }.fileName
            assertFalse(name.any { it in "\\/:*?\"<>|" })
            manager.cancel(id)
        }
    }
}
