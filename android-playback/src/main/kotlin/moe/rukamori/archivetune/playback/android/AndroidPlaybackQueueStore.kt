/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.session.PersistedPlaybackQueue
import moe.rukamori.archivetune.playback.session.PlaybackQueuePersistence
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable JSON queue store for Android implementing [PlaybackQueuePersistence].
 *
 * Only [PlaybackSource.Deferred] and [PlaybackSource.LocalFile] sources are stored verbatim;
 * [PlaybackSource.Remote] keeps its URL, MIME type, and expiry, but never its request headers,
 * which may carry transient credentials.
 */
class AndroidPlaybackQueueStore(private val file: File) : PlaybackQueuePersistence {
    private val normalized = file.absoluteFile.normalize()
    private val lock = ReentrantLock()

    override fun load(): PersistedPlaybackQueue? = lock.withLock {
        if (!normalized.isFile) return@withLock null
        runCatching { read(normalized) }.getOrNull()
    }

    override fun save(snapshot: PlaybackSnapshot) = lock.withLock {
        val items = snapshot.queue
        if (items.isEmpty()) {
            normalized.delete()
            return@withLock
        }
        write(
            normalized,
            PersistedPlaybackQueue(
                title = snapshot.queueTitle,
                items = items,
                currentIndex = snapshot.currentIndex,
                positionMs = snapshot.positionMs,
            ),
        )
    }

    override fun clear() = lock.withLock {
        normalized.delete()
        Unit
    }

    private fun read(target: File): PersistedPlaybackQueue {
        val json = JSONObject(target.readText(Charsets.UTF_8))
        val items = json.optJSONArray("items").orEmptyItems().mapNotNull(::parseItem)
        return PersistedPlaybackQueue(
            title = json.optString("title").takeIf(String::isNotBlank),
            items = items,
            currentIndex = json.optInt("currentIndex", -1).coerceIn(-1, items.lastIndex),
            positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
        )
    }

    private fun write(target: File, persisted: PersistedPlaybackQueue) {
        val parent = checkNotNull(target.parentFile) { "Queue store file must have a parent directory" }
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Failed to create queue store directory: $parent")
        }
        val json = JSONObject()
        persisted.title?.let { json.put("title", it) }
        json.put("currentIndex", persisted.currentIndex)
        json.put("positionMs", persisted.positionMs)
        json.put(
            "items",
            JSONArray().apply {
                persisted.items.take(MAX_PERSISTED_ITEMS).forEach { put(itemToJson(it)) }
            },
        )
        val temporary = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            temporary.writeText(json.toString(), Charsets.UTF_8)
            moveReplacing(temporary, target)
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            if (target.exists() && !target.delete()) {
                throw IOException("Failed to delete existing queue file: $target")
            }
            if (!source.renameTo(target)) {
                throw IOException("Failed to rename temporary queue file to $target")
            }
        }
    }

    private fun itemToJson(item: PlaybackItem): JSONObject =
        JSONObject()
            .put("metadata", metadataToJson(item.metadata))
            .put("source", sourceToJson(item.source))

    private fun parseItem(json: JSONObject): PlaybackItem? {
        val metadata = json.optJSONObject("metadata")?.let(::parseMetadata) ?: return null
        val source = json.optJSONObject("source")?.let(::parseSource) ?: PlaybackSource.Deferred(metadata.id)
        return PlaybackItem(metadata, source)
    }

    private fun metadataToJson(metadata: PlaybackMetadata): JSONObject =
        JSONObject()
            .put("id", metadata.id)
            .put("title", metadata.title)
            .put(
                "artists",
                JSONArray().apply {
                    metadata.artists.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
                },
            )
            .put("album", metadata.album?.let { JSONObject().put("id", it.id).put("title", it.title) } ?: JSONObject.NULL)
            .put("artworkUri", metadata.artworkUri ?: JSONObject.NULL)
            .put("durationHintMs", metadata.durationHintMs ?: JSONObject.NULL)
            .put("explicit", metadata.explicit)
            .put("isMusicVideo", metadata.isMusicVideo)
            .put("isPodcast", metadata.isPodcast)
            .put("extras", JSONObject(metadata.extras))
            .put("albumArtist", metadata.albumArtist ?: JSONObject.NULL)
            .put("trackNumber", metadata.trackNumber ?: JSONObject.NULL)
            .put("discNumber", metadata.discNumber ?: JSONObject.NULL)
            .put("year", metadata.year ?: JSONObject.NULL)

    private fun parseMetadata(json: JSONObject): PlaybackMetadata =
        PlaybackMetadata(
            id = json.optString("id"),
            title = json.optString("title"),
            artists = json.optJSONArray("artists").orEmptyItems().map {
                PlaybackArtist(id = it.optString("id").takeIf(String::isNotBlank), name = it.optString("name"))
            },
            album = json.optJSONObject("album")?.takeUnless { it === JSONObject.NULL }?.let {
                PlaybackAlbum(id = it.optString("id").takeIf(String::isNotBlank), title = it.optString("title"))
            },
            artworkUri = json.optString("artworkUri").takeIf(String::isNotBlank),
            durationHintMs = json.optLong("durationHintMs", -1L).takeIf { it >= 0L },
            explicit = json.optBoolean("explicit"),
            isMusicVideo = json.optBoolean("isMusicVideo"),
            isPodcast = json.optBoolean("isPodcast"),
            extras = json.optJSONObject("extras").orEmptyMap(),
            albumArtist = json.optString("albumArtist").takeIf(String::isNotBlank),
            trackNumber = json.optInt("trackNumber", -1).takeIf { it > 0 },
            discNumber = json.optInt("discNumber", -1).takeIf { it > 0 },
            year = json.optInt("year", -1).takeIf { it in 1..9999 },
        )

    private fun sourceToJson(source: PlaybackSource): JSONObject =
        when (source) {
            is PlaybackSource.Deferred -> JSONObject().put("type", "deferred").put("key", source.key)
            is PlaybackSource.LocalFile -> JSONObject().put("type", "local").put("path", source.path)
            is PlaybackSource.Remote ->
                JSONObject()
                    .put("type", "remote")
                    .put("url", source.url)
                    .put("mimeType", source.mimeType ?: JSONObject.NULL)
                    .put("expiresAtEpochMs", source.expiresAtEpochMs ?: JSONObject.NULL)
        }

    private fun parseSource(json: JSONObject): PlaybackSource =
        when (json.optString("type")) {
            "local" -> PlaybackSource.LocalFile(json.optString("path"))
            "remote" -> PlaybackSource.Remote(
                url = json.optString("url"),
                mimeType = json.optString("mimeType").takeIf(String::isNotBlank),
                headers = emptyMap(),
                expiresAtEpochMs = json.optLong("expiresAtEpochMs", -1L).takeIf { it >= 0L },
            )
            else -> PlaybackSource.Deferred(json.optString("key"))
        }

    private fun JSONArray?.orEmptyItems(): List<JSONObject> =
        if (this == null) {
            emptyList()
        } else {
            buildList(length()) { for (i in 0 until length()) optJSONObject(i)?.let(::add) }
        }

    private fun JSONObject?.orEmptyMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val keys = keys()
        val result = LinkedHashMap<String, String>()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = optString(key)
        }
        return result
    }

    private companion object {
        const val MAX_PERSISTED_ITEMS = 2_000
    }
}
