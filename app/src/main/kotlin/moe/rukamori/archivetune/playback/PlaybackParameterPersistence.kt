/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import kotlin.math.log2
import kotlin.math.pow

internal data class PersistedPlaybackParameters(
    val speed: Float,
    val pitchSemitones: Float,
)

internal object PlaybackParameterPersistence {
    const val DEFAULT_SPEED = 1f
    const val DEFAULT_PITCH_SEMITONES = 0f
    private const val MIN_SPEED = 0.25f
    private const val MAX_SPEED = 4f
    private const val MIN_PITCH_SEMITONES = -12f
    private const val MAX_PITCH_SEMITONES = 12f

    fun sanitize(
        speed: Float?,
        pitchSemitones: Float?,
    ): PersistedPlaybackParameters =
        PersistedPlaybackParameters(
            speed = speed.finiteOr(DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED),
            pitchSemitones =
                pitchSemitones
                    .finiteOr(DEFAULT_PITCH_SEMITONES)
                    .coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES),
        )

    fun pitchMultiplierToSemitones(multiplier: Float): Float {
        val safe = multiplier.takeIf { it.isFinite() && it > 0f } ?: 1f
        return (12f * log2(safe))
            .coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES)
    }

    fun semitonesToPitchMultiplier(semitones: Float): Float {
        val safe = semitones.finiteOr(DEFAULT_PITCH_SEMITONES).coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES)
        return 2f.pow(safe / 12f)
    }

    private fun Float?.finiteOr(fallback: Float): Float =
        this?.takeIf(Float::isFinite) ?: fallback
}
