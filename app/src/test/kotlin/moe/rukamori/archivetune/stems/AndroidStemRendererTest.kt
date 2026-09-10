/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStemRendererTest {
    @Test
    fun sixAlignedSources_doNotResyncInsideTolerance() {
        assertFalse(
            stemRendererNeedsResync(
                primaryPositionMs = 10_000L,
                stemPositionsMs = listOf(9_900L, 9_920L, 10_000L, 10_020L, 10_050L, 10_100L),
            ),
        )
    }

    @Test
    fun missingSourceOrDriftOutsideTolerance_requestsResync() {
        assertTrue(stemRendererNeedsResync(10_000L, listOf(10_000L)))
        assertTrue(
            stemRendererNeedsResync(
                primaryPositionMs = 10_000L,
                stemPositionsMs = listOf(10_000L, 10_000L, 10_000L, 10_000L, 10_121L, 10_000L),
            ),
        )
    }
}