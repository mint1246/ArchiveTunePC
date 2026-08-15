/*
 * Animation curves modelled after SpicyLyrics' LyricsAnimator.ts + Shared.ts.
 * Each keyframe list represents a `[rest, peak, settle]` motion over the unit interval.
 */
package moe.rukamori.archivetune.desktop.lyrics.anim

import moe.rukamori.archivetune.desktop.lyrics.anim.CubicSpline as Spline

data class AnimCurves(
    val wordScale: Spline,
    val wordYOffset: Spline,
    val wordGlow: Spline,
    val letterScale: Spline,
    val letterYOffset: Spline,
) {
    companion object {
        val Standard: AnimCurves = run {
            AnimCurves(
                wordScale = Spline(doubleArrayOf(0.0, 0.7, 1.0), doubleArrayOf(0.95, 1.0505, 1.0)),
                wordYOffset = Spline(doubleArrayOf(0.0, 0.9, 1.0), doubleArrayOf(0.01, -1.0 / 60.0, 0.0)),
                wordGlow = Spline(doubleArrayOf(0.0, 0.15, 0.6, 1.0), doubleArrayOf(0.0, 1.0, 1.0, 0.0)),
                letterScale = Spline(doubleArrayOf(0.0, 0.7, 1.0), doubleArrayOf(0.95, 1.175, 1.0)),
                letterYOffset = Spline(doubleArrayOf(0.0, 0.9, 1.0), doubleArrayOf(0.02, -1.0 / 56.0, 0.0)),
            )
        }
    }
}

/** Spring constants for the rest of the animator (frequency Hz, damping ratio). */
object SpringTuning {
    const val SCALE_FREQ = 0.88
    const val SCALE_DAMPING = 0.64
    const val Y_OFFSET_FREQ = 1.45
    const val Y_OFFSET_DAMPING = 0.40
    const val GLOW_FREQ = 1.18
    const val GLOW_DAMPING = 0.56
}

/** Per-song animator state — springs + cached curve references. */
class WordSprings(scaleGoal: Double, yOffsetGoal: Double, glowGoal: Double) {
    val scale: Spring = Spring(scaleGoal, SpringTuning.SCALE_FREQ, SpringTuning.SCALE_DAMPING)
    val yOffset: Spring = Spring(yOffsetGoal, SpringTuning.Y_OFFSET_FREQ, SpringTuning.Y_OFFSET_DAMPING)
    val glow: Spring = Spring(glowGoal, SpringTuning.GLOW_FREQ, SpringTuning.GLOW_DAMPING)
}

class LetterSprings(scaleGoal: Double, yOffsetGoal: Double, glowGoal: Double) {
    val scale: Spring = Spring(scaleGoal, SpringTuning.SCALE_FREQ, SpringTuning.SCALE_DAMPING)
    val yOffset: Spring = Spring(yOffsetGoal, SpringTuning.Y_OFFSET_FREQ, SpringTuning.Y_OFFSET_DAMPING)
    val glow: Spring = Spring(glowGoal, SpringTuning.GLOW_FREQ, SpringTuning.GLOW_DAMPING)
}
