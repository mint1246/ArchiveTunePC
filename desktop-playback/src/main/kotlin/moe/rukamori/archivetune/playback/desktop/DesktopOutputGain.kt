/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import kotlin.math.pow
import kotlin.math.roundToInt
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource

internal fun interface OutputVolumeBackend {
    fun setVolumePercent(volumePercent: Int)
}

/**
 * Keeps the user's 0..1 volume intent separate from a track-local loudness correction. libVLC
 * accepts volume percentages above 100; we deliberately cap the effective output at Android's
 * +3 dB normalization ceiling (about 141%) rather than exposing that boost through UI state.
 */
internal class DesktopOutputGain {
    private var backend: OutputVolumeBackend? = null
    private var userVolume = 1f
    private var normalizationEnabled = false
    private var sourceNormalizationFactor = 1f
    private var fadeMultiplier = 1f

    @Synchronized
    fun setUserVolume(volume: Float): Float {
        userVolume = sanitizeUserVolume(volume)
        applyLocked()
        return userVolume
    }

    @Synchronized
    fun setNormalizationEnabled(enabled: Boolean) {
        normalizationEnabled = enabled
        applyLocked()
    }

    @Synchronized
    fun setSource(source: ResolvedPlaybackSource?) {
        sourceNormalizationFactor = loudnessNormalizationFactor(
            enabled = true,
            perceptualLoudnessDb = source?.perceptualLoudnessDb,
            loudnessDb = source?.loudnessDb,
        )
        applyLocked()
    }

    @Synchronized
    fun setFadeMultiplier(multiplier: Float) {
        fadeMultiplier = sanitizeFadeMultiplier(multiplier)
        applyLocked()
    }

    /** Returns the matching volume for a second player without changing the primary output. */
    @Synchronized
    fun volumePercentForSource(source: ResolvedPlaybackSource?, multiplier: Float = 1f): Int {
        val factor = loudnessNormalizationFactor(
            enabled = normalizationEnabled,
            perceptualLoudnessDb = source?.perceptualLoudnessDb,
            loudnessDb = source?.loudnessDb,
        )
        return effectiveVlcVolumePercent(
            userVolume = userVolume * sanitizeFadeMultiplier(multiplier),
            normalizationFactor = factor,
        )
    }

    @Synchronized
    fun attach(attached: OutputVolumeBackend) {
        backend = attached
        applyLocked()
    }

    @Synchronized
    fun detach(detached: OutputVolumeBackend) {
        if (backend === detached) backend = null
    }

    @Synchronized
    fun reapply() {
        applyLocked()
    }

    @Synchronized
    internal fun userVolumeForTest(): Float = userVolume

    @Synchronized
    internal fun effectiveFactorForTest(): Float = if (normalizationEnabled) sourceNormalizationFactor else 1f

    private fun applyLocked() {
        backend?.setVolumePercent(
            effectiveVlcVolumePercent(
                userVolume = userVolume * fadeMultiplier,
                normalizationFactor = if (normalizationEnabled) sourceNormalizationFactor else 1f,
            ),
        )
    }
}

internal fun loudnessNormalizationFactor(
    enabled: Boolean,
    perceptualLoudnessDb: Double?,
    loudnessDb: Double?,
): Float {
    if (!enabled) return 1f
    val selectedDb = sequenceOf(perceptualLoudnessDb, loudnessDb)
        .mapNotNull(::sanitizeLoudnessDb)
        .firstOrNull()
        ?: return 1f
    val raw = 10.0.pow(-selectedDb / 20.0)
    if (!raw.isFinite()) return 1f
    return raw.coerceIn(MIN_AUDIO_NORMALIZATION_FACTOR.toDouble(), MAX_AUDIO_NORMALIZATION_FACTOR.toDouble()).toFloat()
}

internal fun effectiveVlcVolumePercent(userVolume: Float, normalizationFactor: Float): Int {
    val safeUserVolume = sanitizeUserVolume(userVolume)
    val safeFactor = normalizationFactor
        .takeIf(Float::isFinite)
        ?.coerceIn(MIN_AUDIO_NORMALIZATION_FACTOR, MAX_AUDIO_NORMALIZATION_FACTOR)
        ?: 1f
    return (safeUserVolume * safeFactor * 100f)
        .coerceIn(0f, MAX_AUDIO_NORMALIZATION_FACTOR * 100f)
        .roundToInt()
}

private fun sanitizeUserVolume(volume: Float): Float = volume.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f

private fun sanitizeFadeMultiplier(multiplier: Float): Float =
    multiplier.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f

private fun sanitizeLoudnessDb(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it in MIN_VALID_LOUDNESS_DB..MAX_VALID_LOUDNESS_DB }

internal const val MIN_AUDIO_NORMALIZATION_FACTOR = 0.25f
internal const val MAX_AUDIO_NORMALIZATION_FACTOR = 1.414f
private const val MIN_VALID_LOUDNESS_DB = -60.0
private const val MAX_VALID_LOUDNESS_DB = 30.0
