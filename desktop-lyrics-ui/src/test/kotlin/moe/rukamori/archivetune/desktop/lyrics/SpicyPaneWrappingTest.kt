package moe.rukamori.archivetune.desktop.lyrics

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpicyPaneWrappingTest {
    private val measurer = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = Density(1f),
        defaultLayoutDirection = LayoutDirection.Ltr,
        cacheSize = 8,
    )

    private val style = TextStyle(
        fontSize = 40.sp,
        lineHeight = 48.sp,
        textAlign = TextAlign.Center,
    )

    private fun timedLine(text: String): CharLine {
        var searchFrom = 0
        val words = text.split(' ').mapIndexed { index, word ->
            val start = text.indexOf(word, searchFrom)
            searchFrom = start + word.length
            WordSpan(
                text = word,
                startChar = start,
                endChar = start + word.length,
                startMs = index * 1_000L,
                endMs = (index + 1) * 1_000L,
            )
        }
        return CharLine(
            chars = emptyList(),
            text = text,
            words = words,
            startMs = 0L,
            endMs = words.size * 1_000L,
        )
    }

    private fun geometry(text: String, maxWidth: Int): LineTextGeometry {
        val line = timedLine(text)
        val layout = measurer.measure(
            text = AnnotatedString(text),
            style = style,
            constraints = Constraints(maxWidth = maxWidth),
        )
        return LineTextGeometry.build(layout, line, measurer, style)
    }

    @Test
    fun wrappedWordsKeepTheirOwnVerticalRow() {
        val text = "alpha beta gamma delta epsilon"
        val geometry = geometry(text, maxWidth = 190)
        assertTrue(geometry.layout.lineCount >= 2, "test text must wrap")

        geometry.wordLetters.forEachIndexed { wordIndex, letters ->
            val visible = letters.filter { it.width > 0f }
            assertTrue(visible.isNotEmpty())
            val row = visible.first().lineIndex
            assertTrue(visible.all { it.lineIndex == row }, "normal word unexpectedly split across rows")
            assertEquals(visible.minOf { it.box.top }, geometry.wordTop[wordIndex])
            assertEquals(visible.maxOf { it.box.bottom }, geometry.wordBottom[wordIndex])

            val baseline = geometry.layout.getLineBaseline(row)
            assertTrue(baseline >= geometry.layout.getLineTop(row))
            assertTrue(baseline <= geometry.layout.getLineBottom(row))
        }

        val firstRowBottom = geometry.layout.getLineBottom(0)
        val secondRowWord = geometry.wordLetters.indexOfFirst { letters ->
            letters.firstOrNull()?.lineIndex?.let { it > 0 } == true
        }
        assertTrue(secondRowWord >= 0, "expected at least one word on a wrapped row")
        assertTrue(
            geometry.wordTop[secondRowWord] >= firstRowBottom,
            "wrapped word must animate around its own row instead of the paragraph center",
        )
    }

    @Test
    fun baseTextMasksAreSplitPerVisualRow() {
        val text = "alpha beta gamma delta epsilon"
        val geometry = geometry(text, maxWidth = 190)
        assertTrue(geometry.layout.lineCount >= 2, "test text must wrap")

        geometry.wordLetters.flatten().filter { it.width > 0f }.forEach { letter ->
            val box = letter.box
            val intersectsBaseOnSameRow = geometry.baseTextSegments.any { segment ->
                val segmentRow = geometry.layout.getLineForVerticalPosition((segment.top + segment.bottom) / 2f)
                val overlapsX = segment.left < box.right && segment.right > box.left
                segmentRow == letter.lineIndex && overlapsX
            }
            assertTrue(!intersectsBaseOnSameRow, "timed glyph ${letter.letter.ch} leaked into dim base mask")
        }

        val rowsWithSegments = geometry.baseTextSegments
            .map { segment -> geometry.layout.getLineForVerticalPosition((segment.top + segment.bottom) / 2f) }
            .toSet()
        assertEquals((0 until geometry.layout.lineCount).toSet(), rowsWithSegments)
    }
}
