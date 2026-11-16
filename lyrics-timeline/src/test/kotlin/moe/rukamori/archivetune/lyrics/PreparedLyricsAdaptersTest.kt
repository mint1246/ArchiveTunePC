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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedLyricsAdaptersTest {
    @Test
    fun toLyricsEntries_preservesRichLineAndTrackSemantics() {
        val main =
            PreparedLyricsTrack(
                text = "שלום world",
                language = "he",
                words =
                    ImmutableList.of(
                        PreparedLyricsWord("שלום", 1_000L, 1_800L),
                        PreparedLyricsWord("world", 2_200L, 3_000L),
                    ),
                startMs = 1_000L,
                endMs = 5_000L,
            )
        val firstBackground =
            PreparedLyricsTrack(
                text = "bg one",
                language = "en",
                words = ImmutableList.of(PreparedLyricsWord("bg one", 1_500L, 2_100L)),
                startMs = 1_500L,
                endMs = 2_100L,
            )
        val secondBackground =
            PreparedLyricsTrack(
                text = "bg two",
                language = "ja",
                words = ImmutableList.of(PreparedLyricsWord("bg two", 2_600L, 3_200L)),
                startMs = 2_600L,
                endMs = 3_200L,
            )
        val translation =
            PreparedLyricsTrack(
                text = "translated",
                language = "en",
                words = ImmutableList.of(),
                startMs = 1_000L,
                endMs = 5_000L,
            )
        val romanization =
            PreparedLyricsTrack(
                text = "shalom world",
                language = "he-Latn",
                words =
                    ImmutableList.of(
                        PreparedLyricsWord("shalom", 1_000L, 1_800L),
                        PreparedLyricsWord("world", 2_200L, 3_000L),
                    ),
                startMs = 1_000L,
                endMs = 5_000L,
            )
        val partialPhonetics = ImmutableList.of(PreparedLyricsPhonetic(wordIndex = 1, text = "wurld"))
        val prepared =
            PreparedLyrics(
                sourceFormat = LyricsSourceFormat.TTML,
                syncType = LyricsSyncType.WORD,
                lines =
                    ImmutableList.of(
                        PreparedLyricsLine(
                            id = "line-1",
                            startMs = 1_000L,
                            endMs = 5_000L,
                            text = "שלום world",
                            alignment = LyricsLineAlignment.CENTER,
                            direction = LyricsTextDirection.RTL,
                            main = main,
                            backgrounds = ImmutableList.of(firstBackground, secondBackground),
                            translation = "translated",
                            romanizedText = "shalom world",
                            phonetics = partialPhonetics,
                            isInstrumental = false,
                            translationTracks = ImmutableList.of(translation),
                            romanizationTracks = ImmutableList.of(romanization),
                        ),
                    ),
                preferences = preferences(),
            )

        val entry = prepared.toLyricsEntries().single()

        assertEquals(1_000L, entry.time)
        assertEquals(5_000L, requireNotNull(entry.endTimeMs))
        assertEquals(4_000L, entry.durationMs)
        assertEquals(LyricsLineAlignment.CENTER, entry.lineAlignment)
        assertEquals(LyricsTextDirection.RTL, entry.textDirection)
        assertEquals(true, entry.isRtl)
        assertEquals("group", entry.agent)
        assertEquals(main, entry.mainTrack)
        assertEquals(listOf(firstBackground, secondBackground), entry.backgroundTracks)
        assertEquals(listOf(translation), entry.translationTracks)
        assertEquals(listOf(romanization), entry.romanizationTracks)
        assertEquals(partialPhonetics, entry.phonetics)
        assertEquals("translated", entry.providerTranslationText)
        assertEquals("shalom world", entry.providerRomanizedText)
        assertEquals("he-Latn", entry.providerRomanizedLanguage)
        assertNull(entry.providerRomanizedWords)

        val legacyWords = requireNotNull(entry.words)
        assertEquals(4, legacyWords.size)
        assertEquals(listOf(false, false, true, true), legacyWords.map(WordTimestamp::isBackground))
        assertEquals(listOf(1.0, 2.2, 1.5, 2.6), legacyWords.map(WordTimestamp::startTime))
        assertEquals(listOf(1.8, 3.0, 2.1, 3.2), legacyWords.map(WordTimestamp::endTime))
    }

    @Test
    fun wordTimestampConversion_doesNotInventDuration() {
        val exactZeroDuration = WordTimestamp("held", 1.234, 1.234).toPreparedLyricsWord()

        assertEquals(1_234L, exactZeroDuration.startMs)
        assertEquals(1_234L, exactZeroDuration.endMs)
    }

    @Test
    fun preparedInstrumentalBreaks_matchDesktopUnionAndNeverAddOutro() {
        val result =
            insertPreparedInstrumentalBreaks(
                listOf(
                    preparedLine("long", 3_000L, 12_000L),
                    preparedLine("short overlap", 4_000L, 5_000L),
                    preparedLine("still covered", 9_000L, 10_000L),
                    preparedLine("after gap", 15_000L, 16_000L),
                ),
            )

        assertEquals(6, result.size)
        assertTrue(result[0].isInstrumental)
        assertEquals(0L, result[0].startMs)
        assertEquals(3_000L, result[0].endMs)
        assertFalse(result[1].isInstrumental)
        assertFalse(result[2].isInstrumental)
        assertFalse(result[3].isInstrumental)
        assertTrue(result[4].isInstrumental)
        assertEquals(12_000L, result[4].startMs)
        assertEquals(15_000L, result[4].endMs)
        assertFalse(result[5].isInstrumental)
        assertEquals("after gap", result[5].text)
    }

    private fun preparedLine(
        text: String,
        startMs: Long,
        endMs: Long,
    ) = PreparedLyricsLine(
        id = "$text:$startMs",
        startMs = startMs,
        endMs = endMs,
        text = text,
        alignment = LyricsLineAlignment.START,
        direction = LyricsTextDirection.LTR,
        main =
            PreparedLyricsTrack(
                text = text,
                language = null,
                words = ImmutableList.of(),
                startMs = startMs,
                endMs = endMs,
            ),
        backgrounds = ImmutableList.of(),
        translation = null,
        romanizedText = null,
        phonetics = ImmutableList.of(),
        isInstrumental = false,
    )

    private fun preferences() =
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
        )
}
