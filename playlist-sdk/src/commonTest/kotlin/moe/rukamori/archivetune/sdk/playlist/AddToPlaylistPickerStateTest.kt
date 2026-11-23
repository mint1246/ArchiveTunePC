package moe.rukamori.archivetune.sdk.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddToPlaylistPickerStateTest {
    @Test
    fun refresh_failure_with_cached_playlists_keeps_list_usable_and_surfaces_error() {
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
    fun refresh_failure_without_cache_is_not_mislabeled_as_empty_library() {
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
    fun successful_refresh_with_no_playlists_shows_empty_state() {
        val presentation = addToPlaylistPickerPresentation(
            playlistCount = 0,
            refreshing = false,
            refreshError = null,
        )

        assertTrue(presentation.showEmptyState)
        assertNull(presentation.refreshError)
    }

    @Test
    fun add_failure_keeps_same_song_context_and_reports_failure() {
        val session = AddToPlaylistPickerSession("song-1").beginAdd()

        val failed = session.finishAdd(Result.failure<Unit>(IllegalStateException("playlist rejected")))

        requireNotNull(failed)
        assertEquals("song-1", failed.song)
        assertFalse(failed.adding)
        assertEquals("playlist rejected", failed.addError)
    }

    @Test
    fun add_success_dismisses_picker_session() {
        val session = AddToPlaylistPickerSession("song-1").beginAdd()

        assertNull(session.finishAdd(Result.success(Unit)))
    }

    @Test
    fun album_session_keeps_every_song_in_order() {
        val songs = listOf("song-1", "song-2", "song-3")
        val session = AddToPlaylistPickerSession(songs.first(), songs)

        assertEquals(songs, session.songs)
    }
}
