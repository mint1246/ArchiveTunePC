/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.stems

import kotlin.math.abs

/** Portable cache/model contract shared with the current HTDemucs 6-source output. */
const val STEM_CACHE_VERSION = "htdemucs-6s-1s-dml-v1"
const val STEM_OPUS_CACHE_FORMAT = "ogg-opus-6x-stereo"
const val STEM_WAV_CACHE_FORMAT = "wav-pcm16-6x-stereo"

enum class StemEncoding(
    val extension: String,
    val manifestFormat: String,
) {
    OGG_OPUS("opus", STEM_OPUS_CACHE_FORMAT),
    PCM16_WAV("wav", STEM_WAV_CACHE_FORMAT),
}

/** Keep this order identical to the model worker/source order. */
enum class StemSource(val cacheName: String) {
    DRUMS("drums"),
    BASS("bass"),
    OTHER("other"),
    VOCALS("vocals"),
    GUITAR("guitar"),
    PIANO("piano"),
    ;

    companion object {
        val workerOrder: List<StemSource> = entries
    }
}

data class StemChannelControl(
    val gain: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
) {
    fun sanitized(): StemChannelControl = copy(gain = sanitizeStemGain(gain))
}

data class StemControls(
    val drums: StemChannelControl = StemChannelControl(),
    val bass: StemChannelControl = StemChannelControl(),
    val other: StemChannelControl = StemChannelControl(),
    val vocals: StemChannelControl = StemChannelControl(),
    val guitar: StemChannelControl = StemChannelControl(),
    val piano: StemChannelControl = StemChannelControl(),
) {
    fun sanitized(): StemControls = copy(
        drums = drums.sanitized(),
        bass = bass.sanitized(),
        other = other.sanitized(),
        vocals = vocals.sanitized(),
        guitar = guitar.sanitized(),
        piano = piano.sanitized(),
    )

    fun control(source: StemSource): StemChannelControl =
        when (source) {
            StemSource.DRUMS -> drums
            StemSource.BASS -> bass
            StemSource.OTHER -> other
            StemSource.VOCALS -> vocals
            StemSource.GUITAR -> guitar
            StemSource.PIANO -> piano
        }

    fun withControl(
        source: StemSource,
        control: StemChannelControl,
    ): StemControls =
        when (source) {
            StemSource.DRUMS -> copy(drums = control)
            StemSource.BASS -> copy(bass = control)
            StemSource.OTHER -> copy(other = control)
            StemSource.VOCALS -> copy(vocals = control)
            StemSource.GUITAR -> copy(guitar = control)
            StemSource.PIANO -> copy(piano = control)
        }.sanitized()

    /** Effective per-source gains in worker/cache order, including mute/solo semantics. */
    fun effectiveGains(): List<Float> {
        val safe = sanitized()
        val anySolo = StemSource.workerOrder.any { safe.control(it).solo }
        return StemSource.workerOrder.map { source ->
            val channel = safe.control(source)
            if (!channel.muted && (!anySolo || channel.solo)) channel.gain else 0f
        }
    }

    fun isEffectiveNeutral(): Boolean = effectiveGains().all { abs(it - 1f) <= 0.001f }

    fun isDefault(): Boolean = this == StemControls()
}

fun sanitizeStemGain(gain: Float): Float = gain.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
