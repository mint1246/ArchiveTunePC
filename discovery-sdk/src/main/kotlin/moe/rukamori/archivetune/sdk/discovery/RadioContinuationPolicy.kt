/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

/**
 * Pure policies for local/online mix selection, album radio continuation slicing,
 * and next-page availability.
 */
object RadioContinuationPolicy {
    fun <T> selectLocalMixItems(
        playlistSongIds: List<String>,
        relatedSongsForSeed: (String) -> Iterable<T>,
        idOf: (T) -> String,
        isPodcast: (T) -> Boolean,
        maxMixSize: Int = 500,
    ): List<T> {
        if (maxMixSize <= 0 || playlistSongIds.isEmpty()) return emptyList()
        val excludedIds = playlistSongIds.toHashSet()
        val seenIds = HashSet<String>()
        val result = ArrayList<T>()
        for (seedId in playlistSongIds) {
            for (song in relatedSongsForSeed(seedId)) {
                if (isPodcast(song)) continue
                val id = idOf(song)
                if (id !in excludedIds && seenIds.add(id)) {
                    result.add(song)
                    if (result.size >= maxMixSize) return result
                }
            }
        }
        return result
    }

    fun <T> mergeOnlineMixItems(
        playlistSongIds: Set<String>,
        nextItems: List<T>,
        relatedItems: List<T>,
        idOf: (T) -> String,
        maxMixSize: Int = 500,
    ): List<T> {
        if (maxMixSize <= 0) return emptyList()
        val filtered = (nextItems + relatedItems).filter { idOf(it) !in playlistSongIds }
        return DiscoveryLists.takeDistinct(filtered, maxMixSize, idOf)
    }

    fun <T> sliceAlbumRadioContinuation(
        items: List<T>,
        albumSongCount: Int,
        firstTimeLoaded: Boolean,
    ): List<T> =
        if (!firstTimeLoaded) {
            items.drop(albumSongCount.coerceIn(0, items.size))
        } else {
            items
        }

    fun <T> filterLocalAlbumRadioItems(
        items: List<T>,
        initialSongIds: Set<String>,
        firstTimeLoaded: Boolean,
        idOf: (T) -> String,
    ): List<T> =
        if (!firstTimeLoaded) {
            items.filterNot { idOf(it) in initialSongIds }
        } else {
            items
        }

    fun hasRadioNextPage(
        localOnly: Boolean = false,
        firstTimeLoaded: Boolean,
        continuation: String?,
    ): Boolean = !localOnly && (!firstTimeLoaded || continuation != null)
}
