/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audio

import kotlin.math.max

/** Playback-position sample of an analyzed beat grid, matching the desktop beat-sync contract. */
internal data class AndroidBeatTimelineSample(
    val beatIndex: Int,
    val phase: Float,
    val pulse: Float,
    val beatDurationMs: Float,
    val localBpm: Float,
)

/**
 * Samples actual beat timestamps rather than integrating a synthetic clock. This intentionally
 * mirrors DesktopBeatSync so seek/speed/pause callers can derive identical visual timing once an
 * Android analyzer supplies a beat grid.
 */
internal fun sampleAndroidBeatTimeline(
    beatsSeconds: FloatArray,
    positionMs: Double,
): AndroidBeatTimelineSample {
    if (beatsSeconds.size < 2 || positionMs < 0.0) return EMPTY_ANDROID_BEAT_SAMPLE

    val positionSeconds = positionMs / 1_000.0
    val first = beatsSeconds[0].toDouble()
    val lastIndex = beatsSeconds.lastIndex

    if (positionSeconds < first) {
        val interval = (beatsSeconds[1] - beatsSeconds[0]).coerceAtLeast(MIN_BEAT_INTERVAL_SECONDS)
        val distance = (first - positionSeconds).toFloat()
        val attackWindow = interval * ATTACK_FRACTION
        val pulse = if (distance < attackWindow) smoothStep(1f - distance / attackWindow) else 0f
        return AndroidBeatTimelineSample(-1, 0f, pulse, interval * 1_000f, 60f / interval)
    }

    var low = 0
    var high = lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (beatsSeconds[mid].toDouble() <= positionSeconds) low = mid + 1 else high = mid - 1
    }
    val index = high.coerceIn(0, lastIndex)

    if (index == lastIndex) {
        val interval =
            (beatsSeconds[lastIndex] - beatsSeconds[lastIndex - 1]).coerceAtLeast(MIN_BEAT_INTERVAL_SECONDS)
        val elapsed = (positionSeconds - beatsSeconds[lastIndex]).toFloat()
        val decayWindow = interval * DECAY_FRACTION
        val pulse = if (elapsed < decayWindow) 1f - smoothStep(elapsed / decayWindow) else 0f
        return AndroidBeatTimelineSample(lastIndex, 1f, pulse, interval * 1_000f, 60f / interval)
    }

    val start = beatsSeconds[index]
    val end = beatsSeconds[index + 1]
    val interval = (end - start).coerceAtLeast(MIN_BEAT_INTERVAL_SECONDS)
    val phase = (((positionSeconds - start) / interval).toFloat()).coerceIn(0f, 1f)
    val decay =
        if (phase < DECAY_FRACTION) {
            1f - smoothStep(phase / DECAY_FRACTION)
        } else {
            0f
        }
    val attackStart = 1f - ATTACK_FRACTION
    val attack =
        if (phase > attackStart) {
            smoothStep((phase - attackStart) / ATTACK_FRACTION)
        } else {
            0f
        }
    return AndroidBeatTimelineSample(index, phase, max(decay, attack), interval * 1_000f, 60f / interval)
}

private fun smoothStep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private val EMPTY_ANDROID_BEAT_SAMPLE = AndroidBeatTimelineSample(-1, 0f, 0f, 0f, 0f)
private const val ATTACK_FRACTION = 0.13f
private const val DECAY_FRACTION = 0.52f
private const val MIN_BEAT_INTERVAL_SECONDS = 0.05f