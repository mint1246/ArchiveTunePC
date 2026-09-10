/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/** Small durable per-track stem-mix store. Stem audio itself remains disposable cache data. */
class AndroidStemMixStore(
    private val file: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxEntries > 0)
    }

    @Synchronized
    fun load(trackId: String): AndroidStemControls {
        if (trackId.isBlank()) return AndroidStemControls()
        val properties = readProperties()
        val prefix = entryPrefix(trackId)
        if (properties.getProperty("${prefix}updated") == null) return AndroidStemControls()
        return AndroidStemControls(
            drums = properties.readControl(prefix, AndroidStemSource.DRUMS),
            bass = properties.readControl(prefix, AndroidStemSource.BASS),
            other = properties.readControl(prefix, AndroidStemSource.OTHER),
            vocals = properties.readControl(prefix, AndroidStemSource.VOCALS),
            guitar = properties.readControl(prefix, AndroidStemSource.GUITAR),
            piano = properties.readControl(prefix, AndroidStemSource.PIANO),
        ).sanitized()
    }

    @Synchronized
    fun save(
        trackId: String,
        controls: AndroidStemControls,
    ) {
        if (trackId.isBlank()) return
        val properties = readProperties()
        properties.setProperty("version", STORE_VERSION)
        val prefix = entryPrefix(trackId)
        properties.removeEntry(prefix)
        val safe = controls.sanitized()
        if (!safe.isDefault()) {
            properties.setProperty("${prefix}updated", nowMs().toString())
            AndroidStemSource.workerOrder.forEach { source ->
                val channel = safe.control(source)
                val channelPrefix = "$prefix${source.cacheName}."
                properties.setProperty("${channelPrefix}gain", channel.gain.toString())
                properties.setProperty("${channelPrefix}muted", channel.muted.toString())
                properties.setProperty("${channelPrefix}solo", channel.solo.toString())
            }
        }
        pruneOldest(properties)
        writeProperties(properties)
    }

    @Synchronized
    fun clear(trackId: String) {
        if (trackId.isBlank()) return
        val properties = readProperties()
        properties.removeEntry(entryPrefix(trackId))
        writeProperties(properties)
    }

    private fun readProperties(): Properties =
        Properties().also { properties ->
            if (!file.isFile) return@also
            runCatching { file.inputStream().buffered().use(properties::load) }
                .onFailure { properties.clear() }
        }

    private fun writeProperties(properties: Properties) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            properties.store(output, "ArchiveTune Android stem mixes")
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun pruneOldest(properties: Properties) {
        val entries =
            properties.stringPropertyNames()
                .asSequence()
                .filter { it.startsWith(ENTRY_PREFIX) && it.endsWith("updated") }
                .map { key ->
                    val prefix = key.removeSuffix("updated")
                    val updated = properties.getProperty(key)?.toLongOrNull() ?: Long.MIN_VALUE
                    prefix to updated
                }.sortedBy { it.second }
                .toList()
        entries.take((entries.size - maxEntries).coerceAtLeast(0)).forEach { (prefix, _) ->
            properties.removeEntry(prefix)
        }
    }

    private fun Properties.readControl(
        prefix: String,
        source: AndroidStemSource,
    ): AndroidStemChannelControl {
        val channelPrefix = "$prefix${source.cacheName}."
        return AndroidStemChannelControl(
            gain = getProperty("${channelPrefix}gain")?.toFloatOrNull() ?: 1f,
            muted = getProperty("${channelPrefix}muted")?.toBooleanStrictOrNull() ?: false,
            solo = getProperty("${channelPrefix}solo")?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun Properties.removeEntry(prefix: String) {
        stringPropertyNames().filter { it.startsWith(prefix) }.forEach(::remove)
    }

    private fun entryPrefix(trackId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(trackId.toByteArray(Charsets.UTF_8))
        val encoded = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$ENTRY_PREFIX$encoded."
    }

    private companion object {
        const val STORE_VERSION = "1"
        const val ENTRY_PREFIX = "track."
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
