/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParityTest {
    @Test
    fun instrumentalBreaks_includeInternalGap_afterKnownVocalInterval() {
        val entries =
            listOf(
                LyricsEntry(time = 1_000L, text = "one", durationMs = 2_000L),
                LyricsEntry(time = 7_000L, text = "two", durationMs = 2_000L),
            )

        val result = LyricsUtils.insertInstrumentalBreaks(entries)
        val interlude = result.single { it.isInstrumental }

        assertEquals(3_000L, interlude.time)
        assertEquals(4_000L, interlude.durationMs)
    }

    @Test
    fun instrumentalBreaks_useUnionOfOverlappingVocals() {
        val entries =
            listOf(
                LyricsEntry(time = 1_000L, text = "long", durationMs = 9_000L),
                LyricsEntry(time = 4_000L, text = "short overlap", durationMs = 1_000L),
                LyricsEntry(time = 12_000L, text = "next", durationMs = 1_000L),
            )

        val result = LyricsUtils.insertInstrumentalBreaks(entries)

        assertTrue(result.none { it.isInstrumental })
    }

    @Test
    fun providerWordIntervals_keepOverlappingLinesActive() {
        val entries =
            listOf(
                LyricsEntry(
                    time = 1_000L,
                    text = "held vocal",
                    words = listOf(WordTimestamp("held vocal", 1.0, 5.0)),
                ),
                LyricsEntry(time = 3_000L, text = "overlap", durationMs = 1_000L),
            )

        assertEquals(5_000L, LyricsUtils.effectiveLyricsEntryEndMs(entries, 0))
        assertTrue(LyricsUtils.isLyricsEntryActiveAt(entries, 0, 3_500L))
        assertTrue(LyricsUtils.isLyricsEntryActiveAt(entries, 1, 3_500L))
        assertTrue(LyricsUtils.isLyricsEntryActiveAt(entries, 0, 4_500L))
        assertFalse(LyricsUtils.isLyricsEntryActiveAt(entries, 1, 4_500L))
    }

    @Test
    fun explicitProviderLineEnd_winsOverLastWordEnd() {
        val entries =
            listOf(
                LyricsEntry(
                    time = 1_000L,
                    text = "held line",
                    words = listOf(WordTimestamp("held", 1.0, 3.0)),
                    durationMs = 4_000L,
                ),
            )

        assertEquals(5_000L, LyricsUtils.effectiveLyricsEntryEndMs(entries, 0))
        assertTrue(LyricsUtils.isLyricsEntryActiveAt(entries, 0, 4_500L))
    }

    @Test
    fun instrumentalBreaks_includeIntroAndOutro_atDesktopThreshold() {
        val entries = listOf(LyricsEntry(time = 3_000L, text = "only", durationMs = 1_000L))

        val result = LyricsUtils.insertInstrumentalBreaks(entries, songDurationMs = 7_000L)
        val interludes = result.filter { it.isInstrumental }

        assertEquals(2, interludes.size)
        assertEquals(0L, interludes[0].time)
        assertEquals(3_000L, interludes[0].durationMs)
        assertEquals(4_000L, interludes[1].time)
        assertEquals(3_000L, interludes[1].durationMs)
    }

    @Test
    fun providerQueryVariants_tryCanonicalTitleThenOriginal() {
        assertEquals(
            listOf(
                "My Song",
                "My Song (feat. Guest) [Official Music Video]",
            ),
            LyricsQueryPolicy.titleVariants("My Song (feat. Guest) [Official Music Video]"),
        )
        assertEquals(listOf("Track"), LyricsQueryPolicy.titleVariants(" Track "))
        assertTrue(LyricsQueryPolicy.titleVariants("   ").isEmpty())
    }

    @Test
    fun lyricsCacheKey_isTrackScoped_whenMediaIdIsAvailable() {
        assertEquals("id:video-a", LyricsQueryPolicy.cacheKey("video-a", "Song", "Artist"))
        assertEquals("id:video-b", LyricsQueryPolicy.cacheKey("video-b", "Song", "Artist"))
        assertEquals("meta:Artist\u0000Song", LyricsQueryPolicy.cacheKey(null, " Song ", " Artist "))
    }

    @Test
    fun interludeDots_useDesktopThirdsAndPrehideWindow() {
        val start = LyricsInterludeMotion.frame(startMs = 1_000L, durationMs = 6_000L, positionMs = 1_000L)
        val middle = LyricsInterludeMotion.frame(startMs = 1_000L, durationMs = 6_000L, positionMs = 4_000L)
        val prehide = LyricsInterludeMotion.frame(startMs = 1_000L, durationMs = 6_000L, positionMs = 6_750L)
        val end = LyricsInterludeMotion.frame(startMs = 1_000L, durationMs = 6_000L, positionMs = 7_000L)

        assertEquals(1f, start.groupScale, 0.0001f)
        assertEquals(0.75f, start.dots.first().scale, 0.0001f)
        assertTrue(middle.dots[0].opacity > start.dots[0].opacity)
        assertTrue(middle.dots[1].opacity > start.dots[1].opacity)
        assertEquals(0.5f, prehide.groupScale, 0.0001f)
        assertEquals(0f, end.groupScale, 0.0001f)
    }
}
