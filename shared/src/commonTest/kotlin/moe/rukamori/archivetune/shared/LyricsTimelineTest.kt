package moe.rukamori.archivetune.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsTimelineTest {
    @Test
    fun multipleTimestampTagsFractionPrecisionAndUnicodeArePreserved() {
        val source = """
            [ti:多言語]
            [offset:+250]
            [00:02.5][00:01.02] Привет 世界 🌙
            [00:01.02] مرحبًا
            [00:02.500] こんにちは
            [00:03.001] naïve café 🎵
        """.trimIndent()
        assertEquals(
            listOf(
                LyricsLine(1_270L, "Привет 世界 🌙"),
                LyricsLine(1_270L, "مرحبًا"),
                LyricsLine(2_750L, "Привет 世界 🌙"),
                LyricsLine(2_750L, "こんにちは"),
                LyricsLine(3_251L, "naïve café 🎵"),
            ),
            LyricsTimeline.parseSynced(source),
        )
    }

    @Test
    fun malformedTagsMetadataAndUntimedTextDoNotCreateCues() {
        val lines = LyricsTimeline.parseSynced(
            "[ar:Some Artist]\n" +
                "[offset:invalid]\n" +
                "[00:60.00] invalid seconds\n" +
                "[00:02.1234] invalid fraction\n" +
                "[99] malformed\n" +
                "plain untimed text\n" +
                "[00:01.999] valid\n" +
                "[00:02] \n" +
                "[00:01.000] other",
        )
        assertEquals(
            listOf(
                LyricsLine(1_000L, "other"),
                LyricsLine(1_999L, "valid"),
                LyricsLine(2_000L, ""),
            ),
            lines,
        )
    }

    @Test
    fun negativeOffsetAppliesFileWideAndClampsBelowZero() {
        val source = "[00:01.000] first\r\n[00:00.100] second\r\n[OFFSET: -500]"
        assertEquals(
            listOf(LyricsLine(0L, "second"), LyricsLine(500L, "first")),
            LyricsTimeline.parseSynced(source),
        )
    }

    @Test
    fun zeroAndFractionalTimestampBoundariesAreExact() {
        val cues = LyricsTimeline.parseSynced(
            "[00:00]begin\n[00:00.001]next\n[01:00.01]minute",
        )
        assertEquals(listOf(0L, 1L, 60_010L), cues.map { it.startTimeMs })
        assertNull(LyricsTimeline.activeLine(cues, -1))
        assertEquals(0, LyricsTimeline.activeLine(cues, 0))
        assertEquals(0, LyricsTimeline.activeLine(cues, 0L))
        assertEquals(1, LyricsTimeline.activeLine(cues, 1))
        assertEquals(1, LyricsTimeline.activeLine(cues, 60_009))
        assertEquals(2, LyricsTimeline.activeLine(cues, 60_010))
        assertEquals(2, LyricsTimeline.activeLine(cues, Long.MAX_VALUE))
    }

    @Test
    fun preFirstAndOverlappingEqualTimeLinesAreQueriedWithoutDroppingEither() {
        val cues = LyricsTimeline.parseSynced("[00:10.00]漢字\n[00:10.00]العربية\n[00:20]last")
        assertNull(LyricsTimeline.activeLine(cues, 9_999))
        assertNull(LyricsTimeline.activeLines(cues, 9_999))
        assertEquals(1, LyricsTimeline.activeLine(cues, 10_000))
        assertEquals(0..1, LyricsTimeline.activeLines(cues, 10_000))
        assertEquals(0..1, LyricsTimeline.activeLines(cues, 19_999))
        assertEquals(2..2, LyricsTimeline.activeLines(cues, 20_000))
    }

    @Test
    fun plainLyricsHaveNoFakeTimingAndPreserveInternalParagraphBreaks() {
        val plain = LyricsTimeline.parsePlain("\r\n  你好 世界  \r\n\r\nשלום עולם\r\n  \r\n")
        assertEquals(
            listOf(
                LyricsLine(null, "  你好 世界"),
                LyricsLine(null, ""),
                LyricsLine(null, "שלום עולם"),
            ),
            plain,
        )
        assertNull(LyricsTimeline.activeLine(plain, 999_999))
        assertNull(LyricsTimeline.activeLines(plain, 999_999))
        assertTrue(LyricsTimeline.parsePlain(" \n \r\n").isEmpty())
        assertTrue(LyricsTimeline.parseSynced("[ar:artist]\nuntimed").isEmpty())
    }
}
