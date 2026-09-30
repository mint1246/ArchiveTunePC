/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Stable translation identity. Only its SHA-256 digest is persisted, never source text or keys. */
data class LyricsTranslationCacheKey(
    val trackId: String,
    val sourceText: String,
    val targetLanguage: String,
    val provider: LyricsTranslationProvider,
    val endpoint: String,
    val model: String,
    val customPrompt: String,
) {
    internal fun digest(): String {
        val fields = listOf(trackId, sourceText, targetLanguage, provider.name, endpoint, model, customPrompt)
        val canonical = buildString {
            fields.forEach { field ->
                append(field.length)
                append(':')
                append(field)
                append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** Small atomic disk cache for user-requested translations. It is bounded and stores no API key. */
class JvmLyricsTranslationCache(
    private val file: Path,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val entries = LinkedHashMap<String, List<String>>(16, 0.75f, true)

    init {
        require(maxEntries > 0)
        load()
    }

    @Synchronized
    fun get(key: LyricsTranslationCacheKey): List<String>? = entries[key.digest()]?.toList()

    @Synchronized
    fun put(key: LyricsTranslationCacheKey, translations: List<String>) {
        if (translations.isEmpty() || translations.any(String::isBlank)) return
        entries[key.digest()] = translations.toList()
        while (entries.size > maxEntries) entries.entries.iterator().run { next(); remove() }
        persist()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        Files.deleteIfExists(file)
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun load() {
        if (!Files.isRegularFile(file)) return
        if (runCatching { Files.size(file) > MAX_CACHE_BYTES }.getOrDefault(true)) return
        runCatching {
            val root = JSONObject(Files.readString(file))
            val values = root.optJSONArray("translations") ?: JSONArray()
            for (index in 0 until values.length()) {
                val entry = values.optJSONObject(index) ?: continue
                val key = entry.optString("key").takeIf { HEX_DIGEST.matches(it) } ?: continue
                val lines = entry.optJSONArray("lines") ?: continue
                val translations = List(lines.length()) { line -> lines.optString(line) }
                if (translations.isNotEmpty() && translations.none(String::isBlank)) {
                    entries[key] = translations
                }
            }
            while (entries.size > maxEntries) entries.entries.iterator().run { next(); remove() }
        }
    }

    private fun persist() {
        val parent = checkNotNull(file.parent) { "Translation cache path must have a parent directory" }
        runCatching {
            Files.createDirectories(parent)
            var payload = encodedEntries()
            while (payload.toByteArray(Charsets.UTF_8).size > MAX_CACHE_BYTES && entries.size > 1) {
                entries.entries.iterator().run { next(); remove() }
                payload = encodedEntries()
            }
            if (payload.toByteArray(Charsets.UTF_8).size > MAX_CACHE_BYTES) return
            val temporary = Files.createTempFile(parent, ".${file.fileName}.", ".tmp")
            try {
                Files.writeString(
                    temporary,
                    payload,
                    Charsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    private fun encodedEntries(): String {
        val translations = JSONArray()
        entries.forEach { (key, lines) ->
            translations.put(JSONObject().put("key", key).put("lines", JSONArray(lines)))
        }
        return JSONObject().put("schema", 1).put("translations", translations).toString()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 128
        private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
        private val HEX_DIGEST = Regex("^[a-f0-9]{64}$")
    }
}
