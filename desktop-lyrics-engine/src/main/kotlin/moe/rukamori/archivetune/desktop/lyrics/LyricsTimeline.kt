package moe.rukamori.archivetune.desktop.lyrics

import moe.rukamori.archivetune.betterlyrics.QRCParser
import moe.rukamori.archivetune.betterlyrics.TTMLParser
import moe.rukamori.archivetune.lyrics.LyricsDocument
import moe.rukamori.archivetune.lyrics.LyricsFormat

/** One visible lyric line. Translation and romanization stay null unless a provider exposes them. */
data class TimedLyricLine(
    val text: String,
    val startTimeMs: Long? = null,
    val endTimeMs: Long? = null,
    val translation: String? = null,
    val romanization: String? = null,
    val words: List<TimedWord> = emptyList(),
)

/** One word within a synced lyric line, for word-by-word highlighting. */
data class TimedWord(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

data class LyricsTimeline(
    val lines: List<TimedLyricLine>,
    val synchronized: Boolean,
) {
    fun activeLineIndex(positionMs: Long): Int =
        if (!synchronized) -1 else lines.indexOfLast { line ->
            val start = line.startTimeMs ?: return@indexOfLast false
            start <= positionMs && (line.endTimeMs == null || positionMs < line.endTimeMs)
        }
}

object LyricsTimelineParser {
    fun parse(document: LyricsDocument): LyricsTimeline =
        when (document.format) {
            LyricsFormat.PLAIN -> plain(document.text)
            LyricsFormat.LRC -> lrc(document.text)
            LyricsFormat.TTML -> ttml(document.text)
            LyricsFormat.QRC -> qrc(document.text)
        }

    private fun plain(text: String) = LyricsTimeline(
        lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).map(::TimedLyricLine).toList(),
        synchronized = false,
    )

    private fun lrc(text: String): LyricsTimeline {
        val parsed = buildList {
            text.lineSequence().forEach { raw ->
                val matches = timestamp.findAll(raw).toList()
                if (matches.isEmpty()) return@forEach
                val line = raw.substring(matches.last().range.last + 1).trim()
                if (line.isEmpty()) return@forEach
                matches.forEach { add(TimedLyricLine(text = line, startTimeMs = it.toMilliseconds())) }
            }
        }.sortedBy { it.startTimeMs }
        return timed(parsed)
    }

    private fun ttml(text: String): LyricsTimeline = timed(
        TTMLParser.parseTTML(text).map { line ->
            TimedLyricLine(
                text = line.text,
                startTimeMs = line.startTime.toMilliseconds(),
                endTimeMs = line.endTime.toMilliseconds(),
                translation = line.providerTranslationText,
                romanization = line.providerRomanizedText,
                words = line.words.map { word ->
                    TimedWord(
                        text = word.text,
                        startTimeMs = word.startTime.toMilliseconds(),
                        endTimeMs = word.endTime.toMilliseconds(),
                    )
                },
            )
        },
    )

    private fun qrc(text: String): LyricsTimeline {
        val productionLines = QRCParser.parseQrc(text).map { line ->
            TimedLyricLine(
                text = line.text,
                startTimeMs = line.startTime.toMilliseconds(),
                endTimeMs = line.endTime.toMilliseconds(),
            )
        }
        if (productionLines.isNotEmpty()) return timed(productionLines)

        // Some mirrors return line-timed QRC without per-word timing. Keep that valid fallback.
        val content = extractQrcContent(text)
        return timed(qrcLine.findAll(content).mapNotNull { match ->
            val start = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val line = match.groupValues[3].replace(qrcWordTiming, "").replace(Regex("\\s+"), " ").trim()
            line.takeIf(String::isNotEmpty)?.let { TimedLyricLine(it, start, start + duration) }
        }.toList())
    }

    private fun extractQrcContent(text: String): String {
        val marker = Regex("""LyricContent\s*=\s*\"""", RegexOption.IGNORE_CASE).find(text) ?: return text
        val start = marker.range.last + 1
        val end = text.lastIndexOf("\"")
        return text.substring(start, end.takeIf { it >= start } ?: text.length)
            .replace("&#13;", "\r")
            .replace("&#10;", "\n")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private fun timed(lines: List<TimedLyricLine>): LyricsTimeline = LyricsTimeline(
        lines = lines.mapIndexed { index, line ->
            line.copy(endTimeMs = line.endTimeMs ?: lines.getOrNull(index + 1)?.startTimeMs)
        },
        synchronized = true,
    )

    private fun MatchResult.toMilliseconds(): Long {
        val minutes = groupValues[1].toLong()
        val seconds = groupValues[2].toLong()
        val fraction = groupValues[3].orEmpty().padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return minutes * 60_000L + seconds * 1_000L + fraction
    }

    private fun Double.toMilliseconds(): Long = (this * 1_000.0).toLong()

    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val qrcLine = Regex("""(?m)^\s*\[(\d+),(\d+)]([^\r\n]*)""")
    private val qrcWordTiming = Regex("""\(\d+,\d+\)""")
}
