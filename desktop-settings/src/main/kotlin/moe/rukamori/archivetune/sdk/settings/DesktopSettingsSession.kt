/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.sdk.settings

import moe.rukamori.archivetune.desktop.settings.DesktopEqualizerSettings
import moe.rukamori.archivetune.desktop.settings.DesktopKeybind
import moe.rukamori.archivetune.desktop.settings.DesktopKeybindAction
import moe.rukamori.archivetune.desktop.settings.DesktopSettings
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Stores sanitized settings and applies each [update] transform once, atomically. */
interface DesktopSettingsBackend {
    fun snapshot(): DesktopSettings
    fun update(transform: (DesktopSettings) -> DesktopSettings): DesktopSettings
    fun save(settings: DesktopSettings)
}

class InMemoryDesktopSettingsBackend(initial: DesktopSettings = DesktopSettings()) : DesktopSettingsBackend {
    private val lock = ReentrantLock()
    private var current: DesktopSettings = DesktopSettingsPolicy.sanitize(initial)

    override fun snapshot(): DesktopSettings = lock.withLock { current }

    override fun update(transform: (DesktopSettings) -> DesktopSettings): DesktopSettings = lock.withLock {
        current = DesktopSettingsPolicy.sanitize(transform(current))
        current
    }

    override fun save(settings: DesktopSettings) = lock.withLock {
        current = DesktopSettingsPolicy.sanitize(settings)
    }
}

/**
 * [DesktopSettingsBackend] backed by a [DesktopSettingsStore].
 *
 * Ownership is explicit: when [ownsStore] is `false` (the default for [DesktopSettingsSession.forStore]),
 * the backend borrows the caller-supplied [store] and [close] is a no-op so shared store lifecycles
 * remain managed by the caller. Set [ownsStore] to `true` only when this backend exclusively owns [store].
 */
class StoreDesktopSettingsBackend(
    private val store: DesktopSettingsStore,
    private val ownsStore: Boolean = false,
) : DesktopSettingsBackend, AutoCloseable {
    override fun snapshot(): DesktopSettings = store.snapshot()
    override fun update(transform: (DesktopSettings) -> DesktopSettings): DesktopSettings = store.update(transform)
    override fun save(settings: DesktopSettings) = store.save(settings)
    override fun close() {
        if (ownsStore) {
            store.close()
        }
    }
}

/**
 * Independently usable headless settings coordinator that applies [DesktopSettingsPolicy]
 * against any [DesktopSettingsBackend] (file-backed [DesktopSettingsStore] or in-memory).
 *
 * Calling [close] closes [backend] only if [backend] implements [AutoCloseable] and owns its
 * underlying resource; sessions created via [forStore] borrow their [DesktopSettingsStore] and
 * never close it.
 */
class DesktopSettingsSession(
    private val backend: DesktopSettingsBackend,
) : AutoCloseable {
    fun snapshot(): DesktopSettings = backend.snapshot()

    fun update(transform: (DesktopSettings) -> DesktopSettings): DesktopSettings = backend.update(transform)

    fun save(settings: DesktopSettings) = backend.save(settings)

    fun assignKeybind(binding: DesktopKeybind): DesktopSettings =
        backend.update { current -> DesktopSettingsPolicy.assignKeybind(current, binding) }

    fun clearKeybind(action: DesktopKeybindAction): DesktopSettings =
        backend.update { current -> DesktopSettingsPolicy.clearKeybind(current, action) }

    fun toggleSponsorBlockCategory(categoryApiValue: String): DesktopSettings =
        backend.update { current -> DesktopSettingsPolicy.toggleSponsorBlockCategory(current, categoryApiValue) }

    fun toggleLyricsProvider(
        providerId: String,
        defaultProviderIds: List<String>,
    ): LyricsProviderToggleResult {
        if (DesktopSettingsPolicy.toggleLyricsProvider(backend.snapshot(), providerId, defaultProviderIds)
            is LyricsProviderToggleResult.EmptySelectionNotAllowed
        ) {
            return LyricsProviderToggleResult.EmptySelectionNotAllowed
        }
        var accepted = false
        val stored = backend.update { current ->
            when (val result = DesktopSettingsPolicy.toggleLyricsProvider(current, providerId, defaultProviderIds)) {
                is LyricsProviderToggleResult.Updated -> {
                    accepted = true
                    result.settings
                }
                LyricsProviderToggleResult.EmptySelectionNotAllowed -> current
            }
        }
        return if (accepted) {
            LyricsProviderToggleResult.Updated(stored)
        } else {
            LyricsProviderToggleResult.EmptySelectionNotAllowed
        }
    }

    fun setEqualizerEnabled(enabled: Boolean): DesktopSettings =
        backend.update { current ->
            current.copy(equalizer = DesktopSettingsPolicy.setEqualizerEnabled(current.equalizer, enabled))
        }

    fun applyEqualizerPreset(
        presetName: String,
        preampDb: Float,
        bandGainsDb: List<Float>,
    ): DesktopSettings = backend.update { current ->
        current.copy(
            equalizer = DesktopSettingsPolicy.applyEqualizerPreset(
                presetName = presetName,
                preampDb = preampDb,
                bandGainsDb = bandGainsDb,
            ),
        )
    }

    fun updateEqualizerPreamp(preampDb: Float): DesktopSettings =
        backend.update { current ->
            current.copy(equalizer = DesktopSettingsPolicy.updateEqualizerPreamp(current.equalizer, preampDb))
        }

    fun updateEqualizerBand(bandIndex: Int, bandCount: Int, gainDb: Float): DesktopSettings =
        backend.update { current ->
            current.copy(
                equalizer = DesktopSettingsPolicy.updateEqualizerBand(
                    equalizer = current.equalizer,
                    bandIndex = bandIndex,
                    bandCount = bandCount,
                    gainDb = gainDb,
                ),
            )
        }

    fun setEqualizer(equalizer: DesktopEqualizerSettings): DesktopSettings =
        backend.update { current -> current.copy(equalizer = equalizer) }

    fun setCrossfadeEnabled(enabled: Boolean): DesktopSettings =
        backend.update { it.copy(crossfadeEnabled = enabled) }

    fun setCrossfadeDurationSeconds(seconds: Float, snapToHalfSecond: Boolean = true): DesktopSettings =
        backend.update {
            val target = if (snapToHalfSecond) DesktopSettingsPolicy.snapCrossfadeDurationSeconds(seconds) else seconds
            it.copy(crossfadeDurationSeconds = target)
        }

    fun setCrossfadeGaplessAlbumTransitions(enabled: Boolean): DesktopSettings =
        backend.update { it.copy(crossfadeGaplessAlbumTransitions = enabled) }

    override fun close() {
        (backend as? AutoCloseable)?.close()
    }

    companion object {
        /**
         * Creates a session that borrows [store] without taking ownership of its lifecycle.
         * Calling [DesktopSettingsSession.close] will not close or flush [store]; the caller
         * remains responsible for closing [store].
         */
        fun forStore(store: DesktopSettingsStore): DesktopSettingsSession =
            DesktopSettingsSession(StoreDesktopSettingsBackend(store, ownsStore = false))

        fun inMemory(initial: DesktopSettings = DesktopSettings()): DesktopSettingsSession =
            DesktopSettingsSession(InMemoryDesktopSettingsBackend(initial))
    }
}
