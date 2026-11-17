/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.home

import android.content.Context
import com.google.common.collect.ImmutableList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.QuickPicks
import moe.rukamori.archivetune.constants.QuickPicksKey
import moe.rukamori.archivetune.constants.QuickPicksDisplayMode
import moe.rukamori.archivetune.constants.QuickPicksDisplayModeKey
import moe.rukamori.archivetune.constants.ShowHomeCategoryChipsKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.extensions.toEnum
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.sdk.discovery.QuickPickSeedPolicy
import moe.rukamori.archivetune.sdk.discovery.RelatedSongsResolver
import moe.rukamori.archivetune.utils.dataStore
import timber.log.Timber
import javax.inject.Inject

class HomeRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val database: MusicDatabase,
    ) {
        val showCategoryChips: Flow<Boolean> =
            context.dataStore.data
                .map { preferences -> preferences[ShowHomeCategoryChipsKey] ?: true }
                .distinctUntilChanged()

        val quickPicksDisplayMode: Flow<QuickPicksDisplayMode> =
            context.dataStore.data
                .map { preferences ->
                    preferences[QuickPicksDisplayModeKey].toEnum(QuickPicksDisplayMode.CARD)
                }.distinctUntilChanged()

        val quickPicksMode: Flow<QuickPicks> =
            context.dataStore.data
                .map { preferences -> preferences[QuickPicksKey].toEnum(QuickPicks.QUICK_PICKS) }
                .distinctUntilChanged()

        val showTonalBackdrop: Flow<Boolean> =
            context.dataStore.data
                .map { preferences -> preferences[DisableBlurKey] != true }
                .distinctUntilChanged()

        suspend fun loadQuickPickSeeds(limit: Int): List<QuickPickSeed> {
            val mostPlayed =
                database
                    .mostPlayedSongs(fromTimeStamp = 0L, limit = limit * 3)
                    .first()
                    .filter { song -> song.isYouTubeRecommendationSeed() }
            val localCandidates =
                mostPlayed.ifEmpty {
                    database
                        .recentSongs(limit = limit * 6)
                        .first()
                        .filter { song -> song.isYouTubeRecommendationSeed() }
                }
            val localSeeds =
                QuickPickSeedPolicy.selectLocalSeeds(
                    candidates = localCandidates.map { song -> song.toQuickPickSeed() },
                    limit = limit,
                    primaryArtistKeyOf = QuickPickSeed::primaryArtistKey,
                )
            if (localSeeds.size >= limit) return localSeeds

            val remoteSeeds = loadRemoteQuickPickSeeds(limit * REMOTE_HISTORY_LOOKUP_MULTIPLIER)
            return QuickPickSeedPolicy.mergeSeeds(
                localSeeds = localSeeds,
                remoteSeeds = remoteSeeds,
                limit = limit,
                idOf = QuickPickSeed::id,
                primaryArtistKeyOf = QuickPickSeed::primaryArtistKey,
            )
        }

        suspend fun loadRelatedSongs(seed: QuickPickSeed): Result<List<SongItem>> =
            RelatedSongsResolver.resolve(
                seedSongId = seed.id,
                idOf = SongItem::id,
                titleOf = SongItem::title,
                hasArtists = { song -> song.artists.isNotEmpty() },
                loadPrimary = {
                    val nextResult = YouTube.next(WatchEndpoint(videoId = seed.id))
                    nextResult.exceptionOrNull()?.let { throwable ->
                        if (throwable is CancellationException) throw throwable
                    }
                    val nextPage = nextResult.getOrNull()
                    val relatedSongs =
                        nextPage
                            ?.relatedEndpoint
                            ?.let { endpoint -> YouTube.related(endpoint).getOrNullPreservingCancellation()?.songs }
                            .orEmpty()
                    RelatedSongsResolver.PrimaryOutcome(
                        songs = relatedSongs + nextPage?.items.orEmpty(),
                        error = nextResult.exceptionOrNull(),
                    )
                },
                loadSearchFallback = {
                    YouTube
                        .search(
                            query = seed.searchQuery,
                            filter = YouTube.SearchFilter.FILTER_SONG,
                            useAccountContext = true,
                        ).map { page -> page.items.filterIsInstance<SongItem>() }
                },
                loadLocalFallback = { loadCachedRelatedSongs(seed.id) },
            )

        suspend fun loadLibrarySongIds(): Set<String> = database.librarySongIds().toSet()

        suspend fun loadPlaylistPreview(
            playlistId: String,
            limit: Int,
        ): Result<List<SongItem>> {
            val result = YouTube.playlist(playlistId)
            result.exceptionOrNull()?.let { throwable ->
                if (throwable is CancellationException) throw throwable
                Timber.w(throwable, "Failed to load Home playlist preview for %s", playlistId)
            }
            return result.map { page -> page.songs.take(limit.coerceAtLeast(0)) }
        }

        private suspend fun loadRemoteQuickPickSeeds(limit: Int): List<QuickPickSeed> {
            val songs =
                YouTube
                    .musicHistory()
                    .getOrNullPreservingCancellation()
                    ?.sections
                    .orEmpty()
                    .flatMap { section -> section.songs }
            return QuickPickSeedPolicy
                .rankRemoteSeeds(
                    songs = songs,
                    limit = limit,
                    idOf = SongItem::id,
                    artistKeysOf = { song ->
                        song.artists.map { artist ->
                            QuickPickSeedPolicy.artistRecommendationKey(artist.id, artist.name)
                        }
                    },
                ).map { song -> song.toQuickPickSeed() }
        }

        private suspend fun loadCachedRelatedSongs(seedSongId: String): List<SongItem> =
            QuickPickSeedPolicy.filterValidRecommendations(
                songs =
                    database
                        .getRelatedSongs(seedSongId)
                        .first()
                        .filter { song -> song.isYouTubeRecommendationSeed() }
                        .map { song -> song.toSongItem() },
                seedSongId = seedSongId,
                idOf = SongItem::id,
                titleOf = SongItem::title,
                hasArtists = { song -> song.artists.isNotEmpty() },
            )

        private fun Song.toQuickPickSeed(): QuickPickSeed =
            QuickPickSeed(
                id = id,
                title = title,
                artistNames = ImmutableList.copyOf(artists.map { artist -> artist.name }),
                artistIds = ImmutableList.copyOf(artists.map { artist -> artist.id }),
            )

        private fun SongItem.toQuickPickSeed(): QuickPickSeed =
            QuickPickSeed(
                id = id,
                title = title,
                artistNames = ImmutableList.copyOf(artists.map(Artist::name)),
                artistIds = ImmutableList.copyOf(artists.mapNotNull(Artist::id)),
            )

        private fun Song.toSongItem(): SongItem =
            SongItem(
                id = id,
                title = title,
                artists = artists.map { artist -> Artist(name = artist.name, id = artist.id) },
                album = album?.let { album -> Album(name = album.title, id = album.id) },
                duration = song.duration.takeIf { duration -> duration >= 0 },
                thumbnail = song.thumbnailUrl.orEmpty(),
                explicit = song.explicit,
                endpoint = WatchEndpoint(videoId = id),
            )

        private fun Song.isYouTubeRecommendationSeed(): Boolean =
            !song.isLocal && !song.isPodcast && QuickPickSeedPolicy.isValidVideoId(id)

        private fun <T> Result<T>.getOrNullPreservingCancellation(): T? {
            exceptionOrNull()?.let { throwable ->
                if (throwable is CancellationException) throw throwable
            }
            return getOrNull()
        }

        private companion object {
            const val REMOTE_HISTORY_LOOKUP_MULTIPLIER = 6
        }
    }
