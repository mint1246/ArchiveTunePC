/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.statistics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.min

data class AndroidStatisticsHourSlice(
    val hourStartEpochMs: Long,
    val listeningMs: Long,
)

interface AndroidStatisticsQuerySource {
    val zoneId: ZoneId

    fun querySummary(range: AndroidStatisticsEpochRange): AndroidStatisticsSummary

    fun queryHourSlices(range: AndroidStatisticsEpochRange): List<AndroidStatisticsHourSlice>

    fun queryTopSongs(
        range: AndroidStatisticsEpochRange,
        limit: Int,
    ): List<AndroidStatisticsMedia>

    fun queryTopArtists(
        range: AndroidStatisticsEpochRange,
        limit: Int,
    ): List<AndroidStatisticsMedia>

    fun queryTopAlbums(
        range: AndroidStatisticsEpochRange,
        limit: Int,
    ): List<AndroidStatisticsMedia>
}

class AndroidStatisticsQueryService(
    private val source: AndroidStatisticsQuerySource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun overview(
        range: AndroidStatisticsEpochRange,
        songLimit: Int = DEFAULT_SONG_LIMIT,
        artistLimit: Int = DEFAULT_ARTIST_LIMIT,
        albumLimit: Int = DEFAULT_ALBUM_LIMIT,
    ): AndroidStatisticsOverview {
        require(songLimit >= 0 && artistLimit >= 0 && albumLimit >= 0) {
            "Statistics limits must be non-negative"
        }
        val summary = source.querySummary(range)
        val hourSlices = source.queryHourSlices(range)
        val topSongs = if (songLimit == 0) emptyList() else source.queryTopSongs(range, songLimit)
        val topArtists = if (artistLimit == 0) emptyList() else source.queryTopArtists(range, artistLimit)
        val topAlbums = if (albumLimit == 0) emptyList() else source.queryTopAlbums(range, albumLimit)

        return buildStatisticsOverview(
            summary = summary,
            hourSlices = hourSlices,
            topSongs = topSongs,
            topArtists = topArtists,
            topAlbums = topAlbums,
            range = range,
            zoneId = source.zoneId,
        )
    }

    fun overviewForRange(
        range: AndroidStatisticsRange,
        nowEpochMs: Long = clock(),
        songLimit: Int = DEFAULT_SONG_LIMIT,
        artistLimit: Int = DEFAULT_ARTIST_LIMIT,
        albumLimit: Int = DEFAULT_ALBUM_LIMIT,
    ): AndroidStatisticsOverview =
        overview(
            range = range.toEpochRange(nowEpochMs, source.zoneId),
            songLimit = songLimit,
            artistLimit = artistLimit,
            albumLimit = albumLimit,
        )

    fun allRangeOverviews(
        nowEpochMs: Long = clock(),
        songLimit: Int = DEFAULT_SONG_LIMIT,
        artistLimit: Int = DEFAULT_ARTIST_LIMIT,
        albumLimit: Int = DEFAULT_ALBUM_LIMIT,
    ): Map<AndroidStatisticsRange, AndroidStatisticsOverview> =
        AndroidStatisticsRange.entries.associateWith { range ->
            overviewForRange(
                range = range,
                nowEpochMs = nowEpochMs,
                songLimit = songLimit,
                artistLimit = artistLimit,
                albumLimit = albumLimit,
            )
        }

    companion object {
        const val DEFAULT_SONG_LIMIT = 20
        const val DEFAULT_ARTIST_LIMIT = 12
        const val DEFAULT_ALBUM_LIMIT = 12
    }
}

fun buildStatisticsOverview(
    summary: AndroidStatisticsSummary,
    hourSlices: List<AndroidStatisticsHourSlice>,
    topSongs: List<AndroidStatisticsMedia>,
    topArtists: List<AndroidStatisticsMedia>,
    topAlbums: List<AndroidStatisticsMedia>,
    range: AndroidStatisticsEpochRange,
    zoneId: ZoneId = ZoneId.systemDefault(),
): AndroidStatisticsOverview =
    AndroidStatisticsOverview(
        summary = summary,
        dailyListening = buildDailyListeningTrend(hourSlices, range, zoneId),
        listeningByDayOfWeek = buildDayOfWeekListeningTrend(hourSlices, zoneId),
        listeningByHour = buildHourOfDayListeningTrend(hourSlices, zoneId),
        topSongs = topSongs,
        topArtists = topArtists,
        topAlbums = topAlbums,
    )

fun buildDailyListeningTrend(
    slices: List<AndroidStatisticsHourSlice>,
    range: AndroidStatisticsEpochRange,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<AndroidStatisticsBucket> {
    if (slices.isEmpty()) return emptyList()
    val values = LinkedHashMap<LocalDate, Long>()
    slices.forEach { slice ->
        val date = Instant.ofEpochMilli(slice.hourStartEpochMs).atZone(zoneId).toLocalDate()
        values[date] = (values[date] ?: 0L) + slice.listeningMs
    }
    val first =
        range.fromEpochMs
            ?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
            ?: values.keys.first()
    val lastFromRange =
        range.toEpochMs
            ?.let { end -> Instant.ofEpochMilli((end - 1L).coerceAtLeast(0L)).atZone(zoneId).toLocalDate() }
    val last = lastFromRange ?: values.keys.last()
    if (first > last) return emptyList()
    val formatter = DateTimeFormatter.ofPattern("d MMM")
    return generateSequence(first) { date -> date.plusDays(1).takeIf { it <= last } }
        .map { date -> AndroidStatisticsBucket(date.format(formatter), values[date] ?: 0L) }
        .toList()
}

fun buildDayOfWeekListeningTrend(
    slices: List<AndroidStatisticsHourSlice>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<AndroidStatisticsBucket> {
    if (slices.isEmpty()) return emptyList()
    val totals = LongArray(7)
    slices.forEach { slice ->
        val day = Instant.ofEpochMilli(slice.hourStartEpochMs).atZone(zoneId).dayOfWeek
        totals[day.value % 7] += slice.listeningMs // Sunday first, matching desktop.
    }
    val labels = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    return totals.indices
        .filter { totals[it] > 0L }
        .map { index -> AndroidStatisticsBucket(labels[index], totals[index]) }
}

fun buildHourOfDayListeningTrend(
    slices: List<AndroidStatisticsHourSlice>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<AndroidStatisticsBucket> {
    if (slices.isEmpty()) return emptyList()
    val totals = LongArray(24)
    slices.forEach { slice ->
        val hour = Instant.ofEpochMilli(slice.hourStartEpochMs).atZone(zoneId).hour
        totals[hour] += slice.listeningMs
    }
    return totals.indices
        .filter { totals[it] > 0L }
        .map { hour -> AndroidStatisticsBucket("%02d:00".format(hour), totals[hour]) }
}

fun splitAcrossHours(
    startEpochMs: Long,
    durationMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    onSlice: (hourStartEpochMs: Long, sliceDurationMs: Long) -> Unit,
) {
    var cursor = startEpochMs
    var remaining = durationMs
    while (remaining > 0L) {
        val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
        val hourStart = zoned.truncatedTo(ChronoUnit.HOURS)
        val nextHour = hourStart.plusHours(1).toInstant().toEpochMilli()
        val untilBoundary = (nextHour - cursor).takeIf { it > 0L } ?: remaining
        val slice = min(remaining, untilBoundary)
        onSlice(hourStart.toInstant().toEpochMilli(), slice)
        cursor = safeAdd(cursor, slice)
        remaining -= slice
    }
}

fun hourStartEpochMs(
    epochMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long = Instant.ofEpochMilli(epochMs).atZone(zoneId).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

fun artistKey(artist: AndroidStatisticsArtist): String =
    artist.id?.takeIf(String::isNotBlank)?.let { "id:$it" }
        ?: "name:${artist.name.trim().lowercase()}"

private fun safeAdd(
    left: Long,
    right: Long,
): Long = if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
