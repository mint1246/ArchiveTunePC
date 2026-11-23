/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.playlist

data class PlaylistTagDraft(
    val name: String,
    val color: String,
)

data class PlaylistTagAssignment(
    val playlistId: String,
    val tagIds: List<String>,
)

data class BulkPlaylistTagAssignment(
    val playlistIds: List<String>,
    val tagIds: List<String>,
)

/** Pure-JVM normalization, parsing, and assignment policies for playlist tags. */
object PlaylistTagPolicy {
    fun normalizeTagName(name: String): String = name.trim()

    fun normalizeTagColor(color: String): String = color.trim()

    fun normalizeDraft(
        name: String,
        color: String,
    ): PlaylistTagDraft? {
        val normalizedName = normalizeTagName(name)
        val normalizedColor = normalizeTagColor(color)
        if (normalizedName.isEmpty() || normalizedColor.isEmpty()) return null
        return PlaylistTagDraft(name = normalizedName, color = normalizedColor)
    }

    fun normalizeIds(ids: Iterable<String>): List<String> =
        ids
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()

    fun parseTagNames(
        raw: String,
        delimiter: Char = ',',
    ): List<String> {
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        raw.split(delimiter).forEach { token ->
            val trimmed = token.trim()
            if (trimmed.isNotEmpty() && seen.add(trimmed.lowercase())) {
                result += trimmed
            }
        }
        return result
    }

    fun planReplacePlaylistTags(
        playlistId: String,
        tagIds: Iterable<String>,
    ): PlaylistTagAssignment? {
        val normalizedPlaylistId = playlistId.trim()
        if (normalizedPlaylistId.isEmpty()) return null
        return PlaylistTagAssignment(
            playlistId = normalizedPlaylistId,
            tagIds = normalizeIds(tagIds),
        )
    }

    fun planBulkTagAssignment(
        playlistIds: Iterable<String>,
        tagIds: Iterable<String>,
    ): BulkPlaylistTagAssignment? {
        val normalizedPlaylists = normalizeIds(playlistIds)
        val normalizedTags = normalizeIds(tagIds)
        if (normalizedPlaylists.isEmpty() || normalizedTags.isEmpty()) return null
        return BulkPlaylistTagAssignment(
            playlistIds = normalizedPlaylists,
            tagIds = normalizedTags,
        )
    }
}
