package moe.rukamori.archivetune.desktop.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.lyrics.LyricsDocument
import moe.rukamori.archivetune.lyrics.LyricsFormat

class LyricsTimelineParserTest {
    @Test fun `lrc supports multiple timestamps and follows next line`() {
        val timeline = LyricsTimelineParser.parse(document(LyricsFormat.LRC, "[00:01.20][00:02.500]Hello\n[00:04]World"))
        assertTrue(timeline.synchronized)
        assertEquals(listOf(1200L, 2500L, 4000L), timeline.lines.map { it.startTimeMs })
        assertEquals(1, timeline.activeLineIndex(3000))
        assertEquals(-1, timeline.activeLineIndex(900))
    }

    @Test fun `ttml reads p begin end timestamps`() {
        val timeline = LyricsTimelineParser.parse(document(LyricsFormat.TTML, "<tt><body><p begin=\"00:00:01.500\" end=\"3s\">First <span>line</span></p><p begin=\"00:00:03.000\">Second</p></body></tt>"))
        assertEquals(listOf("First line", "Second"), timeline.lines.map { it.text })
        assertEquals(1500L, timeline.lines.first().startTimeMs)
        assertEquals(0, timeline.activeLineIndex(2999))
    }

    @Test fun `plain text remains readable but has no active timed line`() {
        val timeline = LyricsTimelineParser.parse(document(LyricsFormat.PLAIN, "One\n\nTwo"))
        assertFalse(timeline.synchronized)
        assertEquals(listOf("One", "Two"), timeline.lines.map { it.text })
        assertEquals(-1, timeline.activeLineIndex(10_000))
    }

    @Test fun `qrc karaoke content becomes timed display lines`() {
        val qrc = """<?xml version="1.0"?><QrcInfos><LyricInfo><Lyric_1 LyricContent="[ti:Song]
[1000,2000]First(1000,800) (1800,200)line(2000,1000)
[3000,1500]Second(3000,1500)"/></LyricInfo></QrcInfos>"""
        val timeline = LyricsTimelineParser.parse(document(LyricsFormat.QRC, qrc))

        assertEquals(listOf("First line", "Second"), timeline.lines.map { it.text })
        assertEquals(listOf(1000L, 3000L), timeline.lines.map { it.startTimeMs })
        assertEquals(1, timeline.activeLineIndex(3200))
    }

    private fun document(format: LyricsFormat, text: String) = LyricsDocument("test", "Test", text, format)
}
