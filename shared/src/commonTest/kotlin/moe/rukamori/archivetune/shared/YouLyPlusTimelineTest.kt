package moe.rukamori.archivetune.shared

import kotlin.test.*

class YouLyPlusTimelineTest {
    @Test fun retainsProviderWordTimesAndUnicodeWithoutInventingMissingTimings() {
        val lines = decodeYouLyPlusTimeline("""{"type":"Word","lyrics":[
            {"time":17000,"duration":5000,"text":"ignored","syllabus":[
              {"time":17000,"duration":350,"text":"Hello "},
              {"time":17500,"duration":450,"text":"世界","isBackground":true},
              {"text":"untimed"}]},
            {"text":"plain remains plain"}]}""")
        assertEquals("Hello 世界", lines[0].text)
        assertEquals(22000, lines[0].endTimeMs)
        assertEquals(listOf(LyricsWord("Hello ",17000,17350),LyricsWord("世界",17500,17950,true)),lines[0].words)
        assertNull(lines[1].startTimeMs)
        assertTrue(lines[1].words.isEmpty())
    }
}
