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
import moe.rukamori.archivetune.innertube.pages.ArtistItemsContinuationPage
import moe.rukamori.archivetune.innertube.pages.ArtistItemsPage
import moe.rukamori.archivetune.innertube.pages.ArtistPage
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import moe.rukamori.archivetune.innertube.pages.PodcastPage

sealed interface DesktopBrowseRoute {
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

    data class Podcast(val browseId: String) : DesktopBrowseRoute {
        override val cacheId = "podcast:$browseId"
    }

    data class ArtistItems(
        val parentArtistId: String,
        val endpoint: BrowseEndpoint,
    ) : DesktopBrowseRoute {
        override val cacheId = "artist-items:$parentArtistId:${endpoint.browseId}:${endpoint.params.orEmpty()}"
    }
}

internal class DesktopBrowsePageCache(
    private val accountScopeProvider: () -> String,
    private val maxPages: Int,
) {
    internal data class LoadToken(
        val scopedKey: String,
        val revision: Long,
    ) {
        val singleFlightKey: String = "browse:$scopedKey:r$revision"
    }

    private data class CacheEntry(val value: Any)

    private val lock = Any()
    private var nextRevision = 0L
    private var globalRevision = 0L
    private val routeRevisions = mutableMapOf<String, Long>()
    private val pages = object : LinkedHashMap<String, CacheEntry>(maxPages + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > maxPages
    }

    init {
        require(maxPages > 0) { "Browse cache must keep at least one page" }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(route: DesktopBrowseRoute): T? = synchronized(lock) {
        pages[scopedKey(route)]?.value as? T
    }

    fun beginLoad(route: DesktopBrowseRoute): LoadToken = synchronized(lock) {
        val key = scopedKey(route)
        LoadToken(key, routeRevisions[key] ?: globalRevision)
    }

    fun putIfCurrent(token: LoadToken, value: Any) = synchronized(lock) {
        val currentRevision = routeRevisions[token.scopedKey] ?: globalRevision
        if (currentRevision == token.revision) pages[token.scopedKey] = CacheEntry(value)
    }

    fun invalidate(route: DesktopBrowseRoute) = synchronized(lock) {
        val key = scopedKey(route)
        pages.remove(key)
        routeRevisions[key] = ++nextRevision
    }

    fun clear() = synchronized(lock) {
        pages.clear()
        globalRevision = ++nextRevision
        routeRevisions.clear()
    }

    fun size(): Int = synchronized(lock) { pages.size }

    private fun scopedKey(route: DesktopBrowseRoute): String = "${accountScopeProvider()}:${route.cacheId}"
}

/**
 * Bounded application-scoped cache for expensive browse/detail pages.
 *
 * Navigation keeps only [DesktopBrowseRoute] values. Parsed pages are retained here, capped by
 * [maxPages], so returning to a recent detail is instant without allowing a long browsing session
 * to retain every album/artist/playlist ever opened.
 */
class DesktopBrowseRepository(
    private val backend: ArchiveTuneJvmClient,
    private val coordinator: DesktopResourceCoordinator,
    private val accountScopeProvider: () -> String,
    private val maxPages: Int = 24,
) {
    private val cache = DesktopBrowsePageCache(accountScopeProvider, maxPages)

    suspend fun album(route: DesktopBrowseRoute.Album, forceRefresh: Boolean = false): Result<AlbumPage> =
        load(route, forceRefresh) { backend.album(route.browseId) }

    suspend fun artist(route: DesktopBrowseRoute.Artist, forceRefresh: Boolean = false): Result<ArtistPage> =
        load(route, forceRefresh) { backend.artist(route.id) }

    suspend fun playlist(route: DesktopBrowseRoute.Playlist, forceRefresh: Boolean = false): Result<PlaylistPage> =
        load(route, forceRefresh) { backend.playlist(route.id) }

    suspend fun podcast(route: DesktopBrowseRoute.Podcast, podcasts: DesktopPodcastRepository, forceRefresh: Boolean = false): Result<PodcastPage> =
        load(route, forceRefresh) { podcasts.podcast(route.browseId) }

    suspend fun artistItems(route: DesktopBrowseRoute.ArtistItems, forceRefresh: Boolean = false): Result<ArtistItemsPage> =
        load(route, forceRefresh) { backend.artistItems(route.endpoint) }

    suspend fun artistItemsContinuation(continuation: String): Result<ArtistItemsContinuationPage> =
        coordinator.singleFlight(
            key = "browse:${accountScopeProvider()}:artist-items-continuation:$continuation",
            priority = DesktopRequestPriority.FOREGROUND,
        ) { backend.artistItemsContinuation(continuation) }

    fun cachedAlbum(route: DesktopBrowseRoute.Album): AlbumPage? = cache.get(route)
    fun cachedArtist(route: DesktopBrowseRoute.Artist): ArtistPage? = cache.get(route)
    fun cachedPlaylist(route: DesktopBrowseRoute.Playlist): PlaylistPage? = cache.get(route)
    fun cachedPodcast(route: DesktopBrowseRoute.Podcast): PodcastPage? = cache.get(route)
    fun cachedArtistItems(route: DesktopBrowseRoute.ArtistItems): ArtistItemsPage? = cache.get(route)

    fun invalidate(route: DesktopBrowseRoute) = cache.invalidate(route)

    fun clearAccountCache() = cache.clear()

    internal fun cachedPageCount(): Int = cache.size()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> load(
        route: DesktopBrowseRoute,
        forceRefresh: Boolean,
        request: suspend () -> Result<T>,
    ): Result<T> {
        if (!forceRefresh) {
            cache.get<T>(route)?.let { return Result.success(it) }
        }

        val token = cache.beginLoad(route)
        return coordinator.singleFlight(
            key = token.singleFlightKey,
            priority = DesktopRequestPriority.FOREGROUND,
        ) { request() }.onSuccess { page ->
            cache.putIfCurrent(token, page)
        }
    }
}
