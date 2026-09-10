/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

/** Small provider-query policy shared by automatic lyrics lookup paths. */
internal object LyricsQueryPolicy {
    const val PROVIDER_TIMEOUT_MS = 8_000L

    fun titleVariants(title: String): List<String> {
        val original = title.trim()
        if (original.isEmpty()) return emptyList()
        val canonical =
            original
                .replace(FEATURE_SUFFIX, "")
                .replace(RELEASE_SUFFIX, "")
                .replace(DASH_RELEASE_SUFFIX, "")
                .replace(WHITESPACE, " ")
                .trim()
        return listOf(canonical, original).filter(String::isNotEmpty).distinct()
    }

    fun cacheKey(
        mediaId: String?,
        title: String,
        artists: String,
    ): String =
        mediaId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { "id:$it" }
            ?: "meta:${artists.trim()}\u0000${title.trim()}"

    private val FEATURE_SUFFIX =
        Regex("""\s*[\[(](?:feat(?:uring)?|ft)\.?\s+[^\])]+[\])]""", RegexOption.IGNORE_CASE)
    private val RELEASE_SUFFIX =
        Regex(
            """\s*[\[(](?:official\s+(?:music\s+)?video|official\s+audio|lyrics?\s+video|music\s+video|visuali[sz]er|remaster(?:ed)?(?:\s+\d{4})?|hd|hq)\s*[\])]""",
            RegexOption.IGNORE_CASE,
        )
    private val DASH_RELEASE_SUFFIX =
        Regex(
            """\s+-\s+(?:official\s+(?:music\s+)?video|official\s+audio|lyrics?\s+video|music\s+video|visuali[sz]er|remaster(?:ed)?(?:\s+\d{4})?)\s*$""",
            RegexOption.IGNORE_CASE,
        )
    private val WHITESPACE = Regex("""\s+""")
}
