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
}

/** Natural cubic spline matching the desktop `cubic-spline` interpolation. */
private class NaturalCubicSpline(
    times: DoubleArray,
    values: DoubleArray,
) {
    private val xs = times.copyOf()
    private val ys = values.copyOf()
    private val slopes: DoubleArray

    init {
        require(xs.size == ys.size && xs.size >= 2)
        for (index in 1 until xs.size) require(xs[index] > xs[index - 1])
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
        for (column in 0 until size) {
            var pivot = column
            for (row in column + 1 until size) {
                if (kotlin.math.abs(matrix[row][column]) > kotlin.math.abs(matrix[pivot][column])) pivot = row
            }
            if (pivot != column) {
                val temporary = matrix[column]
                matrix[column] = matrix[pivot]
                matrix[pivot] = temporary
            }
            val divisor = matrix[column][column]
            require(kotlin.math.abs(divisor) > 1e-12)
            for (cell in column until size + 1) matrix[column][cell] /= divisor
            for (row in 0 until size) {
                if (row == column) continue
                val factor = matrix[row][column]
                if (factor == 0.0) continue
                for (cell in column until size + 1) matrix[row][cell] -= factor * matrix[column][cell]
            }
        }
        return DoubleArray(size) { index -> matrix[index][size] }
    }
}
