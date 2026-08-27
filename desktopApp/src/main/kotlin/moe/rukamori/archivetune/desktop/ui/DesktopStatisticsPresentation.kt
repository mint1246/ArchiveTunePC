/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.desktop.statistics.StatisticsAlbum
import moe.rukamori.archivetune.desktop.statistics.StatisticsArtist
import moe.rukamori.archivetune.desktop.statistics.StatisticsRange
import moe.rukamori.archivetune.desktop.statistics.StatisticsSongMetadata
import moe.rukamori.archivetune.desktop.statistics.DesktopStatisticsStore
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class DesktopStatisticsPresentation(
    val model: DesktopStatisticsUiModel,
    val songsById: Map<String, StatisticsSongMetadata>,
    val artistTargets: Map<String, String>,
    val albumTargets: Map<String, String>,
)

internal fun DesktopStatisticsStore.presentation(
    range: DesktopStatisticsRange,
    nowEpochMs: Long = System.currentTimeMillis(),
): DesktopStatisticsPresentation {
    val queryRange = range.toStatisticsRange(nowEpochMs, zoneId)
    val overview = overview(queryRange, limit = 20)
    val songsById = overview.topSongs.associate { it.song.id to it.song }

    val artistTargets = LinkedHashMap<String, String>()
    val topArtists = overview.topArtists.map { stats ->
        val uiId = stats.artist.uiId()
        stats.artist.id?.takeIf(String::isNotBlank)?.let { artistTargets[uiId] = it }
        DesktopStatisticsMedia(
            id = uiId,
            title = stats.artist.name,
            artworkUrl = artworkForArtist(stats.artist, overview.topSongs.map { it.song }),
            listeningMillis = stats.listeningTimeMs,
            playCount = stats.playCount,
        )
    }

    val albumTargets = LinkedHashMap<String, String>()
    val topAlbums = overview.topAlbums.map { stats ->
        val uiId = stats.album.uiId()
        stats.album.id?.takeIf(String::isNotBlank)?.let { albumTargets[uiId] = it }
        val representative = overview.topSongs.firstOrNull { song -> song.song.album.sameAlbum(stats.album) }?.song
        DesktopStatisticsMedia(
            id = uiId,
            title = stats.album.title,
            subtitle = representative?.artistText?.takeIf(String::isNotBlank),
            artworkUrl = representative?.artworkUri,
            listeningMillis = stats.listeningTimeMs,
            playCount = stats.playCount,
        )
    }

    val topSongs = overview.topSongs.map { stats ->
        DesktopStatisticsMedia(
            id = stats.song.id,
            title = stats.song.title,
            subtitle = stats.song.artistText.takeIf(String::isNotBlank),
            artworkUrl = stats.song.artworkUri,
            listeningMillis = stats.listeningTimeMs,
            playCount = stats.playCount,
        )
    }

    val daily = dailyTrend(queryRange)
    val filledDaily = fillDailyGaps(daily.map { it.date to it.listeningTimeMs }, queryRange, zoneId, nowEpochMs)
    val dayFormatter = DateTimeFormatter.ofPattern("d MMM")

    return DesktopStatisticsPresentation(
        model = DesktopStatisticsUiModel(
            summary = DesktopStatisticsSummary(
                totalListeningMillis = overview.totals.listeningTimeMs,
                totalPlays = overview.totals.playCount,
                uniqueSongs = overview.totals.uniqueSongs,
                uniqueArtists = overview.totals.uniqueArtists,
                uniqueAlbums = overview.totals.uniqueAlbums,
            ),
            favoriteArtist = topArtists.firstOrNull(),
            favoriteSong = topSongs.firstOrNull(),
            dailyListening = filledDaily.map { (date, listeningMs) ->
                DesktopStatisticsBucket(date.format(dayFormatter), listeningMs)
            },
            listeningByDayOfWeek = dayOfWeekTrend(queryRange)
                .sortedBy { (it.dayOfWeek.value % 7) }
                .map { DesktopStatisticsBucket(it.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase), it.listeningTimeMs) },
            listeningByHour = hourOfDayTrend(queryRange)
                .map { DesktopStatisticsBucket("%02d:00".format(it.hour), it.listeningTimeMs) },
            topSongs = topSongs,
            topArtists = topArtists,
            topAlbums = topAlbums,
        ),
        songsById = songsById,
        artistTargets = artistTargets,
        albumTargets = albumTargets,
    )
}

private fun DesktopStatisticsRange.toStatisticsRange(nowEpochMs: Long, zoneId: ZoneId): StatisticsRange {
    if (this == DesktopStatisticsRange.ALL_TIME) return StatisticsRange.All
    val now = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
    val fromDate = when (this) {
        DesktopStatisticsRange.DAYS_7 -> now.toLocalDate().minusDays(6)
        DesktopStatisticsRange.MONTH_1 -> now.toLocalDate().minusMonths(1)
        DesktopStatisticsRange.MONTHS_3 -> now.toLocalDate().minusMonths(3)
        DesktopStatisticsRange.MONTHS_6 -> now.toLocalDate().minusMonths(6)
        DesktopStatisticsRange.YEAR_1 -> now.toLocalDate().minusYears(1)
        DesktopStatisticsRange.ALL_TIME -> error("handled above")
    }
    val from = fromDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
    return StatisticsRange.between(from, if (nowEpochMs == Long.MAX_VALUE) nowEpochMs else nowEpochMs + 1L)
}

private fun fillDailyGaps(
    points: List<Pair<LocalDate, Long>>,
    range: StatisticsRange,
    zoneId: ZoneId,
    nowEpochMs: Long,
): List<Pair<LocalDate, Long>> {
    if (points.isEmpty()) return emptyList()
    val values = points.toMap()
    val first = range.fromEpochMs
        ?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
        ?: points.first().first
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
    val last = range.toEpochMs
        ?.let { Instant.ofEpochMilli((it - 1L).coerceAtLeast(0L)).atZone(zoneId).toLocalDate() }
        ?.coerceAtMost(today)
        ?: points.last().first
    if (first > last) return points
    return generateSequence(first) { date -> date.plusDays(1).takeIf { it <= last } }
        .map { date -> date to (values[date] ?: 0L) }
        .toList()
}

private fun StatisticsArtist.uiId(): String =
    id?.takeIf(String::isNotBlank) ?: "artist-name:${name.lowercase()}"

private fun StatisticsAlbum.uiId(): String =
    id?.takeIf(String::isNotBlank) ?: "album-title:${title.lowercase()}"

private fun StatisticsAlbum?.sameAlbum(other: StatisticsAlbum): Boolean = when {
    this == null -> false
    id?.isNotBlank() == true && other.id?.isNotBlank() == true -> id == other.id
    else -> title.equals(other.title, ignoreCase = true)
}

private fun artworkForArtist(
    artist: StatisticsArtist,
    songs: List<StatisticsSongMetadata>,
): String? = songs.firstOrNull { song ->
    song.artists.any { candidate ->
        when {
            artist.id?.isNotBlank() == true && candidate.id?.isNotBlank() == true -> artist.id == candidate.id
            else -> artist.name.equals(candidate.name, ignoreCase = true)
        }
    }
}?.artworkUri

internal fun StatisticsSongMetadata.toSongItem(): SongItem =
    SongItem(
        id = id,
        title = title,
        artists = artists.map { Artist(id = it.id, name = it.name) },
        album = album?.let { Album(id = it.id.orEmpty(), name = it.title) },
        duration = durationMs?.div(1_000L)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
        thumbnail = artworkUri.orEmpty(),
        explicit = false,
    )
