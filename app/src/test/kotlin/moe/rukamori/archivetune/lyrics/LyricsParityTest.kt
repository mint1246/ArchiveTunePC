/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import com.google.common.collect.ImmutableList
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
    fun preparedTimeline_matchesDesktopActiveLineSelection() {
        val prepared =
            preparedLyrics(
                syncType = LyricsSyncType.LINE,
                lines =
                    listOf(
                        preparedLine("first", 1_000L, 5_000L),
                        preparedLine("overlap", 3_000L, 4_000L),
                        preparedLine("interlude", 5_000L, 8_000L, isInstrumental = true),
                    ),
            )

        assertTrue(prepared.isSynchronized)
        assertEquals(-1, prepared.activeLineIndex(999L))
        assertEquals(0, prepared.activeLineIndex(2_000L))
        assertEquals(1, prepared.activeLineIndex(3_500L))
        assertEquals(0, prepared.activeLineIndex(4_500L))
        assertEquals(-1, prepared.activeLineIndex(5_000L))
    }

    @Test
    fun preparedTimeline_plainLyrics_neverSelectActiveLine() {
        val prepared = preparedLyrics(LyricsSyncType.PLAIN, listOf(preparedLine("plain", -1L, -1L)))

        assertFalse(prepared.isSynchronized)
        assertEquals(-1, prepared.activeLineIndex(10_000L))
    }

    @Test
    fun lineTimedQrc_withoutWordTiming_usesDesktopFallback() {
        val qrc =
            """<QrcInfos><LyricInfo LyricContent="[1000,2000]first&#13;&#10;[3000,1500]second"/></QrcInfos>"""

        val entries = LyricsUtils.parseLyrics(qrc)

        assertEquals(2, entries.size)
        assertEquals(1_000L, entries[0].time)
        assertEquals(2_000L, entries[0].durationMs)
        assertEquals("first", entries[0].text)
        assertEquals(3_000L, entries[1].time)
        assertEquals(1_500L, entries[1].durationMs)
        assertEquals("second", entries[1].text)
        assertTrue(LyricsUtils.hasMeaningfulLyricsContent(qrc))
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
    fun instrumentalBreaks_includeIntroButNeverSyntheticOutro_likeDesktopScene() {
        val entries = listOf(LyricsEntry(time = 3_000L, text = "only", durationMs = 1_000L))

        val result = LyricsUtils.insertInstrumentalBreaks(entries)
        val interludes = result.filter { it.isInstrumental }

        assertEquals(1, interludes.size)
        assertEquals(0L, interludes.single().time)
        assertEquals(3_000L, interludes.single().durationMs)
    }

    @Test
    fun currentLineSelection_switchesAtExactDesktopLineStart() {
        val entries =
            listOf(
                LyricsEntry(time = 1_000L, text = "first"),
                LyricsEntry(time = 2_000L, text = "second"),
            )

        assertEquals(0, LyricsUtils.findCurrentLineIndex(entries, 1_999L, leadMs = 0L))
        assertEquals(1, LyricsUtils.findCurrentLineIndex(entries, 2_000L, leadMs = 0L))
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

    private fun preparedLyrics(
        syncType: LyricsSyncType,
        lines: List<PreparedLyricsLine>,
    ) = PreparedLyrics(
        sourceFormat = if (syncType == LyricsSyncType.PLAIN) LyricsSourceFormat.PLAIN else LyricsSourceFormat.LRC,
        syncType = syncType,
        lines = ImmutableList.copyOf(lines),
        preferences =
            LyricsRenderingPreferences(
                clickEnabled = true,
                scrollEnabled = true,
                textSizeSp = 26f,
                lineSpacing = 1.3f,
                lineBlurEnabled = true,
                v2BounceFactor = 1f,
                v2GlowFactor = 1f,
                v2FillTransitionWidthDp = 8f,
                v2LrcBounceEnabled = true,
                romanization =
                    LyricsRomanizationPreferences(
                        romanizeJapanese = true,
                        romanizeKorean = true,
                        romanizeChinese = true,
                        romanizeHindi = true,
                        romanizeOther = true,
                    ),
            ),
    )

    private fun preparedLine(
        text: String,
        startMs: Long,
        endMs: Long,
        isInstrumental: Boolean = false,
    ) = PreparedLyricsLine(
        id = "$text:$startMs",
        startMs = startMs,
        endMs = endMs,
        text = text,
        alignment = LyricsLineAlignment.START,
        direction = LyricsTextDirection.LTR,
        main = PreparedLyricsTrack(text, null, ImmutableList.of()),
        backgrounds = ImmutableList.of(),
        translation = null,
        romanizedText = null,
        phonetics = ImmutableList.of(),
        isInstrumental = isInstrumental,
    )
}
