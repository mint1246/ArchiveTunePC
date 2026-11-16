package moe.rukamori.archivetune.shared

/**
 * [startTimeMs] is null for unsynchronized text. Plain lyrics must never be assigned
 * synthetic timestamps, even when a track duration is known.
 */
data class LyricsWord(val text: String, val startTimeMs: Long, val endTimeMs: Long, val background: Boolean = false)

data class LyricsLine(
    val startTimeMs: Long?,
    val text: String,
    val endTimeMs: Long? = null,
    val words: List<LyricsWord> = emptyList(),
)

/** Pure LRC parsing and timeline queries; no platform APIs or audio dependencies. */
object LyricsTimeline {
    private const val MAX_LINES = 4_000
    private val timeTag = Regex("""^\[(\d{1,6}):([0-5]\d)(?:\.(\d{1,3}))?\]""")
    private val offsetTag = Regex("""^\[offset\s*:\s*([+-]?\d{1,9})\]$""", RegexOption.IGNORE_CASE)

    /**
     * Supports [mm:ss], [mm:ss.x], [mm:ss.xx], [mm:ss.xxx] and multiple leading tags
     * on one line. Offset is a file-wide millisecond adjustment, independent of where
     * its metadata tag occurs. Blank timestamped lines are kept as pause cues.
     *
     * Equal timestamps retain input order, including overlapping/multilingual lines.
     * Malformed tags and un-timed metadata are ignored rather than given invented time.
     */
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

        // Explicit original index avoids relying on sort stability for simultaneous cues.
        return cues.withIndex().sortedWith(
            compareBy<IndexedValue<LyricsLine>>({ it.value.startTimeMs ?: Long.MAX_VALUE }, { it.index }),
        ).take(MAX_LINES).map { it.value }
    }

    /** Preserve paragraph breaks and Unicode; do not manufacture timing for plain text. */
    fun parsePlain(text: String): List<LyricsLine> {
        val source = normalizedLines(text).map { it.trimEnd() }
        val first = source.indexOfFirst { it.isNotBlank() }
        if (first < 0) return emptyList()
        val last = source.indexOfLast { it.isNotBlank() }
        return source.subList(first, last + 1).take(MAX_LINES).map { LyricsLine(null, it) }
    }

    /**
     * Index of the latest cue at or before [positionMs] in a sorted synchronized list.
     * Returns null before the first cue, for negative position, or for plain lyrics.
     * For equal timestamps the final cue is active; use [activeLines] for the full group.
     */
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

    /** All cues sharing the currently active timestamp, preserving overlapping lines. */
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
