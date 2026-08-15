/*
 * Pre-built lyric timeline used by the SpicyLyrics-style renderer.
 *
 * Each line carries three representations:
 *   - `text`: the full rendered line (words joined with a single space, native shaping),
 *   - `words`: timed word spans addressed by range into `text` (for word-level sweeps),
 *   - `chars`: per-character spans (legacy; kept for the spring animator).
 *
 * The renderer lays out `text` as a single Text so the font's native kerning and
 * whitespace advances are preserved, and drives the timed highlight from `words`.
 */
package moe.rukamori.archivetune.desktop.lyrics

/** One rendered character on the lyrics surface. */
data class CharSpan(
    val ch: String,
    /** Document-relative start time in milliseconds (matches SpicyLyrics units). */
    val startMs: Long,
    val endMs: Long,
    /** Whether this character ends a word and should leave a hard word gap. */
    val wordBreakAfter: Boolean,
)

/** A timed word, addressed by its character range into the line's full `text`. */
data class WordSpan(
    val text: String,
    /** Inclusive start index into `CharLine.text`. */
    val startChar: Int,
    /** Exclusive end index into `CharLine.text`. */
    val endChar: Int,
    val startMs: Long,
    val endMs: Long,
)

/** A pre-built line: full text plus per-word and per-char timing. */
data class CharLine(
    val chars: List<CharSpan>,
    val text: String,
    val words: List<WordSpan>,
    val startMs: Long,
    val endMs: Long,
    /** Optional BG-vocal (lower-opacity) parts. */
    val isDuetOpposite: Boolean = false,
    val translation: String? = null,
    val romanization: String? = null,
)

/** Whole-song pre-built structure, ready for the renderer. */
data class CharLyrics(
    val lines: List<CharLine>,
    /** doc-relative offset until the first lyric starts. */
    val preludeMs: Long,
)

/**
 * Build the per-word/per-character timeline from the existing `LyricsTimeline`.
 * Spaces between words are a single regular space appended to `text`; word ranges
 * index into that string.
 */
object CharTimelineBuilder {
    fun build(timeline: LyricsTimeline): CharLyrics {
        val lines = mutableListOf<CharLine>()
        var preludeMs = 0L
        for ((lineIndex, line) in timeline.lines.withIndex()) {
            val lineStart = line.startTimeMs ?: continue
            val lineEnd = line.endTimeMs ?: continue
            val isDuetOpposite = line.text.trimStart().startsWith("(") ||
                line.words.isNotEmpty() && line.words.first().startTimeMs <= lineStart
            // The provider's own line text is authoritative: it preserves the source's
            // exact whitespace (words may carry trailing spaces in the parser output).
            val displayText = line.text

            // Match timed words against the display text by sequential search, so word
            // ranges index into the exact string we render.
            val words = mutableListOf<WordSpan>()
            var searchFrom = 0
            for (word in line.words) {
                val w = word.text.trim()
                if (w.isEmpty() || word.endTimeMs <= word.startTimeMs) continue
                val idx = displayText.indexOf(w, searchFrom)
                if (idx < 0) continue
                val end = idx + w.length
                words += WordSpan(w, idx, end, word.startTimeMs, word.endTimeMs)
                searchFrom = end
            }
            if (words.isEmpty()) {
                words += WordSpan(displayText, 0, displayText.length, lineStart, lineEnd)
            }

            lines += CharLine(
                chars = buildChars(line, lineStart, lineEnd),
                text = displayText,
                words = words,
                startMs = lineStart,
                endMs = lineEnd,
                isDuetOpposite = isDuetOpposite,
                translation = line.translation,
                romanization = line.romanization,
            )

            if (lineIndex == 0 && lineStart > 0L && preludeMs == 0L) {
                preludeMs = lineStart
            }
        }
        return CharLyrics(lines = lines, preludeMs = preludeMs)
    }

    /** Legacy per-character spans (the spring animator is currently unused). */
    private fun buildChars(line: TimedLyricLine, lineStart: Long, lineEnd: Long): List<CharSpan> {
        if (line.words.isEmpty()) {
            return listOf(CharSpan(line.text, lineStart, lineEnd, wordBreakAfter = false))
        }
        val out = mutableListOf<CharSpan>()
        line.words.forEachIndexed { wi, word ->
            val wordText = word.text.trim()
            if (wordText.isEmpty()) return@forEachIndexed
            val ws = word.startTimeMs
            val we = word.endTimeMs
            if (we <= ws) return@forEachIndexed
            val perChar = (we - ws).toDouble() / wordText.length
            wordText.forEachIndexed { ci, raw ->
                val cs = ws + (perChar * ci).toLong()
                val ce = if (ci == wordText.length - 1) we else ws + (perChar * (ci + 1)).toLong()
                out += CharSpan(
                    ch = raw.toString(),
                    startMs = cs,
                    endMs = ce.coerceAtLeast(cs + 1),
                    wordBreakAfter = ci == wordText.length - 1 && wi != line.words.lastIndex,
                )
            }
        }
        return out
    }
}
