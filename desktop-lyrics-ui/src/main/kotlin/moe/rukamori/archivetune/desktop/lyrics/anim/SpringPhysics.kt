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
 * Cubic-spline interpolation over (time, value) keyframes, used to map a normalised
 * lyric progress onto a non-linear animation curve (e.g. 0 → 1.05 → 1 along the
 * word timeline). Knot times must be strictly ascending and lie in `[0, 1]`.
 */
class CubicSpline(times: DoubleArray, values: DoubleArray) {
    init { require(times.size == values.size) { "times and values mismatch" } }
    private val xs: DoubleArray = times
    private val ys: DoubleArray = values

    fun at(progress: Double): Double {
        val n = xs.size
        if (n == 0) return 0.0
        if (n == 1) return ys[0]
        val t = progress.coerceIn(xs[0], xs[n - 1])
        if (t <= xs[0]) return ys[0]
        if (t >= xs[n - 1]) return ys[n - 1]

        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] > t) hi = mid else lo = mid
        }
        val h = xs[hi] - xs[lo]
        if (h == 0.0) return ys[lo]
        val dLo = (t - xs[lo]) / h
        val dHi = (t - xs[hi]) / h
        val basis00 = (1.0 + 2.0 * dLo) * dHi * dHi
        val basis10 = dLo * dHi * dHi * h
        val basis01 = (1.0 + 2.0 * dHi) * dLo * dLo
        val basis11 = -dHi * dLo * dLo * h
        val eased = (basis00 + basis10 + basis01 + basis11)
        return eased * (ys[hi] - ys[lo]) + ys[lo]
    }
}
