/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.statistics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.absolute
import kotlin.math.min

sealed interface StatisticsWriteMode {
    data object Immediate : StatisticsWriteMode

    data class Debounced(val delayMs: Long = 5_000L) : StatisticsWriteMode {
        init {
            require(delayMs >= 0L) { "Statistics write debounce must be non-negative" }
        }
    }
}

/**
 * Persistent desktop statistics database backed by one atomic JSON file.
 *
 * The persisted schema stores song metadata once, all-time aggregates separately, and unbounded
 * local-hour aggregates for range/trend queries. No raw playback-position deltas are persisted and
 * there is deliberately no event/bucket cap.
 */
class DesktopStatisticsStore(
    file: Path,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    private val writeMode: StatisticsWriteMode = StatisticsWriteMode.Debounced(),
) : AutoCloseable {
    private data class MutableMeasure(
        var listeningTimeMs: Long = 0L,
        var playCount: Long = 0L,
        var lastPlayedAtEpochMs: Long? = null,
    ) {
        fun immutable(): StatisticsMeasure = StatisticsMeasure(listeningTimeMs, playCount)
    }

    private data class ArtistAccumulator(
        val artist: StatisticsArtist,
        var listeningTimeMs: Long = 0L,
        var playCount: Long = 0L,
        val songs: MutableSet<String> = LinkedHashSet(),
    )

    private data class AlbumAccumulator(
        val album: StatisticsAlbum,
        var listeningTimeMs: Long = 0L,
        var playCount: Long = 0L,
        val songs: MutableSet<String> = LinkedHashSet(),
    )

    private val file = file.absolute().normalize()
    private val lock = ReentrantLock()
    private val songs = LinkedHashMap<String, StatisticsSongMetadata>()
    private val totals = LinkedHashMap<String, MutableMeasure>()
    private val hours = TreeMap<Long, MutableMap<String, MutableMeasure>>()
    private var containsLegacyImportedData = false
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()
    private val writer: ScheduledExecutorService? =
        if (writeMode is StatisticsWriteMode.Debounced) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ArchiveTune-statistics-writer").apply { isDaemon = true }
            }
        } else {
            null
        }
    private var pendingWrite: ScheduledFuture<*>? = null
    private var dirty = false

    @Volatile
    var lastPersistenceFailure: Throwable? = null
        private set

    init {
        load()
    }

    /**
     * Adds exactly [durationMs] of real audible time. [startEpochMs] is used only to assign the
     * monotonic duration to local calendar-hour buckets. If [playAtEpochMs] is non-null, one play is
     * added at that wall-clock timestamp.
     */
    fun recordAudible(
        song: StatisticsSongMetadata,
        startEpochMs: Long,
        durationMs: Long,
        playAtEpochMs: Long? = null,
    ) {
        require(durationMs >= 0L) { "Listening duration must be non-negative" }
        if (durationMs == 0L && playAtEpochMs == null) return

        lock.withLock {
            recordAudibleLocked(song, startEpochMs, durationMs, playAtEpochMs)
            markDirtyLocked()
        }
    }

    /**
     * The first statistics build imported Home's old wall-clock play history. That recorder could
     * include paused/background time, and the imported rows were merged into the same aggregates as
     * the new monotonic tracker. They cannot be separated reliably after the fact, so tagged stores
     * are reset once and then continue only with the authoritative tracker.
     */
    fun discardLegacyImportedData(): Boolean = lock.withLock {
        if (!containsLegacyImportedData) return@withLock false
        songs.clear()
        totals.clear()
        hours.clear()
        containsLegacyImportedData = false
        markDirtyLocked()
        true
    }

    fun totals(range: StatisticsRange = StatisticsRange.All): StatisticsTotals = lock.withLock {
        val measures = measuresForRangeLocked(range)
        val activeSongs = measures.filterValues { it.listeningTimeMs > 0L || it.playCount > 0L }
        val artistKeys = LinkedHashSet<String>()
        val albumKeys = LinkedHashSet<String>()
        activeSongs.keys.forEach { songId ->
            val metadata = songs[songId] ?: return@forEach
            metadata.artists.forEach { artistKeys += artistKey(it) }
            metadata.album?.let { albumKeys += albumKey(it) }
        }
        StatisticsTotals(
            listeningTimeMs = activeSongs.values.sumOfSaturated { it.listeningTimeMs },
            playCount = activeSongs.values.sumOfSaturated { it.playCount },
            uniqueSongs = activeSongs.size,
            uniqueArtists = artistKeys.size,
            uniqueAlbums = albumKeys.size,
        )
    }

    fun topSongs(
        range: StatisticsRange = StatisticsRange.All,
        limit: Int = 20,
        sort: StatisticsSort = StatisticsSort.LISTENING_TIME,
    ): List<SongStatistics> = lock.withLock {
        require(limit >= 0) { "Statistics limit must be non-negative" }
        measuresForRangeLocked(range)
            .asSequence()
            .filter { (_, measure) -> measure.listeningTimeMs > 0L || measure.playCount > 0L }
            .map { (songId, measure) ->
                SongStatistics(
                    song = songs[songId] ?: StatisticsSongMetadata(songId, songId),
                    listeningTimeMs = measure.listeningTimeMs,
                    playCount = measure.playCount,
                )
            }
            .sortedWith(songComparator(sort))
            .take(limit)
            .toList()
    }

    fun topArtists(
        range: StatisticsRange = StatisticsRange.All,
        limit: Int = 20,
        sort: StatisticsSort = StatisticsSort.LISTENING_TIME,
    ): List<ArtistStatistics> = lock.withLock {
        require(limit >= 0) { "Statistics limit must be non-negative" }
        val accumulators = LinkedHashMap<String, ArtistAccumulator>()
        measuresForRangeLocked(range).forEach { (songId, measure) ->
            if (measure.listeningTimeMs <= 0L && measure.playCount <= 0L) return@forEach
            songs[songId]?.artists?.forEach { artist ->
                val accumulator = accumulators.getOrPut(artistKey(artist)) { ArtistAccumulator(artist) }
                accumulator.listeningTimeMs = safeAdd(accumulator.listeningTimeMs, measure.listeningTimeMs)
                accumulator.playCount = safeAdd(accumulator.playCount, measure.playCount)
                accumulator.songs += songId
            }
        }
        accumulators.values
            .map { ArtistStatistics(it.artist, it.listeningTimeMs, it.playCount, it.songs.size) }
            .sortedWith(artistComparator(sort))
            .take(limit)
    }

    fun topAlbums(
        range: StatisticsRange = StatisticsRange.All,
        limit: Int = 20,
        sort: StatisticsSort = StatisticsSort.LISTENING_TIME,
    ): List<AlbumStatistics> = lock.withLock {
        require(limit >= 0) { "Statistics limit must be non-negative" }
        val accumulators = LinkedHashMap<String, AlbumAccumulator>()
        measuresForRangeLocked(range).forEach { (songId, measure) ->
            if (measure.listeningTimeMs <= 0L && measure.playCount <= 0L) return@forEach
            val album = songs[songId]?.album ?: return@forEach
            val accumulator = accumulators.getOrPut(albumKey(album)) { AlbumAccumulator(album) }
            accumulator.listeningTimeMs = safeAdd(accumulator.listeningTimeMs, measure.listeningTimeMs)
            accumulator.playCount = safeAdd(accumulator.playCount, measure.playCount)
            accumulator.songs += songId
        }
        accumulators.values
            .map { AlbumStatistics(it.album, it.listeningTimeMs, it.playCount, it.songs.size) }
            .sortedWith(albumComparator(sort))
            .take(limit)
    }

    fun overview(
        range: StatisticsRange = StatisticsRange.All,
        limit: Int = 20,
        sort: StatisticsSort = StatisticsSort.LISTENING_TIME,
    ): StatisticsOverview =
        StatisticsOverview(
            totals = totals(range),
            topSongs = topSongs(range, limit, sort),
            topArtists = topArtists(range, limit, sort),
            topAlbums = topAlbums(range, limit, sort),
        )

    /** Chronological local-hour trend. Empty hours are omitted. */
    fun hourlyTrend(range: StatisticsRange = StatisticsRange.All): List<StatisticsTimeBucket> = lock.withLock {
        selectedHoursLocked(range).map { (start, bySong) ->
            val active = bySong.filterValues { it.listeningTimeMs > 0L || it.playCount > 0L }
            StatisticsTimeBucket(
                startEpochMs = start,
                listeningTimeMs = active.values.sumOfSaturated { it.listeningTimeMs },
                playCount = active.values.sumOfSaturated { it.playCount },
                uniqueSongs = active.size,
            )
        }
    }

    /** Chronological local-calendar-day trend. Empty days are omitted. */
    fun dailyTrend(range: StatisticsRange = StatisticsRange.All): List<StatisticsDayBucket> = lock.withLock {
        data class DayAccum(
            var listeningTimeMs: Long = 0L,
            var playCount: Long = 0L,
            val songIds: MutableSet<String> = LinkedHashSet(),
        )

        val grouped = TreeMap<LocalDate, DayAccum>()
        selectedHoursLocked(range).forEach { (hourStart, bySong) ->
            val date = Instant.ofEpochMilli(hourStart).atZone(zoneId).toLocalDate()
            val day = grouped.getOrPut(date) { DayAccum() }
            bySong.forEach { (songId, measure) ->
                if (measure.listeningTimeMs <= 0L && measure.playCount <= 0L) return@forEach
                day.listeningTimeMs = safeAdd(day.listeningTimeMs, measure.listeningTimeMs)
                day.playCount = safeAdd(day.playCount, measure.playCount)
                day.songIds += songId
            }
        }
        grouped.map { (date, value) ->
            StatisticsDayBucket(
                date = date,
                startEpochMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                listeningTimeMs = value.listeningTimeMs,
                playCount = value.playCount,
                uniqueSongs = value.songIds.size,
            )
        }
    }

    fun dayOfWeekTrend(range: StatisticsRange = StatisticsRange.All): List<StatisticsDayOfWeekBucket> = lock.withLock {
        data class DowAccum(
            var listeningTimeMs: Long = 0L,
            var playCount: Long = 0L,
            val songIds: MutableSet<String> = LinkedHashSet(),
        )

        val grouped = DayOfWeek.entries.associateWith { DowAccum() }
        selectedHoursLocked(range).forEach { (hourStart, bySong) ->
            val dayOfWeek = Instant.ofEpochMilli(hourStart).atZone(zoneId).dayOfWeek
            val value = grouped.getValue(dayOfWeek)
            bySong.forEach { (songId, measure) ->
                if (measure.listeningTimeMs <= 0L && measure.playCount <= 0L) return@forEach
                value.listeningTimeMs = safeAdd(value.listeningTimeMs, measure.listeningTimeMs)
                value.playCount = safeAdd(value.playCount, measure.playCount)
                value.songIds += songId
            }
        }
        DayOfWeek.entries.map { day ->
            val value = grouped.getValue(day)
            StatisticsDayOfWeekBucket(day, value.listeningTimeMs, value.playCount, value.songIds.size)
        }
    }

    fun hourOfDayTrend(range: StatisticsRange = StatisticsRange.All): List<StatisticsHourOfDayBucket> = lock.withLock {
        data class HourAccum(
            var listeningTimeMs: Long = 0L,
            var playCount: Long = 0L,
            val songIds: MutableSet<String> = LinkedHashSet(),
        )

        val grouped = Array(24) { HourAccum() }
        selectedHoursLocked(range).forEach { (hourStart, bySong) ->
            val hour = Instant.ofEpochMilli(hourStart).atZone(zoneId).hour
            val value = grouped[hour]
            bySong.forEach { (songId, measure) ->
                if (measure.listeningTimeMs <= 0L && measure.playCount <= 0L) return@forEach
                value.listeningTimeMs = safeAdd(value.listeningTimeMs, measure.listeningTimeMs)
                value.playCount = safeAdd(value.playCount, measure.playCount)
                value.songIds += songId
            }
        }
        grouped.mapIndexed { hour, value ->
            StatisticsHourOfDayBucket(hour, value.listeningTimeMs, value.playCount, value.songIds.size)
        }
    }

    /** Forces the latest in-memory state to disk and reports any persistence failure. */
    fun flush(): Result<Unit> = lock.withLock {
        pendingWrite?.cancel(false)
        pendingWrite = null
        if (!dirty) return@withLock Result.success(Unit)
        runCatching { writeLocked() }
            .onSuccess {
                dirty = false
                lastPersistenceFailure = null
                bumpRevisionLocked()
            }
            .onFailure { lastPersistenceFailure = it }
    }

    override fun close() {
        flush()
        writer?.shutdown()
    }

    private fun load() = lock.withLock {
        if (!Files.isRegularFile(file)) return@withLock
        runCatching {
            val root = JSONObject(Files.readString(file))
            val version = root.optInt(KEY_VERSION, 1)
            require(version == SCHEMA_VERSION) { "Unsupported statistics schema version $version" }
            containsLegacyImportedData = root.optInt(KEY_LEGACY_IMPORT_VERSION, 0) > 0

            root.optJSONObject(KEY_SONGS)?.let { json ->
                json.keys().forEach { id ->
                    json.optJSONObject(id)?.let { parseSong(id, it) }?.let { songs[id] = it }
                }
            }
            root.optJSONObject(KEY_TOTALS)?.let { json ->
                json.keys().forEach { id ->
                    json.optJSONArray(id)?.let(::parseMeasure)?.let { totals[id] = it }
                }
            }
            root.optJSONObject(KEY_HOURS)?.let { json ->
                json.keys().forEach { key ->
                    val start = key.toLongOrNull() ?: return@forEach
                    val bucket = json.optJSONObject(key) ?: return@forEach
                    val values = LinkedHashMap<String, MutableMeasure>()
                    bucket.keys().forEach { id ->
                        bucket.optJSONArray(id)?.let(::parseMeasure)?.let { values[id] = it }
                    }
                    if (values.isNotEmpty()) hours[start] = values
                }
            }
        }.onFailure { lastPersistenceFailure = it }
    }

    private fun markDirtyLocked() {
        dirty = true
        when (val mode = writeMode) {
            StatisticsWriteMode.Immediate -> {
                runCatching { writeLocked() }
                    .onSuccess {
                        dirty = false
                        lastPersistenceFailure = null
                        bumpRevisionLocked()
                    }
                    .onFailure { lastPersistenceFailure = it }
            }
            is StatisticsWriteMode.Debounced -> {
                if (pendingWrite == null) {
                    pendingWrite = writer?.schedule(
                        { flush() },
                        mode.delayMs,
                        TimeUnit.MILLISECONDS,
                    )
                }
            }
        }
    }

    private fun writeLocked() {
        val parent = checkNotNull(file.parent) { "Statistics file must have a parent directory" }
        Files.createDirectories(parent)
        val root = JSONObject()
            .put(KEY_VERSION, SCHEMA_VERSION)
            .put(KEY_LEGACY_IMPORT_VERSION, 0)
            .put(
                KEY_SONGS,
                JSONObject().apply {
                    songs.forEach { (id, song) -> put(id, songToJson(song)) }
                },
            )
            .put(
                KEY_TOTALS,
                JSONObject().apply {
                    totals.forEach { (id, measure) -> put(id, measureToJson(measure)) }
                },
            )
            .put(
                KEY_HOURS,
                JSONObject().apply {
                    hours.forEach { (start, bySong) ->
                        put(
                            start.toString(),
                            JSONObject().apply {
                                bySong.forEach { (id, measure) -> put(id, measureToJson(measure)) }
                            },
                        )
                    }
                },
            )

        val temporary = Files.createTempFile(parent, ".${file.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, root.toString())
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun splitAcrossHours(startEpochMs: Long, durationMs: Long, block: (Long, Long) -> Unit) {
        var cursor = startEpochMs
        var remaining = durationMs
        while (remaining > 0L) {
            val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
            val hourStart = zoned.truncatedTo(ChronoUnit.HOURS)
            val nextHour = hourStart.plusHours(1).toInstant().toEpochMilli()
            val untilBoundary = (nextHour - cursor).takeIf { it > 0L } ?: remaining
            val slice = min(remaining, untilBoundary)
            block(hourStart.toInstant().toEpochMilli(), slice)
            cursor = safeAdd(cursor, slice)
            remaining -= slice
        }
    }

    private fun recordAudibleLocked(
        song: StatisticsSongMetadata,
        startEpochMs: Long,
        durationMs: Long,
        playAtEpochMs: Long?,
    ) {
        songs[song.id] = song
        val total = totals.getOrPut(song.id, ::MutableMeasure)
        total.listeningTimeMs = safeAdd(total.listeningTimeMs, durationMs)
        if (playAtEpochMs != null) {
            total.playCount = safeAdd(total.playCount, 1L)
            total.lastPlayedAtEpochMs = maxOf(total.lastPlayedAtEpochMs ?: Long.MIN_VALUE, playAtEpochMs)
        }

        splitAcrossHours(startEpochMs, durationMs) { hourStart, sliceMs ->
            val measure = hours.getOrPut(hourStart) { LinkedHashMap() }.getOrPut(song.id, ::MutableMeasure)
            measure.listeningTimeMs = safeAdd(measure.listeningTimeMs, sliceMs)
        }
        if (playAtEpochMs != null) {
            val hourStart = hourStartEpochMs(playAtEpochMs)
            val measure = hours.getOrPut(hourStart) { LinkedHashMap() }.getOrPut(song.id, ::MutableMeasure)
            measure.playCount = safeAdd(measure.playCount, 1L)
            measure.lastPlayedAtEpochMs = maxOf(measure.lastPlayedAtEpochMs ?: Long.MIN_VALUE, playAtEpochMs)
        }
    }

    private fun bumpRevisionLocked() {
        _revision.value = if (_revision.value == Long.MAX_VALUE) 0L else _revision.value + 1L
    }

    private fun hourStartEpochMs(epochMs: Long): Long =
        Instant.ofEpochMilli(epochMs).atZone(zoneId).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    /**
     * Range selection is calendar-bucket based. A partially covered hour is included if its local
     * hour bucket intersects the requested epoch range; callers wanting exact day/week periods should
     * pass local calendar boundaries.
     */
    private fun selectedHoursLocked(range: StatisticsRange): List<Map.Entry<Long, MutableMap<String, MutableMeasure>>> {
        if (range.isAllTime) return hours.entries.toList()
        return hours.entries.filter { (start, _) ->
            val end = nextHourStartEpochMs(start)
            (range.fromEpochMs == null || end > range.fromEpochMs) &&
                (range.toEpochMs == null || start < range.toEpochMs)
        }
    }

    private fun nextHourStartEpochMs(startEpochMs: Long): Long =
        Instant.ofEpochMilli(startEpochMs)
            .atZone(zoneId)
            .plusHours(1)
            .truncatedTo(ChronoUnit.HOURS)
            .toInstant()
            .toEpochMilli()

    private fun measuresForRangeLocked(range: StatisticsRange): Map<String, StatisticsMeasure> {
        if (range.isAllTime) return totals.mapValues { it.value.immutable() }
        val result = LinkedHashMap<String, StatisticsMeasure>()
        selectedHoursLocked(range).forEach { (_, bySong) ->
            bySong.forEach { (songId, measure) ->
                result[songId] = (result[songId] ?: StatisticsMeasure()) + measure.immutable()
            }
        }
        return result
    }

    private fun songToJson(song: StatisticsSongMetadata): JSONObject =
        JSONObject()
            .put("t", song.title)
            .put(
                "r",
                JSONArray().apply {
                    song.artists.forEach { artist ->
                        put(JSONArray().put(artist.id ?: JSONObject.NULL).put(artist.name))
                    }
                },
            )
            .put(
                "a",
                song.album?.let { JSONArray().put(it.id ?: JSONObject.NULL).put(it.title) } ?: JSONObject.NULL,
            )
            .put("i", song.artworkUri ?: JSONObject.NULL)
            .put("d", song.durationMs ?: JSONObject.NULL)
            .put("v", song.isMusicVideo)

    private fun parseSong(id: String, json: JSONObject): StatisticsSongMetadata =
        StatisticsSongMetadata(
            id = id,
            title = json.optString("t").ifBlank { id },
            artists = json.optJSONArray("r").toArtistList(),
            album = json.optJSONArray("a")?.let { array ->
                val title = array.optString(1)
                if (title.isBlank()) null else StatisticsAlbum(array.optNullableString(0), title)
            },
            artworkUri = json.optNullableString("i"),
            durationMs = json.optLong("d", -1L).takeIf { it >= 0L },
            isMusicVideo = json.optBoolean("v", false),
        )

    private fun measureToJson(measure: MutableMeasure): JSONArray =
        JSONArray()
            .put(measure.listeningTimeMs)
            .put(measure.playCount)
            .put(measure.lastPlayedAtEpochMs ?: JSONObject.NULL)

    private fun parseMeasure(json: JSONArray): MutableMeasure =
        MutableMeasure(
            listeningTimeMs = json.optLong(0, 0L).coerceAtLeast(0L),
            playCount = json.optLong(1, 0L).coerceAtLeast(0L),
            lastPlayedAtEpochMs = json.optLong(2, Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE },
        )

    private fun JSONArray?.toArtistList(): List<StatisticsArtist> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val artist = optJSONArray(index) ?: continue
                val name = artist.optString(1)
                if (name.isNotBlank()) add(StatisticsArtist(artist.optNullableString(0), name))
            }
        }
    }

    private fun JSONArray.optNullableString(index: Int): String? =
        if (isNull(index)) null else optString(index).takeIf(String::isNotBlank)

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun artistKey(artist: StatisticsArtist): String =
        artist.id?.takeIf(String::isNotBlank)?.let { "id:$it" }
            ?: "name:${artist.name.trim().lowercase()}"

    private fun albumKey(album: StatisticsAlbum): String =
        album.id?.takeIf(String::isNotBlank)?.let { "id:$it" }
            ?: "title:${album.title.trim().lowercase()}"

    private fun songComparator(sort: StatisticsSort): Comparator<SongStatistics> =
        when (sort) {
            StatisticsSort.LISTENING_TIME -> compareByDescending<SongStatistics> { it.listeningTimeMs }
                .thenByDescending { it.playCount }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.song.title }
            StatisticsSort.PLAY_COUNT -> compareByDescending<SongStatistics> { it.playCount }
                .thenByDescending { it.listeningTimeMs }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.song.title }
        }

    private fun artistComparator(sort: StatisticsSort): Comparator<ArtistStatistics> =
        when (sort) {
            StatisticsSort.LISTENING_TIME -> compareByDescending<ArtistStatistics> { it.listeningTimeMs }
                .thenByDescending { it.playCount }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist.name }
            StatisticsSort.PLAY_COUNT -> compareByDescending<ArtistStatistics> { it.playCount }
                .thenByDescending { it.listeningTimeMs }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist.name }
        }

    private fun albumComparator(sort: StatisticsSort): Comparator<AlbumStatistics> =
        when (sort) {
            StatisticsSort.LISTENING_TIME -> compareByDescending<AlbumStatistics> { it.listeningTimeMs }
                .thenByDescending { it.playCount }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album.title }
            StatisticsSort.PLAY_COUNT -> compareByDescending<AlbumStatistics> { it.playCount }
                .thenByDescending { it.listeningTimeMs }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album.title }
        }

    private inline fun <T> Iterable<T>.sumOfSaturated(selector: (T) -> Long): Long {
        var total = 0L
        for (item in this) total = safeAdd(total, selector(item))
        return total
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_VERSION = "version"
        const val KEY_LEGACY_IMPORT_VERSION = "legacyImportVersion"
        const val KEY_SONGS = "songs"
        const val KEY_TOTALS = "totals"
        const val KEY_HOURS = "hours"
    }
}
