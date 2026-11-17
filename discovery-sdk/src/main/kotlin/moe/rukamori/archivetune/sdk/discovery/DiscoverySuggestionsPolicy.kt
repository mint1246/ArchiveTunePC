/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

/**
 * Pure policies for discovery suggestions: song/artist deduplication, seed exclusion,
 * artist occurrence ranking, and fallback search query formatting.
 */
object DiscoverySuggestionsPolicy {
    fun <T> filterSuggestedSongs(
        songs: List<T>,
        seedSongIds: Set<String>,
        maxItems: Int,
        idOf: (T) -> String?,
    ): List<T> {
        if (maxItems <= 0 || songs.isEmpty()) return emptyList()
        val filtered =
            songs.filter { song ->
                val id = idOf(song)
                id != null && id !in seedSongIds
            }
        return DiscoveryLists.takeDistinct(filtered, maxItems, idOf)
    }

    fun buildRelatedSongSearchQuery(
        title: String,
        firstArtistName: String?,
    ): String =
        listOfNotNull(
            title.takeIf(String::isNotBlank),
            firstArtistName?.takeIf(String::isNotBlank),
        ).joinToString(" ")

    fun <T> rankAndFilterSuggestedArtists(
        artists: List<T>,
        seedArtistIds: Set<String>,
        maxItems: Int,
        idOf: (T) -> String?,
    ): List<T> {
        if (maxItems <= 0 || artists.isEmpty()) return emptyList()

        data class ArtistBucket<T>(
            val item: T,
            val firstSeenIndex: Int,
            var occurrences: Int = 0,
        )

        val buckets = LinkedHashMap<String, ArtistBucket<T>>()
        artists.forEachIndexed { index, artist ->
            val id = idOf(artist)?.takeIf(String::isNotBlank) ?: return@forEachIndexed
            if (id in seedArtistIds) return@forEachIndexed
            val bucket =
                buckets.getOrPut(id) {
                    ArtistBucket(item = artist, firstSeenIndex = index)
                }
            bucket.occurrences += 1
        }

        return buckets.values
            .sortedWith(
                compareByDescending<ArtistBucket<T>> { it.occurrences }
                    .thenBy { it.firstSeenIndex },
            ).take(maxItems)
            .map { it.item }
    }
}
