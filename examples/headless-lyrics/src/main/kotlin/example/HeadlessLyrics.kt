package example

import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsTextTimelineBuilder
import moe.rukamori.archivetune.lyrics.WordTimestamp

fun main() {
    val entry = LyricsEntry(
        time = 1_000L,
        text = "你好 world",
        words = listOf(
            WordTimestamp("你好", 1.0, 1.8),
            WordTimestamp("world", 1.8, 2.5),
        ),
    )
    val timeline = LyricsTextTimelineBuilder.fromEntry(entry)
    check(timeline.words.size == 2 && timeline.words.first().graphemes.size == 2)
    println("Prepared ${timeline.words.size} timed words from a multilingual lyric line")
}
