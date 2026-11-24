/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkedDataSourceTest {
    @Test
    fun adaptiveChunkRampStartsSmallAndGrowsToConfiguredMax() {
        val max = 8L * 1024L * 1024L
        assertEquals(128L * 1024L, ChunkedDataSource.nextChunkLimit(0, max))
        assertEquals(512L * 1024L, ChunkedDataSource.nextChunkLimit(1, max))
        assertEquals(2L * 1024L * 1024L, ChunkedDataSource.nextChunkLimit(2, max))
        assertEquals(max, ChunkedDataSource.nextChunkLimit(3, max))
        assertEquals(max, ChunkedDataSource.nextChunkLimit(10, max))
    }

    @Test
    fun adaptiveChunkRampNeverExceedsSmallConfiguredMaxChunkLength() {
        val smallMax = 64L * 1024L
        assertEquals(smallMax, ChunkedDataSource.nextChunkLimit(0, smallMax))
        assertEquals(smallMax, ChunkedDataSource.nextChunkLimit(1, smallMax))
        assertEquals(smallMax, ChunkedDataSource.nextChunkLimit(5, smallMax))
    }
}
