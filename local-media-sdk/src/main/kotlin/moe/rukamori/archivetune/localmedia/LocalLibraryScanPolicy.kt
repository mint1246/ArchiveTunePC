/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmedia

import androidx.compose.runtime.Immutable
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

/**
 * Sanitized configuration for a local-library scan.
 *
 * Folder entries are normalized and case-insensitively deduplicated exactly once here so every
 * consumer (Android scanning, settings UI and the view model) observes the same value. Moving this
 * type into the headless SDK does not change the scan decision for any existing settings value.
 */
data class LocalSongScanConfig(
    val minimumDurationSeconds: Int = 0,
    val includedFolders: Set<String> = emptySet(),
    val excludedFolders: Set<String> = emptySet(),
) {
    val sanitizedMinimumDurationSeconds: Int
        get() = minimumDurationSeconds.coerceAtLeast(0)

    val sanitizedIncludedFolders: Set<String>
        get() = LocalLibraryScanPolicy.deduplicateFolderEntries(includedFolders)

    val sanitizedExcludedFolders: Set<String>
        get() = LocalLibraryScanPolicy.deduplicateFolderEntries(excludedFolders)

    companion object {
        fun normalizeFolderEntry(raw: String): String = LocalLibraryScanPolicy.normalizeFolderEntry(raw)

        fun deduplicateFolderEntries(entries: Iterable<String>): Set<String> =
            LocalLibraryScanPolicy.deduplicateFolderEntries(entries)
    }
}

/** Outcome of a local-library scan. Kept stable for Compose state consumers. */
@Immutable
data class LocalSongScanSummary(
    val scannedSongs: Int,
    val removedSongs: Int,
    val metadataLookupFailed: Boolean = false,
)

/**
 * Headless policy for local-library scanning.
 *
 * Owns the platform-independent decisions used before any Android `MediaStore` cursor or database
 * write: folder entry normalization/inclusion, tag cleaning, artist identity, stable local ids and
 * the "has this file changed" check. The Android scanner now delegates to this object instead of
 * carrying private copies, so the same policy can be reused and tested without an Android runtime.
 */
object LocalLibraryScanPolicy {
    /** Tags that MediaStore and common taggers use for "no value"; treated as absent. */
    val UNKNOWN_TAGS: Set<String> =
        setOf("<unknown>", "unknown", "unknown artist", "unknown album", "unknown title")

    private val DuplicateSlashRegex = Regex("/+")

    fun normalizeFolderEntry(raw: String): String =
        raw
            .trim()
            .replace('\\', '/')
            .replace(DuplicateSlashRegex, "/")
            .trim('/')

    /** Case-insensitive, first-occurrence-wins deduplication that preserves folder display case. */
    fun deduplicateFolderEntries(entries: Iterable<String>): Set<String> {
        val deduplicated = linkedMapOf<String, String>()
        entries.forEach { entry ->
            val normalized = normalizeFolderEntry(entry)
            if (normalized.isNotEmpty()) {
                deduplicated.putIfAbsent(normalized.lowercase(Locale.ROOT), normalized)
            }
        }
        return deduplicated.values.toSet()
    }

    /**
     * Prefers MediaStore's relative path; falls back to the parent directory of a legacy absolute
     * data path. The result is normalized and lowercased so configured folders can be matched
     * consistently on every Android version.
     */
    fun resolveNormalizedFolderPath(
        relativePath: String?,
        absolutePath: String?,
    ): String? {
        val relativeFolder = normalizeFolderEntry(relativePath.orEmpty())
        if (relativeFolder.isNotEmpty()) {
            return relativeFolder.lowercase(Locale.ROOT)
        }

        val absoluteFolder =
            absolutePath
                ?.replace('\\', '/')
                ?.substringBeforeLast('/', missingDelimiterValue = "")
                .orEmpty()
        val normalizedAbsoluteFolder = normalizeFolderEntry(absoluteFolder)
        return normalizedAbsoluteFolder.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
    }

    fun shouldIncludeFolder(
        folderPath: String?,
        includedFolders: Set<String>,
    ): Boolean = if (includedFolders.isEmpty()) true else matchesFolderEntry(folderPath, includedFolders)

    fun shouldExcludeFolder(
        folderPath: String?,
        excludedFolders: Set<String>,
    ): Boolean = if (excludedFolders.isEmpty()) false else matchesFolderEntry(folderPath, excludedFolders)

    /** Matches an exact folder, any descendant and any ancestor segment. */
    fun matchesFolderEntry(
        folderPath: String?,
        folders: Set<String>,
    ): Boolean {
        if (folderPath.isNullOrEmpty()) return false
        return folders.any { folder ->
            folderPath == folder ||
                folderPath.startsWith("$folder/") ||
                folderPath.endsWith("/$folder") ||
                folderPath.contains("/$folder/")
        }
    }

    fun cleanTag(
        value: String?,
        unknownTags: Set<String> = UNKNOWN_TAGS,
    ): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && it.lowercase(Locale.ROOT) !in unknownTags
    }

    fun splitArtistNames(
        value: String,
        unknownTags: Set<String> = UNKNOWN_TAGS,
    ): List<String> =
        value
            .split(';', '\u0000')
            .mapNotNull { cleanTag(it, unknownTags) }
            .distinctBy(LocalMediaIdentity::normalize)

    fun albumArtistNames(
        artists: List<String>,
        albumArtist: String?,
        unknownTags: Set<String> = UNKNOWN_TAGS,
    ): List<String> {
        val splitAlbumArtist = albumArtist?.let { splitArtistNames(it, unknownTags) }
        return if (splitAlbumArtist.isNullOrEmpty()) artists else splitAlbumArtist
    }

    /** Stable grouping key for an album: normalized title plus sorted, normalized album artists. */
    fun albumKey(
        albumName: String?,
        artists: List<String>,
        albumArtist: String?,
        unknownTags: Set<String> = UNKNOWN_TAGS,
    ): String {
        val names = albumArtistNames(artists, albumArtist, unknownTags).map(LocalMediaIdentity::normalize)
        return "${LocalMediaIdentity.normalize(albumName.orEmpty())}|${names.sorted().joinToString("\u0000")}"
    }

    fun sameName(
        first: String,
        second: String,
    ): Boolean = LocalMediaIdentity.normalize(first) == LocalMediaIdentity.normalize(second)

    /** Deterministic name-based id suffix used when no remote artist/album id is available. */
    fun stableHash(source: String): String =
        UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8)).toString().replace("-", "")

    /**
     * A file is unchanged only when both previously persisted records exist, a modification time is
     * known, the modification time matches and the byte size matches. This is the same rule the
     * Android scanner used to avoid reparsing tags on every scan.
     */
    fun isTrackUnchanged(
        dateModified: LocalDateTime?,
        sizeBytes: Long,
        previousDateModified: LocalDateTime?,
        previousSizeBytes: Long?,
    ): Boolean =
        dateModified != null &&
            previousDateModified == dateModified &&
            previousSizeBytes == sizeBytes
}
