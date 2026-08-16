/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.platform

import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import org.json.JSONObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.absolute

/**
 * Song→album memory for desktop, mirroring the album knowledge Android accumulates in its local
 * database. Every song that carries a usable album browse id teaches it to this index; the index
 * then backfills now-playing metadata for songs the YouTube Music response served without an album
 * (radio/mix tracks, some lyric or add-on uploads), so pressing the song name still opens the album.
 *
 * The index is only ever additive: it never removes or overrides album data, and a missing/blank id
 * is never stored because it cannot be browsed to.
 */
class DesktopAlbumIndex(private val file: Path) : AutoCloseable {
    private val normalized = file.absolute().normalize()
    private val entries = ConcurrentHashMap(load())
    private val lock = ReentrantLock()
    private val flusher: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "album-index-flusher").apply { isDaemon = true }
        }
    @Volatile private var saveScheduled = false

    /** Remembers a song→album mapping if it carries an id that can be browsed to. */
    fun learn(songId: String, album: PlaybackAlbum?) {
        val usable = album?.takeIf { canBrowse(it) } ?: return
        if (songId.isBlank()) return
        if (entries[songId] == usable) return
        entries[songId] = usable
        scheduleSave()
    }

    /** Best-known album for a song id that is actually navigable, or null if none is known yet. */
    fun albumFor(songId: String): PlaybackAlbum? =
        entries[songId]?.takeIf(::canBrowse)

    override fun close() {
        flush()
        flusher.shutdownNow()
    }

    private fun scheduleSave() = lock.withLock {
        if (saveScheduled) return@withLock
        saveScheduled = true
        flusher.schedule({ save() }, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun flush() = save()

    private fun save() {
        val snapshot = lock.withLock {
            saveScheduled = false
            entries.toMap()
        }
        if (snapshot.isEmpty()) {
            runCatching { Files.deleteIfExists(normalized) }
            return
        }
        val parent = runCatching { checkNotNull(normalized.parent) }.getOrNull() ?: return
        runCatching { Files.createDirectories(parent) }
        runCatching {
            val temporary = Files.createTempFile(parent, ".${normalized.fileName}.", ".tmp")
            try {
                Files.writeString(temporary, snapshotToJson(snapshot))
                try {
                    Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    private fun load(): Map<String, PlaybackAlbum> {
        if (!Files.isRegularFile(normalized)) return emptyMap()
        return runCatching {
            val json = JSONObject(Files.readString(normalized))
            buildMap {
                json.keys().forEach { songId ->
                    val album =
                        json.optJSONObject(songId)?.let { entry ->
                            PlaybackAlbum(
                                id = entry.optString("id").takeIf(String::isNotBlank),
                                title = entry.optString("title"),
                            )
                        }
                    if (album != null && canBrowse(album)) put(songId, album)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val SAVE_DELAY_MILLIS = 2_000L

        fun canBrowse(album: PlaybackAlbum): Boolean = !album.id.isNullOrBlank() && !album.title.isBlank()

        fun snapshotToJson(snapshot: Map<String, PlaybackAlbum>): String =
            JSONObject().apply {
                snapshot.forEach { (songId, album) ->
                    put(songId, JSONObject().put("id", album.id.orEmpty()).put("title", album.title))
                }
            }.toString()
    }
}