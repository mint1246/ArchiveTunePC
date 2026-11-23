package moe.rukamori.archivetune.sdk.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistTagPolicyTest {
    @Test
    fun normalizeDraft_trims_inputs_and_rejects_blanks() {
        assertEquals(
            PlaylistTagDraft(name = "Late Night", color = "#FF6B6B"),
            PlaylistTagPolicy.normalizeDraft("  Late Night  ", "  #FF6B6B "),
        )
        assertNull(PlaylistTagPolicy.normalizeDraft("   ", "#FF6B6B"))
        assertNull(PlaylistTagPolicy.normalizeDraft("Late Night", "   "))
    }

    @Test
    fun parseTagNames_trims_deduplicates_case_insensitively_and_preserves_order() {
        assertEquals(
            listOf("Chill", "Workout", "Lo-Fi"),
            PlaylistTagPolicy.parseTagNames(" Chill, Workout , CHILL , , Lo-Fi "),
        )
    }

    @Test
    fun planReplacePlaylistTags_trims_deduplicates_tagIds_and_rejects_blank_playlistId() {
        assertNull(PlaylistTagPolicy.planReplacePlaylistTags("   ", listOf("t1")))
        assertEquals(
            PlaylistTagAssignment(playlistId = "p1", tagIds = listOf("t1", "t2")),
            PlaylistTagPolicy.planReplacePlaylistTags(" p1 ", listOf(" t1 ", "", "t1", "t2")),
        )
    }

    @Test
    fun planBulkTagAssignment_deduplicates_ids_and_returns_null_when_either_side_is_empty() {
        assertNull(PlaylistTagPolicy.planBulkTagAssignment(listOf(" ", ""), listOf("t1")))
        assertNull(PlaylistTagPolicy.planBulkTagAssignment(listOf("p1"), emptyList()))
        assertEquals(
            BulkPlaylistTagAssignment(
                playlistIds = listOf("p1", "p2"),
                tagIds = listOf("t1", "t2"),
            ),
            PlaylistTagPolicy.planBulkTagAssignment(
                playlistIds = listOf(" p1 ", "p1", "p2", ""),
                tagIds = listOf("t1", " t2 ", "t1"),
            ),
        )
    }
}
