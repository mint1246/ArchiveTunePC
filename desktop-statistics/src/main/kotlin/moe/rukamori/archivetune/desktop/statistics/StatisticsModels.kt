/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.statistics

import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import java.time.DayOfWeek
import java.time.LocalDate

/** Compact metadata stored once per song id and reused by every statistics bucket. */
data class StatisticsSongMetadata(
    val id: String,
    val title: String,
    val artists: List<StatisticsArtist> = emptyList(),
    val album: StatisticsAlbum? = null,
    val artworkUri: String? = null,
    val durationMs: Long? = null,
    val isMusicVideo: Boolean = false,
) {
    val artistText: String
        get() = artists.joinToString { it.name }

    companion object {
        fun fromPlayback(metadata: PlaybackMetadata): StatisticsSongMetadata =
            StatisticsSongMetadata(
                id = metadata.id,
                title = metadata.title,
                artists = metadata.artists.map { StatisticsArtist(it.id, it.name) },
                album = metadata.album?.let { StatisticsAlbum(it.id, it.title) },
                artworkUri = metadata.artworkUri,
                durationMs = metadata.durationHintMs,
                isMusicVideo = metadata.isMusicVideo,
            )
    }
}

data class StatisticsArtist(
    val id: String? = null,
    val name: String,
)

data class StatisticsAlbum(
    val id: String? = null,
    val title: String,
)

/**
 * Epoch range used by every query. The lower bound is inclusive and the upper bound is exclusive.
 * Null bounds are open ended; [all] selects the persisted all-time aggregates directly.
 */
data class StatisticsRange(
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

    companion object {
        val All: StatisticsRange = StatisticsRange()

        fun all(): StatisticsRange = All

        fun between(fromEpochMs: Long, toEpochMs: Long): StatisticsRange =
            StatisticsRange(fromEpochMs, toEpochMs)

        fun since(fromEpochMs: Long): StatisticsRange = StatisticsRange(fromEpochMs = fromEpochMs)

        fun until(toEpochMs: Long): StatisticsRange = StatisticsRange(toEpochMs = toEpochMs)
    }
}

data class StatisticsMeasure(
    val listeningTimeMs: Long = 0L,
    val playCount: Long = 0L,
) {
    operator fun plus(other: StatisticsMeasure): StatisticsMeasure =
        StatisticsMeasure(
            listeningTimeMs = listeningTimeMs + other.listeningTimeMs,
            playCount = playCount + other.playCount,
        )
}

data class StatisticsTotals(
    val listeningTimeMs: Long,
    val playCount: Long,
    val uniqueSongs: Int,
    val uniqueArtists: Int,
    val uniqueAlbums: Int,
)

enum class StatisticsSort {
    LISTENING_TIME,
    PLAY_COUNT,
}

data class SongStatistics(
    val song: StatisticsSongMetadata,
    val listeningTimeMs: Long,
    val playCount: Long,
)

data class ArtistStatistics(
    val artist: StatisticsArtist,
    val listeningTimeMs: Long,
    val playCount: Long,
    val uniqueSongs: Int,
)

data class AlbumStatistics(
    val album: StatisticsAlbum,
    val listeningTimeMs: Long,
    val playCount: Long,
    val uniqueSongs: Int,
)

data class StatisticsOverview(
    val totals: StatisticsTotals,
    val topSongs: List<SongStatistics>,
    val topArtists: List<ArtistStatistics>,
    val topAlbums: List<AlbumStatistics>,
)

/** Chronological local-hour or local-day point. */
data class StatisticsTimeBucket(
    val startEpochMs: Long,
    val listeningTimeMs: Long,
    val playCount: Long,
    val uniqueSongs: Int,
)

data class StatisticsDayBucket(
    val date: LocalDate,
    val startEpochMs: Long,
    val listeningTimeMs: Long,
    val playCount: Long,
    val uniqueSongs: Int,
)

data class StatisticsDayOfWeekBucket(
    val dayOfWeek: DayOfWeek,
    val listeningTimeMs: Long,
    val playCount: Long,
    val uniqueSongs: Int,
)

data class StatisticsHourOfDayBucket(
    val hour: Int,
    val listeningTimeMs: Long,
    val playCount: Long,
    val uniqueSongs: Int,
)
