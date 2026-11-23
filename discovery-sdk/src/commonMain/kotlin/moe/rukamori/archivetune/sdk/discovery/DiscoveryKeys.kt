/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

/**
 * Stable namespace and key builders for cached discovery/search/browse content.
 *
 * The returned strings are only used as in-memory cache keys inside [DiscoveryContentCache].
 * They are never logged, persisted or sent to a remote service, and never contain credentials,
 * signed URLs or auth headers. The account scope is supplied separately by the cache's provider.
 */
object DiscoveryKeys {
    const val SEARCH: String = "search"
    const val SEARCH_SUMMARY: String = "search-summary"
    const val BROWSE: String = "browse"
    const val BROWSE_CONTINUATION: String = "browse-continuation"
    const val DISCOVERY: String = "discovery"

    fun search(
        query: String,
        filter: String,
    ): String = "$SEARCH|$filter|${query.trim()}"

    fun searchSummary(query: String): String = "$SEARCH_SUMMARY|${query.trim()}"

    fun browse(browseId: String): String = "$BROWSE|${browseId.trim()}"

    fun browse(browseId: String, params: String?): String =
        if (params.isNullOrBlank()) browse(browseId) else "$BROWSE|${browseId.trim()}|${params.trim()}"

    fun continuation(token: String): String = "$BROWSE_CONTINUATION|${token.trim()}"

    fun discovery(): String = DISCOVERY
}
