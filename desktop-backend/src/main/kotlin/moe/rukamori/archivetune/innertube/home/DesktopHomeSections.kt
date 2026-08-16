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
    val playTimeBySong =
        snapshot.playHistory
            .groupBy { it.song.id }
            .mapValues { (_, events) -> events.sumOf { it.playTimeMs.toLong() } }

    val rankedSongs =
        snapshot.playHistory
            .map { it.song }
            .distinctBy { it.id }
            .sortedByDescending { playTimeBySong[it.id] ?: 0L }

    val quickPicks =
        (snapshot.likedSongs + rankedSongs)
            .distinctBy { it.id }
            .shuffled()
            .take(20)

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
