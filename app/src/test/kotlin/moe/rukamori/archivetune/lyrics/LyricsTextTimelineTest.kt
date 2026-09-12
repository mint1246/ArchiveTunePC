/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTextTimelineTest {
    @Test
    fun providerTextStaysAuthoritative_andRepeatedWordsMapSequentially() {
        val line =
            LyricsEntry(
                time = 1_000L,
                text = "  go   go  ",
                durationMs = 2_000L,
                words =
                    listOf(
                        WordTimestamp(" go ", 1.0, 1.5),
                        WordTimestamp("go", 1.5, 2.0),
                    ),
            )

        val result = LyricsTextTimelineBuilder.fromEntry(line)

        assertEquals("  go   go  ", result.displayText)
        assertEquals(listOf(2 to 4, 7 to 9), result.words.map { it.startUtf16 to it.endUtf16 })
        assertEquals(listOf("go", "go"), result.words.map(LyricsTextWord::text))
    }

    @Test
    fun unmatchedWordDoesNotConsumeSearchPosition_andFallbackUsesWholeDisplayLine() {
        val partiallyMapped =
            LyricsTextTimelineBuilder.fromEntry(
                LyricsEntry(
                    time = 0L,
                    text = "hello world",
                    words =
                        listOf(
                            WordTimestamp("missing", 0.0, 0.5),
                            WordTimestamp("world", 0.5, 1.0),
                        ),
                ),
            )
        assertEquals(1, partiallyMapped.words.size)
        assertEquals("world", partiallyMapped.words.single().text)
        assertEquals(6, partiallyMapped.words.single().startUtf16)

        val fallback =
            LyricsTextTimelineBuilder.fromEntry(
                LyricsEntry(
                    time = 2_000L,
                    text = " exact  spacing ",
                    durationMs = 1_500L,
                    words = listOf(WordTimestamp("not present", 2.0, 3.0)),
                ),
            )
        assertEquals(1, fallback.words.size)
        assertEquals(" exact  spacing ", fallback.words.single().text)
        assertEquals(0, fallback.words.single().startUtf16)
        assertEquals(fallback.displayText.length, fallback.words.single().endUtf16)
        assertEquals(2_000L, fallback.words.single().startMs)
        assertEquals(3_500L, fallback.words.single().endMs)
    }

    @Test
    fun graphemesKeepCombiningMarksSurrogatesAndZwJEmojiWhole() {
        val hebrew = "שָׁלוֹם"
        val family = "👩‍👩‍👧‍👦"
        val text = "$hebrew 😀 $family"
        val result =
            LyricsTextTimelineBuilder.fromEntry(
                LyricsEntry(
                    time = 0L,
                    text = text,
                    words =
                        listOf(
                            WordTimestamp(hebrew, 0.0, 0.8),
                            WordTimestamp("😀", 0.8, 1.2),
                            WordTimestamp(family, 1.2, 2.0),
                        ),
                ),
            )

        assertEquals(listOf("שָׁ", "ל", "וֹ", "ם"), result.words[0].graphemes.map(LyricsTextGrapheme::text))
        assertEquals(listOf("😀"), result.words[1].graphemes.map(LyricsTextGrapheme::text))
        assertEquals(listOf(family), result.words[2].graphemes.map(LyricsTextGrapheme::text))
        assertEquals(0, result.words[0].graphemes.first().startUtf16)
        assertEquals(hebrew.length, result.words[0].graphemes.last().endUtf16)
    }

    @Test
    fun graphemeTimingEvenlyPartitionsProviderWordInterval() {
        val result =
            LyricsTextTimelineBuilder.fromEntry(
                LyricsEntry(
                    time = 1_000L,
                    text = "abcd",
                    words = listOf(WordTimestamp("abcd", 1.0, 2.0)),
                ),
            )

        assertEquals(
            listOf(1_000L to 1_250L, 1_250L to 1_500L, 1_500L to 1_750L, 1_750L to 2_000L),
            result.words.single().graphemes.map { it.startMs to it.endMs },
        )
    }

    @Test
    fun preparedLineUsesLineText_andMainWordTimingsOnly() {
        val line =
            PreparedLyricsLine(
                id = "line",
                startMs = 1_000L,
                endMs = 4_000L,
                text = "hello   world",
                alignment = LyricsLineAlignment.END,
                direction = LyricsTextDirection.RTL,
                main =
                    PreparedLyricsTrack(
                        text = "hello world",
                        language = null,
                        words =
                            ImmutableList.of(
                                PreparedLyricsWord("hello", 1_000L, 2_000L),
                                PreparedLyricsWord("world", 2_000L, 3_500L),
                            ),
                    ),
                backgrounds =
                    ImmutableList.of(
                        PreparedLyricsTrack(
                            text = "background",
                            language = null,
                            words = ImmutableList.of(PreparedLyricsWord("background", 1_500L, 2_500L)),
                        ),
                    ),
                translation = null,
                romanizedText = null,
                phonetics = ImmutableList.of(),
                isInstrumental = false,
            )

        val result = LyricsTextTimelineBuilder.fromPreparedLine(line)

        assertEquals("hello   world", result.displayText)
        assertEquals(listOf("hello", "world"), result.words.map(LyricsTextWord::text))
        assertEquals(listOf(0 to 5, 8 to 13), result.words.map { it.startUtf16 to it.endUtf16 })
        assertEquals(1_000L, result.startMs)
        assertEquals(4_000L, result.endMs)
    }

    @Test
    fun unmatchedBackgroundWordsDoNotDisplaceMainTextMapping() {
        val result =
            LyricsTextTimelineBuilder.fromEntry(
                LyricsEntry(
                    time = 0L,
                    text = "main",
                    durationMs = 1_000L,
                    words =
                        listOf(
                            WordTimestamp("background", 0.0, 1.0, isBackground = true),
                            WordTimestamp("main", 0.0, 1.0),
                        ),
                ),
            )

        assertEquals(listOf("main"), result.words.map(LyricsTextWord::text))
    }

    @Test
    fun backgroundOnlyTimedTokensMapIntoAuthoritativeDisplayLikeDesktop() {
        val result =
            LyricsTextTimelineBuilder.fromEntry(
                LyricsEntry(
                    time = 1_000L,
                    text = "background",
                    durationMs = 2_000L,
                    words = listOf(WordTimestamp("background", 1.2, 2.4, isBackground = true)),
                ),
            )

        assertEquals(listOf("background"), result.words.map(LyricsTextWord::text))
        assertEquals(1_200L, result.words.single().startMs)
        assertEquals(2_400L, result.words.single().endMs)
    }
}
