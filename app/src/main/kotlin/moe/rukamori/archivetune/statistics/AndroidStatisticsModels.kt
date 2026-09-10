/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.statistics

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.ZoneId

@Immutable
data class AndroidStatisticsArtist(
    val id: String? = null,
    val name: String,
)

@Immutable
data class AndroidStatisticsAlbum(
    val id: String? = null,
    val title: String,
)

@Immutable
data class AndroidStatisticsSong(
    val id: String,
    val title: String,
    val artists: List<AndroidStatisticsArtist> = emptyList(),
    val album: AndroidStatisticsAlbum? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
) {
    val artistText: String
        get() = artists.joinToString { it.name }
}

enum class AndroidStatisticsRange(
    val label: String,
) {
    DAYS_7("7 days"),
    MONTH_1("1 month"),
    MONTHS_3("3 months"),
    MONTHS_6("6 months"),
    YEAR_1("1 year"),
    ALL_TIME("All time"),
}

data class AndroidStatisticsEpochRange(
    val fromEpochMs: Long? = null,
    val toEpochMs: Long? = null,
) {
    init {
        if (fromEpochMs != null && toEpochMs != null) {
            require(fromEpochMs < toEpochMs) { "Statistics range must have from < to" }
        }
    }

    val isAllTime: Boolean
        get() = fromEpochMs == null && toEpochMs == null
}

fun AndroidStatisticsRange.toEpochRange(
    nowEpochMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): AndroidStatisticsEpochRange {
    if (this == AndroidStatisticsRange.ALL_TIME) return AndroidStatisticsEpochRange()

    val now = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
    val fromDate =
        when (this) {
            AndroidStatisticsRange.DAYS_7 -> now.toLocalDate().minusDays(6)
            AndroidStatisticsRange.MONTH_1 -> now.toLocalDate().minusMonths(1)
            AndroidStatisticsRange.MONTHS_3 -> now.toLocalDate().minusMonths(3)
            AndroidStatisticsRange.MONTHS_6 -> now.toLocalDate().minusMonths(6)
            AndroidStatisticsRange.YEAR_1 -> now.toLocalDate().minusYears(1)
            AndroidStatisticsRange.ALL_TIME -> error("handled above")
        }
    val from = fromDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val to = if (nowEpochMs == Long.MAX_VALUE) Long.MAX_VALUE else nowEpochMs + 1L
    return AndroidStatisticsEpochRange(fromEpochMs = from, toEpochMs = to)
}

@Immutable
data class AndroidStatisticsSummary(
    val totalListeningMs: Long = 0L,
    val totalPlays: Long = 0L,
    val uniqueSongs: Int = 0,
    val uniqueArtists: Int = 0,
    val uniqueAlbums: Int = 0,
)

sealed interface AndroidStatisticsTarget {
    val id: String

    data class Song(override val id: String) : AndroidStatisticsTarget

    data class Artist(override val id: String) : AndroidStatisticsTarget

    data class Album(override val id: String) : AndroidStatisticsTarget
}

@Immutable
data class AndroidStatisticsMedia(
    val stableId: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val listeningMs: Long = 0L,
    val playCount: Long = 0L,
    val uniqueSongs: Int = 0,
    val target: AndroidStatisticsTarget? = null,
)

@Immutable
data class AndroidStatisticsBucket(
    val label: String,
    val listeningMs: Long,
)

@Immutable
data class AndroidStatisticsOverview(
    val summary: AndroidStatisticsSummary = AndroidStatisticsSummary(),
    val dailyListening: List<AndroidStatisticsBucket> = emptyList(),
    val listeningByDayOfWeek: List<AndroidStatisticsBucket> = emptyList(),
    val listeningByHour: List<AndroidStatisticsBucket> = emptyList(),
    val topSongs: List<AndroidStatisticsMedia> = emptyList(),
    val topArtists: List<AndroidStatisticsMedia> = emptyList(),
    val topAlbums: List<AndroidStatisticsMedia> = emptyList(),
) {
    val isEmpty: Boolean
        get() = summary.totalListeningMs <= 0L && summary.totalPlays <= 0L
}

internal fun effectivePlayThresholdMs(
    durationMs: Long?,
    defaultThresholdMs: Long = AndroidStatisticsTracker.DEFAULT_PLAY_THRESHOLD_MS,
): Long {
    val duration = durationMs?.takeIf { it > 0L } ?: return defaultThresholdMs
    val halfDuration = (duration / 2L).coerceAtLeast(1L)
    return minOf(defaultThresholdMs, halfDuration)
}
