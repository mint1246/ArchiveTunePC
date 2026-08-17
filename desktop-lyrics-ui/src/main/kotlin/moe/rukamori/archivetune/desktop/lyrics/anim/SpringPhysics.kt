/*
 * Spring physics used by the lyrics animator.
 * Port of https://github.com/Fraktality/spr/blob/master/spr.lua (MIT).
 * Critically/under/over-damped solutions for the damped harmonic oscillator.
 */
package moe.rukamori.archivetune.desktop.lyrics.anim

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

private const val SLEEP_OFFSET_SQ_LIMIT = (1.0 / 3840.0) * (1.0 / 3840.0)
private const val SLEEP_VELOCITY_SQ_LIMIT = 1e-2 * 1e-2
private const val EPS = 1e-5
private const val TWO_PI = 2.0 * Math.PI

class Spring(
    startPosition: Double,
    frequency: Double,
    dampingRatio: Double,
    goal: Double? = null,
) {
    private var damping: Double = dampingRatio
    private var frequencyHz: Double = frequency
    var goalValue: Double = goal ?: startPosition
        private set
    private var position: Double = startPosition
    private var velocity: Double = 0.0

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
            val o = p - g
            p = o * c0 + v * w + g
            v = v * c2 - o * c3
        } else if (d < 1.0) {
            val q = exp(-d * f * dt)
            val c = sqrt(1.0 - d * d)
            val i = cos(dt * f * c)
            val j = sin(dt * f * c)
            val z: Double = if (c > EPS) j / c else {
                val a = dt * f
                a + ((a * a) * (c * c) * (c * c) / 20.0 - c * c) * (a * a * a) / 6.0
            }
            val y: Double = if (f * c > EPS) j / (f * c) else {
                val b = f * c
                dt + ((dt * dt) * (b * b) * (b * b) / 20.0 - b * b) * (dt * dt * dt) / 6.0
            }
            val o = p - g
            p = (o * (i + z * d) + v * y) * q + g
            v = (v * (i - z * d) - o * (z * f)) * q
        } else {
            val c = sqrt(d * d - 1.0)
            val r1 = -f * (d + c)
            val r2 = -f * (d - c)
            val ec1 = exp(r1 * dt)
            val ec2 = exp(r2 * dt)
            val o = p - g
            val co2 = (v - o * r1) / (2.0 * f * c)
            val co1 = ec1 * (o - co2)
            p = co1 + co2 * ec2 + g
            v = co1 * r1 + co2 * ec2 * r2
        }

        position = p
        velocity = v
        return p
    }

    fun canSleep(): Boolean {
        if (velocity * velocity > SLEEP_VELOCITY_SQ_LIMIT) return false
        val offset = position - goalValue
        return offset * offset <= SLEEP_OFFSET_SQ_LIMIT
    }

    fun setGoal(goal: Double, snap: Boolean = false) {
        goalValue = goal
        if (snap) {
            position = goal
            velocity = 0.0
        }
    }

    fun setDampingRatio(value: Double) { damping = value }
    fun setFrequency(value: Double) { frequencyHz = value }

    fun snapshotPosition(): Double = position
    fun snapshotVelocity(): Double = velocity
}

/**
 * Natural cubic-spline interpolation matching the `cubic-spline` package used by
 * SpicyLyrics. The previous implementation was only a local smoothstep-like curve,
 * which changed the timing/overshoot of every inline lyric effect.
 */
class CubicSpline(times: DoubleArray, values: DoubleArray) {
    private val xs = times.copyOf()
    private val ys = values.copyOf()
    private val ks: DoubleArray

    init {
        require(xs.size == ys.size) { "times and values mismatch" }
        require(xs.size >= 2) { "at least two spline points are required" }
        for (i in 1 until xs.size) {
            require(xs[i] > xs[i - 1]) { "times must be strictly ascending" }
        }
        ks = naturalSlopes()
    }

    fun at(progress: Double): Double {
        val x = progress.coerceIn(xs.first(), xs.last())
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()

        var lo = 0
        var hi = xs.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= x) lo = mid else hi = mid
        }

        val width = xs[hi] - xs[lo]
        val t = (x - xs[lo]) / width
        val delta = ys[hi] - ys[lo]
        val a = ks[lo] * width - delta
        val b = -ks[hi] * width + delta
        return (1.0 - t) * ys[lo] + t * ys[hi] +
            t * (1.0 - t) * (a * (1.0 - t) + b * t)
    }

    /** Port of cubic-spline's natural-knot solve. */
    private fun naturalSlopes(): DoubleArray {
        val n = xs.lastIndex
        val matrix = Array(n + 1) { DoubleArray(n + 2) }

        for (i in 1 until n) {
            val left = xs[i] - xs[i - 1]
            val right = xs[i + 1] - xs[i]
            matrix[i][i - 1] = 1.0 / left
            matrix[i][i] = 2.0 * (1.0 / left + 1.0 / right)
            matrix[i][i + 1] = 1.0 / right
            matrix[i][n + 1] = 3.0 * (
                (ys[i] - ys[i - 1]) / (left * left) +
                    (ys[i + 1] - ys[i]) / (right * right)
                )
        }

        val firstWidth = xs[1] - xs[0]
        matrix[0][0] = 2.0 / firstWidth
        matrix[0][1] = 1.0 / firstWidth
        matrix[0][n + 1] = 3.0 * (ys[1] - ys[0]) / (firstWidth * firstWidth)

        val lastWidth = xs[n] - xs[n - 1]
        matrix[n][n - 1] = 1.0 / lastWidth
        matrix[n][n] = 2.0 / lastWidth
        matrix[n][n + 1] = 3.0 * (ys[n] - ys[n - 1]) / (lastWidth * lastWidth)

        return solve(matrix)
    }

    private fun solve(matrix: Array<DoubleArray>): DoubleArray {
        val m = matrix.size
        val out = DoubleArray(m)
        var h = 0
        var k = 0
        while (h < m && k <= m) {
            var pivot = h
            var max = Double.NEGATIVE_INFINITY
            for (i in h until m) {
                val v = kotlin.math.abs(matrix[i][k])
                if (v > max) {
                    pivot = i
                    max = v
                }
            }
            if (matrix[pivot][k] == 0.0) {
                k++
            } else {
                val tmp = matrix[h]
                matrix[h] = matrix[pivot]
                matrix[pivot] = tmp
                for (i in h + 1 until m) {
                    val factor = matrix[i][k] / matrix[h][k]
                    matrix[i][k] = 0.0
                    for (j in k + 1..m) matrix[i][j] -= matrix[h][j] * factor
                }
                h++
                k++
            }
        }

        for (i in m - 1 downTo 0) {
            val v = if (matrix[i][i] != 0.0) matrix[i][m] / matrix[i][i] else 0.0
            out[i] = v
            for (j in i - 1 downTo 0) {
                matrix[j][m] -= matrix[j][i] * v
                matrix[j][i] = 0.0
            }
        }
        return out
    }
}
