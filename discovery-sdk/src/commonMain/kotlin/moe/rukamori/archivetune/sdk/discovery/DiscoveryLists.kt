/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

/** Pure list policies used to build bounded discovery UI models from mixed content sources. */
object DiscoveryLists {
    /** Stable, first-occurrence-wins deduplication by the id returned by [idOf]. */
    fun <T> distinctById(
        items: List<T>,
        idOf: (T) -> String?,
    ): List<T> {
        val seen = HashSet<String>()
        return items.filter { item ->
            val id = idOf(item)
            id != null && seen.add(id)
        }
    }

    /** Deduplicates by id and then keeps at most [limit] items, preserving input order. */
    fun <T> takeDistinct(
        items: List<T>,
        limit: Int,
        idOf: (T) -> String?,
    ): List<T> = distinctById(items, idOf).take(limit.coerceAtLeast(0))
}
