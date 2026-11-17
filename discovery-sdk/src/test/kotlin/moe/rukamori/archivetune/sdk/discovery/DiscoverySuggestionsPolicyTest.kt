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
    fun `filterSuggestedSongs excludes seeds deduplicates and respects limit`() {
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
    fun `buildRelatedSongSearchQuery joins non-blank title and first artist`() {
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
    fun `rankAndFilterSuggestedArtists excludes seeds ranks by frequency and preserves first-seen order on ties`() {
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
