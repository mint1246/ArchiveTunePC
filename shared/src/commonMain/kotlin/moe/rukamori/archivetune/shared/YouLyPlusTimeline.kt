/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import kotlinx.serialization.json.*

/** Android YouLyPlus v2's real syllable timings; no synthesized word durations. */
internal fun decodeYouLyPlusTimeline(body: String): List<LyricsLine> {
    if (body.length > 1_500_000) throw LyricsException(LyricsError.INVALID_RESPONSE)
    val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
        ?: throw LyricsException(LyricsError.INVALID_RESPONSE)
    val raw = (root["lyrics"] as? JsonArray).orEmpty().take(4000)
    val wordTimed = (root["type"] as? JsonPrimitive)?.contentOrNull.equals("Word", ignoreCase = true)
    return raw.mapNotNull { value ->
        val line = value as? JsonObject ?: return@mapNotNull null
        val start = (line["time"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        val duration = (line["duration"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..3_600_000 }
        val text = (line["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val words = if (!wordTimed) emptyList() else (line["syllabus"] as? JsonArray).orEmpty().take(2000).mapNotNull { syllable ->
            val word = syllable as? JsonObject ?: return@mapNotNull null
            val at = (word["time"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: return@mapNotNull null
            val length = (word["duration"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..3_600_000 } ?: return@mapNotNull null
            val content = (word["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            LyricsWord(content, at, at + length, (word["isBackground"] as? JsonPrimitive)?.booleanOrNull == true)
        }
        val actualText = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim() else text.trim()
        if (actualText.isEmpty()) return@mapNotNull null
        LyricsLine(start, actualText, if (start != null && duration != null) start + duration else null, words)
    }.withIndex().sortedWith(compareBy({ it.value.startTimeMs ?: Long.MAX_VALUE }, { it.index })).map { it.value }
}
