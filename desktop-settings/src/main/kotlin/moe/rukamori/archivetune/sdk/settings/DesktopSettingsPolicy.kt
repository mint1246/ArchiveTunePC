/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.sdk.settings

import moe.rukamori.archivetune.desktop.settings.DEFAULT_DESKTOP_SPONSOR_BLOCK_API_URL
import moe.rukamori.archivetune.desktop.settings.DEFAULT_DESKTOP_SPONSOR_BLOCK_CATEGORIES
import moe.rukamori.archivetune.desktop.settings.DesktopEqualizerSettings
import moe.rukamori.archivetune.desktop.settings.DesktopKeybind
import moe.rukamori.archivetune.desktop.settings.DesktopKeybindAction
import moe.rukamori.archivetune.desktop.settings.DesktopSettings
import moe.rukamori.archivetune.desktop.settings.DesktopWindowPlacement
import moe.rukamori.archivetune.desktop.settings.supportsHoldRepeat
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.math.roundToInt

sealed interface LyricsProviderToggleResult {
    data class Updated(val settings: DesktopSettings) : LyricsProviderToggleResult
    data object EmptySelectionNotAllowed : LyricsProviderToggleResult
}

/**
 * Pure, headless domain policy for ArchiveTune desktop settings: sanitization, normalization,
 * validation, and conflict-free settings mutations with zero UI or persistence dependencies.
 */
object DesktopSettingsPolicy {
    const val ARGB_MAX = 0xFFFF_FFFFL
    const val MAX_LIBRARY_FOLDERS = 64
    const val MAX_LYRICS_PROVIDERS = 32
    val LEGACY_REMOVED_LYRICS_PROVIDER_IDS = setOf("paxsenix", "paxsenix-netease", "paxsenix-youtube")
    const val MAX_TRANSLATION_LANGUAGE_LENGTH = 64
    const val MAX_TRANSLATION_MODEL_LENGTH = 128
    const val MAX_TRANSLATION_PROMPT_LENGTH = 4_000
    const val MAX_SETTINGS_URL_LENGTH = 2_048
    const val DEFAULT_TRANSLATION_ENDPOINT = "https://api.openai.com/v1/chat/completions"
    const val DEFAULT_LAST_FM_ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
    const val MAX_EQUALIZER_BANDS = 32
    const val MAX_EQUALIZER_PRESET_LENGTH = 80
    const val MIN_EQUALIZER_GAIN_DB = -20f
    const val MAX_EQUALIZER_GAIN_DB = 20f
    const val MAX_AUDIO_OUTPUT_DEVICE_ID_LENGTH = 1_024
    const val MIN_WINDOWS_VIRTUAL_KEY = 1
    const val MAX_WINDOWS_VIRTUAL_KEY = 0xFF
    const val MIN_CROSSFADE_SECONDS = 0f
    const val MAX_CROSSFADE_SECONDS = 10f

    fun sanitize(settings: DesktopSettings): DesktopSettings = settings.copy(
        accentSeedArgb = settings.accentSeedArgb?.takeIf { it in 0..ARGB_MAX },
        beatSyncedVisualsIntensity = settings.beatSyncedVisualsIntensity.finiteOr(0.75f).coerceIn(0f, 1f),
        volume = settings.volume.finiteOr(1f).coerceIn(0f, 1f),
        speed = settings.speed.finiteOr(1f).coerceIn(0.25f, 4f),
        pitchSemitones = settings.pitchSemitones.finiteOr(0f).coerceIn(-12f, 12f),
        crossfadeDurationSeconds = settings.crossfadeDurationSeconds
            .finiteOr(5f)
            .coerceIn(MIN_CROSSFADE_SECONDS, MAX_CROSSFADE_SECONDS),
        equalizer = sanitizeEqualizer(settings.equalizer),
        audioOutputDeviceId = settings.audioOutputDeviceId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(MAX_AUDIO_OUTPUT_DEVICE_ID_LENGTH),
        downloadConcurrency = settings.downloadConcurrency.coerceIn(1, 8),
        windowPlacement = sanitizeWindowPlacement(settings.windowPlacement),
        libraryFolders = settings.libraryFolders
            .map(Path::absolute)
            .map(Path::normalize)
            .distinct()
            .take(MAX_LIBRARY_FOLDERS),
        downloadLocation = settings.downloadLocation?.absolute()?.normalize(),
        lyricsProviderIds = settings.lyricsProviderIds
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot(LEGACY_REMOVED_LYRICS_PROVIDER_IDS::contains)
            .distinct()
            .take(MAX_LYRICS_PROVIDERS),
        lyricsTranslationTargetLanguage = settings.lyricsTranslationTargetLanguage
            .trim()
            .takeIf(String::isNotEmpty)
            ?.take(MAX_TRANSLATION_LANGUAGE_LENGTH)
            ?: "en",
        lyricsTranslationEndpoint = normalizeHttpsUrl(settings.lyricsTranslationEndpoint)
            ?: DEFAULT_TRANSLATION_ENDPOINT,
        lyricsTranslationModel = settings.lyricsTranslationModel
            .trim()
            .takeIf(String::isNotEmpty)
            ?.take(MAX_TRANSLATION_MODEL_LENGTH)
            ?: "gpt-4o-mini",
        lyricsTranslationCustomPrompt = settings.lyricsTranslationCustomPrompt.take(MAX_TRANSLATION_PROMPT_LENGTH),
        sponsorBlockCategories = settings.sponsorBlockCategories.filterTo(
            linkedSetOf(),
            DEFAULT_DESKTOP_SPONSOR_BLOCK_CATEGORIES::contains,
        ),
        sponsorBlockApiUrl = normalizeHttpsUrl(settings.sponsorBlockApiUrl)
            ?: DEFAULT_DESKTOP_SPONSOR_BLOCK_API_URL,
        desktopCanvasCacheLimitMb = sanitizeCanvasCacheLimitMb(settings.desktopCanvasCacheLimitMb),
        lastFmMinTrackDurationSeconds = settings.lastFmMinTrackDurationSeconds.coerceIn(0, 600),
        lastFmScrobbleDelayPercent = settings.lastFmScrobbleDelayPercent.finiteOr(0.5f).coerceIn(0.1f, 1f),
        lastFmScrobbleDelaySeconds = settings.lastFmScrobbleDelaySeconds.coerceIn(1, 600),
        lastFmEndpoint = normalizeLastFmEndpoint(settings.lastFmEndpoint),
        keybinds = settings.keybinds
            .mapNotNull(::sanitizeKeybind)
            .distinctBy { it.action },
    )

    fun sanitizeKeybind(keybind: DesktopKeybind): DesktopKeybind? {
        val keys = keybind.virtualKeys
            .filter { it in MIN_WINDOWS_VIRTUAL_KEY..MAX_WINDOWS_VIRTUAL_KEY }
            .distinct()
        return keybind.takeIf { keys.isNotEmpty() }?.copy(
            virtualKeys = keys,
            repeatWhileHeld = keybind.repeatWhileHeld && keybind.action.supportsHoldRepeat,
        )
    }

    fun sanitizeWindowPlacement(placement: DesktopWindowPlacement): DesktopWindowPlacement = placement.copy(
        widthDp = placement.widthDp.finiteOr(1_280f).coerceIn(640f, 5_120f),
        heightDp = placement.heightDp.finiteOr(800f).coerceIn(480f, 2_880f),
        xDp = placement.xDp?.takeIf(Float::isFinite),
        yDp = placement.yDp?.takeIf(Float::isFinite),
    )

    fun sanitizeEqualizer(equalizer: DesktopEqualizerSettings): DesktopEqualizerSettings = equalizer.copy(
        presetName = equalizer.presetName?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_EQUALIZER_PRESET_LENGTH),
        preampDb = equalizer.preampDb.finiteOr(0f).coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB),
        bandGainsDb = equalizer.bandGainsDb.take(MAX_EQUALIZER_BANDS).map {
            it.finiteOr(0f).coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB)
        },
    )

    fun normalizeHttpsUrl(value: String): String? {
        val candidate = value.trim()
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (candidate.length > MAX_SETTINGS_URL_LENGTH) return null
        return candidate.trimEnd('/')
    }

    fun normalizeLastFmEndpoint(value: String): String =
        normalizeHttpsUrl(value)?.let { endpoint ->
            if (value.trim().endsWith("/")) "$endpoint/" else endpoint
        } ?: DEFAULT_LAST_FM_ENDPOINT

    fun sanitizeCanvasCacheLimitMb(value: Int): Int = when (value) {
        0 -> 0
        else -> value.coerceIn(64, 8_192)
    }

    /** Assigns [binding], replacing any existing binding for the same action or conflicting chord. */
    fun assignKeybind(settings: DesktopSettings, binding: DesktopKeybind): DesktopSettings {
        val action = binding.action
        val chord = binding.virtualKeys.toSet()
        return sanitize(
            settings.copy(
                keybinds = settings.keybinds
                    .filterNot { it.action == action || it.virtualKeys.toSet() == chord }
                    .plus(binding),
            ),
        )
    }

    fun clearKeybind(settings: DesktopSettings, action: DesktopKeybindAction): DesktopSettings =
        sanitize(settings.copy(keybinds = settings.keybinds.filterNot { it.action == action }))

    fun toggleSponsorBlockCategory(settings: DesktopSettings, categoryApiValue: String): DesktopSettings {
        val next = if (categoryApiValue in settings.sponsorBlockCategories) {
            settings.sponsorBlockCategories - categoryApiValue
        } else {
            settings.sponsorBlockCategories + categoryApiValue
        }
        return sanitize(settings.copy(sponsorBlockCategories = next))
    }

    fun isLyricsProviderEnabled(selectedProviderIds: List<String>, providerId: String): Boolean =
        selectedProviderIds.isEmpty() || providerId in selectedProviderIds

    fun toggleLyricsProvider(
        settings: DesktopSettings,
        providerId: String,
        defaultProviderIds: List<String>,
    ): LyricsProviderToggleResult {
        val enabled = isLyricsProviderEnabled(settings.lyricsProviderIds, providerId)
        val current = if (settings.lyricsProviderIds.isEmpty()) defaultProviderIds else settings.lyricsProviderIds
        val next = if (enabled) current - providerId else current + providerId
        if (next.isEmpty()) return LyricsProviderToggleResult.EmptySelectionNotAllowed
        val persisted = if (next.size == defaultProviderIds.size) emptyList() else next
        return LyricsProviderToggleResult.Updated(sanitize(settings.copy(lyricsProviderIds = persisted)))
    }

    fun setEqualizerEnabled(equalizer: DesktopEqualizerSettings, enabled: Boolean): DesktopEqualizerSettings =
        sanitizeEqualizer(equalizer.copy(enabled = enabled))

    fun applyEqualizerPreset(
        presetName: String,
        preampDb: Float,
        bandGainsDb: List<Float>,
    ): DesktopEqualizerSettings = sanitizeEqualizer(
        DesktopEqualizerSettings(
            enabled = true,
            presetName = presetName,
            preampDb = preampDb,
            bandGainsDb = bandGainsDb,
        ),
    )

    fun updateEqualizerPreamp(equalizer: DesktopEqualizerSettings, preampDb: Float): DesktopEqualizerSettings =
        sanitizeEqualizer(equalizer.copy(preampDb = preampDb, presetName = null))

    fun updateEqualizerBand(
        equalizer: DesktopEqualizerSettings,
        bandIndex: Int,
        bandCount: Int,
        gainDb: Float,
    ): DesktopEqualizerSettings {
        if (bandCount <= 0 || bandIndex !in 0 until bandCount) return sanitizeEqualizer(equalizer)
        val gains = List(bandCount) { index ->
            if (index == bandIndex) gainDb else equalizer.bandGainsDb.getOrElse(index) { 0f }
        }
        return sanitizeEqualizer(equalizer.copy(bandGainsDb = gains, presetName = null))
    }

    fun snapCrossfadeDurationSeconds(seconds: Float): Float =
        (seconds.finiteOr(5f).coerceIn(MIN_CROSSFADE_SECONDS, MAX_CROSSFADE_SECONDS) * 2f).roundToInt() / 2f

    fun crossfadeDurationMillis(settings: DesktopSettings): Long =
        (sanitize(settings).crossfadeDurationSeconds * 1_000f).toLong()

    private fun Float.finiteOr(default: Float): Float = takeIf(Float::isFinite) ?: default
}
