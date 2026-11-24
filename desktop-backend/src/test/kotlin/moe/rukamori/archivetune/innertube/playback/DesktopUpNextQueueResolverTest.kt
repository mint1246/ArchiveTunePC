/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.pages.NextResult
import moe.rukamori.archivetune.playback.api.ClickedItemQueueSelection
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUpNextQueueResolverTest {
    @Test
    fun `clicked song index wins over server currentIndex and learns albums`() = runBlocking {
        val clicked = song(id = "clicked-id", title = "Clicked", album = Album(id = "alb-1", name = "Clicked Album"))
        val other = song(id = "other-id", title = "Other", album = null)
        val learnedAlbums = mutableMapOf<String, PlaybackAlbum>()
        val infos = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val resolver = DesktopUpNextQueueResolver(
            fetchUpNext = {
                Result.success(
                    NextResult(
                        title = "Radio Mix",
                        items = listOf(other, clicked),
                        currentIndex = 0, // Server points to other-id, clicked-id is at index 1
                        continuation = null,
                        endpoint = WatchEndpoint(videoId = "clicked-id"),
                    ),
                )
            },
            learnAlbum = { id, album -> learnedAlbums[id] = album },
            lookupAlbum = { id -> if (id == "other-id") PlaybackAlbum("alb-cached", "Cached Album") else null },
            diagnostics = RecordingDiagnostics(infos, warnings),
        )

        val resolution = resolver.resolve(clicked)

        assertNull(resolution.failureMessage)
        assertEquals(ClickedItemQueueSelection.Outcome.MATCHED, resolution.selection.outcome)
        assertEquals(1, resolution.selection.startIndex)
        assertEquals("Radio Mix", resolution.selection.title)
        assertEquals(listOf("other-id", "clicked-id"), resolution.selection.items.map { it.metadata.id })
        assertEquals(PlaybackAlbum("alb-1", "Clicked Album"), learnedAlbums["clicked-id"])
        assertEquals(PlaybackAlbum("alb-cached", "Cached Album"), resolution.selection.items[0].metadata.album)
        assertTrue(warnings.isEmpty())
        assertTrue(infos.single().contains("up_next_selection event=resolved requestedVideoId=clicked-id backendCurrentIndex=0 backendSelectedVideoId=other-id matchedIndex=1 queueSize=2"))
    }

    @Test
    fun `missing target falls back to clicked song alone and logs warning`() = runBlocking {
        val clicked = song(id = "clicked-id", title = "Clicked", album = Album(id = "alb-1", name = "Album"))
        val wrong1 = song(id = "wrong-1", title = "Wrong 1")
        val wrong2 = song(id = "wrong-2", title = "Wrong 2")
        val infos = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val resolver = DesktopUpNextQueueResolver(
            fetchUpNext = {
                Result.success(
                    NextResult(
                        title = "Server Mix",
                        items = listOf(wrong1, wrong2),
                        currentIndex = 0,
                        continuation = null,
                        endpoint = WatchEndpoint(videoId = "clicked-id"),
                    ),
                )
            },
            diagnostics = RecordingDiagnostics(infos, warnings),
        )

        val resolution = resolver.resolve(clicked)

        assertNull(resolution.failureMessage)
        assertEquals(ClickedItemQueueSelection.Outcome.MISSING_TARGET_FALLBACK, resolution.selection.outcome)
        assertEquals(0, resolution.selection.startIndex)
        assertEquals("Server Mix", resolution.selection.title)
        assertEquals(listOf("clicked-id"), resolution.selection.items.map { it.metadata.id })
        assertTrue(infos.single().contains("matchedIndex=null"))
        assertTrue(warnings.single().contains("up_next_selection event=requested_missing requestedVideoId=clicked-id"))
        assertTrue(warnings.single().contains("queueVideoIds=wrong-1,wrong-2"))
    }

    @Test
    fun `empty queue falls back to clicked song alone`() = runBlocking {
        val clicked = song(id = "clicked-id", title = "Clicked")
        val infos = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val resolver = DesktopUpNextQueueResolver(
            fetchUpNext = {
                Result.success(
                    NextResult(
                        title = "Ignored Title",
                        items = emptyList(),
                        currentIndex = null,
                        continuation = null,
                        endpoint = WatchEndpoint(videoId = "clicked-id"),
                    ),
                )
            },
            diagnostics = RecordingDiagnostics(infos, warnings),
        )

        val resolution = resolver.resolve(clicked)

        assertNull(resolution.failureMessage)
        assertEquals(ClickedItemQueueSelection.Outcome.EMPTY_QUEUE_FALLBACK, resolution.selection.outcome)
        assertEquals(0, resolution.selection.startIndex)
        assertEquals("Up next", resolution.selection.title)
        assertEquals(listOf("clicked-id"), resolution.selection.items.map { it.metadata.id })
        assertTrue(infos.single().contains("up_next_selection event=empty requestedVideoId=clicked-id backendCurrentIndex=null"))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `failure falls back to clicked song alone with sanitized warning and album lookup`() = runBlocking {
        val clicked = song(id = "clicked-id", title = "Clicked", album = null)
        val infos = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val resolver = DesktopUpNextQueueResolver(
            fetchUpNext = { Result.failure(IllegalStateException("Network\n  timeout\toccurred")) },
            lookupAlbum = { id -> if (id == "clicked-id") PlaybackAlbum("alb-learned", "Learned Album") else null },
            diagnostics = RecordingDiagnostics(infos, warnings),
        )

        val resolution = resolver.resolve(clicked)

        assertEquals("Network\n  timeout\toccurred", resolution.failureMessage)
        assertEquals(ClickedItemQueueSelection.Outcome.FAILURE_FALLBACK, resolution.selection.outcome)
        assertEquals(0, resolution.selection.startIndex)
        assertEquals("Up next", resolution.selection.title)
        assertEquals(listOf("clicked-id"), resolution.selection.items.map { it.metadata.id })
        assertEquals(PlaybackAlbum("alb-learned", "Learned Album"), resolution.selection.items.single().metadata.album)
        assertTrue(infos.isEmpty())
        assertTrue(warnings.single().contains("up_next_selection event=failure requestedVideoId=clicked-id errorType=IllegalStateException error=Network timeout occurred"))
    }

    @Test
    fun `cancellation exception is rethrown instead of falling back`() = runBlocking {
        val clicked = song(id = "clicked-id", title = "Clicked")
        val infos = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val resolver = DesktopUpNextQueueResolver(
            fetchUpNext = { Result.failure(CancellationException("superseded")) },
            diagnostics = RecordingDiagnostics(infos, warnings),
        )

        var caught: CancellationException? = null
        try {
            resolver.resolve(clicked)
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }

        assertEquals("superseded", caught?.message)
        assertTrue(infos.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    private fun song(id: String, title: String, album: Album? = null) = SongItem(
        id = id,
        title = title,
        artists = listOf(Artist(id = "artist-1", name = "Artist")),
        album = album,
        duration = 200,
        thumbnail = "https://example.com/$id.jpg",
    )

    private class RecordingDiagnostics(
        private val infos: MutableList<String>,
        private val warnings: MutableList<String>,
    ) : DesktopUpNextDiagnostics {
        override fun info(message: String) {
            infos += message
        }

        override fun warning(message: String) {
            warnings += message
        }
    }
}
