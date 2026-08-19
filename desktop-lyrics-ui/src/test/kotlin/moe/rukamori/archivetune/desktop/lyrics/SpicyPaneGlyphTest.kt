package moe.rukamori.archivetune.desktop.lyrics

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless verification of the single-canvas inline animator's text geometry pipeline.
 *
 * The pipeline must derive every animated glyph position from ONE `TextLayoutResult`
 * (the same layout that paints the dim/inactive line) so that activating a line never
 * re-shapes, re-orders or re-spaces the text. All assertions here check that the word
 * geometry is exactly what the BiDi engine laid out: cluster boxes come from
 * `getBoundingBox`, words keep their visual order, and the sweep position follows the
 * word's visual reading direction.
 */
class SpicyPaneGlyphTest {

    private val measurer = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = Density(1f),
        defaultLayoutDirection = LayoutDirection.Ltr,
        cacheSize = 8,
    )

    private val style = TextStyle(
        fontSize = 40.sp,
        lineHeight = 48.sp,
    )

    private fun measure(text: String, maxWidth: Int) =
        measurer.measure(
            text = AnnotatedString(text),
            style = style,
            constraints = Constraints(maxWidth = maxWidth),
        )

    private fun geometryOf(text: String, line: CharLine, maxWidth: Int = 800) =
        LineTextGeometry.build(measure(text, maxWidth), line, measurer, style)

    private fun lineOf(text: String, words: List<Pair<String, Long>>) = CharLine(
        chars = emptyList(),
        text = text,
        words = words.map { (word, startMs) ->
            val idx = text.indexOf(word)
            WordSpan(
                text = word,
                startChar = idx,
                endChar = idx + word.length,
                startMs = startMs,
                endMs = startMs + 1000L,
            )
        },
        startMs = 0L,
        endMs = 3000L,
    )

    private fun sceneLine(text: String, startMs: Long, endMs: Long) = CharLine(
        chars = emptyList(),
        text = text,
        words = emptyList(),
        startMs = startMs,
        endMs = endMs,
    )

    private fun singleWordLine(durationMs: Long) = CharLine(
        chars = emptyList(),
        text = "hello",
        words = listOf(
            WordSpan(
                text = "hello",
                startChar = 0,
                endChar = 5,
                startMs = 0L,
                endMs = durationMs,
            ),
        ),
        startMs = 0L,
        endMs = durationMs,
    )

    @Test
    fun buildsClusterBandsForEveryLetter() {
        val geometry = geometryOf(
            "Hello world",
            lineOf(
                "Hello world",
                listOf("Hello" to 0L, "world" to 1000L),
            ),
        )

        assertEquals(2, geometry.wordLetters.size)
        assertEquals(5, geometry.wordLetters[0].size)
        assertEquals(5, geometry.wordLetters[1].size)

        // Every cluster has a real glyph box from the line layout, sitting inside the
        // measured line, and its width equals that box (never a standalone re-measure).
        geometry.wordLetters.forEachIndexed { wi, letters ->
            letters.forEach { lt ->
                assertTrue(lt.width > 0f, "word $wi cluster ${lt.letter.ch} has zero width")
                assertEquals(lt.box.width, lt.width, "word $wi width must come from the layout box")
                assertTrue(
                    lt.box.left >= 0f && lt.box.right <= geometry.layout.size.width,
                    "word $wi cluster ${lt.letter.ch} escapes the laid-out line",
                )
            }
            // Clusters tile the word contiguously in visual order — no overlaps, no
            // gaps larger than the engine's own spacing.
            val boxes = letters.map { it.box }
            boxes.zipWithNext { a, b ->
                // Next cluster starts at or after the previous one ends (advance boxes
                // can differ from ink by sub-pixel rounding on some platforms).
                assertTrue(b.left >= a.right - 1f, "word $wi cluster bands overlap: ${a} -> ${b}")
            }
        }
    }

    @Test
    fun wordBoundsAreMonotonic() {
        val geometry = geometryOf(
            "Hello world",
            lineOf("Hello world", listOf("Hello" to 0L, "world" to 1000L)),
        )

        val first = geometry.wordRight[0]
        val second = geometry.wordLeft[1]
        assertTrue(second > first, "second word should start after first word ends")
    }

    @Test
    fun letterTimingSplitsWordEvenly() {
        val line = lineOf("Hello world", listOf("Hello" to 0L))
        val word = line.words[0]
        val letters = lettersOf(line, word)
        assertEquals(5, letters.size)
        assertEquals(0L, letters.first().startMs)
        assertEquals(1000L, letters.last().endMs)
        letters.forEachIndexed { i, l ->
            if (i > 0) assertEquals(letters[i - 1].endMs, l.startMs)
        }
    }

    @Test
    fun emptyWordListFallsBackToWholeLine() {
        val single = CharLine(
            chars = emptyList(),
            text = "No timings",
            words = emptyList(),
            startMs = 0L,
            endMs = 5000L,
        )
        val geometry = geometryOf("No timings", single)
        // No words -> no per-word geometry, but the pipeline must not crash.
        assertTrue(geometry.wordLetters.isEmpty())
        assertTrue(geometry.wordLeft.isEmpty())
    }

    @Test
    fun detectsRtlWords() {
        // "שלום עולם" (Hebrew "hello world") — words run right-to-left.
        val hebrew = lineOf("שלום עולם", listOf("שלום" to 0L, "עולם" to 1000L))
        val geometry = geometryOf("שלום עולם", hebrew)

        assertEquals(2, geometry.wordLetters.size)
        assertTrue(geometry.wordRtl[0], "first word should be detected RTL")
        assertTrue(geometry.wordRtl[1], "second word should be detected RTL")

        // RTL letters advance right-to-left in physical space: later letters have
        // smaller X within a word.
        val xs = geometry.wordLetters[0].map { it.box.left }
        assertTrue(xs.zipWithNext().all { (a, b) -> a > b }, "RTL letters should recede leftward")

        // ...and the first (logical) word is laid out to the RIGHT of the second.
        val firstWordLeft = geometry.wordLeft[0]
        val secondWordRight = geometry.wordRight[1]
        assertTrue(firstWordLeft > secondWordRight, "in an RTL line the first word sits right of the second")
    }

    @Test
    fun rtlSweepMatchesVisualReadingOrder() {
        // Hebrew "שלום" — the sweep must start from the word's RIGHT edge and travel
        // left, which for a pure RTL word is also the logical timestamp order.
        val hebrew = lineOf("שלום עולם", listOf("שלום" to 0L))
        val geometry = geometryOf("שלום עולם", hebrew)

        val letters = geometry.wordLetters[0]
        val wordLeft = geometry.wordLeft[0]
        val wordWidth = geometry.wordRight[0] - wordLeft

        val sweepPositions = letters.map {
            val centerRelX = (it.box.left + it.box.right) / 2f - wordLeft
            sweepPositionRel(centerRelX, wordWidth, wordRtl = true)
        }
        // Logical order -> ascending sweep position: first logical letter illuminates
        // first (it sits at the visual right edge).
        assertTrue(
            sweepPositions.zipWithNext().all { (a, b) -> b > a },
            "RTL sweep positions must follow logical letter order, got $sweepPositions",
        )
    }

    @Test
    fun ltrSweepMatchesVisualReadingOrder() {
        val line = lineOf("Hello world", listOf("Hello" to 0L))
        val geometry = geometryOf("Hello world", line)

        val letters = geometry.wordLetters[0]
        val wordLeft = geometry.wordLeft[0]
        val wordWidth = geometry.wordRight[0] - wordLeft

        val sweepPositions = letters.map {
            val centerRelX = (it.box.left + it.box.right) / 2f - wordLeft
            sweepPositionRel(centerRelX, wordWidth, wordRtl = false)
        }
        assertTrue(
            sweepPositions.zipWithNext().all { (a, b) -> b > a },
            "LTR sweep positions must follow logical letter order, got $sweepPositions",
        )
    }

    @Test
    fun mixedDirectionLineKeepsBiDiOrdering() {
        // Mixed Hebrew + English + numbers in one LTR-paragraph line. The BiDi engine
        // must place "Hello", the RTL run "שלום" and the number run "123" without the
        // active renderer re-ordering or overlapping them.
        val text = "Hello שלום 123"
        val line = lineOf(text, listOf("Hello" to 0L, "שלום" to 1000L, "123" to 2000L))
        val geometry = geometryOf(text, line)

        assertEquals(3, geometry.wordLetters.size)

        // Hebrew word is RTL; English and digits stay LTR — never reversed by locale.
        assertTrue(!geometry.wordRtl[0], "English word must stay LTR")
        assertTrue(geometry.wordRtl[1], "Hebrew word must be RTL")
        assertTrue(!geometry.wordRtl[2], "digit run must stay LTR")

        // No two words overlap, and the whole line spans the laid-out width: the active
        // word bands are exactly the pieces of one layout, so the line reads correctly.
        // NB: mixed-direction lines reorder words visually (here the trailing digit run
        // is pulled next to the Hebrew word, so `123` sits BETWEEN "Hello" and "שלום"),
        // so we must not assume logical index == visual order.
        val words = line.words.indices.map { i ->
            geometry.wordLeft[i] to geometry.wordRight[i]
        }
        words.sortedBy { it.first }.zipWithNext { (_, aRight), (bLeft, _) ->
            assertTrue(bLeft >= aRight, "mixed-direction words must not overlap: $words")
        }
        val allEdges = words.flatMap { listOf(it.first, it.second) }
        assertTrue(allEdges.max() <= geometry.layout.size.width)
        assertTrue(allEdges.min() >= 0f)
    }

    @Test
    fun combiningMarksStayInTheirCluster() {
        // Fully vowelised "שָׁלוֹם" — base letters with niqqud marks. Each mark must
        // stay glued to its base letter instead of becoming an orphan band.
        val text = "שָׁלוֹם"
        val line = lineOf(text, listOf(text to 0L))
        val letters = lettersOf(line, line.words[0])

        assertEquals(4, letters.size, "expected 4 grapheme clusters for שָׁלוֹם")
        assertEquals("שָׁ", letters[0].ch)
        assertEquals("ל", letters[1].ch)
        assertEquals("וֹ", letters[2].ch)
        assertEquals("ם", letters[3].ch)

        val geometry = geometryOf(text, line)
        assertEquals(4, geometry.wordLetters[0].size)
        assertTrue(geometry.wordRtl[0], "niqqud word must still be detected RTL")
        // Every cluster box real and positive-width (marks share their base's advance).
        geometry.wordLetters[0].forEach { lt ->
            assertTrue(lt.width > 0f, "cluster ${lt.letter.ch} must carry the base letter's width")
        }
    }

    @Test
    fun surrogatePairsAreKeptTogether() {
        val text = "שלום 😀"
        val line = lineOf(text, listOf("שלום" to 0L, "😀" to 1000L))
        val letters = lettersOf(line, line.words[1])

        assertEquals(1, letters.size)
        assertEquals("😀", letters[0].ch)

        val geometry = geometryOf(text, line)
        assertTrue(geometry.wordLetters[1][0].width > 0f)
    }

    @Test
    fun wordStatesKeepCompletedInlineWordsBrightDuringGaps() {
        val line = lineOf(
            "one two",
            listOf("one" to 0L, "two" to 2_000L),
        )
        val words = line.words

        assertEquals(WordState.ACTIVE, wordStateAt(500L, words[0]))
        assertEquals(WordState.SUNG, wordStateAt(1_500L, words[0]))
        assertEquals(WordState.NOT_SUNG, wordStateAt(1_500L, words[1]))
        assertEquals(-1, activeWordIndexAt(1_500L, words))
    }

    @Test
    fun activeWordIndexDoesNotTreatFutureWordsAsActive() {
        val line = lineOf("one two", listOf("one" to 0L, "two" to 2_000L))

        assertEquals(-1, activeWordIndexAt(-1L, line.words))
        assertEquals(0, activeWordIndexAt(500L, line.words))
        assertEquals(1, activeWordIndexAt(2_500L, line.words))
    }

    @Test
    fun letterAnimationStartsAtFiveHundredMilliseconds() {
        val tooShort = singleWordLine(LETTER_ANIMATION_MIN_MS - 1L)
        val capable = singleWordLine(LETTER_ANIMATION_MIN_MS)

        assertTrue(!geometryOf(tooShort.text, tooShort).wordLetterCapable.single())
        assertTrue(geometryOf(capable.text, capable).wordLetterCapable.single())
    }

    @Test
    fun heldWordsGetProgressivelyStrongerLetterMotionWithACap() {
        val short = letterEmphasisProfile(500L)
        val medium = letterEmphasisProfile(1_500L)
        val long = letterEmphasisProfile(3_000L)
        val absurdlyLong = letterEmphasisProfile(10_000L)

        assertTrue(medium.scaleExcursionMultiplier > short.scaleExcursionMultiplier)
        assertTrue(long.scaleExcursionMultiplier > medium.scaleExcursionMultiplier)
        assertEquals(long, absurdlyLong, "duration strength should stop growing after the cap")
        assertTrue(long.falloffExponent < short.falloffExponent, "long holds should involve nearby letters more")

        val normalPeak = emphasizedLetterScale(1.175f, short)
        val heldPeak = emphasizedLetterScale(1.175f, long)
        assertTrue(heldPeak > normalPeak)
        assertTrue(heldPeak <= 1.30f)
        assertEquals(0.95f, emphasizedLetterScale(0.95f, long))
    }

    @Test
    fun interludeDotsRequireAtLeastThreeSecondsOfSilence() {
        val justShort = CharLyrics(
            lines = listOf(
                sceneLine("one", 0L, 1_000L),
                sceneLine("two", 3_999L, 5_000L),
            ),
            preludeMs = 0L,
        )
        val exactThreshold = CharLyrics(
            lines = listOf(
                sceneLine("one", 0L, 1_000L),
                sceneLine("two", 4_000L, 5_000L),
            ),
            preludeMs = 0L,
        )

        assertTrue(buildLyricsSceneItems(justShort).none { it is LyricsSceneItem.Interlude })

        val items = buildLyricsSceneItems(exactThreshold)
        assertEquals(3, items.size)
        val interlude = items[1] as LyricsSceneItem.Interlude
        assertEquals(1_000L, interlude.startMs)
        assertEquals(4_000L, interlude.endMs)
        assertEquals(1, activeSceneIndexAt(2_500L, items))
        assertEquals(2, activeSceneIndexAt(4_000L, items))
    }

    @Test
    fun overlappingLinesRemainActiveIndependentlyForTheirFullIntervals() {
        val main = sceneLine("main vocal", 10_000L, 15_000L)
        val backing = sceneLine("(backing vocal)", 12_000L, 14_000L)

        assertTrue(isVocalLineActiveAt(12_500L, main))
        assertTrue(isVocalLineActiveAt(12_500L, backing))

        // The shorter overlapping line finishing must not make the longer line finish too.
        assertTrue(isVocalLineActiveAt(14_500L, main))
        assertTrue(!isVocalLineActiveAt(14_500L, backing))
        assertTrue(!isVocalLineActiveAt(15_000L, main))
    }

    @Test
    fun finishedLineKeepsAnimatedRendererDuringExitSettle() {
        val line = sceneLine("smooth handoff", 10_000L, 12_000L)

        assertTrue(shouldKeepAnimatedLineAt(11_999L, line))
        assertTrue(shouldKeepAnimatedLineAt(12_000L, line))
        assertTrue(shouldKeepAnimatedLineAt(12_000L + LINE_EXIT_SETTLE_MS - 1L, line))
        assertTrue(!shouldKeepAnimatedLineAt(12_000L + LINE_EXIT_SETTLE_MS, line))
    }

    @Test
    fun overlappingVocalsDoNotCreateFakeInterludeDots() {
        val lyrics = CharLyrics(
            lines = listOf(
                sceneLine("long main", 0L, 10_000L),
                sceneLine("short backing", 1_000L, 2_000L),
                // There appears to be a 3 s gap after the backing line, but the main
                // vocal still covers it all the way through 10 s.
                sceneLine("another backing", 5_000L, 6_000L),
                sceneLine("next real line", 13_000L, 14_000L),
            ),
            preludeMs = 0L,
        )

        val items = buildLyricsSceneItems(lyrics)
        val interludes = items.filterIsInstance<LyricsSceneItem.Interlude>()

        assertEquals(1, interludes.size)
        assertEquals(10_000L, interludes.single().startMs)
        assertEquals(13_000L, interludes.single().endMs)
        assertEquals(0, interludes.single().previousLineIndex)
        assertEquals(3, interludes.single().nextLineIndex)
    }

    @Test
    fun longPreludeGetsMusicalDotsBeforeFirstVocal() {
        val lyrics = CharLyrics(
            lines = listOf(sceneLine("finally", 3_000L, 4_000L)),
            preludeMs = 3_000L,
        )
        val items = buildLyricsSceneItems(lyrics)

        assertEquals(2, items.size)
        val interlude = items.first() as LyricsSceneItem.Interlude
        assertEquals(null, interlude.previousLineIndex)
        assertEquals(0, interlude.nextLineIndex)
        assertEquals(0L, interlude.startMs)
        assertEquals(3_000L, interlude.endMs)
    }

    @Test
    fun scrollAnchorLetsNextCurrentLineStayLowerBeforeAdvancing() {
        assertEquals(0, pinnedScrollAnchorLine(previousAnchorLine = -1, activeLine = 0))
        assertEquals(0, pinnedScrollAnchorLine(previousAnchorLine = 0, activeLine = 1))
        assertEquals(2, pinnedScrollAnchorLine(previousAnchorLine = 0, activeLine = 2))
        assertEquals(1, pinnedScrollAnchorLine(previousAnchorLine = 3, activeLine = 1))
        assertEquals(4, pinnedScrollAnchorLine(previousAnchorLine = 2, activeLine = 4, forceReset = true))
    }
}
