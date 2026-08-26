/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

data class DesktopStemStorageUsage(
    val stemsBytes: Long,
    val modelsBytes: Long,
) {
    val totalBytes: Long get() = stemsBytes + modelsBytes
}

object DesktopStemStorage {
    fun stemsDirectory(paths: AppPaths): Path = paths.cache.resolve("stems")

    fun modelsDirectory(paths: AppPaths): Path = paths.cache.resolve("stem-models")

    fun measure(paths: AppPaths): DesktopStemStorageUsage =
        DesktopStemStorageUsage(
            stemsBytes = directoryBytes(stemsDirectory(paths)),
            modelsBytes = directoryBytes(modelsDirectory(paths)),
        )

    private fun directoryBytes(root: Path): Long {
        if (!Files.isDirectory(root)) return 0L
        return try {
            Files.walk(root).use { entries ->
                entries
                    .filter(Files::isRegularFile)
                    .mapToLong { path -> runCatching { Files.size(path) }.getOrDefault(0L) }
                    .sum()
            }
        } catch (_: IOException) {
            0L
        } catch (_: SecurityException) {
            0L
        }
    }
}

internal fun formatStemStorageBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L).toDouble()
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    return when {
        safe >= gib -> "%.2f GB".format(safe / gib)
        safe >= mib -> "%.1f MB".format(safe / mib)
        safe >= kib -> "%.1f KB".format(safe / kib)
        else -> "${bytes.coerceAtLeast(0L)} B"
    }
}
