package moe.rukamori.archivetune.sdk.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistTagPolicyTest {
    @Test
    fun `normalizeDraft trims inputs and rejects blanks`() {
        assertEquals(
            PlaylistTagDraft(name = "Late Night", color = "#FF6B6B"),
            PlaylistTagPolicy.normalizeDraft("  Late Night  ", "  #FF6B6B "),
        )
        assertNull(PlaylistTagPolicy.normalizeDraft("   ", "#FF6B6B"))
        assertNull(PlaylistTagPolicy.normalizeDraft("Late Night", "   "))
    }

    @Test
    fun `parseTagNames trims deduplicates case-insensitively and preserves order`() {
        assertEquals(
            listOf("Chill", "Workout", "Lo-Fi"),
            PlaylistTagPolicy.parseTagNames(" Chill, Workout , CHILL , , Lo-Fi "),
        )
    }

    @Test
    fun `planReplacePlaylistTags trims deduplicates tagIds and rejects blank playlistId`() {
        assertNull(PlaylistTagPolicy.planReplacePlaylistTags("   ", listOf("t1")))
        assertEquals(
            PlaylistTagAssignment(playlistId = "p1", tagIds = listOf("t1", "t2")),
            PlaylistTagPolicy.planReplacePlaylistTags(" p1 ", listOf(" t1 ", "", "t1", "t2")),
        )
    }

    @Test
    fun `planBulkTagAssignment deduplicates ids and returns null when either side is empty`() {
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
