/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Word/syllable cue with real provider timing (never synthesized). */
data class LyricsWord(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val background: Boolean = false,
)

/**
 * Portable lyric line. [startTimeMs] is null for unsynchronized text; plain lyrics must never be
 * assigned synthetic timestamps.
 */
data class LyricsLine(
    val startTimeMs: Long?,
    val text: String,
    val endTimeMs: Long? = null,
    val words: List<LyricsWord> = emptyList(),
)

/** Pure-Kotlin LRC + YouLyPlus v2 parsing and active-line timeline queries. */
object LyricsTimelineParser {
    private const val MAX_LINES = 4_000
    private val timeTag = Regex("""^\[(\d{1,6}):([0-5]\d)(?:\.(\d{1,3}))?\]""")
    private val offsetTag = Regex("""^\[offset\s*:\s*([+-]?\d{1,9})\]$""", RegexOption.IGNORE_CASE)

    fun parseSynced(lrc: String): List<LyricsLine> {
        val source = normalizedLines(lrc)
        var offsetMs = 0L
        for (raw in source) {
            val match = offsetTag.matchEntire(raw.removePrefix("\uFEFF").trim()) ?: continue
            offsetMs = match.groupValues[1].toLongOrNull() ?: offsetMs
        }

        val cues = mutableListOf<LyricsLine>()
        for (raw in source) {
            var remainder = raw.removePrefix("\uFEFF").trimStart()
            val timestamps = mutableListOf<Long>()
            while (true) {
                val match = timeTag.find(remainder)?.takeIf { it.range.first == 0 } ?: break
                val minutes = match.groupValues[1].toLongOrNull() ?: break
                val seconds = match.groupValues[2].toLongOrNull() ?: break
                val fractional = match.groupValues[3]
                val milliseconds = if (fractional.isEmpty()) 0L
                else fractional.padEnd(3, '0').toLongOrNull() ?: break
                val at = (minutes * 60_000L + seconds * 1_000L + milliseconds + offsetMs)
                    .coerceAtLeast(0L)
                timestamps += at
                remainder = remainder.substring(match.value.length)
            }
            if (timestamps.isEmpty()) continue
            val lyric = remainder.trim()
            for (at in timestamps) cues += LyricsLine(at, lyric)
        }

        return cues.withIndex().sortedWith(
            compareBy<IndexedValue<LyricsLine>>({ it.value.startTimeMs ?: Long.MAX_VALUE }, { it.index }),
        ).take(MAX_LINES).map { it.value }
    }

    fun parsePlain(text: String): List<LyricsLine> {
        val source = normalizedLines(text).map { it.trimEnd() }
        val first = source.indexOfFirst { it.isNotBlank() }
        if (first < 0) return emptyList()
        val last = source.indexOfLast { it.isNotBlank() }
        return source.subList(first, last + 1).take(MAX_LINES).map { LyricsLine(null, it) }
    }

    /** Decodes YouLyPlus v2 JSON payloads; returns null when the payload is malformed. */
    fun decodeYouLyPlusTimeline(body: String): List<LyricsLine>? {
        if (body.length > 1_500_000) return null
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val raw = (root["lyrics"] as? JsonArray).orEmpty().take(MAX_LINES)
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

    fun activeLine(lines: List<LyricsLine>, positionMs: Long): Int? {
        if (positionMs < 0 || lines.isEmpty() || lines.first().startTimeMs == null) return null
        var low = 0
        var high = lines.size
        while (low < high) {
            val mid = low + (high - low) / 2
            val time = lines[mid].startTimeMs ?: return null
            if (time <= positionMs) low = mid + 1 else high = mid
        }
        return (low - 1).takeIf { it >= 0 }
    }

    fun activeLines(lines: List<LyricsLine>, positionMs: Long): IntRange? {
        val last = activeLine(lines, positionMs) ?: return null
        val time = lines[last].startTimeMs
        var first = last
        while (first > 0 && lines[first - 1].startTimeMs == time) first--
        return first..last
    }

    private fun normalizedLines(input: String): List<String> =
        input.replace("\r\n", "\n").replace('\r', '\n').lines()
}
