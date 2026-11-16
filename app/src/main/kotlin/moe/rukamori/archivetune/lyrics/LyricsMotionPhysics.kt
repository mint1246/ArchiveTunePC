/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val SPRING_SLEEP_OFFSET_SQ_LIMIT = (1.0 / 3840.0) * (1.0 / 3840.0)
private const val SPRING_SLEEP_VELOCITY_SQ_LIMIT = 1e-2 * 1e-2
private const val SPRING_EPS = 1e-5
private const val TWO_PI = 2.0 * PI

/** Desktop SpicyLyrics damped harmonic oscillator, kept platform-neutral for Android motion. */
internal class LyricsSpring(
    startPosition: Double,
    frequency: Double,
    dampingRatio: Double,
    goal: Double? = null,
) {
    private var damping = dampingRatio
    private var frequencyHz = frequency
    var goalValue: Double = goal ?: startPosition
        private set
    private var position = startPosition
    private var velocity = 0.0

    fun step(dt: Double): Double {
        val d = damping
        val f = frequencyHz * TWO_PI
        val g = goalValue
        var p = position
        var v = velocity

        if (d == 1.0) {
            val q = exp(-f * dt)
            val w = dt * q
            val c0 = q + w * f
            val c2 = q - w * f
            val c3 = w * f * f
            val offset = p - g
            p = offset * c0 + v * w + g
            v = v * c2 - offset * c3
        } else if (d < 1.0) {
            val q = exp(-d * f * dt)
            val c = sqrt(1.0 - d * d)
            val i = cos(dt * f * c)
            val j = sin(dt * f * c)
            val z =
                if (c > SPRING_EPS) {
                    j / c
                } else {
                    val a = dt * f
                    a + ((a * a) * (c * c) * (c * c) / 20.0 - c * c) * (a * a * a) / 6.0
                }
            val y =
                if (f * c > SPRING_EPS) {
                    j / (f * c)
                } else {
                    val b = f * c
                    dt + ((dt * dt) * (b * b) * (b * b) / 20.0 - b * b) * (dt * dt * dt) / 6.0
                }
            val offset = p - g
            p = (offset * (i + z * d) + v * y) * q + g
            v = (v * (i - z * d) - offset * (z * f)) * q
        } else {
            val c = sqrt(d * d - 1.0)
            val r1 = -f * (d + c)
            val r2 = -f * (d - c)
            val ec1 = exp(r1 * dt)
            val ec2 = exp(r2 * dt)
            val offset = p - g
            val co2 = (v - offset * r1) / (2.0 * f * c)
            val co1 = ec1 * (offset - co2)
            p = co1 + co2 * ec2 + g
            v = co1 * r1 + co2 * ec2 * r2
        }

        position = p
        velocity = v
        return p
    }

    fun canSleep(): Boolean {
        if (velocity * velocity > SPRING_SLEEP_VELOCITY_SQ_LIMIT) return false
        val offset = position - goalValue
        return offset * offset <= SPRING_SLEEP_OFFSET_SQ_LIMIT
    }

    fun setGoal(goal: Double, snap: Boolean = false) {
        goalValue = goal
        if (snap) {
            position = goal
            velocity = 0.0
        }
    }

    /** Reconcile an externally measured position without discarding spring momentum. */
    fun shiftPosition(delta: Double) {
        position += delta
    }

    fun snapshotPosition(): Double = position
}

/** Natural cubic spline matching desktop's `cubic-spline` interpolation. */
internal class NaturalCubicSpline(
    times: DoubleArray,
    values: DoubleArray,
) {
    private val xs = times.copyOf()
    private val ys = values.copyOf()
    private val slopes: DoubleArray

    init {
        require(xs.size == ys.size) { "times and values mismatch" }
        require(xs.size >= 2) { "at least two spline points are required" }
        for (index in 1 until xs.size) {
            require(xs[index] > xs[index - 1]) { "times must be strictly ascending" }
        }
        slopes = solveNaturalSlopes()
    }

    fun at(progress: Double): Double {
        val x = progress.coerceIn(xs.first(), xs.last())
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()

        var low = 0
        var high = xs.lastIndex
        while (high - low > 1) {
            val middle = (low + high) ushr 1
            if (xs[middle] <= x) low = middle else high = middle
        }

        val width = xs[high] - xs[low]
        val t = (x - xs[low]) / width
        val delta = ys[high] - ys[low]
        val a = slopes[low] * width - delta
        val b = -slopes[high] * width + delta
        return (1.0 - t) * ys[low] + t * ys[high] +
            t * (1.0 - t) * (a * (1.0 - t) + b * t)
    }

    private fun solveNaturalSlopes(): DoubleArray {
        val last = xs.lastIndex
        val matrix = Array(last + 1) { DoubleArray(last + 2) }
        for (index in 1 until last) {
            val left = xs[index] - xs[index - 1]
            val right = xs[index + 1] - xs[index]
            matrix[index][index - 1] = 1.0 / left
            matrix[index][index] = 2.0 * (1.0 / left + 1.0 / right)
            matrix[index][index + 1] = 1.0 / right
            matrix[index][last + 1] =
                3.0 * (
                    (ys[index] - ys[index - 1]) / (left * left) +
                        (ys[index + 1] - ys[index]) / (right * right)
                    )
        }

        val firstWidth = xs[1] - xs[0]
        matrix[0][0] = 2.0 / firstWidth
        matrix[0][1] = 1.0 / firstWidth
        matrix[0][last + 1] = 3.0 * (ys[1] - ys[0]) / (firstWidth * firstWidth)

        val lastWidth = xs[last] - xs[last - 1]
        matrix[last][last - 1] = 1.0 / lastWidth
        matrix[last][last] = 2.0 / lastWidth
        matrix[last][last + 1] = 3.0 * (ys[last] - ys[last - 1]) / (lastWidth * lastWidth)
        return solve(matrix)
    }

    private fun solve(matrix: Array<DoubleArray>): DoubleArray {
        val size = matrix.size
        var row = 0
        var column = 0
        while (row < size && column <= size) {
            var pivot = row
            var maximum = Double.NEGATIVE_INFINITY
            for (candidate in row until size) {
                val value = abs(matrix[candidate][column])
                if (value > maximum) {
                    pivot = candidate
                    maximum = value
                }
            }
            if (matrix[pivot][column] == 0.0) {
                column++
            } else {
                val temporary = matrix[row]
                matrix[row] = matrix[pivot]
                matrix[pivot] = temporary
                for (candidate in row + 1 until size) {
                    val factor = matrix[candidate][column] / matrix[row][column]
                    matrix[candidate][column] = 0.0
                    for (cell in column + 1..size) {
                        matrix[candidate][cell] -= matrix[row][cell] * factor
                    }
                }
                row++
                column++
            }
        }

        val result = DoubleArray(size)
        for (index in size - 1 downTo 0) {
            val value = if (matrix[index][index] != 0.0) matrix[index][size] / matrix[index][index] else 0.0
            result[index] = value
            for (previous in index - 1 downTo 0) {
                matrix[previous][size] -= matrix[previous][index] * value
                matrix[previous][index] = 0.0
            }
        }
        return result
    }
}

internal data class LyricsGraphemeTiming(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val startMs: Long,
    val endMs: Long,
)

internal data class LyricsInlineMotionTarget(
    val scale: Float,
    val yOffsetFraction: Float,
    val glow: Float,
    val fillProgress: Float,
)

internal data class LyricsInlineSpringSnapshot(
    val scale: Float,
    val yOffsetFraction: Float,
    val glow: Float,
)

internal data class LyricsLetterEmphasisProfile(
    val scaleExcursionMultiplier: Float,
    val falloffExponent: Double,
    val yMultiplier: Float,
    val glowMultiplier: Float,
)

internal data class LyricsGraphemeMotionTarget(
    val scale: Float,
    val yOffsetFraction: Float,
    val glow: Float,
)

internal data class LyricsGraphemeSpringSnapshot(
    val scale: Float,
    val yOffsetFraction: Float,
    val glow: Float,
)

internal class LyricsInlineSpringState {
    private val scale = LyricsSpring(0.95, 0.88, 0.64)
    private val yOffset = LyricsSpring(1.0 / 100.0, 1.45, 0.40)
    private val glow = LyricsSpring(0.0, 1.18, 0.56)

    fun setTargets(target: LyricsInlineMotionTarget, snap: Boolean = false) {
        scale.setGoal(target.scale.toDouble(), snap)
        yOffset.setGoal(target.yOffsetFraction.toDouble(), snap)
        glow.setGoal(target.glow.toDouble(), snap)
    }

    fun step(dt: Double) {
        if (!scale.canSleep()) scale.step(dt)
        if (!yOffset.canSleep()) yOffset.step(dt)
        if (!glow.canSleep()) glow.step(dt)
    }

    fun snapshot(): LyricsInlineSpringSnapshot =
        LyricsInlineSpringSnapshot(
            scale = scale.snapshotPosition().toFloat(),
            yOffsetFraction = yOffset.snapshotPosition().toFloat(),
            glow = glow.snapshotPosition().toFloat(),
        )
}

/** Per-grapheme springs use the exact desktop SpicyLyrics frequencies/damping. */
internal class LyricsGraphemeSpringState(
    graphemeCount: Int,
) {
    private class Springs {
        val scale = LyricsSpring(0.95, 0.88, 0.64)
        val yOffset = LyricsSpring(1.0 / 100.0, 1.45, 0.40)
        val glow = LyricsSpring(0.0, 1.18, 0.56)
    }

    private val springs = List(graphemeCount.coerceAtLeast(0)) { Springs() }

    fun setTargets(targets: List<LyricsGraphemeMotionTarget>, snap: Boolean = false) {
        require(targets.size == springs.size) { "grapheme target count changed" }
        springs.forEachIndexed { index, state ->
            val target = targets[index]
            state.scale.setGoal(target.scale.toDouble(), snap)
            state.yOffset.setGoal(target.yOffsetFraction.toDouble(), snap)
            state.glow.setGoal(target.glow.toDouble(), snap)
        }
    }

    fun step(dt: Double) {
        springs.forEach { state ->
            if (!state.scale.canSleep()) state.scale.step(dt)
            if (!state.yOffset.canSleep()) state.yOffset.step(dt)
            if (!state.glow.canSleep()) state.glow.step(dt)
        }
    }

    fun snapshot(): List<LyricsGraphemeSpringSnapshot> =
        springs.map { state ->
            LyricsGraphemeSpringSnapshot(
                scale = state.scale.snapshotPosition().toFloat(),
                yOffsetFraction = state.yOffset.snapshotPosition().toFloat(),
                glow = state.glow.snapshotPosition().toFloat(),
            )
        }
}

/**
 * Desktop's visual playback clock: dead-reckon between backend samples and gently
 * phase-lock sample jitter, snapping only for a real seek/discontinuity.
 */
internal class LyricsVisualClock(
    initialPositionMs: Long = 0L,
    initialSampleNanos: Long = 0L,
) {
    private var samplePositionMs = initialPositionMs
    private var sampleNanos = initialSampleNanos
    private var smoothPositionMs = initialPositionMs.toDouble()
    private var haveSmoothClock = false
    private var lastFrameNanos = 0L

    fun updateSample(positionMs: Long, nowNanos: Long) {
        samplePositionMs = positionMs
        sampleNanos = nowNanos
    }

    fun frame(
        frameNanos: Long,
        nowNanos: Long,
        isPlaying: Boolean,
    ): Long {
        val dt =
            if (lastFrameNanos == 0L) {
                0.0167
            } else {
                ((frameNanos - lastFrameNanos) / 1e9).coerceIn(0.0, 0.1)
            }
        lastFrameNanos = frameNanos

        val elapsedSinceSampleMs = ((nowNanos - sampleNanos) / 1_000_000.0).coerceIn(0.0, 750.0)
        val predictedPosition = samplePositionMs.toDouble() + if (isPlaying) elapsedSinceSampleMs else 0.0

        if (!haveSmoothClock) {
            smoothPositionMs = predictedPosition
            haveSmoothClock = true
        } else if (isPlaying) {
            val freeRun = smoothPositionMs + dt * 1000.0
            val error = predictedPosition - freeRun
            smoothPositionMs =
                if (abs(error) > 220.0) {
                    predictedPosition
                } else {
                    val correction = 1.0 - exp(-dt * 10.0)
                    freeRun + error * correction
                }
        } else {
            smoothPositionMs = predictedPosition
        }
        return smoothPositionMs.toLong().coerceAtLeast(0L)
    }
}

/**
 * Pixel-space LazyList counterpart to desktop's scene anchor spring.
 *
 * The measured list error can change as variable-height rows enter the viewport. Reconcile
 * that measured error into the spring while preserving velocity, then return only the scroll
 * delta for this frame. Frequency/damping exactly match desktop's anchor Spring(1.0, .9).
 */
internal class LyricsScrollAnchorSpring(initialErrorPx: Double) {
    private val spring = LyricsSpring(initialErrorPx, frequency = 1.0, dampingRatio = 0.9, goal = 0.0)

    fun scrollDeltaForFrame(
        measuredErrorPx: Double,
        dtSeconds: Double,
    ): Double {
        spring.shiftPosition(measuredErrorPx - spring.snapshotPosition())
        val before = spring.snapshotPosition()
        val after = spring.step(dtSeconds.coerceIn(0.0, 0.1))
        return before - after
    }

    fun canSleep(): Boolean = spring.canSleep()
}

internal object LyricsMotionPhysics {
    const val LETTER_ANIMATION_MIN_MS = 500L
    private const val FAR_WORD_SNAP_MS = 500L
    private const val SCROLL_PIN_LOOKAHEAD = 2
    private const val SUNG_LETTER_GLOW_PROGRESS = 0.2f

    private val wordScaleSpline =
        NaturalCubicSpline(
            doubleArrayOf(0.0, 0.7, 1.0),
            doubleArrayOf(0.95, 1.0505, 1.0),
        )
    private val wordYOffsetSpline =
        NaturalCubicSpline(
            doubleArrayOf(0.0, 0.9, 1.0),
            doubleArrayOf(1.0 / 100.0, -(1.0 / 60.0), 0.0),
        )
    private val glowSpline =
        NaturalCubicSpline(
            doubleArrayOf(0.0, 0.15, 0.6, 1.0),
            doubleArrayOf(0.0, 1.0, 1.0, 0.0),
        )
    private val letterScaleSpline =
        NaturalCubicSpline(
            doubleArrayOf(0.0, 0.7, 1.0),
            doubleArrayOf(0.95, 1.175, 1.0),
        )
    private val letterYOffsetSpline =
        NaturalCubicSpline(
            doubleArrayOf(0.0, 0.9, 1.0),
            doubleArrayOf(1.0 / 100.0, -(1.0 / 56.0), 0.0),
        )

    fun inlineTarget(
        text: String,
        startMs: Long,
        endMs: Long,
        positionMs: Long,
    ): LyricsInlineMotionTarget {
        val safeEnd = endMs.coerceAtLeast(startMs + 1L)
        val progress = progress(positionMs, startMs, safeEnd)
        val curveProgress =
            when {
                positionMs < startMs -> 0f
                positionMs >= safeEnd -> 1f
                else -> progress
            }
        return LyricsInlineMotionTarget(
            scale = wordScaleSpline.at(curveProgress.toDouble()).toFloat(),
            yOffsetFraction = wordYOffsetSpline.at(curveProgress.toDouble()).toFloat(),
            glow = glowSpline.at(curveProgress.toDouble()).toFloat(),
            fillProgress =
                if (safeEnd - startMs >= LETTER_ANIMATION_MIN_MS) {
                    graphemeFillProgress(text, startMs, safeEnd, positionMs)
                } else {
                    progress
                },
        )
    }

    fun shouldAdvanceSpring(positionMs: Long, startMs: Long, endMs: Long): Boolean =
        positionMs >= startMs - FAR_WORD_SNAP_MS && positionMs <= endMs + FAR_WORD_SNAP_MS

    fun letterEmphasisProfile(durationMs: Long): LyricsLetterEmphasisProfile {
        val raw = ((durationMs - LETTER_ANIMATION_MIN_MS).toFloat() / 2_500f).coerceIn(0f, 1f)
        val intensity = raw * raw * (3f - 2f * raw)
        return LyricsLetterEmphasisProfile(
            scaleExcursionMultiplier = 1f + 0.70f * intensity,
            falloffExponent = 2.8 - 0.5 * intensity,
            yMultiplier = 1f + 0.35f * intensity,
            glowMultiplier = 1f + 0.25f * intensity,
        )
    }

    fun emphasizedLetterScale(
        baseScale: Float,
        profile: LyricsLetterEmphasisProfile,
    ): Float =
        if (baseScale <= 1f) {
            baseScale
        } else {
            (1f + (baseScale - 1f) * profile.scaleExcursionMultiplier).coerceAtMost(1.30f)
        }

    fun graphemeMotionTargets(
        text: String,
        startMs: Long,
        endMs: Long,
        positionMs: Long,
    ): List<LyricsGraphemeMotionTarget> {
        val safeEnd = endMs.coerceAtLeast(startMs + 1L)
        if (safeEnd - startMs < LETTER_ANIMATION_MIN_MS) return emptyList()
        val graphemes = graphemeTimings(text, startMs, safeEnd)
        if (graphemes.isEmpty()) return emptyList()

        val profile = letterEmphasisProfile(safeEnd - startMs)
        val wordState = motionState(positionMs, startMs, safeEnd)
        val activeIndex =
            if (wordState == MotionState.ACTIVE) {
                graphemes.indexOfFirst { positionMs >= it.startMs && positionMs < it.endMs }
            } else {
                -1
            }
        val activeProgress =
            if (activeIndex >= 0) {
                val active = graphemes[activeIndex]
                progress(positionMs, active.startMs, active.endMs)
            } else {
                0f
            }

        val activeScale = emphasizedLetterScale(letterScaleSpline.at(activeProgress.toDouble()).toFloat(), profile)
        val activeY = letterYOffsetSpline.at(activeProgress.toDouble()).toFloat() * profile.yMultiplier
        val activeGlow = (glowSpline.at(activeProgress.toDouble()).toFloat() * profile.glowMultiplier).coerceAtMost(1.25f)
        val restScale = letterScaleSpline.at(0.0).toFloat()
        val restY = letterYOffsetSpline.at(0.0).toFloat()
        val restGlow = glowSpline.at(0.0).toFloat()
        val sungScale = letterScaleSpline.at(1.0).toFloat()
        val sungY = letterYOffsetSpline.at(1.0).toFloat()
        val sungGlow = glowSpline.at(1.0).toFloat()
        val noActiveSungGlow = glowSpline.at(SUNG_LETTER_GLOW_PROGRESS.toDouble()).toFloat()

        return graphemes.mapIndexed { index, grapheme ->
            val letterState = motionState(positionMs, grapheme.startMs, grapheme.endMs)
            var targetScale = restScale
            var targetY = restY
            var targetGlow = restGlow

            when (wordState) {
                MotionState.NOT_SUNG -> Unit
                MotionState.SUNG -> {
                    targetScale = sungScale
                    targetY = sungY
                    targetGlow = sungGlow
                }

                MotionState.ACTIVE -> {
                    if (activeIndex >= 0) {
                        val distance = abs(index - activeIndex)
                        val falloff = (1f / (1f + distance.toDouble().pow(profile.falloffExponent))).toFloat()
                        val glowFalloff = 1f / (1f + distance * 0.9f)
                        targetScale = restScale + (activeScale - restScale) * falloff
                        targetY = restY + (activeY - restY) * falloff
                        targetGlow = restGlow + (activeGlow - restGlow) * glowFalloff
                    }
                    if (letterState == MotionState.NOT_SUNG) {
                        targetScale = restScale
                        targetY = restY
                        targetGlow = restGlow
                    } else if (letterState == MotionState.SUNG && activeIndex < 0) {
                        targetGlow = noActiveSungGlow
                    }
                }
            }

            LyricsGraphemeMotionTarget(
                scale = targetScale,
                yOffsetFraction = targetY,
                glow = targetGlow,
            )
        }
    }

    fun lineAlpha(distance: Int): Float =
        (1f / (1f + distance.coerceAtLeast(0) * 0.8f)).coerceIn(0.25f, 1f)

    fun lineScale(distance: Int): Float =
        (1.05f - distance.coerceAtLeast(0) * 0.06f).coerceIn(0.97f, 1.05f)

    fun lineBlurDp(distance: Int): Float =
        (1.15f * distance.coerceAtLeast(0)).coerceIn(0f, 3.5f)

    /** First-strong bidi direction, using code points so supplementary characters are not split. */
    fun firstStrongRtl(text: String): Boolean? {
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
                -> return true

                Character.DIRECTIONALITY_LEFT_TO_RIGHT,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
                -> return false
            }
            offset += Character.charCount(codePoint)
        }
        return null
    }

    /** Desktop's pinned anchor, expressed in vocal-entry order so interludes do not skew lookahead. */
    fun pinnedScrollAnchor(
        previousAnchorIndex: Int,
        activeLineIndex: Int,
        vocalEntryIndices: List<Int>,
        forceReset: Boolean = false,
    ): Int {
        if (previousAnchorIndex < 0 || forceReset) return activeLineIndex
        val previousOrdinal = vocalEntryIndices.indexOf(previousAnchorIndex)
        val activeOrdinal = vocalEntryIndices.indexOf(activeLineIndex)
        if (previousOrdinal < 0 || activeOrdinal < 0) return activeLineIndex
        if (activeOrdinal < previousOrdinal) return activeLineIndex
        return if (activeOrdinal - previousOrdinal >= SCROLL_PIN_LOOKAHEAD) activeLineIndex else previousAnchorIndex
    }

    fun graphemeTimings(
        text: String,
        startMs: Long,
        endMs: Long,
    ): List<LyricsGraphemeTiming> {
        val ranges = graphemeRanges(text)
        if (ranges.isEmpty()) return emptyList()
        val duration = (endMs - startMs).coerceAtLeast(1L)
        return ranges.mapIndexed { index, range ->
            LyricsGraphemeTiming(
                text = text.substring(range.first, range.last + 1),
                startOffset = range.first,
                endOffset = range.last + 1,
                startMs = startMs + duration * index / ranges.size,
                endMs = startMs + duration * (index + 1) / ranges.size,
            )
        }
    }

    fun graphemeFillProgress(
        text: String,
        startMs: Long,
        endMs: Long,
        positionMs: Long,
    ): Float {
        if (positionMs <= startMs) return 0f
        if (positionMs >= endMs) return 1f
        val graphemes = graphemeTimings(text, startMs, endMs)
        if (graphemes.isEmpty()) return progress(positionMs, startMs, endMs)
        val activeIndex = graphemes.indexOfFirst { positionMs >= it.startMs && positionMs < it.endMs }
        if (activeIndex < 0) {
            val completed = graphemes.count { positionMs >= it.endMs }
            return completed.toFloat() / graphemes.size
        }
        val active = graphemes[activeIndex]
        val local = progress(positionMs, active.startMs, active.endMs)
        val eased = sin(local * (PI / 2.0)).toFloat()
        return ((activeIndex + eased) / graphemes.size).coerceIn(0f, 1f)
    }

    private fun progress(positionMs: Long, startMs: Long, endMs: Long): Float =
        ((positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)

    private enum class MotionState {
        NOT_SUNG,
        ACTIVE,
        SUNG,
    }

    private fun motionState(positionMs: Long, startMs: Long, endMs: Long): MotionState =
        when {
            positionMs < startMs -> MotionState.NOT_SUNG
            positionMs >= endMs -> MotionState.SUNG
            else -> MotionState.ACTIVE
        }

    /**
     * Extended-grapheme segmentation for lyric motion. This keeps combining marks,
     * variation selectors, emoji modifiers and ZWJ sequences attached to their base.
     * Offsets remain UTF-16 indices, matching Compose/Android text APIs.
     */
    internal fun graphemeRanges(text: String): List<IntRange> = lyricsGraphemeRanges(text)
}
