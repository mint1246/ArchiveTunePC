package moe.rukamori.archivetune.desktop.ui

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.desktop.library.LocalTrack
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.PlaylistItem

class LocalLibraryFilterTest {
    @Test
    fun `blank query preserves both sections and their ordering`() {
        val playlists = listOf(playlist("p1", "First"), playlist("p2", "Second"))
        val tracks = listOf(track("t1", "One"), track("t2", "Two"))

        val result = filterLocalLibrary(playlists, tracks, "   ")

        assertEquals(listOf("p1", "p2"), result.playlists.map { it.id })
        assertEquals(listOf("t1", "t2"), result.tracks.map { it.id })
    }

    @Test
    fun `playlist title and author match case insensitively`() {
        val playlists = listOf(
            playlist("title", "Late Night Mix", author = "Owner"),
            playlist("author", "Favorites", author = "Needle Person"),
            playlist("other", "Morning", author = "Elsewhere"),
        )

        assertEquals(
            listOf("title"),
            filterLocalLibrary(playlists, emptyList(), "LATE night").playlists.map { it.id },
        )
        assertEquals(
            listOf("author"),
            filterLocalLibrary(playlists, emptyList(), "needle person").playlists.map { it.id },
        )
    }

    @Test
    fun `local title artist and album fields all match`() {
        val tracks = listOf(
            track("title", "Needle Song", artist = "A", album = "Alpha"),
            track("artist", "Two", artist = "Needle Artist", album = "Beta"),
            track("album", "Three", artist = "C", album = "Needle Album"),
            track("other", "Four", artist = "D", album = "Delta"),
        )

        val result = filterLocalLibrary(emptyList(), tracks, "needle")

        assertEquals(listOf("title", "artist", "album"), result.tracks.map { it.id })
    }

    @Test
    fun `mixed playlist and local results stay in separate original order`() {
        val playlists = listOf(
            playlist("p0", "Other"),
            playlist("p1", "Needle One"),
            playlist("p2", "Needle Two"),
        )
        val tracks = listOf(
            track("t0", "Other"),
            track("t1", "Needle Track One"),
            track("t2", "Two", artist = "Needle Artist"),
        )

        val result = filterLocalLibrary(playlists, tracks, "needle")

        assertEquals(listOf("p1", "p2"), result.playlists.map { it.id })
        assertEquals(listOf("t1", "t2"), result.tracks.map { it.id })
    }

    @Test
    fun `no result state leaves both filtered sections empty`() {
        val result = filterLocalLibrary(
            playlists = listOf(playlist("p1", "Favorites", author = "Owner")),
            tracks = listOf(track("t1", "Song", artist = "Artist", album = "Album")),
            query = "definitely missing",
        )

        assertTrue(result.playlists.isEmpty())
        assertTrue(result.tracks.isEmpty())
    }

    @Test
    fun `local track sorting supports title artist album and duration`() {
        val tracks = listOf(
            track("z", "Zulu", artist = "Beta", album = "Gamma", durationMs = 200_000),
            track("a", "Alpha", artist = "Zulu", album = "Beta", durationMs = null),
            track("m", "Middle", artist = "Alpha", album = "Alpha", durationMs = 90_000),
        )

        assertEquals(listOf("a", "m", "z"), sortLocalTracks(tracks, DesktopSongSort.TITLE).map { it.id })
        assertEquals(listOf("m", "z", "a"), sortLocalTracks(tracks, DesktopSongSort.ARTIST).map { it.id })
        assertEquals(listOf("m", "a", "z"), sortLocalTracks(tracks, DesktopSongSort.ALBUM).map { it.id })
        assertEquals(listOf("m", "z", "a"), sortLocalTracks(tracks, DesktopSongSort.DURATION).map { it.id })
    }

    private fun playlist(id: String, title: String, author: String? = null): PlaylistItem =
        PlaylistItem(
            id = id,
            title = title,
            author = author?.let { Artist(it, "artist-$id") },
            songCountText = null,
            thumbnail = null,
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )

    private fun track(
        id: String,
        title: String,
        artist: String = "Artist",
        album: String? = null,
        durationMs: Long? = null,
    ): LocalTrack =
        LocalTrack(
            id = id,
            path = Path.of("$id.mp3"),
            modifiedMillis = 0,
            sizeBytes = 0,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
        )
}
