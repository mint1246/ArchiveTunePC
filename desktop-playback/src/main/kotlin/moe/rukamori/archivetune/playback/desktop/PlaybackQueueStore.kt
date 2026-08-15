/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.absolute

/**
 * Persists the playback queue between launches so a restart keeps the queue contents, current item
 * and position, mirroring Android's `PersistQueue` / `PersistPlayerState` files.
 *
 * Only [PlaybackSource.Deferred] and [PlaybackSource.LocalFile] sources are stored verbatim; remote
 * sources keep their URL and expiry but never their request headers, which may carry credentials.
 */
class PlaybackQueueStore(private val file: Path) {
    data class Persisted(
        val title: String?,
        val items: List<PlaybackItem>,
        val currentIndex: Int,
        val positionMs: Long,
    )

    private val normalized = file.absolute().normalize()
    private val lock = ReentrantLock()

    fun load(): Persisted? = lock.withLock {
        if (!Files.isRegularFile(normalized)) return@withLock null
        runCatching { read(normalized) }.getOrNull()
    }

    fun save(snapshot: PlaybackSnapshot) = lock.withLock {
        val items = snapshot.queue
        if (items.isEmpty()) {
            Files.deleteIfExists(normalized)
            return@withLock
        }
        write(normalized, Persisted(snapshot.queueTitle, items, snapshot.currentIndex, snapshot.positionMs))
    }

    fun clear() = lock.withLock { Files.deleteIfExists(normalized) }

    private fun read(file: Path): Persisted {
        val json = JSONObject(Files.readString(file))
        val items = json.optJSONArray("items").orEmptyItems()
            .mapNotNull(::parseItem)
        return Persisted(
            title = json.optString("title").takeIf(String::isNotBlank),
            items = items,
            currentIndex = json.optInt("currentIndex", -1).coerceIn(-1, items.lastIndex),
            positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
        )
    }

    private fun write(file: Path, persisted: Persisted) {
        val parent = checkNotNull(file.parent) { "Queue store file must have a parent directory" }
        Files.createDirectories(parent)
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
        val temporary = Files.createTempFile(parent, ".${file.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, json.toString())
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
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
                JSONArray().apply { metadata.artists.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } },
            )
            .put("album", metadata.album?.let { JSONObject().put("id", it.id).put("title", it.title) } ?: JSONObject.NULL)
            .put("artworkUri", metadata.artworkUri ?: JSONObject.NULL)
            .put("durationHintMs", metadata.durationHintMs ?: JSONObject.NULL)
            .put("explicit", metadata.explicit)
            .put("isMusicVideo", metadata.isMusicVideo)
            .put("extras", JSONObject(metadata.extras))

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
            extras = json.optJSONObject("extras").orEmptyMap(),
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

    private fun JSONArray.orEmptyItems(): List<JSONObject> =
        buildList(length()) { for (i in 0 until length()) optJSONObject(i)?.let(::add) }

    private fun JSONObject.orEmptyMap(): Map<String, String> {
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
