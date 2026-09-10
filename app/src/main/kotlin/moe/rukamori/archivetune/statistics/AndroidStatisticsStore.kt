/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.statistics

import androidx.sqlite.db.SupportSQLiteDatabase
import moe.rukamori.archivetune.db.MusicDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.min

/**
 * Authoritative Android statistics persisted in the existing song.db without changing Room's schema.
 *
 * The app's historical `event` table deliberately keeps its existing history semantics. Statistics
 * need a different invariant: all audible time is stored even before a play reaches its threshold.
 * These sidecar tables are therefore created lazily with IF NOT EXISTS and never registered as Room
 * entities, avoiding a destructive migration/version bump while keeping the data in the same DB.
 */
class AndroidStatisticsStore(
    private val database: MusicDatabase,
    val zoneId: ZoneId = ZoneId.systemDefault(),
) : AndroidStatisticsSink {
    @Volatile
    private var schemaReady = false

    override fun recordAudible(
        song: AndroidStatisticsSong,
        startEpochMs: Long,
        durationMs: Long,
        playAtEpochMs: Long?,
    ) {
        require(durationMs >= 0L) { "Listening duration must be non-negative" }
        if (durationMs == 0L && playAtEpochMs == null) return

        val db = writableDb()
        db.beginTransaction()
        try {
            upsertMetadata(db, song)
            splitAcrossHours(startEpochMs, durationMs) { hourStart, sliceMs ->
                db.execSQL(
                    "INSERT OR IGNORE INTO $TABLE_HOUR(songId, hourStartEpochMs, listeningTimeMs, playCount) VALUES (?, ?, 0, 0)",
                    arrayOf<Any?>(song.id, hourStart),
                )
                db.execSQL(
                    "UPDATE $TABLE_HOUR SET listeningTimeMs = listeningTimeMs + ? WHERE songId = ? AND hourStartEpochMs = ?",
                    arrayOf<Any?>(sliceMs, song.id, hourStart),
                )
            }
            if (playAtEpochMs != null) {
                val hourStart = hourStartEpochMs(playAtEpochMs)
                db.execSQL(
                    "INSERT OR IGNORE INTO $TABLE_HOUR(songId, hourStartEpochMs, listeningTimeMs, playCount) VALUES (?, ?, 0, 0)",
                    arrayOf<Any?>(song.id, hourStart),
                )
                db.execSQL(
                    "UPDATE $TABLE_HOUR SET playCount = playCount + 1 WHERE songId = ? AND hourStartEpochMs = ?",
                    arrayOf<Any?>(song.id, hourStart),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun overview(
        range: AndroidStatisticsEpochRange,
        songLimit: Int = 20,
        artistLimit: Int = 12,
        albumLimit: Int = 12,
    ): AndroidStatisticsOverview {
        require(songLimit >= 0 && artistLimit >= 0 && albumLimit >= 0) { "Statistics limits must be non-negative" }
        val db = writableDb()
        val where = rangeWhere(range, alias = "h")
        val args = rangeArgs(range)

        val summary =
            AndroidStatisticsSummary(
                totalListeningMs = scalarLong(db, "SELECT COALESCE(SUM(h.listeningTimeMs), 0) FROM $TABLE_HOUR h$where", args),
                totalPlays = scalarLong(db, "SELECT COALESCE(SUM(h.playCount), 0) FROM $TABLE_HOUR h$where", args),
                uniqueSongs = scalarLong(db, "SELECT COUNT(DISTINCT h.songId) FROM $TABLE_HOUR h$where", args).toSafeInt(),
                uniqueArtists =
                    scalarLong(
                        db,
                        """
                        SELECT COUNT(DISTINCT a.artistKey)
                        FROM $TABLE_ARTIST a
                        JOIN $TABLE_HOUR h ON h.songId = a.songId
                        $where
                        """.trimIndent(),
                        args,
                    ).toSafeInt(),
                uniqueAlbums =
                    scalarLong(
                        db,
                        """
                        SELECT COUNT(DISTINCT CASE
                            WHEN s.albumId IS NOT NULL AND s.albumId != '' THEN 'id:' || s.albumId
                            WHEN s.albumTitle IS NOT NULL AND s.albumTitle != '' THEN 'title:' || lower(trim(s.albumTitle))
                            ELSE NULL
                        END)
                        FROM $TABLE_SONG s
                        JOIN $TABLE_HOUR h ON h.songId = s.songId
                        $where
                        """.trimIndent(),
                        args,
                    ).toSafeInt(),
            )

        return AndroidStatisticsOverview(
            summary = summary,
            topSongs = topSongs(db, range, songLimit),
            topArtists = topArtists(db, range, artistLimit),
            topAlbums = topAlbums(db, range, albumLimit),
        )
    }

    private fun topSongs(
        db: SupportSQLiteDatabase,
        range: AndroidStatisticsEpochRange,
        limit: Int,
    ): List<AndroidStatisticsMedia> {
        if (limit == 0) return emptyList()
        val where = rangeWhere(range, "h")
        val args = rangeArgs(range) + limit
        val sql =
            """
            SELECT s.songId, s.title, s.artworkUrl,
                   COALESCE(SUM(h.listeningTimeMs), 0) AS listeningMs,
                   COALESCE(SUM(h.playCount), 0) AS playCount
            FROM $TABLE_SONG s
            JOIN $TABLE_HOUR h ON h.songId = s.songId
            $where
            GROUP BY s.songId, s.title, s.artworkUrl
            HAVING listeningMs > 0 OR playCount > 0
            ORDER BY listeningMs DESC, playCount DESC, s.title COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent()

        val result = ArrayList<AndroidStatisticsMedia>()
        db.query(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val songId = cursor.getString(0)
                result +=
                    AndroidStatisticsMedia(
                        stableId = songId,
                        title = cursor.getString(1),
                        subtitle = artistText(db, songId),
                        artworkUrl = cursor.getStringOrNull(2),
                        listeningMs = cursor.getLong(3),
                        playCount = cursor.getLong(4),
                        target = AndroidStatisticsTarget.Song(songId),
                    )
            }
        }
        return result
    }

    private fun topArtists(
        db: SupportSQLiteDatabase,
        range: AndroidStatisticsEpochRange,
        limit: Int,
    ): List<AndroidStatisticsMedia> {
        if (limit == 0) return emptyList()
        val where = rangeWhere(range, "h")
        val args = rangeArgs(range) + limit
        val sql =
            """
            SELECT a.artistKey, a.artistId, a.artistName,
                   COALESCE(SUM(h.listeningTimeMs), 0) AS listeningMs,
                   COALESCE(SUM(h.playCount), 0) AS playCount,
                   COUNT(DISTINCT h.songId) AS uniqueSongs
            FROM $TABLE_ARTIST a
            JOIN $TABLE_HOUR h ON h.songId = a.songId
            $where
            GROUP BY a.artistKey, a.artistId, a.artistName
            HAVING listeningMs > 0 OR playCount > 0
            ORDER BY listeningMs DESC, playCount DESC, a.artistName COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent()

        val result = ArrayList<AndroidStatisticsMedia>()
        db.query(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val artistKey = cursor.getString(0)
                val artistId = cursor.getStringOrNull(1)
                result +=
                    AndroidStatisticsMedia(
                        stableId = artistKey,
                        title = cursor.getString(2),
                        artworkUrl = artistArtwork(db, artistKey, range),
                        listeningMs = cursor.getLong(3),
                        playCount = cursor.getLong(4),
                        uniqueSongs = cursor.getInt(5),
                        target = artistId?.takeIf(String::isNotBlank)?.let(AndroidStatisticsTarget::Artist),
                    )
            }
        }
        return result
    }

    private fun topAlbums(
        db: SupportSQLiteDatabase,
        range: AndroidStatisticsEpochRange,
        limit: Int,
    ): List<AndroidStatisticsMedia> {
        if (limit == 0) return emptyList()
        val rangeAnd = rangeAnd(range, "h")
        val args = rangeArgs(range) + limit
        val albumKey =
            "CASE WHEN s.albumId IS NOT NULL AND s.albumId != '' THEN 'id:' || s.albumId ELSE 'title:' || lower(trim(s.albumTitle)) END"
        val sql =
            """
            SELECT $albumKey AS albumKey, s.albumId, s.albumTitle,
                   COALESCE(SUM(h.listeningTimeMs), 0) AS listeningMs,
                   COALESCE(SUM(h.playCount), 0) AS playCount,
                   COUNT(DISTINCT h.songId) AS uniqueSongs
            FROM $TABLE_SONG s
            JOIN $TABLE_HOUR h ON h.songId = s.songId
            WHERE s.albumTitle IS NOT NULL AND s.albumTitle != ''$rangeAnd
            GROUP BY albumKey, s.albumId, s.albumTitle
            HAVING listeningMs > 0 OR playCount > 0
            ORDER BY listeningMs DESC, playCount DESC, s.albumTitle COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent()

        val result = ArrayList<AndroidStatisticsMedia>()
        db.query(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                val albumId = cursor.getStringOrNull(1)
                result +=
                    AndroidStatisticsMedia(
                        stableId = key,
                        title = cursor.getString(2),
                        subtitle = albumArtistText(db, key, range),
                        artworkUrl = albumArtwork(db, key, range),
                        listeningMs = cursor.getLong(3),
                        playCount = cursor.getLong(4),
                        uniqueSongs = cursor.getInt(5),
                        target = albumId?.takeIf(String::isNotBlank)?.let(AndroidStatisticsTarget::Album),
                    )
            }
        }
        return result
    }

    private fun upsertMetadata(
        db: SupportSQLiteDatabase,
        song: AndroidStatisticsSong,
    ) {
        db.execSQL(
            "INSERT OR IGNORE INTO $TABLE_SONG(songId, title, artworkUrl, durationMs, albumId, albumTitle) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(song.id, song.title, song.artworkUrl, song.durationMs, song.album?.id, song.album?.title),
        )
        db.execSQL(
            """
            UPDATE $TABLE_SONG
            SET title = ?,
                artworkUrl = COALESCE(?, artworkUrl),
                durationMs = COALESCE(?, durationMs),
                albumId = COALESCE(?, albumId),
                albumTitle = COALESCE(?, albumTitle)
            WHERE songId = ?
            """.trimIndent(),
            arrayOf<Any?>(song.title, song.artworkUrl, song.durationMs, song.album?.id, song.album?.title, song.id),
        )

        db.execSQL("DELETE FROM $TABLE_ARTIST WHERE songId = ?", arrayOf(song.id))
        song.artists.forEachIndexed { index, artist ->
            val key = artistKey(artist)
            db.execSQL(
                "INSERT INTO $TABLE_ARTIST(songId, artistKey, artistId, artistName, position) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(song.id, key, artist.id, artist.name, index),
            )
        }
    }

    private fun artistText(
        db: SupportSQLiteDatabase,
        songId: String,
    ): String? {
        val names = ArrayList<String>()
        db.query(
            "SELECT artistName FROM $TABLE_ARTIST WHERE songId = ? ORDER BY position",
            arrayOf(songId),
        ).use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        return names.joinToString().takeIf(String::isNotBlank)
    }

    private fun artistArtwork(
        db: SupportSQLiteDatabase,
        artistKey: String,
        range: AndroidStatisticsEpochRange,
    ): String? {
        val rangeAnd = rangeAnd(range, "h")
        val args = listOf(artistKey) + rangeArgs(range)
        return scalarString(
            db,
            """
            SELECT s.artworkUrl
            FROM $TABLE_ARTIST a
            JOIN $TABLE_SONG s ON s.songId = a.songId
            JOIN $TABLE_HOUR h ON h.songId = a.songId
            WHERE a.artistKey = ?$rangeAnd
              AND s.artworkUrl IS NOT NULL AND s.artworkUrl != ''
            GROUP BY s.songId, s.artworkUrl
            ORDER BY SUM(h.listeningTimeMs) DESC
            LIMIT 1
            """.trimIndent(),
            args,
        )
    }

    private fun albumArtwork(
        db: SupportSQLiteDatabase,
        albumKey: String,
        range: AndroidStatisticsEpochRange,
    ): String? {
        val rangeAnd = rangeAnd(range, "h")
        val args = listOf(albumKey) + rangeArgs(range)
        val keyExpression =
            "CASE WHEN s.albumId IS NOT NULL AND s.albumId != '' THEN 'id:' || s.albumId ELSE 'title:' || lower(trim(s.albumTitle)) END"
        return scalarString(
            db,
            """
            SELECT s.artworkUrl
            FROM $TABLE_SONG s
            JOIN $TABLE_HOUR h ON h.songId = s.songId
            WHERE $keyExpression = ?$rangeAnd
              AND s.artworkUrl IS NOT NULL AND s.artworkUrl != ''
            GROUP BY s.songId, s.artworkUrl
            ORDER BY SUM(h.listeningTimeMs) DESC
            LIMIT 1
            """.trimIndent(),
            args,
        )
    }

    private fun albumArtistText(
        db: SupportSQLiteDatabase,
        albumKey: String,
        range: AndroidStatisticsEpochRange,
    ): String? {
        val rangeAnd = rangeAnd(range, "h")
        val args = listOf(albumKey) + rangeArgs(range)
        val keyExpression =
            "CASE WHEN s.albumId IS NOT NULL AND s.albumId != '' THEN 'id:' || s.albumId ELSE 'title:' || lower(trim(s.albumTitle)) END"
        return scalarString(
            db,
            """
            SELECT group_concat(name, ', ')
            FROM (
                SELECT DISTINCT a.artistName AS name
                FROM $TABLE_SONG s
                JOIN $TABLE_ARTIST a ON a.songId = s.songId
                JOIN $TABLE_HOUR h ON h.songId = s.songId
                WHERE $keyExpression = ?$rangeAnd
                ORDER BY a.position
                LIMIT 3
            )
            """.trimIndent(),
            args,
        )
    }

    private fun writableDb(): SupportSQLiteDatabase {
        val db = database.openHelper.writableDatabase
        ensureSchema(db)
        return db
    }

    @Synchronized
    private fun ensureSchema(db: SupportSQLiteDatabase) {
        if (schemaReady) return
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SONG (
                songId TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                artworkUrl TEXT,
                durationMs INTEGER,
                albumId TEXT,
                albumTitle TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ARTIST (
                songId TEXT NOT NULL,
                artistKey TEXT NOT NULL,
                artistId TEXT,
                artistName TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(songId, artistKey)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_HOUR (
                songId TEXT NOT NULL,
                hourStartEpochMs INTEGER NOT NULL,
                listeningTimeMs INTEGER NOT NULL,
                playCount INTEGER NOT NULL,
                PRIMARY KEY(songId, hourStartEpochMs)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_${TABLE_HOUR}_hour ON $TABLE_HOUR(hourStartEpochMs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_${TABLE_ARTIST}_artist ON $TABLE_ARTIST(artistKey)")
        schemaReady = true
    }

    private fun splitAcrossHours(
        startEpochMs: Long,
        durationMs: Long,
        block: (Long, Long) -> Unit,
    ) {
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

    private fun hourStartEpochMs(epochMs: Long): Long =
        Instant.ofEpochMilli(epochMs).atZone(zoneId).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    private fun rangeWhere(
        range: AndroidStatisticsEpochRange,
        alias: String,
    ): String {
        if (range.isAllTime) return ""
        val clauses = buildList {
            if (range.fromEpochMs != null) add("$alias.hourStartEpochMs >= ?")
            if (range.toEpochMs != null) add("$alias.hourStartEpochMs < ?")
        }
        return if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
    }

    private fun rangeAnd(
        range: AndroidStatisticsEpochRange,
        alias: String,
    ): String {
        val where = rangeWhere(range, alias)
        return if (where.isEmpty()) "" else " AND " + where.removePrefix(" WHERE ")
    }

    private fun rangeArgs(range: AndroidStatisticsEpochRange): List<Any> =
        buildList {
            range.fromEpochMs?.let(::add)
            range.toEpochMs?.let(::add)
        }

    private fun scalarLong(
        db: SupportSQLiteDatabase,
        sql: String,
        args: List<Any>,
    ): Long = db.query(sql, args.toTypedArray()).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun scalarString(
        db: SupportSQLiteDatabase,
        sql: String,
        args: List<Any>,
    ): String? = db.query(sql, args.toTypedArray()).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun artistKey(artist: AndroidStatisticsArtist): String =
        artist.id?.takeIf(String::isNotBlank)?.let { "id:$it" }
            ?: "name:${artist.name.trim().lowercase()}"

    private fun Long.toSafeInt(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val TABLE_SONG = "archivetune_statistics_song"
        const val TABLE_ARTIST = "archivetune_statistics_artist"
        const val TABLE_HOUR = "archivetune_statistics_hour"
    }
}
