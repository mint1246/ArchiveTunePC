/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackResumptionPlannerTest {
    private val persisted = PlaybackResumptionPlanner.PersistedItems(listOf("a", "b", "c"), 1, 5_000L)

    @Test
    fun `empty current queue restores persisted queue for playback`() {
        val result = PlaybackResumptionPlanner.resolve(emptyList(), 0, 0, persisted, true)
        assertEquals(PlaybackResumptionPlanner.Result(listOf("a", "b", "c"), 1, 5_000L), result)
    }

    @Test
    fun `matching single current item restores persisted queue only for playback`() {
        val playing = PlaybackResumptionPlanner.resolve(listOf("b"), 0, 2_000, persisted, true)
        val browsing = PlaybackResumptionPlanner.resolve(listOf("b"), 0, 2_000, persisted, false)
        assertEquals(PlaybackResumptionPlanner.Result(listOf("a", "b", "c"), 1, 5_000L), playing)
        assertEquals(PlaybackResumptionPlanner.Result(listOf("b"), 0, 2_000L), browsing)
    }

    @Test
    fun `nonmatching current queue takes precedence over persisted queue`() {
        val result = PlaybackResumptionPlanner.resolve(listOf("x", "y"), 1, 10, persisted, true)
        assertEquals(PlaybackResumptionPlanner.Result(listOf("x", "y"), 1, 10L), result)
    }

    @Test
    fun `browse returns selected persisted item and clamps invalid index and position`() {
        val invalid = PlaybackResumptionPlanner.PersistedItems(listOf("a", "b"), 9, -20L)
        val result = PlaybackResumptionPlanner.resolve(emptyList(), 0, 0, invalid, false)
        assertEquals(PlaybackResumptionPlanner.Result(listOf("b"), 0, 0L), result)
    }

    @Test
    fun `empty queues produce empty result`() {
        val result = PlaybackResumptionPlanner.resolve<String>(emptyList(), 4, 7, null, true)
        assertEquals(PlaybackResumptionPlanner.Result(emptyList(), 0, 0L), result)
    }
}
