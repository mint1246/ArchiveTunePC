/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapedLyricsLineTest {
    @Test
    fun inverseSegments_mergeOverlappingTimedBandsAndClampToTheVisualRow() {
        val segments =
            shapedLyricsInverseSegments(
                width = 100f,
                intervals =
                    listOf(
                        80f to 120f,
                        35f to 60f,
                        20f to 40f,
                        -10f to 5f,
                    ),
            )

        assertEquals(listOf(5f to 20f, 60f to 80f), segments)
    }

    @Test
    fun inverseSegments_leaveTheWholeRowWhenThereAreNoTimedGlyphs() {
        assertEquals(
            listOf(0f to 240f),
            shapedLyricsInverseSegments(width = 240f, intervals = emptyList()),
        )
    }

    @Test
    fun heldWordEmphasis_matchesDesktopGrowthAndCap() {
        val short = shapedLyricsHeldEmphasis(500L)
        val medium = shapedLyricsHeldEmphasis(1_500L)
        val long = shapedLyricsHeldEmphasis(3_000L)
        val capped = shapedLyricsHeldEmphasis(10_000L)

        assertTrue(medium.scaleExcursionMultiplier > short.scaleExcursionMultiplier)
        assertTrue(long.scaleExcursionMultiplier > medium.scaleExcursionMultiplier)
        assertTrue(long.falloffExponent < short.falloffExponent)
        assertEquals(long, capped)
        assertEquals(0.95f, shapedLyricsEmphasizedScale(0.95f, long), 0.0001f)
        assertTrue(shapedLyricsEmphasizedScale(1.175f, long) <= 1.30f)
    }

    @Test
    fun graphemeYOffset_matchesDesktopRawTwoPixelScale() {
        assertEquals(0.02f, shapedLyricsGraphemeYOffsetPx(0.01f, 1f), 0.0001f)
        assertEquals(-0.02f, shapedLyricsGraphemeYOffsetPx(-0.005f, 2f), 0.0001f)
    }

}
