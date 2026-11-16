/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import com.google.common.collect.ImmutableList
import kotlin.math.roundToLong

fun WordTimestamp.toPreparedLyricsWord(): PreparedLyricsWord =
    PreparedLyricsWord(
        text = text,
        startMs = (startTime * 1000.0).roundToLong().coerceAtLeast(0L),
        endMs = (endTime * 1000.0).roundToLong().coerceAtLeast(0L),
    )

/** Synthesize intro and internal gaps from the union of vocals, never an outro. */
fun insertPreparedInstrumentalBreaks(lines: List<PreparedLyricsLine>): List<PreparedLyricsLine> {
    if (lines.isEmpty()) return lines
    val sortedLines = lines.sortedBy(PreparedLyricsLine::startMs)
    val result = mutableListOf<PreparedLyricsLine>()
    val first = sortedLines.first()

    if (first.startMs >= PREPARED_INSTRUMENTAL_GAP_THRESHOLD_MS) {
        result += preparedInstrumentalLine(0L, first.startMs)
    }

    result += first
    var coveredUntilMs = first.endMs
    for (line in sortedLines.drop(1)) {
        val gapMs = line.startMs - coveredUntilMs
        if (gapMs >= PREPARED_INSTRUMENTAL_GAP_THRESHOLD_MS) {
            result += preparedInstrumentalLine(coveredUntilMs, line.startMs)
        }
        result += line
        coveredUntilMs = maxOf(coveredUntilMs, line.endMs)
    }
    return result
}

private fun preparedInstrumentalLine(startMs: Long, endMs: Long): PreparedLyricsLine =
    PreparedLyricsLine(
        id = "instrumental:$startMs:$endMs",
        startMs = startMs,
        endMs = endMs,
        text = "",
        alignment = LyricsLineAlignment.START,
        direction = LyricsTextDirection.LTR,
        main = PreparedLyricsTrack(
            text = "",
            language = null,
            words = ImmutableList.of(),
            startMs = startMs,
            endMs = endMs,
        ),
        backgrounds = ImmutableList.of(),
        translation = null,
        romanizedText = null,
        phonetics = ImmutableList.of(),
        isInstrumental = true,
    )

private const val PREPARED_INSTRUMENTAL_GAP_THRESHOLD_MS = 3_000L
