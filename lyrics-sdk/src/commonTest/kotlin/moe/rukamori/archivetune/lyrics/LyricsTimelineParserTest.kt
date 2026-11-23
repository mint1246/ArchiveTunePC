/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LyricsTimelineParserTest {
    @Test
    fun parsesSyncedLrcWithOffsetAndMultiTimestamps() {
        val lrc = """
            [offset:500]
            [00:01.00][00:05.50]Chorus line
            [00:03.25]Middle line
        """.trimIndent()
        val lines = LyricsTimelineParser.parseSynced(lrc)
        assertEquals(3, lines.size)
        assertEquals(1500L, lines[0].startTimeMs)
        assertEquals("Chorus line", lines[0].text)
        assertEquals(3750L, lines[1].startTimeMs)
        assertEquals(6000L, lines[2].startTimeMs)
        assertEquals(1, LyricsTimelineParser.activeLine(lines, 4000L))
        assertNull(LyricsTimelineParser.activeLine(lines, 1000L))
    }

    @Test
    fun parsesYouLyPlusWordSyllables() {
        val json = """
            {
              "type": "Word",
              "lyrics": [
                {
                  "time": 1000,
                  "duration": 2000,
                  "text": "Hello world",
                  "syllabus": [
                    {"time": 1000, "duration": 900, "text": "Hello ", "isBackground": false},
                    {"time": 1900, "duration": 1100, "text": "world", "isBackground": true}
                  ]
                }
              ]
            }
        """.trimIndent()
        val lines = LyricsTimelineParser.decodeYouLyPlusTimeline(json)
        assertNotNull(lines)
        assertEquals(1, lines.size)
        assertEquals(2, lines[0].words.size)
        assertEquals("Hello world", lines[0].text)
        assertEquals(true, lines[0].words[1].background)
    }
}
