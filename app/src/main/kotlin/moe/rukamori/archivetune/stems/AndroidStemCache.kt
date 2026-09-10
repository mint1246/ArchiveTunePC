/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

data class AndroidStemCacheEntry(
    val directory: File,
    val trackId: String,
    val durationMs: Long,
    val title: String?,
    val encoding: AndroidStemEncoding,
) {
    fun file(source: AndroidStemSource): File = File(directory, "${source.cacheName}.${encoding.extension}")
}

/**
 * Owns the portable six-file stem-cache contract. A manifest is written last, so a partially
 * generated/copied set is never advertised as playable.
 */
class AndroidStemCache(
    private val root: File,
    private val minStemBytes: Long = DEFAULT_MIN_STEM_BYTES,
) {
    init {
        require(minStemBytes >= 0L)
    }

    fun find(
        trackId: String,
        durationMs: Long,
    ): AndroidStemCacheEntry? = inspectDirectory(directoryFor(trackId, durationMs), trackId, durationMs)

    fun directoryFor(
        trackId: String,
        durationMs: Long,
    ): File = File(root, cacheKey(trackId, durationMs))

    /**
     * Installs an already-generated portable stem set. The future Android separator only needs to
     * produce six Opus or canonical PCM16 WAV files and call this method; playback/cache semantics
     * stay unchanged. WAV is the Android separator's portable lossless output path.
     */
    @Synchronized
    fun installGeneratedSet(
        trackId: String,
        durationMs: Long,
        title: String?,
        generatedDirectory: File,
    ): AndroidStemCacheEntry {
        require(trackId.isNotBlank()) { "Stem cache track id is blank" }
        require(durationMs >= -1L) { "Stem cache duration is invalid" }
        val encoding = detectGeneratedEncoding(generatedDirectory)
        AndroidStemSource.workerOrder.forEach { source ->
            val sourceFile = File(generatedDirectory, "${source.cacheName}.${encoding.extension}")
            require(sourceFile.isFile && sourceFile.length() > minStemBytes) {
                "Missing or empty ${source.cacheName} stem"
            }
        }

        root.mkdirs()
        val target = directoryFor(trackId, durationMs)
        find(trackId, durationMs)?.let { return it }
        val staging = File(root, ".${target.name}.${UUID.randomUUID()}.part")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not create stem cache staging directory" }
        try {
            AndroidStemSource.workerOrder.forEach { source ->
                File(generatedDirectory, "${source.cacheName}.${encoding.extension}")
                    .copyTo(File(staging, "${source.cacheName}.${encoding.extension}"), overwrite = true)
            }
            writeManifest(staging, trackId, durationMs, title, encoding)
            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                check(target.mkdirs()) { "Could not create stem cache directory" }
                AndroidStemSource.workerOrder.forEach { source ->
                    File(staging, "${source.cacheName}.${encoding.extension}")
                        .copyTo(File(target, "${source.cacheName}.${encoding.extension}"), overwrite = true)
                }
                File(staging, MANIFEST_FILE).copyTo(File(target, MANIFEST_FILE), overwrite = true)
            }
        } finally {
            staging.deleteRecursively()
        }
        return checkNotNull(find(trackId, durationMs)) { "Installed stem cache failed validation" }
    }

    internal fun cacheKey(
        trackId: String,
        durationMs: Long,
    ): String {
        val identity = "$ANDROID_STEM_CACHE_VERSION\u0000$trackId\u0000$durationMs"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun inspectDirectory(
        directory: File,
        expectedTrackId: String,
        expectedDurationMs: Long,
    ): AndroidStemCacheEntry? {
        if (!directory.isDirectory) return null
        val manifestFile = File(directory, MANIFEST_FILE)
        if (!manifestFile.isFile) return null
        val properties = Properties()
        if (runCatching { manifestFile.inputStream().buffered().use(properties::load) }.isFailure) return null
        val encoding = AndroidStemEncoding.entries.firstOrNull { it.manifestFormat == properties.getProperty("format") } ?: return null
        if (properties.getProperty("model") != ANDROID_STEM_CACHE_VERSION) return null
        if (properties.getProperty("trackId") != expectedTrackId) return null
        if (properties.getProperty("durationMs")?.toLongOrNull() != expectedDurationMs) return null
        if (properties.getProperty("stems") != AndroidStemSource.workerOrder.joinToString(",") { it.cacheName }) return null
        if (AndroidStemSource.workerOrder.any { source ->
                val file = File(directory, "${source.cacheName}.${encoding.extension}")
                !file.isFile || file.length() <= minStemBytes
            }
        ) {
            return null
        }
        return AndroidStemCacheEntry(
            directory = directory,
            trackId = expectedTrackId,
            durationMs = expectedDurationMs,
            title = properties.getProperty("title")?.takeIf(String::isNotBlank),
            encoding = encoding,
        )
    }

    private fun writeManifest(
        directory: File,
        trackId: String,
        durationMs: Long,
        title: String?,
        encoding: AndroidStemEncoding,
    ) {
        val properties = Properties().apply {
            setProperty("format", encoding.manifestFormat)
            setProperty("model", ANDROID_STEM_CACHE_VERSION)
            setProperty("trackId", trackId)
            setProperty("durationMs", durationMs.toString())
            setProperty("createdAtEpochMs", System.currentTimeMillis().toString())
            setProperty("stems", AndroidStemSource.workerOrder.joinToString(",") { it.cacheName })
            title?.takeIf(String::isNotBlank)?.let { setProperty("title", it) }
        }
        FileOutputStream(File(directory, MANIFEST_FILE)).use { output ->
            properties.store(output, "ArchiveTune Android stem cache")
            output.fd.sync()
        }
    }

    private fun detectGeneratedEncoding(directory: File): AndroidStemEncoding =
        AndroidStemEncoding.entries.firstOrNull { encoding ->
            AndroidStemSource.workerOrder.all { source ->
                val file = File(directory, "${source.cacheName}.${encoding.extension}")
                file.isFile && file.length() > minStemBytes
            }
        } ?: error("Generated stem set does not contain six complete Opus or WAV files")

    private companion object {
        const val MANIFEST_FILE = "manifest.properties"
        const val DEFAULT_MIN_STEM_BYTES = 1_024L
    }
}