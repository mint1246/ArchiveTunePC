/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.ios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.session.PersistedPlaybackQueue
import moe.rukamori.archivetune.playback.session.PlaybackQueuePersistence

/**
 * Portable JSON codec for durable iOS playback queue checkpoints.
 *
 * Strips transient HTTP request headers on both encode and decode so credentials and signed
 * authorization headers are never persisted to disk.
 */
object IosPlaybackQueueCodec {
    private const val VERSION = 1
    const val MAX_PERSISTED_ITEMS: Int = 2_000
    private const val MAX_JSON_LENGTH: Int = 16_000_000

    fun encode(snapshot: PlaybackSnapshot): String? {
        if (snapshot.queue.isEmpty()) return null
        val bounded = snapshot.queue.take(MAX_PERSISTED_ITEMS)
        val index = snapshot.currentIndex.coerceIn(0, bounded.lastIndex)
        return encode(
            PersistedPlaybackQueue(
                title = snapshot.queueTitle,
                items = bounded,
                currentIndex = index,
                positionMs = snapshot.positionMs.coerceAtLeast(0L),
            ),
        )
    }

    fun encode(queue: PersistedPlaybackQueue): String {
        val items = queue.items.take(MAX_PERSISTED_ITEMS)
        val index = if (items.isEmpty()) -1 else queue.currentIndex.coerceIn(0, items.lastIndex)
        val root = buildJsonObject {
            put("version", VERSION)
            queue.title?.let { put("title", it) }
            put("currentIndex", index)
            put("positionMs", queue.positionMs.coerceAtLeast(0L))
            put("items", buildJsonArray {
                items.forEach { item -> add(encodeItem(item)) }
            })
        }
        return root.toString()
    }

    fun decode(raw: String): PersistedPlaybackQueue? {
        if (raw.isBlank() || raw.length > MAX_JSON_LENGTH) return null
        return try {
            val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
            val version = root.int("version") ?: VERSION
            if (version !in 0..VERSION) return null
            val rawItems = (root["items"] as? JsonArray).orEmpty().take(MAX_PERSISTED_ITEMS)
            val items = rawItems.mapNotNull { decodeItem(it as? JsonObject) }
            if (items.isEmpty()) return null
            val currentIndex = (root.int("currentIndex") ?: 0).coerceIn(0, items.lastIndex)
            val positionMs = (root.long("positionMs") ?: 0L).coerceAtLeast(0L)
            PersistedPlaybackQueue(
                title = root.string("title"),
                items = items,
                currentIndex = currentIndex,
                positionMs = positionMs,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeItem(item: PlaybackItem): JsonObject = buildJsonObject {
        val m = item.metadata
        put("id", m.id)
        put("title", m.title)
        put("artists", buildJsonArray {
            m.artists.forEach { artist ->
                add(buildJsonObject {
                    artist.id?.let { put("id", it) }
                    put("name", artist.name)
                })
            }
        })
        m.album?.let { album ->
            put("album", buildJsonObject {
                album.id?.let { put("id", it) }
                put("title", album.title)
            })
        }
        m.artworkUri?.let { put("artworkUri", it) }
        m.durationHintMs?.let { put("durationHintMs", it) }
        if (m.explicit) put("explicit", true)
        if (m.isMusicVideo) put("isMusicVideo", true)
        if (m.isPodcast) put("isPodcast", true)
        m.albumArtist?.let { put("albumArtist", it) }
        m.trackNumber?.let { put("trackNumber", it) }
        m.discNumber?.let { put("discNumber", it) }
        m.year?.let { put("year", it) }
        if (m.extras.isNotEmpty()) {
            put("extras", buildJsonObject {
                m.extras.forEach { (k, v) -> put(k, v) }
            })
        }
        put("source", encodeSource(item.source))
    }

    private fun encodeSource(source: PlaybackSource): JsonObject = buildJsonObject {
        when (source) {
            is PlaybackSource.Deferred -> {
                put("type", "deferred")
                put("key", source.key)
            }
            is PlaybackSource.Remote -> {
                put("type", "remote")
                put("url", source.url)
                source.mimeType?.let { put("mimeType", it) }
                source.expiresAtEpochMs?.let { put("expiresAtEpochMs", it) }
                // Deliberately omit transient HTTP headers.
            }
            is PlaybackSource.LocalFile -> {
                put("type", "local")
                put("path", source.path)
            }
        }
    }

    private fun decodeItem(obj: JsonObject?): PlaybackItem? {
        if (obj == null) return null
        val id = obj.string("id")?.takeIf(String::isNotBlank) ?: return null
        val title = obj.string("title") ?: id
        val artists = (obj["artists"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val artistObj = entry as? JsonObject ?: return@mapNotNull null
            val name = artistObj.string("name") ?: return@mapNotNull null
            PlaybackArtist(id = artistObj.string("id"), name = name)
        }
        val album = (obj["album"] as? JsonObject)?.let { albumObj ->
            albumObj.string("title")?.let { PlaybackAlbum(id = albumObj.string("id"), title = it) }
        }
        val extras = (obj["extras"] as? JsonObject)?.mapNotNull { (k, v) ->
            val value = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
            k to value
        }?.toMap().orEmpty()
        val metadata = PlaybackMetadata(
            id = id,
            title = title,
            artists = artists,
            album = album,
            artworkUri = obj.string("artworkUri"),
            durationHintMs = obj.long("durationHintMs"),
            explicit = obj.boolean("explicit") ?: false,
            isMusicVideo = obj.boolean("isMusicVideo") ?: false,
            extras = extras,
            isPodcast = obj.boolean("isPodcast") ?: false,
            albumArtist = obj.string("albumArtist"),
            trackNumber = obj.int("trackNumber"),
            discNumber = obj.int("discNumber"),
            year = obj.int("year"),
        )
        val source = decodeSource(obj["source"] as? JsonObject, fallbackId = id)
        return PlaybackItem(metadata = metadata, source = source)
    }

    private fun decodeSource(obj: JsonObject?, fallbackId: String): PlaybackSource {
        if (obj == null) return PlaybackSource.Deferred(fallbackId)
        return when (obj.string("type")) {
            "remote" -> {
                val url = obj.string("url")
                if (url.isNullOrBlank()) {
                    PlaybackSource.Deferred(fallbackId)
                } else {
                    PlaybackSource.Remote(
                        url = url,
                        mimeType = obj.string("mimeType"),
                        headers = emptyMap(),
                        expiresAtEpochMs = obj.long("expiresAtEpochMs"),
                    )
                }
            }
            "local" -> {
                val path = obj.string("path")
                if (path.isNullOrBlank()) PlaybackSource.Deferred(fallbackId)
                else PlaybackSource.LocalFile(path)
            }
            else -> PlaybackSource.Deferred(obj.string("key")?.takeIf(String::isNotBlank) ?: fallbackId)
        }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull
}

/**
 * Caller-backed [PlaybackQueuePersistence] delegating raw JSON read/write/delete to iOS storage
 * (e.g. `NSApplicationSupportDirectory` atomic file store or in-memory test store).
 */
class StringBackedPlaybackQueuePersistence(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val delete: () -> Unit,
) : PlaybackQueuePersistence {
    override fun load(): PersistedPlaybackQueue? = read()?.let(IosPlaybackQueueCodec::decode)

    override fun save(snapshot: PlaybackSnapshot) {
        val encoded = IosPlaybackQueueCodec.encode(snapshot)
        if (encoded == null) {
            delete()
        } else {
            write(encoded)
        }
    }

    override fun clear() {
        delete()
    }
}
