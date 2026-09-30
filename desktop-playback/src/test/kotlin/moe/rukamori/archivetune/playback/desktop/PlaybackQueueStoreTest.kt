/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQueueStoreTest {
    @Test
    fun `round trips queue metadata and position`() {
        val dir = Files.createTempDirectory("archivetune-queue-store")
        val store = PlaybackQueueStore(dir.resolve("persistent-queue.json"))
        val items =
            listOf(
                PlaybackItem(
                    PlaybackMetadata(
                        id = "video-1",
                        title = "First",
                        artists = listOf(PlaybackArtist(id = "artist-1", name = "Artist One")),
                        album = PlaybackAlbum(id = "album-1", title = "Album One"),
                        artworkUri = "https://example.com/art.jpg",
                        durationHintMs = 123_000L,
                        explicit = true,
                        isPodcast = true,
                        albumArtist = "Album Artist",
                        trackNumber = 7,
                        discNumber = 2,
                        year = 2024,
                    ),
                ),
                PlaybackItem(
                    PlaybackMetadata(id = "local-2", title = "Local track"),
                    source = PlaybackSource.LocalFile("/tmp/a.mp3"),
                ),
            )

        store.save(
            PlaybackSnapshot(
                queue = items,
                queueTitle = "Test queue",
                currentIndex = 1,
                positionMs = 42_000L,
            ),
        )

        val restored = store.load()

        assertEquals("Test queue", restored?.title)
        assertEquals(1, restored?.currentIndex)
        assertEquals(42_000L, restored?.positionMs)
        assertEquals(2, restored?.items?.size)
        assertEquals("video-1", restored?.items?.get(0)?.metadata?.id)
        assertEquals("Artist One", restored?.items?.get(0)?.metadata?.artists?.first()?.name)
        assertEquals("Album One", restored?.items?.get(0)?.metadata?.album?.title)
        assertEquals("https://example.com/art.jpg", restored?.items?.get(0)?.metadata?.artworkUri)
        assertEquals(123_000L, restored?.items?.get(0)?.metadata?.durationHintMs)
        assertTrue(restored?.items?.get(0)?.metadata?.explicit == true)
        assertTrue(restored?.items?.get(0)?.metadata?.isPodcast == true)
        assertEquals("Album Artist", restored?.items?.get(0)?.metadata?.albumArtist)
        assertEquals(7, restored?.items?.get(0)?.metadata?.trackNumber)
        assertEquals(2, restored?.items?.get(0)?.metadata?.discNumber)
        assertEquals(2024, restored?.items?.get(0)?.metadata?.year)
        assertEquals("/tmp/a.mp3", (restored?.items?.get(1)?.source as? PlaybackSource.LocalFile)?.path)
    }

    @Test
    fun `saving an empty queue clears the file`() {
        val dir = Files.createTempDirectory("archivetune-queue-store")
        val store = PlaybackQueueStore(dir.resolve("persistent-queue.json"))

        store.save(PlaybackSnapshot(queue = emptyList()))

        assertNull(store.load())
    }
}
