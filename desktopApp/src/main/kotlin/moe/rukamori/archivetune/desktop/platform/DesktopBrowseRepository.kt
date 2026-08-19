/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.util.LinkedHashMap
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.cache.DesktopRequestPriority
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.pages.AlbumPage
import moe.rukamori.archivetune.innertube.pages.ArtistItemsPage
import moe.rukamori.archivetune.innertube.pages.ArtistPage
import moe.rukamori.archivetune.innertube.pages.PlaylistPage

internal sealed interface DesktopBrowseRoute {
    val cacheId: String

    data class Album(val browseId: String) : DesktopBrowseRoute {
        override val cacheId = "album:$browseId"
    }

    data class Artist(val id: String) : DesktopBrowseRoute {
        override val cacheId = "artist:$id"
    }

    data class Playlist(val id: String) : DesktopBrowseRoute {
        override val cacheId = "playlist:$id"
    }

    data class ArtistItems(
        val parentArtistId: String,
        val endpoint: BrowseEndpoint,
    ) : DesktopBrowseRoute {
        override val cacheId = "artist-items:$parentArtistId:${endpoint.browseId}:${endpoint.params.orEmpty()}"
    }
}

/**
 * Bounded application-scoped cache for expensive browse/detail pages.
 *
 * Navigation keeps only [DesktopBrowseRoute] values. Parsed pages are retained here, capped by
 * [maxPages], so returning to a recent detail is instant without allowing a long browsing session
 * to retain every album/artist/playlist ever opened.
 */
internal class DesktopBrowseRepository(
    private val backend: ArchiveTuneJvmClient,
    private val coordinator: DesktopResourceCoordinator,
    private val accountScopeProvider: () -> String,
    private val maxPages: Int = 24,
) {
    private data class CacheEntry(val value: Any)

    private val cacheLock = Any()
    private val pages = object : LinkedHashMap<String, CacheEntry>(maxPages + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > maxPages
    }

    init {
        require(maxPages > 0) { "Browse cache must keep at least one page" }
    }

    suspend fun album(route: DesktopBrowseRoute.Album, forceRefresh: Boolean = false): Result<AlbumPage> =
        load(route, forceRefresh) { backend.album(route.browseId) }

    suspend fun artist(route: DesktopBrowseRoute.Artist, forceRefresh: Boolean = false): Result<ArtistPage> =
        load(route, forceRefresh) { backend.artist(route.id) }

    suspend fun playlist(route: DesktopBrowseRoute.Playlist, forceRefresh: Boolean = false): Result<PlaylistPage> =
        load(route, forceRefresh) { backend.playlist(route.id) }

    suspend fun artistItems(route: DesktopBrowseRoute.ArtistItems, forceRefresh: Boolean = false): Result<ArtistItemsPage> =
        load(route, forceRefresh) { backend.artistItems(route.endpoint) }

    fun cachedAlbum(route: DesktopBrowseRoute.Album): AlbumPage? = cached(route)
    fun cachedArtist(route: DesktopBrowseRoute.Artist): ArtistPage? = cached(route)
    fun cachedPlaylist(route: DesktopBrowseRoute.Playlist): PlaylistPage? = cached(route)
    fun cachedArtistItems(route: DesktopBrowseRoute.ArtistItems): ArtistItemsPage? = cached(route)

    fun invalidate(route: DesktopBrowseRoute) {
        synchronized(cacheLock) { pages.remove(scopedKey(route)) }
    }

    fun clearAccountCache() {
        synchronized(cacheLock) { pages.clear() }
    }

    internal fun cachedPageCount(): Int = synchronized(cacheLock) { pages.size }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(route: DesktopBrowseRoute): T? =
        synchronized(cacheLock) { pages[scopedKey(route)]?.value as? T }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> load(
        route: DesktopBrowseRoute,
        forceRefresh: Boolean,
        request: suspend () -> Result<T>,
    ): Result<T> {
        val key = scopedKey(route)
        if (!forceRefresh) {
            synchronized(cacheLock) { pages[key]?.value as? T }?.let { return Result.success(it) }
        }

        return coordinator.singleFlight(
            key = "browse:$key",
            priority = DesktopRequestPriority.FOREGROUND,
        ) { request() }.onSuccess { page ->
            synchronized(cacheLock) { pages[key] = CacheEntry(page) }
        }
    }

    private fun scopedKey(route: DesktopBrowseRoute): String =
        "${accountScopeProvider()}:${route.cacheId}"
}