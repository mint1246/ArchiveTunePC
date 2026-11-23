/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

private val artworkPathSize = Regex("w\\d+-h\\d+")
private val artworkParamSize = Regex("=w(\\d+)-h(\\d+)")
private val artworkSquareSize = Regex("=s(\\d+)")
private val artworkDuplicateSize = Regex("-s\\d+")

/** YouTubeUtils.highRes: preserve source/crop family, including all ytimg variants. */
internal fun String.androidHighResArtwork(): String {
    val host = substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
    if (!(host == "googleusercontent.com" || host.endsWith(".googleusercontent.com") ||
            host == "ggpht.com" || host.endsWith(".ggpht.com"))) return this
    if (artworkPathSize.containsMatchIn(this)) return replace(artworkPathSize, "w1080-h1080")
    if (artworkParamSize.containsMatchIn(this)) return "${split("=w")[0]}=w1080-h1080-p-l90-rj"
    artworkSquareSize.find(this)?.let { match ->
        return substring(0, match.range.first) + "=s1080" + substring(match.range.last + 1).replace(artworkDuplicateSize, "")
    }
    return this
}
