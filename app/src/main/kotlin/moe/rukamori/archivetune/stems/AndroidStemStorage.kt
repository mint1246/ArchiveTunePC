/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import java.io.File
import java.io.IOException
import moe.rukamori.archivetune.modelruntime.AndroidStemModelSpec

data class AndroidStemStorageUsage(
    val stemsBytes: Long = 0L,
    val modelBytes: Long = 0L,
) {
    val totalBytes: Long get() = stemsBytes + modelBytes
}

data class AndroidStemStorageState(
    val usage: AndroidStemStorageUsage = AndroidStemStorageUsage(),
    val measuring: Boolean = false,
    val clearingStems: Boolean = false,
    val clearingModel: Boolean = false,
    val error: String? = null,
) {
    val busy: Boolean get() = measuring || clearingStems || clearingModel
}

/** Measures and clears only ArchiveTune's HTDemucs assets; shared chord/model assets are untouched. */
class AndroidStemStorage(
    private val stemsRoot: File,
    private val modelRoot: File,
) {
    fun measure(): AndroidStemStorageUsage =
        AndroidStemStorageUsage(
            stemsBytes = directoryBytes(stemsRoot),
            modelBytes = stemModelFiles().sumOf(::safeLength),
        )

    /** Deletes installed stem sets while leaving the root directory available for future writes. */
    fun clearStems(): Long {
        val before = directoryBytes(stemsRoot)
        deleteChildren(stemsRoot)
        return before
    }

    /** Deletes only the pinned HTDemucs model and its interrupted-download parts. */
    fun clearStemModel(): Long {
        val files = stemModelFiles()
        val before = files.sumOf(::safeLength)
        files.forEach { file ->
            check(isInside(modelRoot, file)) { "Refusing to delete model outside ArchiveTune model storage" }
            if (file.exists() && !file.delete()) {
                throw IOException("Could not delete ${file.name}")
            }
        }
        return before
    }

    private fun stemModelFiles(): List<File> {
        if (!modelRoot.isDirectory) return emptyList()
        val acceptedNames = AndroidStemModelSpec.LEGACY_FILES + AndroidStemModelSpec.FILE
        return modelRoot.listFiles().orEmpty().filter { file ->
            file.isFile && acceptedNames.any { name ->
                file.name == name || (file.name.startsWith(".$name.") && file.name.endsWith(".part"))
            }
        }
    }

    private fun deleteChildren(root: File) {
        if (!root.exists()) return
        check(root.isDirectory) { "Stem cache root is not a directory" }
        root.listFiles().orEmpty().forEach { child ->
            check(isInside(root, child)) { "Refusing to delete stem cache outside ArchiveTune storage" }
            if (!child.deleteRecursively() && child.exists()) {
                throw IOException("Could not delete ${child.name}")
            }
        }
    }

    private fun directoryBytes(root: File): Long {
        if (!root.isDirectory) return 0L
        return runCatching {
            root.walkTopDown().filter(File::isFile).sumOf(::safeLength)
        }.getOrDefault(0L)
    }

    private fun safeLength(file: File): Long = runCatching { file.length().coerceAtLeast(0L) }.getOrDefault(0L)

    private fun isInside(root: File, candidate: File): Boolean =
        runCatching {
            val rootPath = root.canonicalFile.toPath()
            candidate.canonicalFile.toPath().startsWith(rootPath)
        }.getOrDefault(false)
}

internal fun formatAndroidStemStorageBytes(bytes: Long): String {
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
