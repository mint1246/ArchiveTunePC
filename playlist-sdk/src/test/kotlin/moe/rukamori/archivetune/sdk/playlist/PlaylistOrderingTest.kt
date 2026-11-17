package moe.rukamori.archivetune.sdk.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PlaylistOrderingTest {
    private data class TestSong(
        val id: String,
        val title: String,
        val artists: List<String> = listOf("Artist"),
        val album: String? = null,
        val duration: Long? = null,
    )

    private data class TestPlaylist(
        val id: String,
        val title: String,
        val author: String? = null,
    )

    private data class TestTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Long? = null,
    )

    @Test
    fun `duplicate ids keep occurrence order and unique stable keys without filtering`() {
        val songs = listOf(
            TestSong(id = "A", title = "First A"),
            TestSong(id = "B", title = "B"),
            TestSong(id = "A", title = "Second A"),
        )

        val occurrences = playlistSongOccurrences(
            songs = songs,
            query = "",
            id = TestSong::id,
            title = TestSong::title,
            artists = TestSong::artists,
            album = TestSong::album,
        )

        assertEquals(listOf("A", "B", "A"), occurrences.map { it.song.id })
        assertEquals(listOf(0, 1, 2), occurrences.map { it.originalIndex })
        assertEquals(listOf("A:0", "B:1", "A:2"), occurrences.map { it.stableKey })
    }

    @Test
    fun `filtering by title artist or album retains original occurrence indices`() {
        val songs = listOf(
            TestSong(id = "one", title = "Needle title"),
            TestSong(id = "two", title = "Two", artists = listOf("Needle Artist")),
            TestSong(id = "three", title = "Three", album = "Needle Album"),
            TestSong(id = "four", title = "Other"),
        )

        val occurrences = playlistSongOccurrences(
            songs = songs,
            query = "needle",
            id = TestSong::id,
            title = TestSong::title,
            artists = TestSong::artists,
            album = TestSong::album,
        )

        assertEquals(listOf("one", "two", "three"), occurrences.map { it.song.id })
        assertEquals(listOf(0, 1, 2), occurrences.map { it.originalIndex })
    }

    @Test
    fun `playlist sorting keeps duplicate occurrences stable and puts unknown duration last`() {
        val songs = listOf(
            TestSong(id = "same", title = "Zulu", artists = listOf("B"), album = "Z", duration = null),
            TestSong(id = "middle", title = "Alpha", artists = listOf("C"), album = "A", duration = 240L),
            TestSong(id = "same", title = "Alpha", artists = listOf("A"), album = "M", duration = 90L),
        )
        val occurrences = playlistSongOccurrences(
            songs = songs,
            query = "",
            id = TestSong::id,
            title = TestSong::title,
            artists = TestSong::artists,
            album = TestSong::album,
        )

        assertEquals(
            listOf("middle:1", "same:2", "same:0"),
            sortOccurrences(occurrences, PlaylistSongSort.TITLE).map { it.stableKey },
        )
        assertEquals(
            listOf("same:2", "same:0", "middle:1"),
            sortOccurrences(occurrences, PlaylistSongSort.ARTIST).map { it.stableKey },
        )
        assertEquals(
            listOf("middle:1", "same:2", "same:0"),
            sortOccurrences(occurrences, PlaylistSongSort.ALBUM).map { it.stableKey },
        )
        assertEquals(
            listOf("same:2", "middle:1", "same:0"),
            sortOccurrences(occurrences, PlaylistSongSort.DURATION).map { it.stableKey },
        )
        assertEquals(
            listOf("same:0", "middle:1", "same:2"),
            sortOccurrences(occurrences, PlaylistSongSort.ORIGINAL).map { it.stableKey },
        )
    }

    @Test
    fun `sortBySongSort preserves original order and sorts case-insensitively`() {
        val tracks = listOf(
            TestTrack(id = "z", title = "zulu", artist = "Beta", album = "Middle", durationMs = 200L),
            TestTrack(id = "a", title = "Alpha", artist = "gamma", album = "Zulu", durationMs = null),
            TestTrack(id = "m", title = "middle", artist = "Alpha", album = "alpha", durationMs = 100L),
        )

        assertSame(
            tracks,
            sortBySongSort(
                tracks,
                PlaylistSongSort.ORIGINAL,
                TestTrack::title,
                TestTrack::artist,
                { it.album.orEmpty() },
                TestTrack::durationMs,
            ),
        )
        assertEquals(
            listOf("a", "m", "z"),
            sortBySongSort(
                tracks,
                PlaylistSongSort.TITLE,
                TestTrack::title,
                TestTrack::artist,
                { it.album.orEmpty() },
                TestTrack::durationMs,
            ).map { it.id },
        )
        assertEquals(
            listOf("m", "z", "a"),
            sortBySongSort(
                tracks,
                PlaylistSongSort.ARTIST,
                TestTrack::title,
                TestTrack::artist,
                { it.album.orEmpty() },
                TestTrack::durationMs,
            ).map { it.id },
        )
        assertEquals(
            listOf("m", "z", "a"),
            sortBySongSort(
                tracks,
                PlaylistSongSort.DURATION,
                TestTrack::title,
                TestTrack::artist,
                { it.album.orEmpty() },
                TestTrack::durationMs,
            ).map { it.id },
        )
    }

    @Test
    fun `filterLibraryContent preserves lists on blank query and filters trimmed case-insensitively`() {
        val playlists = listOf(
            TestPlaylist("p1", "Late Night", "DJ One"),
            TestPlaylist("p2", "Workout", "Needle Person"),
        )
        val tracks = listOf(
            TestTrack("t1", "Needle Track", "Artist"),
            TestTrack("t2", "Other", "Artist", album = "Needle Album"),
        )

        val blankResult = filterLibraryContent(
            playlists = playlists,
            tracks = tracks,
            query = "   ",
            playlistTitle = TestPlaylist::title,
            playlistAuthor = TestPlaylist::author,
            trackTitle = TestTrack::title,
            trackArtist = TestTrack::artist,
            trackAlbum = TestTrack::album,
        )
        assertSame(playlists, blankResult.playlists)
        assertSame(tracks, blankResult.tracks)

        val filtered = filterLibraryContent(
            playlists = playlists,
            tracks = tracks,
            query = "  NEEDLE  ",
            playlistTitle = TestPlaylist::title,
            playlistAuthor = TestPlaylist::author,
            trackTitle = TestTrack::title,
            trackArtist = TestTrack::artist,
            trackAlbum = TestTrack::album,
        )
        assertEquals(listOf("p2"), filtered.playlists.map { it.id })
        assertEquals(listOf("t1", "t2"), filtered.tracks.map { it.id })
    }

    private fun sortOccurrences(
        occurrences: List<PlaylistSongOccurrence<TestSong>>,
        sort: PlaylistSongSort,
    ) = sortPlaylistSongOccurrences(
        occurrences = occurrences,
        sort = sort,
        title = TestSong::title,
        firstArtist = { it.artists.firstOrNull().orEmpty() },
        album = { it.album.orEmpty() },
        duration = TestSong::duration,
    )
}
