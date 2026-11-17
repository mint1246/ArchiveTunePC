/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import moe.rukamori.archivetune.statistics.AndroidStatisticsMedia
import moe.rukamori.archivetune.statistics.AndroidStatisticsOverview
import moe.rukamori.archivetune.statistics.AndroidStatisticsQueryService
import moe.rukamori.archivetune.statistics.AndroidStatisticsRange
import moe.rukamori.archivetune.statistics.AndroidStatisticsStore
import moe.rukamori.archivetune.statistics.AndroidStatisticsTarget

/** Builds the same complete social statistics payload desktop publishes. */
fun AndroidStatisticsStore.socialSnapshot(nowEpochMs: Long = System.currentTimeMillis()): SocialStatisticsSnapshot =
    SocialStatisticsSnapshot(
        ranges =
            AndroidStatisticsQueryService(this)
                .allRangeOverviews(nowEpochMs = nowEpochMs)
                .entries
                .associate { (range, overview) ->
                    range.name to overview.toSocialRange(range)
                },
        updatedAt = nowEpochMs,
    )

private fun AndroidStatisticsOverview.toSocialRange(range: AndroidStatisticsRange): SocialStatisticsRangeSnapshot =
    SocialStatisticsRangeSnapshot(
        summary =
            SocialStatisticsSummary(
                totalListeningMillis = summary.totalListeningMs,
                totalPlays = summary.totalPlays,
                uniqueSongs = summary.uniqueSongs,
                uniqueArtists = summary.uniqueArtists,
                uniqueAlbums = summary.uniqueAlbums,
            ),
        favoriteArtist = topArtists.firstOrNull()?.toSocialMedia(),
        favoriteSong = topSongs.firstOrNull()?.toSocialMedia(),
        dailyListening =
            dailyListening
                .takeLast(range.maxSocialDailyBuckets())
                .map { SocialStatisticsBucket(it.label, it.listeningMs) },
        listeningByDayOfWeek = listeningByDayOfWeek.take(7).map { SocialStatisticsBucket(it.label, it.listeningMs) },
        listeningByHour = listeningByHour.take(24).map { SocialStatisticsBucket(it.label, it.listeningMs) },
        topSongs = topSongs.take(20).map(AndroidStatisticsMedia::toSocialMedia),
        topArtists = topArtists.take(20).map(AndroidStatisticsMedia::toSocialMedia),
        topAlbums = topAlbums.take(20).map(AndroidStatisticsMedia::toSocialMedia),
    )

private fun AndroidStatisticsMedia.toSocialMedia(): SocialStatisticsMedia =
    SocialStatisticsMedia(
        id = when (val destination = target) {
            is AndroidStatisticsTarget.Song -> destination.id
            is AndroidStatisticsTarget.Artist -> destination.id
            is AndroidStatisticsTarget.Album -> destination.id
            null -> stableId
        },
        title = title,
        subtitle = subtitle,
        // The cloud intentionally accepts HTTPS artwork only. Local statistics can also contain
        // content/file URIs, so omit those rather than rejecting the entire social snapshot.
        artworkUrl = artworkUrl?.takeIf { it.startsWith("https://", ignoreCase = true) },
        listeningMillis = listeningMs,
        playCount = playCount,
    )

private fun AndroidStatisticsRange.maxSocialDailyBuckets(): Int =
    when (this) {
        AndroidStatisticsRange.DAYS_7 -> 7
        AndroidStatisticsRange.MONTH_1 -> 32
        AndroidStatisticsRange.MONTHS_3 -> 93
        AndroidStatisticsRange.MONTHS_6 -> 186
        AndroidStatisticsRange.YEAR_1 -> 367
        AndroidStatisticsRange.ALL_TIME -> 4_000
    }
