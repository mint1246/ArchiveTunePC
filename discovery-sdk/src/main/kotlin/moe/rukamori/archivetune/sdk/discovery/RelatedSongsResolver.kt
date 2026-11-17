/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import kotlinx.coroutines.CancellationException

/**
 * Multi-stage related-song fallback coordinator.
 *
 * Resolution order for a seed song:
 * 1. Primary radio/related songs (`related + nextItems` filtered for the seed).
 * 2. Search fallback filtered for the seed.
 * 3. Local cached related songs filtered for the seed.
 * 4. If all stages yield no valid recommendations, returns `Result.success(emptyList())` when the
 *    primary load succeeded, or `Result.failure(primaryError)` when the primary load failed.
 *
 * [CancellationException] is always rethrown immediately at every stage.
 */
object RelatedSongsResolver {
    data class PrimaryOutcome<out Song>(
        val songs: List<Song>,
        val error: Throwable? = null,
    )

    suspend fun <Song> resolve(
        seedSongId: String,
        idOf: (Song) -> String,
        titleOf: (Song) -> String,
        hasArtists: (Song) -> Boolean = { true },
        loadPrimary: suspend () -> PrimaryOutcome<Song>,
        loadSearchFallback: suspend () -> Result<List<Song>>,
        loadLocalFallback: suspend () -> List<Song>,
    ): Result<List<Song>> {
        val primary = loadPrimary()
        primary.error?.let { throwable ->
            if (throwable is CancellationException) throw throwable
        }
        val radioSongs =
            QuickPickSeedPolicy.filterValidRecommendations(
                songs = primary.songs,
                seedSongId = seedSongId,
                idOf = idOf,
                titleOf = titleOf,
                hasArtists = hasArtists,
            )
        if (radioSongs.isNotEmpty()) return Result.success(radioSongs)

        val searchResult = loadSearchFallback()
        searchResult.exceptionOrNull()?.let { throwable ->
            if (throwable is CancellationException) throw throwable
        }
        val searchedSongs =
            QuickPickSeedPolicy.filterValidRecommendations(
                songs = searchResult.getOrDefault(emptyList()),
                seedSongId = seedSongId,
                idOf = idOf,
                titleOf = titleOf,
                hasArtists = hasArtists,
            )
        if (searchedSongs.isNotEmpty()) return Result.success(searchedSongs)

        val cachedSongs =
            QuickPickSeedPolicy.filterValidRecommendations(
                songs = loadLocalFallback(),
                seedSongId = seedSongId,
                idOf = idOf,
                titleOf = titleOf,
                hasArtists = hasArtists,
            )
        if (cachedSongs.isNotEmpty()) return Result.success(cachedSongs)

        return primary.error?.let { throwable -> Result.failure(throwable) }
            ?: Result.success(emptyList())
    }
}
