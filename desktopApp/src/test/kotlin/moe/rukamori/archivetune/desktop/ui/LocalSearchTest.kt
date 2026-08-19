package moe.rukamori.archivetune.desktop.ui

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.desktop.library.LocalTrack
import moe.rukamori.archivetune.playback.api.PlaybackSource

class LocalSearchTest {
    @Test
    fun `blank query has no local matches`() {
        assertTrue(localSearchMatches("   ", listOf(track("1", "Song", "Artist", "Album"))).isEmpty())
    }

    @Test
    fun `title artist and album match case insensitively in library order`() {
        val tracks = listOf(
            track("title", "Needle Song", "One", "Alpha"),
            track("artist", "Second", "The NEEDLE Band", "Beta"),
            track("album", "Third", "Three", "A needle record"),
            track("miss", "Fourth", "Four", "Delta"),
        )

        assertEquals(tracks.take(3), localSearchMatches("  nEeDlE  ", tracks))
    }

    @Test
    fun `remote empty does not suppress a local hit`() {
        val remote = emptyList<Any>()
        val local = localSearchMatches("offline", listOf(track("local", "Offline only", "Artist", null)))

        assertTrue(remote.isEmpty())
        assertEquals(listOf("local"), local.map(LocalTrack::id))
    }

    @Test
    fun `duplicate metadata remains as separate local files`() {
        val first = track("first", "Same Song", "Same Artist", "Same Album", "C:/music/one.flac")
        val second = track("second", "Same Song", "Same Artist", "Same Album", "D:/music/two.flac")

        assertEquals(listOf(first, second), localSearchMatches("same song", listOf(first, second)))
    }

    @Test
    fun `local playback item preserves local source and indexed duration`() {
        val track = track("duration", "Timed", "Artist", "Album", "C:/music/timed.flac", durationMs = 187_000L)

        val item = toPlaybackItem(track)

        assertEquals(187_000L, item.metadata.durationHintMs)
        assertEquals(PlaybackSource.LocalFile(track.path.toString()), item.source)
    }

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String?,
        path: String = "C:/music/$id.flac",
        durationMs: Long? = null,
    ) = LocalTrack(
        id = id,
        path = Paths.get(path),
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        modifiedMillis = 0L,
        sizeBytes = 0L,
    )
}
