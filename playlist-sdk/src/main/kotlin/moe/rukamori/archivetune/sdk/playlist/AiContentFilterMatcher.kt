/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.playlist

/** Pure-JVM channel/artist key normalization, blocklist parsing, and content filtering rules. */
object AiContentFilterMatcher {
    fun normalizeChannelKey(value: String): String? =
        value
            .trim()
            .removePrefix("@")
            .asSequence()
            .filter(Char::isLetterOrDigit)
            .joinToString(separator = "")
            .lowercase()
            .takeIf { it.isNotBlank() }

    fun parseChannelList(raw: String): Set<String> =
        raw
            .lineSequence()
            .map(String::trim)
            .filter { line -> line.isNotEmpty() && !line.startsWith('!') }
            .mapNotNull(::normalizeChannelKey)
            .toSet()

    fun effectiveBlockedKeys(
        blocklist: Set<String>,
        warnlist: Set<String>,
        includeModerateConfidence: Boolean,
    ): Set<String> =
        if (includeModerateConfidence) {
            blocklist + warnlist
        } else {
            blocklist
        }

    fun isBlocked(
        creatorCandidates: Sequence<String?>,
        blockedChannelKeys: Set<String>,
    ): Boolean {
        if (blockedChannelKeys.isEmpty()) return false
        return creatorCandidates
            .filterNotNull()
            .mapNotNull(::normalizeChannelKey)
            .any(blockedChannelKeys::contains)
    }

    fun isBlocked(
        creatorCandidates: Iterable<String?>,
        blockedChannelKeys: Set<String>,
    ): Boolean = isBlocked(creatorCandidates.asSequence(), blockedChannelKeys)

    fun <T> filterItems(
        items: List<T>,
        enabled: Boolean,
        blockedChannelKeys: Set<String>,
        creatorCandidates: (T) -> Sequence<String?>,
    ): List<T> {
        if (!enabled || blockedChannelKeys.isEmpty()) return items
        return items.filterNot { item -> isBlocked(creatorCandidates(item), blockedChannelKeys) }
    }
}
