/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.util.concurrent.atomic.AtomicReference
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer

data class DesktopEqualizerProfile(
    val enabled: Boolean = false,
    val preampDb: Float = 0f,
    val bandGainsDb: List<Float> = emptyList(),
)

data class DesktopEqualizerCapabilities(
    val bandFrequenciesHz: List<Float>,
    val presets: List<String>,
)

interface DesktopAudioEffects {
    suspend fun equalizerCapabilities(): DesktopEqualizerCapabilities

    suspend fun presetEqualizer(name: String): DesktopEqualizerProfile?

    suspend fun applyEqualizer(profile: DesktopEqualizerProfile)

    /** Local-only output correction; does not alter PlaybackSnapshot.volume. */
    suspend fun setLoudnessNormalizationEnabled(enabled: Boolean)
}

internal interface EqualizerBackend {
    val capabilities: DesktopEqualizerCapabilities

    fun preset(name: String): DesktopEqualizerProfile?

    fun apply(profile: DesktopEqualizerProfile)
}

/**
 * Keeps desired equalizer state independent from the native player's lifetime. This matters during
 * startup because libVLC prewarming races settings restoration: whichever finishes first, attaching
 * the backend reapplies the latest requested profile.
 */
internal class VlcjAudioEffects(
    private val applyLoudnessNormalizationEnabled: suspend (Boolean) -> Unit = {},
    private val ensureBackend: suspend () -> EqualizerBackend,
) : DesktopAudioEffects {
    private val desiredProfile = AtomicReference(DesktopEqualizerProfile())
    private val backend = AtomicReference<EqualizerBackend?>(null)

    override suspend fun equalizerCapabilities(): DesktopEqualizerCapabilities =
        currentOrEnsureBackend().capabilities

    override suspend fun presetEqualizer(name: String): DesktopEqualizerProfile? =
        currentOrEnsureBackend().preset(name)

    override suspend fun applyEqualizer(profile: DesktopEqualizerProfile) {
        val sanitized = profile.sanitized()
        desiredProfile.set(sanitized)
        backend.get()?.apply(sanitized)
    }

    override suspend fun setLoudnessNormalizationEnabled(enabled: Boolean) {
        applyLoudnessNormalizationEnabled(enabled)
    }

    fun attach(attached: EqualizerBackend) {
        backend.set(attached)
        attached.apply(desiredProfile.get())
    }

    fun detach(detached: EqualizerBackend) {
        backend.compareAndSet(detached, null)
    }

    private suspend fun currentOrEnsureBackend(): EqualizerBackend =
        backend.get() ?: ensureBackend().also { attached ->
            backend.compareAndSet(null, attached)
        }
}

internal class VlcjEqualizerBackend(
    private val factory: MediaPlayerFactory,
    private val player: MediaPlayer,
) : EqualizerBackend {
    private val equalizerApi = factory.equalizer()

    override val capabilities by lazy {
        DesktopEqualizerCapabilities(
            bandFrequenciesHz = equalizerApi.bands().map { it.takeIf(Float::isFinite) ?: 0f },
            presets = equalizerApi.presets().filter(String::isNotBlank).distinct(),
        )
    }

    override fun preset(name: String): DesktopEqualizerProfile? {
        val canonical = capabilities.presets.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        val equalizer = runCatching { equalizerApi.newEqualizer(canonical) }.getOrNull() ?: return null
        return DesktopEqualizerProfile(
            enabled = true,
            preampDb = equalizer.preamp(),
            bandGainsDb = equalizer.amps().toList(),
        ).normalizedForBands(capabilities.bandFrequenciesHz.size)
    }

    override fun apply(profile: DesktopEqualizerProfile) {
        if (!profile.enabled) {
            player.audio().setEqualizer(null)
            return
        }
        val normalized = profile.normalizedForBands(capabilities.bandFrequenciesHz.size)
        val equalizer = equalizerApi.newEqualizer()
        equalizer.setPreamp(normalized.preampDb)
        equalizer.setAmps(normalized.bandGainsDb.toFloatArray())
        player.audio().setEqualizer(equalizer)
    }
}

internal fun DesktopEqualizerProfile.normalizedForBands(bandCount: Int): DesktopEqualizerProfile {
    val sanitized = sanitized()
    val safeCount = bandCount.coerceIn(0, MAX_EQUALIZER_BANDS)
    return sanitized.copy(
        bandGainsDb = List(safeCount) { index -> sanitized.bandGainsDb.getOrElse(index) { 0f } },
    )
}

private fun DesktopEqualizerProfile.sanitized(): DesktopEqualizerProfile = copy(
    preampDb = preampDb.finiteOrZero().coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB),
    bandGainsDb = bandGainsDb
        .take(MAX_EQUALIZER_BANDS)
        .map { it.finiteOrZero().coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB) },
)

private fun Float.finiteOrZero(): Float = takeIf(Float::isFinite) ?: 0f

internal const val MIN_EQUALIZER_GAIN_DB = -20f
internal const val MAX_EQUALIZER_GAIN_DB = 20f
private const val MAX_EQUALIZER_BANDS = 32
