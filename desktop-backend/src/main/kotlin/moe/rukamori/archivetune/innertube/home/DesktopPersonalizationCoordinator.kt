/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube.home

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.cache.DesktopRequestPriority
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.pages.ArtistPage
import moe.rukamori.archivetune.innertube.pages.LibraryPage
import moe.rukamori.archivetune.innertube.pages.NextResult
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import moe.rukamori.archivetune.innertube.pages.RelatedPage
import moe.rukamori.archivetune.playback.api.PlaybackItem

interface DesktopPersonalizationBackend {
    suspend fun likedSongs(): Result<PlaylistPage>
    suspend fun librarySongs(): Result<LibraryPage>
    suspend fun libraryAlbums(): Result<LibraryPage>
    suspend fun libraryArtists(): Result<LibraryPage>
    suspend fun artist(browseId: String): Result<ArtistPage>
    suspend fun next(videoId: String): Result<NextResult>
    suspend fun related(endpoint: BrowseEndpoint): Result<RelatedPage>
}

private class ArchiveTunePersonalizationBackend(
    private val client: ArchiveTuneJvmClient,
) : DesktopPersonalizationBackend {
    override suspend fun likedSongs(): Result<PlaylistPage> = client.likedSongs()
    override suspend fun librarySongs(): Result<LibraryPage> = client.librarySongs()
    override suspend fun libraryAlbums(): Result<LibraryPage> = client.libraryAlbums()
    override suspend fun libraryArtists(): Result<LibraryPage> = client.libraryArtists()
    override suspend fun artist(browseId: String): Result<ArtistPage> = client.artist(browseId)
    override suspend fun next(videoId: String): Result<NextResult> = client.next(videoId)
    override suspend fun related(endpoint: BrowseEndpoint): Result<RelatedPage> = client.related(endpoint)
}

/**
 * Coordinates headless Home personalization: library sync, "Similar to ..." shelf assembly,
 * single-flight related-song caching, and bounded play-history event recording.
 */
class DesktopPersonalizationCoordinator(
    private val backend: DesktopPersonalizationBackend,
    private val homeStore: DesktopHomeStore,
    private val coordinator: DesktopResourceCoordinator,
    private val scope: CoroutineScope,
    private val accountScopeProvider: () -> String,
    private val isSignedInProvider: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        backend: ArchiveTuneJvmClient,
        homeStore: DesktopHomeStore,
        coordinator: DesktopResourceCoordinator,
        scope: CoroutineScope,
        accountScopeProvider: () -> String,
        isSignedInProvider: () -> Boolean,
        now: () -> Long = System::currentTimeMillis,
    ) : this(
        backend = ArchiveTunePersonalizationBackend(backend),
        homeStore = homeStore,
        coordinator = coordinator,
        scope = scope,
        accountScopeProvider = accountScopeProvider,
        isSignedInProvider = isSignedInProvider,
        now = now,
    )

    private val relatedSongFetches = ConcurrentHashMap.newKeySet<String>()
    private val _similarRecommendations = MutableStateFlow<List<DesktopSimilarRecommendation>>(emptyList())
    val similarRecommendations: StateFlow<List<DesktopSimilarRecommendation>> = _similarRecommendations.asStateFlow()

    private val playHistoryLock = Any()
    private var playHistoryCurrent: Pair<SongItem, Long>? = null

    fun clearAccountData() {
        homeStore.clearAccountData()
        _similarRecommendations.value = emptyList()
    }

    /** Syncs liked songs, library songs, liked albums and subscribed artists into the home store. */
    suspend fun syncHomeLibrary() {
        if (!isSignedInProvider()) {
            clearAccountData()
            return
        }
        backend.likedSongs().onSuccess { homeStore.replaceLikedSongs(it.songs.distinctBy(SongItem::id)) }
        backend.librarySongs().onSuccess { homeStore.replaceLibrarySongs(it.items.filterIsInstance<SongItem>().distinctBy(SongItem::id)) }
        backend.libraryAlbums().onSuccess { homeStore.replaceLikedAlbums(it.items.filterIsInstance<AlbumItem>().distinctBy(AlbumItem::id)) }
        backend.libraryArtists().onSuccess { homeStore.replaceSubscribedArtists(it.items.filterIsInstance<ArtistItem>().distinctBy(ArtistItem::id)) }
    }

    /**
     * Builds "Similar to ..." shelves from the user's most-played artists and songs, mirroring
     * Android's loadSimilarRecommendations. Artist seeds expand their top sections; song seeds use
     * the related-items endpoint.
     */
    suspend fun loadSimilarRecommendations() {
        val snapshot = homeStore.snapshot.value
        val accountScope = accountScopeProvider()
        val playTimeBySong =
            snapshot.playHistory.groupBy { it.song.id }.mapValues { (_, events) -> events.sumOf { it.playTimeMs.toLong() } }
        val mostPlayedSongs =
            snapshot.playHistory.map { it.song }.distinctBy { it.id }
                .sortedByDescending { playTimeBySong[it.id] ?: 0L }
                .take(2)
        val recommendations = mutableListOf<DesktopSimilarRecommendation>()

        // Artist seeds, mirroring Android's artistRecommendations: expand the last two sections of
        // each most-played artist's page into a "Similar to ..." shelf.
        val playTimeByArtist = mutableMapOf<String, Pair<Artist, Long>>()
        snapshot.playHistory.forEach { event ->
            event.song.artists.forEach { artist ->
                artist.id?.let { artistId ->
                    val previous = playTimeByArtist[artistId]
                    playTimeByArtist[artistId] = artist to ((previous?.second ?: 0L) + event.playTimeMs)
                }
            }
        }
        val mostPlayedArtists =
            playTimeByArtist.values
                .sortedByDescending { it.second }
                .map { it.first }
                .distinctBy { it.id }
                .take(3)
        for (artist in mostPlayedArtists) {
            val artistId = artist.id ?: continue
            val page =
                coordinator.singleFlight(
                    "recommendation-artist:$accountScope:$artistId",
                    DesktopRequestPriority.MAINTENANCE,
                ) { backend.artist(artistId) }.getOrNull() ?: continue
            val items =
                (page.sections.getOrNull(page.sections.size - 2)?.items.orEmpty() + page.sections.lastOrNull()?.items.orEmpty())
                    .shuffled()
                    .takeIf { it.isNotEmpty() }
                    ?: continue
            recommendations += DesktopSimilarRecommendation(title = page.artist, items = items)
        }

        // Song seeds: fetch related artists/albums/playlists through the related-items endpoint.
        for (song in mostPlayedSongs) {
            val relatedEndpoint =
                coordinator.singleFlight(
                    "related-next:$accountScope:${song.id}",
                    DesktopRequestPriority.MAINTENANCE,
                ) { backend.next(song.id) }.getOrNull()?.relatedEndpoint ?: continue
            val page =
                coordinator.singleFlight(
                    "related-page:$accountScope:${song.id}",
                    DesktopRequestPriority.MAINTENANCE,
                ) { backend.related(relatedEndpoint) }.getOrNull() ?: continue
            val items =
                (page.songs.shuffled().take(8) + page.albums.shuffled().take(4) + page.artists.shuffled().take(4) + page.playlists.shuffled().take(4))
                    .shuffled()
                    .takeIf { it.isNotEmpty() }
                    ?: continue
            recommendations += DesktopSimilarRecommendation(title = song, items = items)
        }

        _similarRecommendations.value = recommendations
    }

    /** Mirrors Android MusicService: cache related songs the first time a played seed is seen. */
    suspend fun cacheRelatedSongsIfNeeded(videoId: String) {
        if (homeStore.hasRelatedSongs(videoId)) return
        if (!relatedSongFetches.add(videoId)) return
        try {
            val accountScope = accountScopeProvider()
            val relatedEndpoint =
                coordinator.singleFlight(
                    "related-next:$accountScope:$videoId",
                    DesktopRequestPriority.MAINTENANCE,
                ) { backend.next(videoId) }.getOrNull()?.relatedEndpoint ?: return
            val relatedSongs =
                coordinator.singleFlight(
                    "related-page:$accountScope:$videoId",
                    DesktopRequestPriority.MAINTENANCE,
                ) { backend.related(relatedEndpoint) }.getOrNull()?.songs.orEmpty()
            if (relatedSongs.isNotEmpty()) {
                homeStore.replaceRelatedSongs(videoId, relatedSongs)
            }
        } finally {
            relatedSongFetches.remove(videoId)
        }
    }

    /** Backfills Android-style related rows for desktop listens recorded before this cache existed. */
    suspend fun warmQuickPickRelatedCache() {
        for (videoId in quickPickSeedIds(homeStore.snapshot.value)) {
            cacheRelatedSongsIfNeeded(videoId)
        }
    }

    fun onMediaTransition(current: PlaybackItem?, atEpochMillis: Long = now()) {
        flushPlayHistoryCurrent(atEpochMillis)
        val song = current?.let(::songFromPlaybackItem)?.takeIf { it.id.isNotBlank() } ?: return
        synchronized(playHistoryLock) {
            playHistoryCurrent = song to atEpochMillis
        }
        scope.launch { cacheRelatedSongsIfNeeded(song.id) }
    }

    fun flushPlayHistoryCurrent(atEpochMillis: Long = now()) {
        val current = synchronized(playHistoryLock) {
            playHistoryCurrent.also { playHistoryCurrent = null }
        } ?: return
        val playTimeMs = (atEpochMillis - current.second).coerceIn(0L, MAX_PLAY_HISTORY_EVENT_MS)
        if (playTimeMs < MIN_PLAY_HISTORY_EVENT_MS) return
        homeStore.recordPlay(DesktopPlayEvent(current.first, current.second, playTimeMs))
    }

    fun songFromPlaybackItem(item: PlaybackItem): SongItem =
        SongItem(
            id = item.metadata.id,
            title = item.metadata.title,
            artists = item.metadata.artists.map { Artist(id = it.id, name = it.name) },
            album = item.metadata.album?.let { Album(id = it.id.orEmpty(), name = it.title) },
            duration = item.metadata.durationHintMs?.div(1_000L)?.toInt(),
            thumbnail = item.metadata.artworkUri.orEmpty(),
            explicit = item.metadata.explicit,
        )

    companion object {
        const val MIN_PLAY_HISTORY_EVENT_MS = 5_000L
        const val MAX_PLAY_HISTORY_EVENT_MS = 1_800_000L
    }
}
