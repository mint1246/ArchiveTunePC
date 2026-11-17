/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import java.io.File
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidPlaybackQueueStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `save and load round-trip metadata and strip remote headers`() {
        val file = File(tempFolder.root, "nested/queue.json")
        val store = AndroidPlaybackQueueStore(file)

        val deferred = PlaybackItem(
            metadata = PlaybackMetadata(
                id = "yt-1",
                title = "Deferred Track",
                artists = listOf(PlaybackArtist(id = "ar-1", name = "Artist One")),
                album = PlaybackAlbum(id = "al-1", title = "Album One"),
                artworkUri = "https://img.example.com/art.jpg",
                durationHintMs = 215_000L,
                explicit = true,
                isMusicVideo = true,
                extras = mapOf("origin" to "search"),
                albumArtist = "Various",
                trackNumber = 3,
                discNumber = 1,
                year = 2025,
            ),
            source = PlaybackSource.Deferred("key-1"),
        )
        val local = PlaybackItem(
            metadata = PlaybackMetadata(id = "loc-1", title = "Local Track"),
            source = PlaybackSource.LocalFile("/storage/emulated/0/Music/song.flac"),
        )
        val remote = PlaybackItem(
            metadata = PlaybackMetadata(id = "rem-1", title = "Remote Track"),
            source = PlaybackSource.Remote(
                url = "https://cdn.example.com/stream.m4a",
                mimeType = "audio/mp4",
                headers = mapOf("Authorization" to "Bearer secret-token", "Cookie" to "sid=abc"),
                expiresAtEpochMs = 1_800_000_000_000L,
            ),
        )

        store.save(
            PlaybackSnapshot(
                queue = listOf(deferred, local, remote),
                queueTitle = "Late Night Mix",
                currentIndex = 2,
                positionMs = 42_000L,
            ),
        )

        assertTrue(file.isFile)
        val rawText = file.readText(Charsets.UTF_8)
        assertFalse(rawText.contains("secret-token"))
        assertFalse(rawText.contains("Authorization"))

        val loaded = store.load()
        assertNotNull(loaded)
        loaded!!
        assertEquals("Late Night Mix", loaded.title)
        assertEquals(2, loaded.currentIndex)
        assertEquals(42_000L, loaded.positionMs)
        assertEquals(3, loaded.items.size)
        assertEquals(deferred, loaded.items[0])
        assertEquals(local, loaded.items[1])
        assertEquals(
            remote.copy(
                source = (remote.source as PlaybackSource.Remote).copy(headers = emptyMap()),
            ),
            loaded.items[2],
        )
    }

    @Test
    fun `saving empty queue or calling clear removes file`() {
        val file = File(tempFolder.root, "queue.json")
        val store = AndroidPlaybackQueueStore(file)
        val item = PlaybackItem(PlaybackMetadata(id = "1", title = "One"))

        store.save(PlaybackSnapshot(queue = listOf(item), currentIndex = 0, positionMs = 1_000L))
        assertTrue(file.isFile)

        store.save(PlaybackSnapshot(queue = emptyList()))
        assertFalse(file.exists())
        assertNull(store.load())

        store.save(PlaybackSnapshot(queue = listOf(item), currentIndex = 0))
        assertTrue(file.isFile)
        store.clear()
        assertFalse(file.exists())
    }

    @Test
    fun `queue persistence caps items at 2000 and ignores corrupt files`() {
        val file = File(tempFolder.root, "queue.json")
        val store = AndroidPlaybackQueueStore(file)

        val largeQueue = (0 until 2_150).map { index ->
            PlaybackItem(PlaybackMetadata(id = "id-$index", title = "Track $index"))
        }
        store.save(PlaybackSnapshot(queue = largeQueue, currentIndex = 2_100, positionMs = 5_000L))

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(2_000, loaded!!.items.size)
        assertEquals(1_999, loaded.currentIndex)

        file.writeText("{not valid json", Charsets.UTF_8)
        assertNull(store.load())
    }
}
