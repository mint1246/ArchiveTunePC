package moe.rukamori.archivetune.desktop.lyrics

import moe.rukamori.archivetune.lyrics.LyricsDocument
import moe.rukamori.archivetune.lyrics.LyricsFormat
import javax.xml.parsers.DocumentBuilderFactory

/** One visible lyric line. Translation and romanization stay null unless a provider exposes them. */
data class TimedLyricLine(
    val text: String,
    val startTimeMs: Long? = null,
    val endTimeMs: Long? = null,
    val translation: String? = null,
    val romanization: String? = null,
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

    private fun ttml(text: String): LyricsTimeline = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(text.byteInputStream())
        val nodes = document.getElementsByTagNameNS("*", "p")
        val lines = buildList {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val start = parseTtmlTime(node.attributes.getNamedItem("begin")?.nodeValue) ?: continue
                val end = parseTtmlTime(node.attributes.getNamedItem("end")?.nodeValue)
                val line = node.textContent.replace(Regex("\\s+"), " ").trim()
                if (line.isNotEmpty()) add(TimedLyricLine(line, start, end))
            }
        }.sortedBy { it.startTimeMs }
        timed(lines)
    } catch (_: Exception) {
        // A malformed TTML response is still shown honestly as readable, non-synced text.
        plain(text)
    }

    private fun qrc(text: String): LyricsTimeline {
        val content = qrcContent.find(text)?.groupValues?.get(1).orEmpty().ifBlank { text }
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        val lines = qrcLine.findAll(content).mapNotNull { match ->
            val start = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val line = match.groupValues[3].replace(qrcWordTiming, "").replace(Regex("\\s+"), " ").trim()
            line.takeIf(String::isNotEmpty)?.let { TimedLyricLine(it, start, start + duration) }
        }.toList()
        return timed(lines)
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

    private fun parseTtmlTime(value: String?): Long? {
        val clean = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return when {
            clean.endsWith("ms") -> clean.dropLast(2).toDoubleOrNull()?.toLong()
            clean.endsWith("s") -> (clean.dropLast(1).toDoubleOrNull()?.times(1_000))?.toLong()
            ':' in clean -> {
                val parts = clean.split(':')
                if (parts.size != 3) null else {
                    val hours = parts[0].toLongOrNull() ?: return null
                    val minutes = parts[1].toLongOrNull() ?: return null
                    val seconds = parts[2].toDoubleOrNull() ?: return null
                    (hours * 3_600_000 + minutes * 60_000 + seconds * 1_000).toLong()
                }
            }
            else -> null
        }
    }

    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val qrcContent = Regex("""LyricContent="([\s\S]*)"\s*/>""", RegexOption.IGNORE_CASE)
    private val qrcLine = Regex("""(?m)^\[(\d+),(\d+)](.*)$""")
    private val qrcWordTiming = Regex("""\(\d+,\d+\)""")
}
