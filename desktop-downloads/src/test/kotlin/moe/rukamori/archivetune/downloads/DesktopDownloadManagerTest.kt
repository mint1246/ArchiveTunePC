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

    @Test fun `cancel of queued work wins while another download holds the permit`() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(256 * 1024)).throttleBody(1024, 100, TimeUnit.MILLISECONDS))
        DesktopDownloadManager(directory, maxConcurrentDownloads = 1).use { manager ->
            val first = manager.enqueue(DownloadRequest(server.url("/first").toString(), emptyMap(), "first"))
            manager.downloads.filter { snapshots ->
                snapshots.any { it.id == first && it.bytesDownloaded > 0 }
            }.first()
            val queued = manager.enqueue(DownloadRequest(server.url("/queued").toString(), emptyMap(), "queued"))
            manager.downloads.filter { snapshots ->
                snapshots.any { it.id == queued && it.status == DownloadStatus.QUEUED }
            }.first()

            assertTrue(manager.cancel(queued))
            manager.downloads.filter { snapshots ->
                snapshots.any { it.id == queued && it.status == DownloadStatus.CANCELLED }
            }.first()
            assertTrue(manager.hasActiveWork())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `cancel after completion is a no-op and leaves the cached file usable`() = runBlocking {
        server.enqueue(MockResponse().setBody("finished"))
        DesktopDownloadManager(directory).use { manager ->
            val id = manager.enqueue(DownloadRequest(server.url("/finished").toString(), emptyMap(), "finished", mediaId = "finished-id"))
            manager.downloads.filter { snapshots ->
                snapshots.any { it.id == id && it.status == DownloadStatus.COMPLETED }
            }.first()

            assertFalse(manager.cancel(id))
            val file = manager.completedFileFor("finished-id")
            assertEquals("finished", Files.readString(requireNotNull(file)))
            assertEquals(DownloadStatus.COMPLETED, manager.downloads.value.single { it.id == id }.status)
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

    @Test fun `completed file lookup uses media id and ignores missing files`() = runBlocking {
        server.enqueue(MockResponse().setBody("complete"))
        DesktopDownloadManager(directory).use { manager ->
            val id = manager.enqueue(
                DownloadRequest(server.url("/lookup").toString(), emptyMap(), "lookup", mediaId = "media-id"),
            )
            manager.downloads.filter { it.any { item -> item.id == id && item.status == DownloadStatus.COMPLETED } }.first()
            val file = manager.completedFileFor("media-id")
            assertNotNull(file)
            assertEquals(file, manager.completedFileForDownload(id))
            assertEquals("complete", Files.readString(requireNotNull(file)))
            Files.delete(requireNotNull(file))
            assertEquals(null, manager.completedFileFor("media-id"))
            assertEquals(null, manager.completedFileForDownload(id))
            assertEquals(null, manager.completedFileFor("other-id"))
        }
    }

    @Test fun `completed file lookup cannot escape the download directory`() {
        val outside = Files.createTempFile(directory.parent, "archivetune-outside", ".m4a")
        try {
            val properties = Properties().apply {
                setProperty("download.0.id", "escaped-id")
                setProperty("download.0.title", "Escaped")
                setProperty("download.0.fileName", "../${outside.fileName}")
                setProperty("download.0.status", DownloadStatus.COMPLETED.name)
                setProperty("download.0.bytes", "1")
                setProperty("download.0.total", "1")
                setProperty("download.0.updatedAt", "1")
                setProperty("download.0.mediaId", "escaped-media")
            }
            Files.newOutputStream(directory.resolve("downloads.properties")).use { properties.store(it, null) }

            DesktopDownloadManager(directory).use { manager ->
                assertEquals(null, manager.completedFileFor("escaped-media"))
            }
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test fun `completed file lookup skips stale entries and returns a later readable match`() {
        val properties = Properties().apply {
            setProperty("download.0.id", "stale-id")
            setProperty("download.0.title", "Stale")
            setProperty("download.0.fileName", "stale.m4a")
            setProperty("download.0.status", DownloadStatus.COMPLETED.name)
            setProperty("download.0.updatedAt", "1")
            setProperty("download.0.mediaId", "same-media")
            setProperty("download.1.id", "valid-id")
            setProperty("download.1.title", "Valid")
            setProperty("download.1.fileName", "valid.m4a")
            setProperty("download.1.status", DownloadStatus.COMPLETED.name)
            setProperty("download.1.updatedAt", "2")
            setProperty("download.1.mediaId", "same-media")
        }
        Files.writeString(directory.resolve("valid.m4a"), "cached")
        Files.newOutputStream(directory.resolve("downloads.properties")).use { properties.store(it, null) }

        DesktopDownloadManager(directory).use { manager ->
            assertEquals(directory.resolve("valid.m4a"), manager.completedFileFor("same-media"))
            assertEquals(null, manager.completedFileFor(""))
        }
    }

    @Test fun `same metadata downloads use distinct files and survive journal reload`() = runBlocking {
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setBody("second"))
        var ids = emptyList<String>()
        DesktopDownloadManager(directory, maxConcurrentDownloads = 2).use { manager ->
            ids = List(2) {
                manager.enqueue(
                    DownloadRequest(server.url("/collision-$it").toString(), emptyMap(), "Same title", "Same artist"),
                )
            }
            manager.downloads.filter { snapshots ->
                ids.all { id -> snapshots.any { it.id == id && it.status == DownloadStatus.COMPLETED } }
            }.first()
            val completed = manager.downloads.value.filter { it.id in ids }
            assertEquals(2, completed.map { it.fileName }.distinct().size)
            assertEquals(setOf("first", "second"), completed.map { Files.readString(directory.resolve(it.fileName)) }.toSet())
        }

        DesktopDownloadManager(directory).use { recovered ->
            assertEquals(ids.toSet(), recovered.downloads.value.map { it.id }.toSet())
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

    @Test fun `safe filenames avoid Windows device names`() {
        DesktopDownloadManager(directory).use { manager ->
            val id = manager.enqueue(DownloadRequest(server.url("/reserved").toString(), emptyMap(), "CON"))
            val name = manager.downloads.value.single { it.id == id }.fileName
            assertFalse(name.equals("CON.m4a", ignoreCase = true))
            manager.cancel(id)
        }
    }
}
