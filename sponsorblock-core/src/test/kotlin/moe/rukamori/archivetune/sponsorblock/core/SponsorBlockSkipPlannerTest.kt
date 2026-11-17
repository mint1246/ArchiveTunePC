package moe.rukamori.archivetune.sponsorblock.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SponsorBlockSkipPlannerTest {
    @Test
    fun skipsOnceUntilPlaybackLeavesTheSegment() {
        val planner = SponsorBlockSkipPlanner(listOf(SponsorBlockRange(1_000, 2_000)))
        assertEquals(2_000L, planner.targetPosition(1_200, null))
        assertNull(planner.targetPosition(1_300, null))
        assertNull(planner.targetPosition(2_000, null))
        assertEquals(2_000L, planner.targetPosition(1_100, null))
    }

    @Test
    fun respectsDurationAndShortSeekThreshold() {
        val planner = SponsorBlockSkipPlanner(listOf(SponsorBlockRange(1_000, 2_000)))
        assertNull(planner.targetPosition(1_800, 1_850))
        assertNull(planner.targetPosition(1_700, 2_000))
        assertNull(planner.targetPosition(1_960, 2_000))
        assertEquals(1_900L, planner.targetPosition(1_100, 1_900))
    }
}
