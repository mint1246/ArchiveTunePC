/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

internal data class LyricsInterludeDotState(
    val scale: Float,
    val yOffsetFraction: Float,
    val glow: Float,
    val opacity: Float,
)

internal data class LyricsInterludeFrame(
    val groupScale: Float,
    val dots: List<LyricsInterludeDotState>,
)

/** Persistent spring state matching the desktop three-dot interlude animator. */
internal class LyricsInterludeSpringState {
    private class DotSprings {
        val scale = LyricsSpring(0.75, frequency = 0.7, dampingRatio = 0.6)
        val yOffset = LyricsSpring(0.0, frequency = 1.25, dampingRatio = 0.4)
        val glow = LyricsSpring(0.0, frequency = 1.0, dampingRatio = 0.5)
        val opacity = LyricsSpring(0.35, frequency = 1.0, dampingRatio = 0.5)
    }

    private val groupScale = LyricsSpring(1.0, frequency = 5.0, dampingRatio = 0.7)
    private val dots = List(3) { DotSprings() }

    fun setTarget(frame: LyricsInterludeFrame, snap: Boolean = false) {
        groupScale.setGoal(frame.groupScale.toDouble(), snap)
        dots.forEachIndexed { index, springs ->
            val target = frame.dots[index]
            springs.scale.setGoal(target.scale.toDouble(), snap)
            springs.yOffset.setGoal(target.yOffsetFraction.toDouble(), snap)
            springs.glow.setGoal(target.glow.toDouble(), snap)
            springs.opacity.setGoal(target.opacity.toDouble(), snap)
        }
    }

    fun step(dt: Double) {
        stepIfAwake(groupScale, dt)
        dots.forEach { springs ->
            stepIfAwake(springs.scale, dt)
            stepIfAwake(springs.yOffset, dt)
            stepIfAwake(springs.glow, dt)
            stepIfAwake(springs.opacity, dt)
        }
    }

    fun snapshot(): LyricsInterludeFrame =
        LyricsInterludeFrame(
            groupScale = groupScale.snapshotPosition().toFloat(),
            dots =
                dots.map { springs ->
                    LyricsInterludeDotState(
                        scale = springs.scale.snapshotPosition().toFloat(),
                        yOffsetFraction = springs.yOffset.snapshotPosition().toFloat(),
                        glow = springs.glow.snapshotPosition().toFloat(),
                        opacity = springs.opacity.snapshotPosition().toFloat(),
                    )
                },
        )

    private fun stepIfAwake(spring: LyricsSpring, dt: Double) {
        if (!spring.canSleep()) spring.step(dt)
    }
}

/** Timing/curve model shared with the desktop SpicyLyrics-style three-dot interlude. */
internal object LyricsInterludeMotion {
    private const val PREHIDE_MS = 500L
    private const val TAIL_PADDING_MS = 550L

    private val scaleCurve = NaturalCubicSpline(
        doubleArrayOf(0.0, 0.7, 1.0),
        doubleArrayOf(0.75, 1.05, 1.0),
    )
    private val yOffsetCurve = NaturalCubicSpline(
        doubleArrayOf(0.0, 0.9, 1.0),
        doubleArrayOf(0.0, -0.12, 0.0),
    )
    private val glowCurve = NaturalCubicSpline(
        doubleArrayOf(0.0, 0.6, 1.0),
        doubleArrayOf(0.0, 1.0, 1.0),
    )
    private val opacityCurve = NaturalCubicSpline(
        doubleArrayOf(0.0, 0.6, 1.0),
        doubleArrayOf(0.35, 1.0, 1.0),
    )

    fun frame(
        startMs: Long,
        durationMs: Long,
        positionMs: Long,
    ): LyricsInterludeFrame {
        val endMs = startMs + durationMs.coerceAtLeast(0L)
        val effectiveEndMs = (endMs - TAIL_PADDING_MS).coerceAtLeast(startMs + 3L)
        val activeDurationMs = (effectiveEndMs - startMs).coerceAtLeast(3L)
        val groupScale =
            when {
                positionMs < endMs - PREHIDE_MS -> 1f
                positionMs >= endMs -> 0f
                else -> ((endMs - positionMs).toFloat() / PREHIDE_MS).coerceIn(0f, 1f)
            }

        val dots =
            List(3) { index ->
                val dotStart = startMs + activeDurationMs * index / 3L
                val dotEnd = (startMs + activeDurationMs * (index + 1L) / 3L).coerceAtLeast(dotStart + 1L)
                val progress =
                    when {
                        positionMs <= dotStart -> 0.0
                        positionMs >= dotEnd -> 1.0
                        else -> (positionMs - dotStart).toDouble() / (dotEnd - dotStart).toDouble()
                    }
                LyricsInterludeDotState(
                    scale = scaleCurve.at(progress).toFloat(),
                    yOffsetFraction = yOffsetCurve.at(progress).toFloat(),
                    glow = glowCurve.at(progress).toFloat(),
                    opacity = opacityCurve.at(progress).toFloat(),
                )
            }
        return LyricsInterludeFrame(groupScale = groupScale, dots = dots)
    }

    /** Target frame for the desktop spring animator; group hiding is a step goal at -500 ms. */
    fun springTarget(
        startMs: Long,
        durationMs: Long,
        positionMs: Long,
    ): LyricsInterludeFrame {
        val frame = frame(startMs, durationMs, positionMs)
        val endMs = startMs + durationMs.coerceAtLeast(0L)
        return frame.copy(groupScale = if (positionMs >= endMs - PREHIDE_MS) 0f else 1f)
    }
}
