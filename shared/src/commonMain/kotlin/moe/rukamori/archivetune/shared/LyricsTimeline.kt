package moe.rukamori.archivetune.shared

import moe.rukamori.archivetune.lyrics.LyricsTimelineParser

typealias LyricsWord = moe.rukamori.archivetune.lyrics.LyricsWord
typealias LyricsLine = moe.rukamori.archivetune.lyrics.LyricsLine

/** Delegates LRC parsing and timeline queries to `lyrics-sdk` [LyricsTimelineParser]. */
object LyricsTimeline {
    fun parseSynced(lrc: String): List<LyricsLine> = LyricsTimelineParser.parseSynced(lrc)
    fun parsePlain(text: String): List<LyricsLine> = LyricsTimelineParser.parsePlain(text)
    fun activeLine(lines: List<LyricsLine>, positionMs: Long): Int? = LyricsTimelineParser.activeLine(lines, positionMs)
    fun activeLines(lines: List<LyricsLine>, positionMs: Long): IntRange? = LyricsTimelineParser.activeLines(lines, positionMs)
}
