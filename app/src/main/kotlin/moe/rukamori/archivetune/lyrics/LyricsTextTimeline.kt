/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import kotlin.math.roundToLong

/**
 * A grapheme cluster addressed by UTF-16 offsets into [LyricsTextTimeline.displayText].
 *
 * Compose text layout APIs use UTF-16 offsets, while animation must operate on complete
 * user-visible clusters so combining marks, surrogate pairs, and emoji ZWJ sequences are
 * never split into independently timed pieces.
 */
data class LyricsTextGrapheme(
    val text: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val startMs: Long,
    val endMs: Long,
)

/** A timed provider word mapped into the exact full line string rendered by the UI. */
data class LyricsTextWord(
    val text: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val startMs: Long,
    val endMs: Long,
    val graphemes: List<LyricsTextGrapheme>,
)

/**
 * Renderer-ready text timeline for one lyric line.
 *
 * [displayText] is always the provider's line text. Timed words and graphemes only carry
 * ranges into that text; renderers should shape [displayText] once and derive glyph geometry
 * from those ranges instead of reconstructing the line from independently measured words.
 */
data class LyricsTextTimeline(
    val displayText: String,
    val words: List<LyricsTextWord>,
    val startMs: Long,
    val endMs: Long,
)

/** Android counterpart of the desktop CharTimeline mapping semantics. */
object LyricsTextTimelineBuilder {
    /**
     * Builds from the raw provider entry. Background-vocal words are intentionally excluded
     * because [LyricsEntry.text] is the main vocal's authoritative display string.
     *
     * [lineEndMs] can be supplied by a caller that knows the surrounding timeline. Without it,
     * an explicit provider duration wins, then the latest valid main-word end is used.
     */
    fun fromEntry(
        entry: LyricsEntry,
        lineEndMs: Long? = null,
    ): LyricsTextTimeline {
        val timedWords =
            entry.words
                .orEmpty()
                .asSequence()
                .map { word ->
                    TimedWord(
                        text = word.text,
                        startMs = word.startTime.secondsToMilliseconds(),
                        endMs = word.endTime.secondsToMilliseconds(),
                    )
                }.toList()
        val resolvedEndMs =
            lineEndMs
                ?: when {
                    entry.durationMs > 0L -> entry.time + entry.durationMs
                    else -> timedWords.filter(TimedWord::hasPositiveDuration).maxOfOrNull(TimedWord::endMs)
                }
                ?: entry.time + 1L

        return build(
            displayText = entry.text,
            timedWords = timedWords,
            lineStartMs = entry.time,
            lineEndMs = resolvedEndMs,
        )
    }

    /** Builds directly from the prepared main-vocal track used by the Android renderer. */
    fun fromPreparedLine(line: PreparedLyricsLine): LyricsTextTimeline =
        build(
            displayText = line.text,
            timedWords =
                line.main.words.map { word ->
                    TimedWord(
                        text = word.text,
                        startMs = word.startMs,
                        endMs = word.endMs,
                    )
                },
            lineStartMs = line.startMs,
            lineEndMs = line.endMs,
        )

    private fun build(
        displayText: String,
        timedWords: List<TimedWord>,
        lineStartMs: Long,
        lineEndMs: Long,
    ): LyricsTextTimeline {
        val mappedWords = mutableListOf<MappedWord>()
        var searchFrom = 0

        for (word in timedWords) {
            val wordText = word.text.trim()
            if (wordText.isEmpty() || !word.hasPositiveDuration()) continue

            val start = displayText.indexOf(wordText, startIndex = searchFrom)
            if (start < 0) continue

            val end = start + wordText.length
            mappedWords +=
                MappedWord(
                    text = wordText,
                    startUtf16 = start,
                    endUtf16 = end,
                    startMs = word.startMs,
                    endMs = word.endMs,
                )
            searchFrom = end
        }

        val safeLineEndMs = lineEndMs.coerceAtLeast(lineStartMs + 1L)
        if (mappedWords.isEmpty()) {
            mappedWords +=
                MappedWord(
                    text = displayText,
                    startUtf16 = 0,
                    endUtf16 = displayText.length,
                    startMs = lineStartMs,
                    endMs = safeLineEndMs,
                )
        }

        return LyricsTextTimeline(
            displayText = displayText,
            words = mappedWords.map { word -> word.toLyricsTextWord(displayText) },
            startMs = lineStartMs,
            endMs = safeLineEndMs,
        )
    }

    private fun MappedWord.toLyricsTextWord(displayText: String): LyricsTextWord {
        val ranges =
            LyricsMotionPhysics
                .graphemeRanges(displayText.substring(startUtf16, endUtf16))
                .map { range -> (startUtf16 + range.first)..(startUtf16 + range.last) }
        val clusterCount = ranges.size.coerceAtLeast(1)
        val durationMs = (endMs - startMs).coerceAtLeast(1L)
        val graphemes =
            ranges.mapIndexed { index, range ->
                LyricsTextGrapheme(
                    text = displayText.substring(range.first, range.last + 1),
                    startUtf16 = range.first,
                    endUtf16 = range.last + 1,
                    startMs = startMs + durationMs * index / clusterCount,
                    endMs = startMs + durationMs * (index + 1) / clusterCount,
                )
            }

        return LyricsTextWord(
            text = text,
            startUtf16 = startUtf16,
            endUtf16 = endUtf16,
            startMs = startMs,
            endMs = endMs,
            graphemes = graphemes,
        )
    }

    private data class TimedWord(
        val text: String,
        val startMs: Long,
        val endMs: Long,
    ) {
        fun hasPositiveDuration(): Boolean = endMs > startMs
    }

    private data class MappedWord(
        val text: String,
        val startUtf16: Int,
        val endUtf16: Int,
        val startMs: Long,
        val endMs: Long,
    )

    private fun Double.secondsToMilliseconds(): Long =
        (this * MILLIS_PER_SECOND).roundToLong().coerceAtLeast(0L)

    private const val MILLIS_PER_SECOND = 1000.0
}
