/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import kotlin.math.min
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class MusicStatisticsRange(val label: String, val days: Int?) {
    DAYS_7("7 days", 7),
    MONTH_1("1 month", 30),
    MONTHS_3("3 months", 90),
    MONTHS_6("6 months", 180),
    YEAR_1("1 year", 365),
    ALL_TIME("All time", null),
}

data class MusicTrackStat(
    val trackId: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val totalListeningMs: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedEpochMs: Long = 0L,
)

data class MusicStatisticsEvent(
    val trackId: String,
    val epochMs: Long,
    val listeningMs: Long,
    val countedPlay: Boolean,
    val dayOfWeek: Int = 0, // 0=Mon..6=Sun
    val hourOfDay: Int = 0, // 0..23
)

data class MusicStatisticsStoreData(
    val tracks: Map<String, MusicTrackStat> = emptyMap(),
    val events: List<MusicStatisticsEvent> = emptyList(),
)

data class MusicRankedItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String? = null,
    val listeningMs: Long,
    val playCount: Int,
    val track: MusicTrack? = null,
)

data class MusicStatisticsOverview(
    val totalListeningMs: Long = 0L,
    val totalPlays: Int = 0,
    val uniqueSongs: Int = 0,
    val uniqueArtists: Int = 0,
    val uniqueAlbums: Int = 0,
    val topSongs: List<MusicRankedItem> = emptyList(),
    val topArtists: List<MusicRankedItem> = emptyList(),
    val topAlbums: List<MusicRankedItem> = emptyList(),
    val byDayOfWeek: List<Pair<String, Long>> = emptyList(),
    val byHour: List<Pair<String, Long>> = emptyList(),
    val dailyTrend: List<Pair<String, Long>> = emptyList(),
) {
    val isEmpty: Boolean get() = totalListeningMs <= 0L && totalPlays <= 0
}

/**
 * Authoritative audible-time statistics accumulator.
 * - Counts ONLY real elapsed audible playback time via monotonic clock (never paused/buffering/seek jumps).
 * - Play count threshold: min(30_000ms, knownDurationMs / 2), fallback 30_000ms when unknown.
 * - Respects pauseListenHistory and party-listening eligibility at the tracking boundary.
 */
class AudibleStatisticsSession(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private var activeTrack: MusicTrack? = null
    private var activeStartMark: TimeMark? = null
    private var sessionAccumulatedMs: Long = 0L
    private var sessionCountedPlay: Boolean = false

    data class Delta(
        val track: MusicTrack,
        val listeningMs: Long,
        val newlyCountedPlay: Boolean,
    )

    fun playCountThresholdMs(durationMs: Long?): Long {
        val known = durationMs?.takeIf { it > 0L } ?: return 30_000L
        return min(30_000L, (known / 2L).coerceAtLeast(1_000L))
    }

    fun onSample(
        track: MusicTrack?,
        isAudiblePlaying: Boolean,
        eligible: Boolean,
    ): Delta? {
        val now = timeSource.markNow()
        val previousTrack = activeTrack
        val previousMark = activeStartMark

        // Flush elapsed time for previous active audible segment
        var delta: Delta? = null
        if (previousTrack != null && previousMark != null) {
            val elapsed = previousMark.elapsedNow().inWholeMilliseconds.coerceIn(0L, 15_000L)
            if (elapsed > 0L) {
                sessionAccumulatedMs += elapsed
                val threshold = playCountThresholdMs(previousTrack.durationMs)
                val shouldCount = !sessionCountedPlay && sessionAccumulatedMs >= threshold
                if (shouldCount) sessionCountedPlay = true
                delta = Delta(previousTrack, elapsed, shouldCount)
            }
        }

        if (track == null || !isAudiblePlaying || !eligible) {
            if (track?.id != previousTrack?.id) {
                activeTrack = track
                sessionAccumulatedMs = 0L
                sessionCountedPlay = false
            }
            activeStartMark = null
            return delta
        }

        if (previousTrack?.id != track.id) {
            activeTrack = track
            sessionAccumulatedMs = 0L
            sessionCountedPlay = false
        } else {
            activeTrack = track
        }
        activeStartMark = now
        return delta
    }

    fun reset() {
        activeTrack = null
        activeStartMark = null
        sessionAccumulatedMs = 0L
        sessionCountedPlay = false
    }
}

object MusicStatisticsQuery {
    private val weekdayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun overview(
        data: MusicStatisticsStoreData,
        range: MusicStatisticsRange,
        nowEpochMs: Long,
        topLimit: Int = 50,
    ): MusicStatisticsOverview {
        val cutoff = range.days?.let { nowEpochMs - it.toLong() * 86_400_000L }
        val filteredEvents = if (cutoff == null) data.events else data.events.filter { it.epochMs >= cutoff }

        val songMs = mutableMapOf<String, Long>()
        val songPlays = mutableMapOf<String, Int>()
        val weekdayMs = LongArray(7)
        val hourMs = LongArray(24)
        val dayBuckets = linkedMapOf<Long, Long>()

        if (cutoff == null && filteredEvents.isEmpty() && data.tracks.isNotEmpty()) {
            data.tracks.values.forEach { stat ->
                songMs[stat.trackId] = stat.totalListeningMs
                songPlays[stat.trackId] = stat.playCount
            }
        } else {
            filteredEvents.forEach { event ->
                songMs[event.trackId] = (songMs[event.trackId] ?: 0L) + event.listeningMs
                if (event.countedPlay) {
                    songPlays[event.trackId] = (songPlays[event.trackId] ?: 0) + 1
                }
                if (event.dayOfWeek in 0..6) weekdayMs[event.dayOfWeek] += event.listeningMs
                if (event.hourOfDay in 0..23) hourMs[event.hourOfDay] += event.listeningMs
                val dayKey = event.epochMs / 86_400_000L
                dayBuckets[dayKey] = (dayBuckets[dayKey] ?: 0L) + event.listeningMs
            }
        }

        val totalListeningMs = songMs.values.sum()
        val totalPlays = songPlays.values.sum()

        val topSongs = songMs.entries
            .mapNotNull { (id, ms) ->
                val stat = data.tracks[id] ?: return@mapNotNull null
                val plays = songPlays[id] ?: 0
                MusicRankedItem(
                    id = id,
                    title = stat.title,
                    subtitle = stat.artist,
                    artworkUrl = stat.artworkUrl,
                    listeningMs = ms,
                    playCount = plays,
                    track = MusicTrack(
                        id = stat.trackId,
                        title = stat.title,
                        artist = stat.artist,
                        artworkUrl = stat.artworkUrl,
                        durationMs = stat.durationMs,
                        artistId = stat.artistId,
                        albumId = stat.albumId,
                        albumName = stat.albumName,
                    ),
                )
            }
            .sortedWith(compareByDescending<MusicRankedItem> { it.listeningMs }.thenByDescending { it.playCount })
            .take(topLimit.coerceIn(1, 200))

        val artistAgg = mutableMapOf<String, MutableList<Pair<MusicTrackStat, Pair<Long, Int>>>>()
        songMs.forEach { (id, ms) ->
            val stat = data.tracks[id] ?: return@forEach
            val key = stat.artistId?.takeIf(String::isNotBlank) ?: stat.artist.takeIf(String::isNotBlank) ?: return@forEach
            artistAgg.getOrPut(key) { mutableListOf() }.add(stat to (ms to (songPlays[id] ?: 0)))
        }
        val topArtists = artistAgg.entries.map { (key, list) ->
            val rep = list.first().first
            MusicRankedItem(
                id = rep.artistId ?: key,
                title = rep.artist.ifBlank { key },
                subtitle = "${list.size} songs",
                artworkUrl = list.firstNotNullOfOrNull { it.first.artworkUrl },
                listeningMs = list.sumOf { it.second.first },
                playCount = list.sumOf { it.second.second },
            )
        }.sortedByDescending { it.listeningMs }.take(20)

        val albumAgg = mutableMapOf<String, MutableList<Pair<MusicTrackStat, Pair<Long, Int>>>>()
        songMs.forEach { (id, ms) ->
            val stat = data.tracks[id] ?: return@forEach
            val albumTitle = stat.albumName?.takeIf(String::isNotBlank) ?: return@forEach
            val key = stat.albumId?.takeIf(String::isNotBlank) ?: albumTitle
            albumAgg.getOrPut(key) { mutableListOf() }.add(stat to (ms to (songPlays[id] ?: 0)))
        }
        val topAlbums = albumAgg.entries.map { (key, list) ->
            val rep = list.first().first
            MusicRankedItem(
                id = rep.albumId ?: key,
                title = rep.albumName.orEmpty(),
                subtitle = rep.artist,
                artworkUrl = list.firstNotNullOfOrNull { it.first.artworkUrl },
                listeningMs = list.sumOf { it.second.first },
                playCount = list.sumOf { it.second.second },
            )
        }.sortedByDescending { it.listeningMs }.take(20)

        val byDayOfWeek = if (weekdayMs.any { it > 0L }) {
            weekdayLabels.mapIndexed { i, label -> label to weekdayMs[i] }
        } else emptyList()

        val byHour = if (hourMs.any { it > 0L }) {
            (0..23).map { h -> "${h.toString().padStart(2, '0')}h" to hourMs[h] }
        } else emptyList()

        val dailyTrend = dayBuckets.entries.sortedBy { it.key }.takeLast(14)
            .mapIndexed { idx, entry -> "D${idx + 1}" to entry.value }

        return MusicStatisticsOverview(
            totalListeningMs = totalListeningMs,
            totalPlays = totalPlays,
            uniqueSongs = songMs.size,
            uniqueArtists = artistAgg.size,
            uniqueAlbums = albumAgg.size,
            topSongs = topSongs,
            topArtists = topArtists,
            topAlbums = topAlbums,
            byDayOfWeek = byDayOfWeek,
            byHour = byHour,
            dailyTrend = dailyTrend,
        )
    }

    fun formatListeningTime(ms: Long): String {
        val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L -> "${hours}h ${minutes}m"
            totalMinutes > 0L -> "${totalMinutes}m"
            ms > 0L -> "${(ms / 1000L).coerceAtLeast(1L)}s"
            else -> "0m"
        }
    }
}
