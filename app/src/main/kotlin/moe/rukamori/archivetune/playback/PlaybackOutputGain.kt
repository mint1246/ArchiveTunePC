/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import kotlin.math.pow

internal fun playbackLoudnessNormalizationFactor(
    enabled: Boolean,
    perceptualLoudnessDb: Double?,
    loudnessDb: Double?,
): Float {
    if (!enabled) return 1f
    val selectedDb =
        sequenceOf(perceptualLoudnessDb, loudnessDb)
            .mapNotNull(::sanitizePlaybackLoudnessDb)
            .firstOrNull()
            ?: return 1f
    val raw = 10.0.pow(-selectedDb / 20.0)
    if (!raw.isFinite()) return 1f
    return raw.coerceIn(MIN_PLAYBACK_NORMALIZATION_FACTOR, MAX_PLAYBACK_NORMALIZATION_FACTOR).toFloat()
}

private fun sanitizePlaybackLoudnessDb(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it in MIN_VALID_PLAYBACK_LOUDNESS_DB..MAX_VALID_PLAYBACK_LOUDNESS_DB }

internal const val MIN_PLAYBACK_NORMALIZATION_FACTOR = 0.25
internal const val MAX_PLAYBACK_NORMALIZATION_FACTOR = 1.414
private const val MIN_VALID_PLAYBACK_LOUDNESS_DB = -60.0
private const val MAX_VALID_PLAYBACK_LOUDNESS_DB = 30.0
