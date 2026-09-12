/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMotionPhysicsTest {
    @Test
    fun naturalCubicSpline_hitsEveryDesktopKnot() {
        val spline =
            NaturalCubicSpline(
                doubleArrayOf(0.0, 0.15, 0.6, 1.0),
                doubleArrayOf(0.0, 1.0, 1.0, 0.0),
            )

        assertEquals(0.0, spline.at(0.0), 1e-9)
        assertEquals(1.0, spline.at(0.15), 1e-9)
        assertEquals(1.0, spline.at(0.6), 1e-9)
        assertEquals(0.0, spline.at(1.0), 1e-9)
    }

    @Test
    fun desktopSpring_convergesToGoal() {
        val spring = LyricsSpring(startPosition = 0.0, frequency = 1.0, dampingRatio = 0.9, goal = 1.0)

        repeat(480) { spring.step(1.0 / 120.0) }

        assertEquals(1.0, spring.snapshotPosition(), 0.0005)
        assertTrue(spring.canSleep())
    }

    @Test
    fun graphemeTiming_keepsHebrewNiqqudAttachedAndEvenlyTimed() {
        val graphemes = LyricsMotionPhysics.graphemeTimings("שָׁלוֹם", 0L, 1_000L)

        assertEquals(listOf("שָׁ", "ל", "וֹ", "ם"), graphemes.map { it.text })
        assertEquals(listOf(0L, 250L, 500L, 750L), graphemes.map { it.startMs })
        assertEquals(1_000L, graphemes.last().endMs)
    }

    @Test
    fun graphemeTiming_keepsEmojiZwJAndFlagSequencesWhole() {
        val family = "👩‍👩‍👧‍👦"
        val flag = "🇮🇱"

        assertEquals(listOf(family), LyricsMotionPhysics.graphemeTimings(family, 0L, 1_000L).map { it.text })
        assertEquals(listOf(flag), LyricsMotionPhysics.graphemeTimings(flag, 0L, 1_000L).map { it.text })
    }

    @Test
    fun firstStrongDirection_handlesRtlAndMixedScriptByCodePoint() {
        assertEquals(true, LyricsMotionPhysics.firstStrongRtl("👩‍👩‍👧‍👦 שלום"))
        assertEquals(false, LyricsMotionPhysics.firstStrongRtl("hello שלום"))
        assertEquals(true, LyricsMotionPhysics.firstStrongRtl("שלום hello"))
        assertEquals(null, LyricsMotionPhysics.firstStrongRtl("✨ 123"))
    }

    @Test
    fun longWordFill_advancesByEasedGraphemeInsteadOfRawUtf16Units() {
        val progress = LyricsMotionPhysics.graphemeFillProgress("hello", 0L, 1_000L, 100L)

        // First of five graphemes, halfway through its 200 ms slot:
        // sin(pi/4) / 5 ~= .141421.
        assertEquals(0.141421f, progress, 0.0001f)
    }

    @Test
    fun lineDepth_matchesDesktopFalloff() {
        assertEquals(1f, LyricsMotionPhysics.lineAlpha(0), 0.0001f)
        assertEquals(1.05f, LyricsMotionPhysics.lineScale(0), 0.0001f)
        assertEquals(0f, LyricsMotionPhysics.lineBlurDp(0), 0.0001f)

        assertEquals(1f / 1.8f, LyricsMotionPhysics.lineAlpha(1), 0.0001f)
        assertEquals(0.99f, LyricsMotionPhysics.lineScale(1), 0.0001f)
        assertEquals(1.15f, LyricsMotionPhysics.lineBlurDp(1), 0.0001f)

        assertEquals(0.25f, LyricsMotionPhysics.lineAlpha(20), 0.0001f)
        assertEquals(0.97f, LyricsMotionPhysics.lineScale(20), 0.0001f)
        assertEquals(3.5f, LyricsMotionPhysics.lineBlurDp(20), 0.0001f)
    }

    @Test
    fun pinnedScrollAnchor_countsOnlyVocalRowsAndKeepsOneNewCurrentLineBelowAnchor() {
        val vocalRows = listOf(1, 2, 4, 5)

        assertEquals(1, LyricsMotionPhysics.pinnedScrollAnchor(-1, 1, vocalRows))
        assertEquals(1, LyricsMotionPhysics.pinnedScrollAnchor(1, 2, vocalRows))
        assertEquals(4, LyricsMotionPhysics.pinnedScrollAnchor(1, 4, vocalRows))
        assertEquals(2, LyricsMotionPhysics.pinnedScrollAnchor(5, 2, vocalRows))
        assertEquals(5, LyricsMotionPhysics.pinnedScrollAnchor(2, 5, vocalRows, forceReset = true))
    }

    @Test
    fun scrollAnchorSpring_movesContinuouslyAndSettlesAtDesktopFocalTarget() {
        var measuredError = 640.0
        val follower = LyricsScrollAnchorSpring(measuredError)

        val firstDelta = follower.scrollDeltaForFrame(measuredError, 1.0 / 60.0)
        assertTrue(firstDelta > 0.0)
        assertTrue(firstDelta < measuredError)
        measuredError -= firstDelta

        repeat(600) {
            val delta = follower.scrollDeltaForFrame(measuredError, 1.0 / 120.0)
            measuredError -= delta
        }

        assertEquals(0.0, measuredError, 0.001)
        assertTrue(follower.canSleep())

        var reverseError = -320.0
        val reverseFollower = LyricsScrollAnchorSpring(reverseError)
        val reverseDelta = reverseFollower.scrollDeltaForFrame(reverseError, 1.0 / 60.0)
        assertTrue(reverseDelta < 0.0)
        assertTrue(kotlin.math.abs(reverseDelta) < kotlin.math.abs(reverseError))
    }

    @Test
    fun sharedGraphemeRanges_areTheSourceForMotionOffsets() {
        val text = "xשָׁ👩‍👩‍👧‍👦🇮🇱y"
        val ranges = LyricsMotionPhysics.graphemeRanges(text)
        val timings = LyricsMotionPhysics.graphemeTimings(text, 0L, 5_000L)

        assertEquals(ranges.map { text.substring(it.first, it.last + 1) }, timings.map { it.text })
        assertEquals(ranges.map { it.first }, timings.map { it.startOffset })
        assertEquals(ranges.map { it.last + 1 }, timings.map { it.endOffset })
    }

    @Test
    fun inlineTarget_usesDesktopSplineEndpointsAndGraphemeFillForLongWords() {
        val before = LyricsMotionPhysics.inlineTarget("hello", 1_000L, 2_000L, 0L)
        val active = LyricsMotionPhysics.inlineTarget("hello", 1_000L, 2_000L, 1_100L)
        val after = LyricsMotionPhysics.inlineTarget("hello", 1_000L, 2_000L, 2_000L)

        assertEquals(0.95f, before.scale, 0.0001f)
        assertEquals(0f, before.glow, 0.0001f)
        assertEquals(0f, before.fillProgress, 0.0001f)
        assertTrue(active.fillProgress > 0.1f)
        assertEquals(1f, after.scale, 0.0001f)
        assertEquals(0f, after.glow, 0.0001f)
        assertEquals(1f, after.fillProgress, 0.0001f)
    }

    @Test
    fun heldWords_useDesktopLetterEmphasisProfileAndCap() {
        val threshold = LyricsMotionPhysics.letterEmphasisProfile(500L)
        val medium = LyricsMotionPhysics.letterEmphasisProfile(1_500L)
        val capped = LyricsMotionPhysics.letterEmphasisProfile(3_000L)
        val absurd = LyricsMotionPhysics.letterEmphasisProfile(10_000L)

        assertEquals(1f, threshold.scaleExcursionMultiplier, 0.0001f)
        assertEquals(2.8, threshold.falloffExponent, 0.0001)
        assertTrue(medium.scaleExcursionMultiplier > threshold.scaleExcursionMultiplier)
        assertEquals(1.70f, capped.scaleExcursionMultiplier, 0.0001f)
        assertEquals(2.3, capped.falloffExponent, 0.0001)
        assertEquals(1.35f, capped.yMultiplier, 0.0001f)
        assertEquals(1.25f, capped.glowMultiplier, 0.0001f)
        assertEquals(capped, absurd)
        assertEquals(1.2975f, LyricsMotionPhysics.emphasizedLetterScale(1.175f, capped), 0.0001f)
        assertEquals(0.95f, LyricsMotionPhysics.emphasizedLetterScale(0.95f, capped), 0.0001f)
    }

    @Test
    fun graphemeMotion_startsAtDesktopFiveHundredMillisecondThreshold() {
        assertTrue(LyricsMotionPhysics.graphemeMotionTargets("word", 0L, 499L, 250L).isEmpty())
        assertEquals(4, LyricsMotionPhysics.graphemeMotionTargets("word", 0L, 500L, 250L).size)
    }

    @Test
    fun graphemeMotionTargets_applyDesktopNeighbourFalloffAndKeepFutureLettersAtRest() {
        val targets = LyricsMotionPhysics.graphemeMotionTargets("abcd", 0L, 3_000L, 1_875L)

        assertEquals(4, targets.size)
        // c is active, b/a participate with distance falloff, d is future and is explicitly reset.
        assertTrue(targets[2].scale > targets[1].scale)
        assertTrue(targets[1].scale > targets[0].scale)
        assertTrue(targets[2].glow > targets[1].glow)
        assertTrue(targets[1].glow > targets[0].glow)
        assertEquals(0.95f, targets[3].scale, 0.0001f)
        assertEquals(0f, targets[3].glow, 0.0001f)
    }

    @Test
    fun visualClock_deadReckonsPhaseCorrectsAndSnapsRealSeeks() {
        val baseNanos = 1_000_000_000L
        val clock = LyricsVisualClock(initialPositionMs = 1_000L, initialSampleNanos = baseNanos)

        assertEquals(1_000L, clock.frame(baseNanos, baseNanos, isPlaying = true))
        val freeRunNanos = baseNanos + 16_666_667L
        val freeRun = clock.frame(freeRunNanos, freeRunNanos, isPlaying = true)
        assertTrue(freeRun in 1_015L..1_017L)

        clock.updateSample(1_030L, freeRunNanos)
        val correctedNanos = freeRunNanos + 16_666_667L
        val corrected = clock.frame(correctedNanos, correctedNanos, isPlaying = true)
        assertTrue(corrected in 1_033L..1_046L)

        clock.updateSample(5_000L, correctedNanos)
        val seekNanos = correctedNanos + 16_666_667L
        assertEquals(5_016L, clock.frame(seekNanos, seekNanos, isPlaying = true))

        clock.updateSample(4_200L, seekNanos)
        val pausedNanos = seekNanos + 16_666_667L
        assertEquals(4_200L, clock.frame(pausedNanos, pausedNanos, isPlaying = false))
    }

    @Test
    fun graphemeSprings_settleOnPerLetterTargets() {
        val targets = LyricsMotionPhysics.graphemeMotionTargets("held", 0L, 3_000L, 1_500L)
        val springs = LyricsGraphemeSpringState(targets.size)
        springs.setTargets(targets)

        repeat(480) { springs.step(1.0 / 120.0) }

        springs.snapshot().forEachIndexed { index, snapshot ->
            assertEquals(targets[index].scale, snapshot.scale, 0.001f)
            assertEquals(targets[index].yOffsetFraction, snapshot.yOffsetFraction, 0.001f)
            assertEquals(targets[index].glow, snapshot.glow, 0.001f)
        }
    }

    @Test
    fun interludeSpringTarget_usesDesktopStepHideAtFinal500Ms() {
        val visible = LyricsInterludeMotion.springTarget(startMs = 1_000L, durationMs = 6_000L, positionMs = 6_499L)
        val hiding = LyricsInterludeMotion.springTarget(startMs = 1_000L, durationMs = 6_000L, positionMs = 6_500L)

        assertEquals(1f, visible.groupScale, 0.0001f)
        assertEquals(0f, hiding.groupScale, 0.0001f)
    }

    @Test
    fun interludeSprings_followDesktopDotTargets() {
        val target = LyricsInterludeMotion.springTarget(startMs = 1_000L, durationMs = 6_000L, positionMs = 4_000L)
        val springs = LyricsInterludeSpringState()
        springs.setTarget(target)

        repeat(480) { springs.step(1.0 / 120.0) }

        val settled = springs.snapshot()
        assertEquals(target.groupScale, settled.groupScale, 0.001f)
        target.dots.indices.forEach { index ->
            assertEquals(target.dots[index].scale, settled.dots[index].scale, 0.001f)
            assertEquals(target.dots[index].yOffsetFraction, settled.dots[index].yOffsetFraction, 0.001f)
            assertEquals(target.dots[index].glow, settled.dots[index].glow, 0.001f)
            assertEquals(target.dots[index].opacity, settled.dots[index].opacity, 0.001f)
        }
    }
}
