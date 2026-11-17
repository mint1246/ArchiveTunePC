/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossfadeTriggerPlannerTest {
    @Test
    fun effectiveDuration_clampsConfiguredValue_andRejectsTracksTooShortToCrossfade() {
        assertEquals(5_000L, effectiveCrossfadeDuration(5_000L, 30_000L))
        // Longest overlap leaves the end guard intact.
        assertEquals(9_850L, effectiveCrossfadeDuration(20_000L, 10_000L))
        // Shortest allowed overlap wins over a disabled/short configured value.
        assertEquals(CROSSFADE_MIN_DURATION_MS, effectiveCrossfadeDuration(100L, 10_000L))
        assertNull(effectiveCrossfadeDuration(5_000L, CROSSFADE_END_GUARD_MS + CROSSFADE_MIN_DURATION_MS - 1))
        assertNull(effectiveCrossfadeDuration(5_000L, 0L))
        assertNull(effectiveCrossfadeDuration(5_000L, Long.MIN_VALUE))
    }

    @Test
    fun farFromTrigger_preparesIncomingAndPollsCoarsely() {
        val planner = CrossfadeTriggerPlanner(effectiveDurationMs = 5_000L)

        // triggerAt = 60_000 - 5_000 - 150 = 54_850; 30_000 is inside prepare-ahead.
        val decision = planner.plan(trackDurationMs = 60_000L, positionMs = 30_000L, alreadyPrepared = false)
        assertTrue(decision.prepareIncoming)
        assertFalse(decision.startCrossfade)
        assertNull(decision.adjustedDurationMs)
        assertEquals(1_000L, decision.sleepMs)

        val prepared = planner.plan(trackDurationMs = 60_000L, positionMs = 30_000L, alreadyPrepared = true)
        assertFalse(prepared.prepareIncoming)
    }

    @Test
    fun nearTrigger_sleepShortensInStages() {
        val planner = CrossfadeTriggerPlanner(effectiveDurationMs = 1_000L)

        // triggerAt = 20_000 - 1_000 - 150 = 18_850
        assertEquals(1_000L, planner.plan(20_000L, 10_000L, true).sleepMs)
        assertEquals(250L, planner.plan(20_000L, 15_000L, true).sleepMs)
        assertEquals(50L, planner.plan(20_000L, 18_800L, true).sleepMs)
    }

    @Test
    fun sleepIsNeverLongerThanRemainingTime_andNeverZero() {
        val planner = CrossfadeTriggerPlanner(effectiveDurationMs = 1_000L)

        // 20 ms before the trigger, the coarse 1 s poll must be capped to the remaining time.
        assertEquals(20L, planner.plan(20_000L, 18_830L, true).sleepMs)
        // At the trigger point the caller is told to act immediately.
        assertEquals(1L, planner.plan(20_000L, 18_850L, true).sleepMs)
    }

    @Test
    fun atTrigger_startsCrossfadeWithDurationClampedToRemainingTrack() {
        val planner = CrossfadeTriggerPlanner(effectiveDurationMs = 5_000L)

        // triggerAt = 30_000 - 5_000 - 150 = 24_850
        val decision = planner.plan(trackDurationMs = 30_000L, positionMs = 24_900L, alreadyPrepared = true)
        assertTrue(decision.startCrossfade)
        assertEquals(4_950L, decision.adjustedDurationMs)
        assertFalse(decision.prepareIncoming)
    }

    @Test
    fun atTrigger_doesNotStartWhenRemainingAudioIsBelowMinimum() {
        val planner = CrossfadeTriggerPlanner(effectiveDurationMs = 5_000L)

        val decision = planner.plan(trackDurationMs = 5_700L, positionMs = 5_560L, alreadyPrepared = true)
        assertTrue(decision.triggerReached)
        assertTrue(decision.startCrossfade.not())
        assertNull(decision.adjustedDurationMs)
    }

    @Test
    fun adjustedDurationUsesPositionAfterPreparation() {
        val planner = CrossfadeTriggerPlanner(effectiveDurationMs = 5_000L)
        assertEquals(4_950L, planner.plan(30_000L, 24_900L, false).adjustedDurationMs)
        assertNull(planner.adjustedDuration(30_000L, 29_500L))
    }

    @Test
    fun afterTrigger_stillAsksToPrepareOnceButNeverStartsTwicePerPosition() {
        val planner = CrossfadeTriggerPlanner(effectiveDurationMs = 2_000L)

        val decision = planner.plan(trackDurationMs = 10_000L, positionMs = 9_000L, alreadyPrepared = false)
        assertTrue(decision.prepareIncoming)
        // Remaining audio after the guard is 850 ms, above the 500 ms minimum.
        assertTrue(decision.startCrossfade)
        assertEquals(850L, decision.adjustedDurationMs)
    }

    @Test
    fun plannerRejectsInvalidEffectiveDuration() {
        assertFalse(
            runCatching { CrossfadeTriggerPlanner(effectiveDurationMs = CROSSFADE_MIN_DURATION_MS - 1) }
                .isSuccess,
        )
    }
}
