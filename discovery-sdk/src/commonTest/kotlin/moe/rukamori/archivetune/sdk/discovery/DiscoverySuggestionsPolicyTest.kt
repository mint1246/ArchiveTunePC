/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoverySuggestionsPolicyTest {
    private data class Item(
        val id: String,
    )

    @Test
    fun filterSuggestedSongs_excludes_seeds_deduplicates_and_respects_limit() {
        val songs = listOf(Item("seed-1"), Item("s1"), Item("s2"), Item("s1"), Item("s3"))
        val result =
            DiscoverySuggestionsPolicy.filterSuggestedSongs(
                songs = songs,
                seedSongIds = setOf("seed-1"),
                maxItems = 2,
                idOf = { it.id },
            )
        assertEquals(listOf(Item("s1"), Item("s2")), result)
    }

    @Test
    fun buildRelatedSongSearchQuery_joins_non_blank_title_and_first_artist() {
        assertEquals(
            "Nightcall Kavinsky",
            DiscoverySuggestionsPolicy.buildRelatedSongSearchQuery("Nightcall", "Kavinsky"),
        )
        assertEquals(
            "Nightcall",
            DiscoverySuggestionsPolicy.buildRelatedSongSearchQuery("Nightcall", "   "),
        )
        assertEquals(
            "Nightcall",
            DiscoverySuggestionsPolicy.buildRelatedSongSearchQuery("Nightcall", null),
        )
    }

    @Test
    fun rankAndFilterSuggestedArtists_excludes_seeds_ranks_by_frequency_and_preserves_first_seen_order_on_ties() {
        val artists =
            listOf(
                Item("seed-artist"),
                Item("artist-b"),
                Item("artist-a"),
                Item("artist-a"),
                Item("artist-c"),
                Item(""),
            )
        val result =
            DiscoverySuggestionsPolicy.rankAndFilterSuggestedArtists(
                artists = artists,
                seedArtistIds = setOf("seed-artist"),
                maxItems = 2,
                idOf = { it.id },
            )
        assertEquals(listOf(Item("artist-a"), Item("artist-b")), result)
    }
}
