/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube.home

import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem

/** Local, account-derived content for the Home surface, mirroring Android's Room-backed sections. */
data class DesktopHomeLocalContent(
    val quickPicks: List<SongItem> = emptyList(),
    val keepListeningSongs: List<SongItem> = emptyList(),
    val keepListeningAlbums: List<AlbumItem> = emptyList(),
    val keepListeningArtists: List<ArtistItem> = emptyList(),
    val forgottenFavorites: List<SongItem> = emptyList(),
)

/** A "similar to X" shelf: a seed title plus related items fetched from YouTube. */
data class DesktopSimilarRecommendation(
    val title: YTItem,
    val items: List<YTItem>,
)

fun computeLocalHomeContent(
    snapshot: DesktopHomeSnapshot,
    now: Long = System.currentTimeMillis(),
): DesktopHomeLocalContent {
    val eventsBySong =
        snapshot.playHistory
            .groupBy { it.song.id }

    val recencyWeightedPlayTimeBySong =
        eventsBySong.mapValues { (_, events) ->
            events.sumOf { event ->
                event.playTimeMs * recencyMultiplier(now - event.playedAtEpochMillis)
            }
        }
    val lastPlayedAtBySong =
        eventsBySong.mapValues { (_, events) -> events.maxOf { it.playedAtEpochMillis } }

    val rankedSongs =
        snapshot.playHistory
            .map { it.song }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<SongItem> { recencyWeightedPlayTimeBySong[it.id] ?: 0L }
                    .thenByDescending { lastPlayedAtBySong[it.id] ?: 0L },
            )

    // Match Android's quickPicks() seed selection:
    //   - last 5 play events
    //   - top 5 songs by play time during the last 7 days
    //   - top 10 songs by all-time play time
    // Candidates are related songs for those seeds, ranked by how many seeds refer to them.
    val quickPickSeedIds = quickPickSeedIds(snapshot, now)

    val quickPickCandidates =
        quickPickSeedIds
            .flatMap { seedId -> snapshot.relatedSongsBySeed[seedId].orEmpty() }
            .groupBy(SongItem::id)
            .values
            .sortedByDescending(List<SongItem>::size)
            .take(100)
            .map(List<SongItem>::first)

    // Android's HomeViewModel samples 20 at random after the DAO returns its top 100 candidates.
    // It falls back to recent/all-known songs only when no related candidates exist yet.
    val quickPicks =
        if (quickPickCandidates.isNotEmpty()) {
            quickPickCandidates.shuffled().take(20)
        } else {
            val recentFallback =
                snapshot.playHistory
                    .sortedByDescending { it.playedAtEpochMillis }
                    .map { it.song }
                    .distinctBy(SongItem::id)
                    .take(60)
                    .shuffled()
                    .take(20)
            recentFallback.ifEmpty {
                (
                    snapshot.playHistory.map { it.song } +
                        snapshot.likedSongs +
                        snapshot.librarySongs +
                        snapshot.relatedSongsBySeed.values.flatten()
                )
                    .distinctBy(SongItem::id)
                    .shuffled()
                    .take(20)
            }
        }

    val forgottenFavorites =
        snapshot.playHistory
            .groupBy { it.song.id }
            .mapNotNull { (_, events) ->
                val song = events.first().song
                val recent = events.filter { it.playedAtEpochMillis > now - RECENT_WINDOW_MS }.sumOf { it.playTimeMs.toLong() }
                val older = events.filter { it.playedAtEpochMillis <= now - RECENT_WINDOW_MS }.sumOf { it.playTimeMs.toLong() }
                song.takeIf { older > 0L && recent * 5 < older }
            }
            .shuffled()
            .take(20)

    return DesktopHomeLocalContent(
        quickPicks = quickPicks,
        keepListeningSongs = rankedSongs.shuffled().take(10),
        keepListeningAlbums = snapshot.likedAlbums.shuffled().take(5),
        keepListeningArtists = snapshot.subscribedArtists.shuffled().take(5),
        forgottenFavorites = forgottenFavorites,
    )
}

/** Exact seed buckets used by Android's DatabaseDao.quickPicks(). */
fun quickPickSeedIds(
    snapshot: DesktopHomeSnapshot,
    now: Long = System.currentTimeMillis(),
): Set<String> {
    val recentCutoff = now - QUICK_PICKS_RECENT_WINDOW_MS
    val lastFiveSeedIds = snapshot.playHistory.take(5).map { it.song.id }
    val recentTopFiveSeedIds =
        snapshot.playHistory
            .filter { it.playedAtEpochMillis > recentCutoff }
            .groupBy { it.song.id }
            .entries
            .sortedByDescending { (_, events) -> events.sumOf { it.playTimeMs } }
            .take(5)
            .map { it.key }
    val allTimeTopTenSeedIds =
        snapshot.playHistory
            .groupBy { it.song.id }
            .entries
            .sortedByDescending { (_, events) -> events.sumOf { it.playTimeMs } }
            .take(10)
            .map { it.key }

    return linkedSetOf<String>().apply {
        addAll(lastFiveSeedIds)
        addAll(recentTopFiveSeedIds)
        addAll(allTimeTopTenSeedIds)
    }
}

/**
 * Orders new-release albums by artist preference, mirroring Android's Explore screen: favourite
 * (subscribed) artists first, then remaining artists by total play time, then unknown artists.
 */
fun sortNewReleasesByArtistPreference(
    albums: List<AlbumItem>,
    snapshot: DesktopHomeSnapshot,
): List<AlbumItem> {
    if (snapshot.subscribedArtists.isEmpty() && snapshot.playHistory.isEmpty()) return albums

    val playTimeByArtistId = HashMap<String, Long>()
    snapshot.playHistory.forEach { event ->
        event.song.artists.forEach { artist ->
            artist.id?.takeIf(String::isNotBlank)?.let { id ->
                playTimeByArtistId[id] = (playTimeByArtistId[id] ?: 0L) + event.playTimeMs
            }
        }
    }

    val favouriteArtistIds = snapshot.subscribedArtists.map(ArtistItem::id).toHashSet()
    val favouriteRank =
        snapshot.subscribedArtists
            .map(ArtistItem::id)
            .distinct()
            .sortedByDescending { playTimeByArtistId[it] ?: 0L }
    val nonFavouriteRank =
        playTimeByArtistId.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .filterNot(favouriteArtistIds::contains)

    return albums.sortedBy { album ->
        val artistIds = album.artists.orEmpty().mapNotNull { it.id?.takeIf(String::isNotBlank) }
        val favouriteIndex = favouriteRank.indexOfFirst(artistIds::contains)
        if (favouriteIndex >= 0) {
            favouriteIndex
        } else {
            val nonFavouriteIndex = nonFavouriteRank.indexOfFirst(artistIds::contains)
            if (nonFavouriteIndex >= 0) favouriteRank.size + nonFavouriteIndex else Int.MAX_VALUE
        }
    }
}

private const val RECENT_WINDOW_MS = 86400_000L * 30L
private const val QUICK_PICKS_RECENT_WINDOW_MS = 86400_000L * 7L
private const val DAY_MS = 86400_000L

private fun recencyMultiplier(ageMs: Long): Long =
    when {
        ageMs <= DAY_MS * 7 -> 10L
        ageMs <= DAY_MS * 30 -> 5L
        ageMs <= DAY_MS * 90 -> 2L
        else -> 1L
    }
