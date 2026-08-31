package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistSongOccurrencesTest {
    @Test
    fun `duplicate ids keep occurrence order and unique stable keys without filtering`() {
        val songs = listOf(
            song(id = "A", title = "First A"),
            song(id = "B", title = "B"),
            song(id = "A", title = "Second A"),
        )

        val occurrences = playlistSongOccurrences(songs, "")

        assertEquals(listOf("A", "B", "A"), occurrences.map { it.song.id })
        assertEquals(listOf(0, 1, 2), occurrences.map { it.originalIndex })
        assertEquals(listOf("A:0", "B:1", "A:2"), occurrences.map { it.stableKey })
    }

    @Test
    fun `filtering can select either duplicate without losing its original queue index`() {
        val songs = listOf(
            song(id = "A", title = "First duplicate"),
            song(id = "B", title = "Middle"),
            song(id = "A", title = "Second duplicate", artist = "Second Artist"),
        )

        val first = playlistSongOccurrences(songs, "first")
        val second = playlistSongOccurrences(songs, "second artist")

        assertEquals(listOf(0), first.map { it.originalIndex })
        assertEquals(listOf("A:0"), first.map { it.stableKey })
        assertEquals(listOf(2), second.map { it.originalIndex })
        assertEquals(listOf("A:2"), second.map { it.stableKey })
    }

    @Test
    fun `album filtering preserves the original occurrence index`() {
        val songs = listOf(
            song(id = "A", title = "One", album = "Alpha"),
            song(id = "B", title = "Two", album = "Beta"),
            song(id = "C", title = "Three", album = "Needle Album"),
        )

        val occurrences = playlistSongOccurrences(songs, "needle album")

        assertEquals(listOf("C"), occurrences.map { it.song.id })
        assertEquals(listOf(2), occurrences.map { it.originalIndex })
    }

    @Test
    fun `title artist and album matches retain playlist ordering`() {
        val songs = listOf(
            song(id = "one", title = "Needle title"),
            song(id = "two", title = "Two", artist = "Needle Artist"),
            song(id = "three", title = "Three", album = "Needle Album"),
        )

        val occurrences = playlistSongOccurrences(songs, "needle")

        assertEquals(listOf("one", "two", "three"), occurrences.map { it.song.id })
        assertEquals(listOf(0, 1, 2), occurrences.map { it.originalIndex })
    }

    @Test
    fun `ordinary unique ids retain their original indices and ordering`() {
        val songs = listOf(
            song(id = "one", title = "One"),
            song(id = "two", title = "Two"),
            song(id = "three", title = "Three"),
        )

        val occurrences = playlistSongOccurrences(songs, "")

        assertEquals(listOf("one", "two", "three"), occurrences.map { it.song.id })
        assertEquals(listOf(0, 1, 2), occurrences.map { it.originalIndex })
    }

    @Test
    fun `playlist sorting keeps duplicate occurrences stable`() {
        val songs = listOf(
            song(id = "same", title = "Zulu", artist = "B"),
            song(id = "middle", title = "Alpha", artist = "C"),
            song(id = "same", title = "Alpha", artist = "A"),
        )
        val occurrences = playlistSongOccurrences(songs, "")

        assertEquals(
            listOf("middle:1", "same:2", "same:0"),
            sortPlaylistSongOccurrences(occurrences, DesktopSongSort.TITLE).map { it.stableKey },
        )
        assertEquals(
            listOf("same:2", "same:0", "middle:1"),
            sortPlaylistSongOccurrences(occurrences, DesktopSongSort.ARTIST).map { it.stableKey },
        )
        assertEquals(
            listOf("same:0", "middle:1", "same:2"),
            sortPlaylistSongOccurrences(occurrences, DesktopSongSort.ORIGINAL).map { it.stableKey },
        )
    }

    @Test
    fun `song duration sorting puts unknown duration last`() {
        val songs = listOf(
            song(id = "unknown", title = "Unknown"),
            song(id = "long", title = "Long", duration = 240),
            song(id = "short", title = "Short", duration = 90),
        )

        assertEquals(
            listOf("short", "long", "unknown"),
            sortSongs(songs, DesktopSongSort.DURATION).map { it.id },
        )
    }

    private fun song(
        id: String,
        title: String,
        artist: String = "Artist",
        album: String? = null,
        duration: Int? = null,
    ): SongItem =
        SongItem(
            id = id,
            title = title,
            artists = listOf(Artist(artist, "artist-$artist")),
            album = album?.let { Album(it, "album-$it") },
            duration = duration,
            thumbnail = "",
        )
}
