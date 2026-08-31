package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddToPlaylistPickerStateTest {
    @Test
    fun `refresh failure with cached playlists keeps list usable and surfaces error`() {
        val presentation = addToPlaylistPickerPresentation(
            playlistCount = 2,
            refreshing = false,
            refreshError = "offline",
        )

        assertFalse(presentation.showInitialLoading)
        assertFalse(presentation.showEmptyState)
        assertEquals("offline", presentation.refreshError)
    }

    @Test
    fun `refresh failure without cache is not mislabeled as empty library`() {
        val presentation = addToPlaylistPickerPresentation(
            playlistCount = 0,
            refreshing = false,
            refreshError = "network down",
        )

        assertFalse(presentation.showInitialLoading)
        assertFalse(presentation.showEmptyState)
        assertEquals("network down", presentation.refreshError)
    }

    @Test
    fun `successful refresh with no playlists may show real empty state`() {
        val presentation = addToPlaylistPickerPresentation(
            playlistCount = 0,
            refreshing = false,
            refreshError = null,
        )

        assertTrue(presentation.showEmptyState)
        assertNull(presentation.refreshError)
    }

    @Test
    fun `add failure keeps same song context and reports failure`() {
        val session = AddToPlaylistPickerSession(song()).beginAdd()

        val failed = session.finishAdd(Result.failure<Unit>(IllegalStateException("playlist rejected")))

        requireNotNull(failed)
        assertEquals("song-1", failed.song.id)
        assertFalse(failed.adding)
        assertEquals("playlist rejected", failed.addError)
    }

    @Test
    fun `add success dismisses picker session`() {
        val session = AddToPlaylistPickerSession(song()).beginAdd()

        assertNull(session.finishAdd(Result.success(Unit)))
    }

    @Test
    fun `album session keeps every song in album order`() {
        val songs = listOf(song("song-1"), song("song-2"), song("song-3"))
        val session = AddToPlaylistPickerSession(songs.first(), songs)

        assertEquals(listOf("song-1", "song-2", "song-3"), session.songs.map(SongItem::id))
    }

    private fun song(id: String = "song-1") = SongItem(
        id = id,
        title = "Song",
        artists = listOf(Artist(name = "Artist", id = "artist-1")),
        thumbnail = "https://images.invalid/song.jpg",
    )
}
