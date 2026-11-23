/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.session

import kotlin.math.max
import kotlin.math.min

/** Timing constants owned by the shared crossfade scheduling contract. */
const val CROSSFADE_MIN_DURATION_MS = 500L
const val CROSSFADE_END_GUARD_MS = 150L
const val CROSSFADE_PREPARE_AHEAD_MS = 30_000L

/** The usable crossfade duration after clamping to the current track's remaining time. */
fun effectiveCrossfadeDuration(
    configuredDurationMs: Long,
    trackDurationMs: Long,
): Long? {
    if (trackDurationMs == Long.MIN_VALUE || trackDurationMs <= 0L) return null
    val maxDuration = trackDurationMs - CROSSFADE_END_GUARD_MS
    if (maxDuration < CROSSFADE_MIN_DURATION_MS) return null
    return min(max(configuredDurationMs, CROSSFADE_MIN_DURATION_MS), maxDuration)
}

/**
 * The decision produced for one poll of the crossfade scheduler.
 *
 * [prepareIncoming] asks the caller to ready the incoming player before the trigger point;
 * [startCrossfade] asks it to begin the overlap now, with [adjustedDurationMs] as the real length.
 * [sleepMs] is the next poll delay, shortened near the trigger so the handoff stays tight without
 * busy-looping far from it.
 */
data class CrossfadeTriggerDecision(
    val prepareIncoming: Boolean,
    val triggerReached: Boolean,
    val startCrossfade: Boolean,
    val adjustedDurationMs: Long?,
    val sleepMs: Long,
)

/**
 * Headless crossfade scheduling state machine.
 *
 * It owns no player and creates no coroutines: the platform adapter supplies position snapshots
 * and executes the returned decisions. This keeps Android's Media3 polling loop and the desktop
 * renderer on the same timing rules while preserving each platform's actual audio handoff.
 */
class CrossfadeTriggerPlanner(
    val effectiveDurationMs: Long,
) {
    init {
        require(effectiveDurationMs >= CROSSFADE_MIN_DURATION_MS) {
            "effective crossfade duration must be at least $CROSSFADE_MIN_DURATION_MS ms"
        }
    }

    /**
     * Plan one poll. [alreadyPrepared] prevents re-preparing the incoming player on every tick.
     */
    fun plan(
        trackDurationMs: Long,
        positionMs: Long,
        alreadyPrepared: Boolean,
    ): CrossfadeTriggerDecision {
        val triggerAt = trackDurationMs - effectiveDurationMs - CROSSFADE_END_GUARD_MS
        val remainingToTrigger = triggerAt - positionMs
        val prepareIncoming =
            !alreadyPrepared && remainingToTrigger <= CROSSFADE_PREPARE_AHEAD_MS

        if (remainingToTrigger <= 0L) {
            val adjusted = adjustedDuration(trackDurationMs, positionMs)
            return CrossfadeTriggerDecision(
                // Prepare at most once; the platform adapter de-duplicates by target anyway.
                prepareIncoming = !alreadyPrepared,
                triggerReached = true,
                startCrossfade = adjusted != null,
                adjustedDurationMs = adjusted,
                sleepMs = 1L,
            )
        }

        val sleepMs =
            when {
                remainingToTrigger > 5_000L -> 1_000L
                remainingToTrigger > 1_000L -> 250L
                else -> 50L
            }
        return CrossfadeTriggerDecision(
            prepareIncoming = prepareIncoming,
            triggerReached = false,
            startCrossfade = false,
            adjustedDurationMs = null,
            sleepMs = min(max(sleepMs, 1L), remainingToTrigger),
        )
    }

    fun adjustedDuration(trackDurationMs: Long, positionMs: Long): Long? =
        min(trackDurationMs - positionMs - CROSSFADE_END_GUARD_MS, effectiveDurationMs)
            .takeIf { it >= CROSSFADE_MIN_DURATION_MS }
}
