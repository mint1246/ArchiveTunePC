/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

/** Grapheme ranges in UTF-16 offsets, keeping combining marks and common emoji sequences together. */
fun lyricsGraphemeRanges(text: String): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val result = mutableListOf<IntRange>()
    var clusterStart = 0
    var index = 0
    var previousCodePoint = -1
    var regionalIndicatorCount = 0

    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val codePointLength = Character.charCount(codePoint)
        val joinsPrevious =
            index > clusterStart &&
                (
                    isCombining(codePoint) ||
                        isVariationSelector(codePoint) ||
                        isEmojiModifier(codePoint) ||
                        codePoint == 0x200D ||
                        previousCodePoint == 0x200D ||
                        (isRegionalIndicator(codePoint) && regionalIndicatorCount % 2 == 1) ||
                        (previousCodePoint == '\r'.code && codePoint == '\n'.code)
                    )

        if (index > clusterStart && !joinsPrevious) {
            result += clusterStart until index
            clusterStart = index
            regionalIndicatorCount = 0
        }

        regionalIndicatorCount = if (isRegionalIndicator(codePoint)) regionalIndicatorCount + 1 else 0
        previousCodePoint = codePoint
        index += codePointLength
    }
    result += clusterStart until text.length
    return result
}

private fun isCombining(codePoint: Int): Boolean =
    when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }

private fun isVariationSelector(codePoint: Int): Boolean =
    codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF

private fun isEmojiModifier(codePoint: Int): Boolean = codePoint in 0x1F3FB..0x1F3FF

private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF
