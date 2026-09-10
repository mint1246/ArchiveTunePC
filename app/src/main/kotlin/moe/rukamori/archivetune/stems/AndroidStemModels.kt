/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import kotlin.math.abs

/** Portable cache/model contract shared with the current desktop HTDemucs 6-source output. */
const val ANDROID_STEM_CACHE_VERSION = "htdemucs-6s-1s-dml-v1"
const val ANDROID_STEM_CACHE_FORMAT = "ogg-opus-6x-stereo"
const val ANDROID_STEM_WAV_CACHE_FORMAT = "wav-pcm16-6x-stereo"

enum class AndroidStemEncoding(
    val extension: String,
    val manifestFormat: String,
) {
    OGG_OPUS("opus", ANDROID_STEM_CACHE_FORMAT),
    PCM16_WAV("wav", ANDROID_STEM_WAV_CACHE_FORMAT),
}

/** Keep this order identical to the desktop model worker/source order. */
enum class AndroidStemSource(val cacheName: String) {
    DRUMS("drums"),
    BASS("bass"),
    OTHER("other"),
    VOCALS("vocals"),
    GUITAR("guitar"),
    PIANO("piano"),
    ;

    companion object {
        val workerOrder: List<AndroidStemSource> = entries
    }
}

data class AndroidStemChannelControl(
    val gain: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
) {
    fun sanitized(): AndroidStemChannelControl = copy(gain = gain.sanitizeStemGain())
}

data class AndroidStemControls(
    val drums: AndroidStemChannelControl = AndroidStemChannelControl(),
    val bass: AndroidStemChannelControl = AndroidStemChannelControl(),
    val other: AndroidStemChannelControl = AndroidStemChannelControl(),
    val vocals: AndroidStemChannelControl = AndroidStemChannelControl(),
    val guitar: AndroidStemChannelControl = AndroidStemChannelControl(),
    val piano: AndroidStemChannelControl = AndroidStemChannelControl(),
) {
    fun sanitized(): AndroidStemControls = copy(
        drums = drums.sanitized(),
        bass = bass.sanitized(),
        other = other.sanitized(),
        vocals = vocals.sanitized(),
        guitar = guitar.sanitized(),
        piano = piano.sanitized(),
    )

    fun control(source: AndroidStemSource): AndroidStemChannelControl =
        when (source) {
            AndroidStemSource.DRUMS -> drums
            AndroidStemSource.BASS -> bass
            AndroidStemSource.OTHER -> other
            AndroidStemSource.VOCALS -> vocals
            AndroidStemSource.GUITAR -> guitar
            AndroidStemSource.PIANO -> piano
        }

    fun withControl(
        source: AndroidStemSource,
        control: AndroidStemChannelControl,
    ): AndroidStemControls =
        when (source) {
            AndroidStemSource.DRUMS -> copy(drums = control)
            AndroidStemSource.BASS -> copy(bass = control)
            AndroidStemSource.OTHER -> copy(other = control)
            AndroidStemSource.VOCALS -> copy(vocals = control)
            AndroidStemSource.GUITAR -> copy(guitar = control)
            AndroidStemSource.PIANO -> copy(piano = control)
        }.sanitized()

    /** Effective per-source gains in worker/cache order, including mute/solo semantics. */
    fun effectiveGains(): List<Float> {
        val safe = sanitized()
        val anySolo = AndroidStemSource.workerOrder.any { safe.control(it).solo }
        return AndroidStemSource.workerOrder.map { source ->
            val channel = safe.control(source)
            if (!channel.muted && (!anySolo || channel.solo)) channel.gain else 0f
        }
    }

    fun isEffectiveNeutral(): Boolean = effectiveGains().all { abs(it - 1f) <= 0.001f }

    fun isDefault(): Boolean = this == AndroidStemControls()
}

private fun Float.sanitizeStemGain(): Float = takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
