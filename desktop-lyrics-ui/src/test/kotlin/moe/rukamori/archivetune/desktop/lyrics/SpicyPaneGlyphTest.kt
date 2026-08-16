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
        LineTextGeometry.build(measure(text, maxWidth), line)

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
        val xs = geometry.wordLetters[0].map { it.x }
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
}