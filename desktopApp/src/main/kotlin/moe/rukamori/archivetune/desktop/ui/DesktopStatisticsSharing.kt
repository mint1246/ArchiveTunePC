package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.desktop.social.SocialStatisticsBucket
import moe.rukamori.archivetune.desktop.social.SocialStatisticsMedia
import moe.rukamori.archivetune.desktop.social.SocialStatisticsRangeSnapshot
import moe.rukamori.archivetune.desktop.social.SocialStatisticsSnapshot
import moe.rukamori.archivetune.desktop.social.SocialStatisticsSummary
import moe.rukamori.archivetune.desktop.statistics.DesktopStatisticsStore

internal fun DesktopStatisticsStore.socialSnapshot(
    nowEpochMs: Long = System.currentTimeMillis(),
): SocialStatisticsSnapshot =
    SocialStatisticsSnapshot(
        ranges = DesktopStatisticsRange.entries.associate { range ->
            range.name to presentation(range, nowEpochMs).model.toSocialSnapshot()
        },
        updatedAt = nowEpochMs,
    )

internal fun SocialStatisticsRangeSnapshot.toDesktopStatisticsUiModel(): DesktopStatisticsUiModel =
    DesktopStatisticsUiModel(
        summary = DesktopStatisticsSummary(
            totalListeningMillis = summary.totalListeningMillis,
            totalPlays = summary.totalPlays,
            uniqueSongs = summary.uniqueSongs,
            uniqueArtists = summary.uniqueArtists,
            uniqueAlbums = summary.uniqueAlbums,
        ),
        favoriteArtist = favoriteArtist?.toDesktopMedia(),
        favoriteSong = favoriteSong?.toDesktopMedia(),
        dailyListening = dailyListening.map(SocialStatisticsBucket::toDesktopBucket),
        listeningByDayOfWeek = listeningByDayOfWeek.map(SocialStatisticsBucket::toDesktopBucket),
        listeningByHour = listeningByHour.map(SocialStatisticsBucket::toDesktopBucket),
        topSongs = topSongs.map(SocialStatisticsMedia::toDesktopMedia),
        topArtists = topArtists.map(SocialStatisticsMedia::toDesktopMedia),
        topAlbums = topAlbums.map(SocialStatisticsMedia::toDesktopMedia),
    )

private fun DesktopStatisticsUiModel.toSocialSnapshot(): SocialStatisticsRangeSnapshot =
    SocialStatisticsRangeSnapshot(
        summary = SocialStatisticsSummary(
            totalListeningMillis = summary.totalListeningMillis,
            totalPlays = summary.totalPlays,
            uniqueSongs = summary.uniqueSongs,
            uniqueArtists = summary.uniqueArtists,
            uniqueAlbums = summary.uniqueAlbums,
        ),
        favoriteArtist = favoriteArtist?.toSocialMedia(),
        favoriteSong = favoriteSong?.toSocialMedia(),
        dailyListening = dailyListening.map(DesktopStatisticsBucket::toSocialBucket),
        listeningByDayOfWeek = listeningByDayOfWeek.map(DesktopStatisticsBucket::toSocialBucket),
        listeningByHour = listeningByHour.map(DesktopStatisticsBucket::toSocialBucket),
        topSongs = topSongs.map(DesktopStatisticsMedia::toSocialMedia),
        topArtists = topArtists.map(DesktopStatisticsMedia::toSocialMedia),
        topAlbums = topAlbums.map(DesktopStatisticsMedia::toSocialMedia),
    )

private fun DesktopStatisticsMedia.toSocialMedia(): SocialStatisticsMedia =
    SocialStatisticsMedia(id, title, subtitle, artworkUrl, listeningMillis, playCount)

private fun SocialStatisticsMedia.toDesktopMedia(): DesktopStatisticsMedia =
    DesktopStatisticsMedia(id, title, subtitle, artworkUrl, listeningMillis, playCount)

private fun DesktopStatisticsBucket.toSocialBucket(): SocialStatisticsBucket =
    SocialStatisticsBucket(label, listeningMillis)

private fun SocialStatisticsBucket.toDesktopBucket(): DesktopStatisticsBucket =
    DesktopStatisticsBucket(label, listeningMillis)
