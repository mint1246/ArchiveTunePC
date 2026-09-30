/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.settings

import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.absolute
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

enum class DesktopThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    DYNAMIC,
}

enum class DesktopLastDestination {
    HOME,
    EXPLORE,
    SEARCH,
    LIBRARY,
    FRIENDS,
    DOWNLOADS,
    STATISTICS,
    SETTINGS,
}

/** Persisted playback queue defaults. Kept in this module to avoid a settings→playback cycle. */
enum class DesktopRepeatMode {
    OFF,
    ONE,
    ALL,
}

enum class DesktopPartialCleanupPolicy {
    DELETE_ON_CANCEL,
    KEEP_ON_CANCEL,
}

enum class DesktopLyricsTranslationProvider {
    GOOGLE,
    OPENAI_COMPATIBLE,
}

enum class DesktopCanvasSource {
    ALL,
    BETTER_LYRICS,
    APPLE_MUSIC,
    TIDAL,
    SPOTIFY,
}

const val DEFAULT_DESKTOP_SPONSOR_BLOCK_API_URL = "https://sponsor.ajay.app"
val DEFAULT_DESKTOP_SPONSOR_BLOCK_CATEGORIES = setOf(
    "music_offtopic", "sponsor", "intro", "outro", "selfpromo", "preview", "filler", "interaction", "hook",
)

enum class DesktopKeybindAction {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE_TOGGLE,
    SEEK_FORWARD,
    SEEK_BACKWARD,
    SPEED_UP,
    SPEED_DOWN,
    SPEED_RESET,
    SHUFFLE_TOGGLE,
    REPEAT_CYCLE,
    TOGGLE_LYRICS,
    TOGGLE_QUEUE,
}

val DesktopKeybindAction.supportsHoldRepeat: Boolean
    get() = when (this) {
        DesktopKeybindAction.VOLUME_UP,
        DesktopKeybindAction.VOLUME_DOWN,
        DesktopKeybindAction.SEEK_FORWARD,
        DesktopKeybindAction.SEEK_BACKWARD,
        DesktopKeybindAction.SPEED_UP,
        DesktopKeybindAction.SPEED_DOWN,
        -> true
        else -> false
    }

/** Windows virtual-key chord assigned to one desktop action. */
data class DesktopKeybind(
    val action: DesktopKeybindAction,
    val virtualKeys: List<Int>,
    val repeatWhileHeld: Boolean = false,
)

data class DesktopWindowPlacement(
    val widthDp: Float = 1_280f,
    val heightDp: Float = 800f,
    val xDp: Float? = null,
    val yDp: Float? = null,
    val maximized: Boolean = false,
)

data class DesktopEqualizerSettings(
    val enabled: Boolean = false,
    val presetName: String? = null,
    val preampDb: Float = 0f,
    val bandGainsDb: List<Float> = emptyList(),
)

/** Non-secret desktop preferences. Paths are supplied by the host application. */
data class DesktopSettings(
    val themeMode: DesktopThemeMode = DesktopThemeMode.SYSTEM,
    /** Optional unsigned ARGB color value, stored as a Long to avoid UI toolkit dependencies. */
    val accentSeedArgb: Long? = null,
    val beatSyncedVisualsEnabled: Boolean = true,
    val beatSyncedVisualsIntensity: Float = 0.75f,
    val volume: Float = 1f,
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
    val loudnessNormalizationEnabled: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val crossfadeDurationSeconds: Float = 5f,
    /** Preserve seamless album sequencing by skipping crossfade between tracks from the same album. */
    val crossfadeGaplessAlbumTransitions: Boolean = true,
    val repeatMode: DesktopRepeatMode = DesktopRepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val equalizer: DesktopEqualizerSettings = DesktopEqualizerSettings(),
    val audioOutputDeviceId: String? = null,
    val lastDestination: DesktopLastDestination = DesktopLastDestination.HOME,
    val windowPlacement: DesktopWindowPlacement = DesktopWindowPlacement(),
    val libraryFolders: List<Path> = emptyList(),
    val downloadLocation: Path? = null,
    val downloadConcurrency: Int = 2,
    val partialCleanupPolicy: DesktopPartialCleanupPolicy = DesktopPartialCleanupPolicy.DELETE_ON_CANCEL,
    /** Empty means all built-in lyrics providers, preserving the Android fallback behavior. */
    val lyricsProviderIds: List<String> = emptyList(),
    val lyricsTranslationProvider: DesktopLyricsTranslationProvider = DesktopLyricsTranslationProvider.GOOGLE,
    val lyricsTranslationTargetLanguage: String = "en",
    val lyricsTranslationEndpoint: String = "https://api.openai.com/v1/chat/completions",
    val lyricsTranslationModel: String = "gpt-4o-mini",
    val lyricsTranslationCustomPrompt: String = "",
    val sponsorBlockEnabled: Boolean = true,
    val sponsorBlockCategories: Set<String> = DEFAULT_DESKTOP_SPONSOR_BLOCK_CATEGORIES,
    val sponsorBlockApiUrl: String = DEFAULT_DESKTOP_SPONSOR_BLOCK_API_URL,
    val desktopCanvasEnabled: Boolean = false,
    val desktopCanvasSource: DesktopCanvasSource = DesktopCanvasSource.ALL,
    val desktopCanvasCacheLimitMb: Int = 256,
    val lastFmEnabled: Boolean = false,
    val lastFmNowPlayingEnabled: Boolean = true,
    val lastFmMinTrackDurationSeconds: Int = 30,
    val lastFmScrobbleDelayPercent: Float = 0.5f,
    val lastFmScrobbleDelaySeconds: Int = 180,
    val lastFmEndpoint: String = "https://ws.audioscrobbler.com/2.0/",
    val countPartyListeningInStatistics: Boolean = true,
    val keybinds: List<DesktopKeybind> = emptyList(),
)

/**
 * Atomic, process-local concurrent-safe settings storage. Pass a file under the host's config
 * directory (for example, `AppPaths.config.resolve("desktop-settings.properties")`).
 */
class DesktopSettingsStore(file: Path) : AutoCloseable {
    private val file = file.absolute().normalize()
    private val shared = states.computeIfAbsent(this.file) { SharedState(load()) }

    /** Cheap process-local snapshot; disk is read once when this settings path is first opened. */
    fun snapshot(): DesktopSettings = shared.lock.withLock { shared.current }

    /** Explicit save remains synchronous for callers that require a durability boundary. */
    fun save(settings: DesktopSettings) = shared.lock.withLock {
        shared.current = settings.sanitized()
        shared.pendingWrite?.cancel(false)
        shared.pendingWrite = null
        write(shared.current)
    }

    /**
     * Applies the update in memory immediately and coalesces disk persistence. All store instances
     * for the same path share [shared], so concurrent process-local updates cannot overwrite each
     * other even though the filesystem write happens later on the daemon flusher.
     */
    fun update(transform: (DesktopSettings) -> DesktopSettings): DesktopSettings = shared.lock.withLock {
        shared.current = transform(shared.current).sanitized()
        scheduleWriteLocked()
        shared.current
    }

    /** Flushes the latest in-memory state synchronously during orderly application shutdown. */
    override fun close() = shared.lock.withLock {
        shared.pendingWrite?.cancel(false)
        shared.pendingWrite = null
        write(shared.current)
    }

    private fun scheduleWriteLocked() {
        shared.pendingWrite?.cancel(false)
        shared.pendingWrite = flusher.schedule(
            {
                shared.lock.withLock {
                    shared.pendingWrite = null
                    write(shared.current)
                }
            },
            WRITE_DEBOUNCE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun load(): DesktopSettings {
        if (!Files.isRegularFile(file)) return DesktopSettings()
        return runCatching {
            val values = Properties().apply { file.inputStream().use(::load) }
            DesktopSettings(
                themeMode = values.enumValue("appearance.theme", DesktopThemeMode.SYSTEM),
                accentSeedArgb = values.getProperty("appearance.accentArgb")?.toLongOrNull(),
                beatSyncedVisualsEnabled = values.getProperty("appearance.beatSyncedVisuals.enabled")
                    ?.toBooleanStrictOrNull()
                    ?: true,
                beatSyncedVisualsIntensity = values.getProperty("appearance.beatSyncedVisuals.intensity")
                    ?.toFloatOrNull()
                    ?: 0.75f,
                volume = values.getProperty("playback.volume")?.toFloatOrNull() ?: 1f,
                speed = values.getProperty("playback.speed")?.toFloatOrNull() ?: 1f,
                pitchSemitones = values.getProperty("playback.pitchSemitones")?.toFloatOrNull() ?: 0f,
                loudnessNormalizationEnabled = values.getProperty("playback.loudnessNormalization")?.toBooleanStrictOrNull() ?: false,
                crossfadeEnabled = values.getProperty("playback.crossfade.enabled")?.toBooleanStrictOrNull() ?: false,
                crossfadeDurationSeconds = values.getProperty("playback.crossfade.durationSeconds")?.toFloatOrNull() ?: 5f,
                crossfadeGaplessAlbumTransitions = values.getProperty("playback.crossfade.gaplessAlbumTransitions")?.toBooleanStrictOrNull() ?: true,
                repeatMode = values.enumValue("playback.repeatMode", DesktopRepeatMode.OFF),
                shuffleEnabled = values.getProperty("playback.shuffle")?.toBooleanStrictOrNull() ?: false,
                equalizer = DesktopEqualizerSettings(
                    enabled = values.getProperty("playback.equalizer.enabled")?.toBooleanStrictOrNull() ?: false,
                    presetName = values.getProperty("playback.equalizer.preset"),
                    preampDb = values.getProperty("playback.equalizer.preampDb")?.toFloatOrNull() ?: 0f,
                    bandGainsDb = readFloats(values, "playback.equalizer.band", MAX_EQUALIZER_BANDS),
                ),
                audioOutputDeviceId = values.getProperty("playback.audioOutputDevice"),
                lastDestination = values.enumValue("navigation.lastDestination", DesktopLastDestination.HOME),
                windowPlacement = DesktopWindowPlacement(
                    widthDp = values.getProperty("window.widthDp")?.toFloatOrNull() ?: 1_280f,
                    heightDp = values.getProperty("window.heightDp")?.toFloatOrNull() ?: 800f,
                    xDp = values.getProperty("window.xDp")?.toFloatOrNull(),
                    yDp = values.getProperty("window.yDp")?.toFloatOrNull(),
                    maximized = values.getProperty("window.maximized")?.toBooleanStrictOrNull() ?: false,
                ),
                libraryFolders = readPaths(values, "library.folder"),
                downloadLocation = values.getProperty("downloads.location")?.toPathOrNull(),
                downloadConcurrency = values.getProperty("downloads.concurrency")?.toIntOrNull() ?: 2,
                partialCleanupPolicy = values.enumValue("downloads.partialCleanup", DesktopPartialCleanupPolicy.DELETE_ON_CANCEL),
                lyricsProviderIds = readStrings(values, "lyrics.provider"),
                lyricsTranslationProvider = values.enumValue("lyrics.translation.provider", DesktopLyricsTranslationProvider.GOOGLE),
                lyricsTranslationTargetLanguage = values.getProperty("lyrics.translation.targetLanguage") ?: "en",
                lyricsTranslationEndpoint = values.getProperty("lyrics.translation.endpoint") ?: "https://api.openai.com/v1/chat/completions",
                lyricsTranslationModel = values.getProperty("lyrics.translation.model") ?: "gpt-4o-mini",
                lyricsTranslationCustomPrompt = values.getProperty("lyrics.translation.customPrompt") ?: "",
                sponsorBlockEnabled = values.getProperty("playback.sponsorBlock.enabled")?.toBooleanStrictOrNull() ?: true,
                sponsorBlockCategories = values.getProperty("playback.sponsorBlock.categories")
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(DEFAULT_DESKTOP_SPONSOR_BLOCK_CATEGORIES::contains)
                    ?.toSet()
                    ?: DEFAULT_DESKTOP_SPONSOR_BLOCK_CATEGORIES,
                sponsorBlockApiUrl = values.getProperty("playback.sponsorBlock.apiUrl") ?: DEFAULT_DESKTOP_SPONSOR_BLOCK_API_URL,
                desktopCanvasEnabled = values.getProperty("playback.canvas.enabled")?.toBooleanStrictOrNull() ?: false,
                desktopCanvasSource = values.enumValue("playback.canvas.source", DesktopCanvasSource.ALL),
                desktopCanvasCacheLimitMb = values.getProperty("playback.canvas.cacheLimitMb")?.toIntOrNull() ?: 256,
                lastFmEnabled = values.getProperty("integrations.lastfm.enabled")?.toBooleanStrictOrNull() ?: false,
                lastFmNowPlayingEnabled = values.getProperty("integrations.lastfm.nowPlaying")?.toBooleanStrictOrNull() ?: true,
                lastFmMinTrackDurationSeconds = values.getProperty("integrations.lastfm.minTrackDurationSeconds")?.toIntOrNull() ?: 30,
                lastFmScrobbleDelayPercent = values.getProperty("integrations.lastfm.scrobbleDelayPercent")?.toFloatOrNull() ?: 0.5f,
                lastFmScrobbleDelaySeconds = values.getProperty("integrations.lastfm.scrobbleDelaySeconds")?.toIntOrNull() ?: 180,
                lastFmEndpoint = values.getProperty("integrations.lastfm.endpoint") ?: "https://ws.audioscrobbler.com/2.0/",
                countPartyListeningInStatistics = values.getProperty("statistics.countPartyListening")
                    ?.toBooleanStrictOrNull()
                    ?: true,
                keybinds = readKeybinds(values),
            ).sanitized()
        }.getOrDefault(DesktopSettings())
    }

    private fun write(settings: DesktopSettings) {
        val parent = checkNotNull(file.parent) { "Settings file must have a parent directory" }
        Files.createDirectories(parent)
        val values = Properties().apply {
            setProperty("appearance.theme", settings.themeMode.name)
            settings.accentSeedArgb?.let { setProperty("appearance.accentArgb", it.toString()) }
            setProperty("appearance.beatSyncedVisuals.enabled", settings.beatSyncedVisualsEnabled.toString())
            setProperty("appearance.beatSyncedVisuals.intensity", settings.beatSyncedVisualsIntensity.toString())
            setProperty("playback.volume", settings.volume.toString())
            setProperty("playback.speed", settings.speed.toString())
            setProperty("playback.pitchSemitones", settings.pitchSemitones.toString())
            setProperty("playback.loudnessNormalization", settings.loudnessNormalizationEnabled.toString())
            setProperty("playback.crossfade.enabled", settings.crossfadeEnabled.toString())
            setProperty("playback.crossfade.durationSeconds", settings.crossfadeDurationSeconds.toString())
            setProperty("playback.crossfade.gaplessAlbumTransitions", settings.crossfadeGaplessAlbumTransitions.toString())
            setProperty("playback.repeatMode", settings.repeatMode.name)
            setProperty("playback.shuffle", settings.shuffleEnabled.toString())
            setProperty("playback.equalizer.enabled", settings.equalizer.enabled.toString())
            settings.equalizer.presetName?.let { setProperty("playback.equalizer.preset", it) }
            setProperty("playback.equalizer.preampDb", settings.equalizer.preampDb.toString())
            settings.equalizer.bandGainsDb.forEachIndexed { index, gain ->
                setProperty("playback.equalizer.band.$index", gain.toString())
            }
            settings.audioOutputDeviceId?.let { setProperty("playback.audioOutputDevice", it) }
            setProperty("navigation.lastDestination", settings.lastDestination.name)
            setProperty("window.widthDp", settings.windowPlacement.widthDp.toString())
            setProperty("window.heightDp", settings.windowPlacement.heightDp.toString())
            settings.windowPlacement.xDp?.let { setProperty("window.xDp", it.toString()) }
            settings.windowPlacement.yDp?.let { setProperty("window.yDp", it.toString()) }
            setProperty("window.maximized", settings.windowPlacement.maximized.toString())
            settings.libraryFolders.forEachIndexed { index, path -> setProperty("library.folder.$index", path.toString()) }
            settings.downloadLocation?.let { setProperty("downloads.location", it.toString()) }
            setProperty("downloads.concurrency", settings.downloadConcurrency.toString())
            setProperty("downloads.partialCleanup", settings.partialCleanupPolicy.name)
            settings.lyricsProviderIds.forEachIndexed { index, id -> setProperty("lyrics.provider.$index", id) }
            setProperty("lyrics.translation.provider", settings.lyricsTranslationProvider.name)
            setProperty("lyrics.translation.targetLanguage", settings.lyricsTranslationTargetLanguage)
            setProperty("lyrics.translation.endpoint", settings.lyricsTranslationEndpoint)
            setProperty("lyrics.translation.model", settings.lyricsTranslationModel)
            setProperty("lyrics.translation.customPrompt", settings.lyricsTranslationCustomPrompt)
            setProperty("playback.sponsorBlock.enabled", settings.sponsorBlockEnabled.toString())
            setProperty("playback.sponsorBlock.categories", settings.sponsorBlockCategories.sorted().joinToString(","))
            setProperty("playback.sponsorBlock.apiUrl", settings.sponsorBlockApiUrl)
            setProperty("playback.canvas.enabled", settings.desktopCanvasEnabled.toString())
            setProperty("playback.canvas.source", settings.desktopCanvasSource.name)
            setProperty("playback.canvas.cacheLimitMb", settings.desktopCanvasCacheLimitMb.toString())
            setProperty("integrations.lastfm.enabled", settings.lastFmEnabled.toString())
            setProperty("integrations.lastfm.nowPlaying", settings.lastFmNowPlayingEnabled.toString())
            setProperty("integrations.lastfm.minTrackDurationSeconds", settings.lastFmMinTrackDurationSeconds.toString())
            setProperty("integrations.lastfm.scrobbleDelayPercent", settings.lastFmScrobbleDelayPercent.toString())
            setProperty("integrations.lastfm.scrobbleDelaySeconds", settings.lastFmScrobbleDelaySeconds.toString())
            setProperty("integrations.lastfm.endpoint", settings.lastFmEndpoint)
            setProperty("statistics.countPartyListening", settings.countPartyListeningInStatistics.toString())
            settings.keybinds.forEachIndexed { index, binding ->
                setProperty("keybind.$index.action", binding.action.name)
                setProperty("keybind.$index.keys", binding.virtualKeys.joinToString(","))
                setProperty("keybind.$index.repeat", binding.repeatWhileHeld.toString())
            }
        }
        val temporary = Files.createTempFile(parent, ".${file.fileName}.", ".tmp")
        try {
            temporary.outputStream().use { output -> values.store(output, "ArchiveTune desktop settings") }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun DesktopSettings.sanitized(): DesktopSettings = copy(
        accentSeedArgb = accentSeedArgb?.takeIf { it in 0..ARGB_MAX },
        beatSyncedVisualsIntensity = beatSyncedVisualsIntensity.finiteOr(0.75f).coerceIn(0f, 1f),
        volume = volume.finiteOr(1f).coerceIn(0f, 1f),
        speed = speed.finiteOr(1f).coerceIn(0.25f, 4f),
        pitchSemitones = pitchSemitones.finiteOr(0f).coerceIn(-12f, 12f),
        crossfadeDurationSeconds = crossfadeDurationSeconds.finiteOr(5f).coerceIn(0f, 10f),
        equalizer = equalizer.sanitized(),
        audioOutputDeviceId = audioOutputDeviceId?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_AUDIO_OUTPUT_DEVICE_ID_LENGTH),
        downloadConcurrency = downloadConcurrency.coerceIn(1, 8),
        windowPlacement = windowPlacement.sanitized(),
        libraryFolders = libraryFolders.map(Path::absolute).map(Path::normalize).distinct().take(MAX_LIBRARY_FOLDERS),
        downloadLocation = downloadLocation?.absolute()?.normalize(),
        lyricsProviderIds = lyricsProviderIds.map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot(LEGACY_REMOVED_LYRICS_PROVIDER_IDS::contains)
            .distinct()
            .take(MAX_LYRICS_PROVIDERS),
        lyricsTranslationTargetLanguage = lyricsTranslationTargetLanguage.trim().takeIf(String::isNotEmpty)?.take(MAX_TRANSLATION_LANGUAGE_LENGTH) ?: "en",
        lyricsTranslationEndpoint = normalizeSettingsHttpsUrl(lyricsTranslationEndpoint) ?: DEFAULT_TRANSLATION_ENDPOINT,
        lyricsTranslationModel = lyricsTranslationModel.trim().takeIf(String::isNotEmpty)?.take(MAX_TRANSLATION_MODEL_LENGTH) ?: "gpt-4o-mini",
        lyricsTranslationCustomPrompt = lyricsTranslationCustomPrompt.take(MAX_TRANSLATION_PROMPT_LENGTH),
        sponsorBlockCategories = sponsorBlockCategories.filterTo(linkedSetOf(), DEFAULT_DESKTOP_SPONSOR_BLOCK_CATEGORIES::contains),
        sponsorBlockApiUrl = normalizeSettingsHttpsUrl(sponsorBlockApiUrl) ?: DEFAULT_DESKTOP_SPONSOR_BLOCK_API_URL,
        desktopCanvasCacheLimitMb = sanitizeDesktopCanvasCacheLimit(desktopCanvasCacheLimitMb),
        lastFmMinTrackDurationSeconds = lastFmMinTrackDurationSeconds.coerceIn(0, 600),
        lastFmScrobbleDelayPercent = lastFmScrobbleDelayPercent.finiteOr(0.5f).coerceIn(0.1f, 1f),
        lastFmScrobbleDelaySeconds = lastFmScrobbleDelaySeconds.coerceIn(1, 600),
        lastFmEndpoint = normalizeSettingsHttpsUrl(lastFmEndpoint)?.let { endpoint ->
            if (lastFmEndpoint.trim().endsWith("/")) "$endpoint/" else endpoint
        } ?: "https://ws.audioscrobbler.com/2.0/",
        keybinds = keybinds
            .mapNotNull { it.sanitizedOrNull() }
            .distinctBy { it.action },
    )

    private fun DesktopKeybind.sanitizedOrNull(): DesktopKeybind? {
        val keys = virtualKeys.filter { it in MIN_WINDOWS_VIRTUAL_KEY..MAX_WINDOWS_VIRTUAL_KEY }.distinct()
        return takeIf { keys.isNotEmpty() }?.copy(
            virtualKeys = keys,
            repeatWhileHeld = repeatWhileHeld && action.supportsHoldRepeat,
        )
    }

    private fun DesktopWindowPlacement.sanitized(): DesktopWindowPlacement = copy(
        widthDp = widthDp.finiteOr(1_280f).coerceIn(640f, 5_120f),
        heightDp = heightDp.finiteOr(800f).coerceIn(480f, 2_880f),
        xDp = xDp?.takeIf(Float::isFinite),
        yDp = yDp?.takeIf(Float::isFinite),
    )

    private fun DesktopEqualizerSettings.sanitized(): DesktopEqualizerSettings = copy(
        presetName = presetName?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_EQUALIZER_PRESET_LENGTH),
        preampDb = preampDb.finiteOr(0f).coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB),
        bandGainsDb = bandGainsDb.take(MAX_EQUALIZER_BANDS).map {
            it.finiteOr(0f).coerceIn(MIN_EQUALIZER_GAIN_DB, MAX_EQUALIZER_GAIN_DB)
        },
    )

    private fun Float.finiteOr(default: Float): Float = takeIf(Float::isFinite) ?: default

    private fun <T : Enum<T>> Properties.enumValue(key: String, default: T): T {
        val persistedName = getProperty(key) ?: return default
        // Release shrinking must not be allowed to turn one enum-backed preference into a full
        // settings reset. If an optimizer has stripped enum metadata, Class#getEnumConstants may
        // legally return null from our point of view even though Kotlin exposes it as a platform
        // type. Treat that preference as unreadable and preserve the remaining settings instead of
        // throwing out of load() and falling back to an all-default DesktopSettings instance.
        val constants = default.declaringJavaClass.enumConstants ?: return default
        return constants.firstOrNull { it.name == persistedName } ?: default
    }

    private fun readPaths(values: Properties, prefix: String): List<Path> =
        (0 until MAX_LIBRARY_FOLDERS)
            .mapNotNull { values.getProperty("$prefix.$it")?.toPathOrNull() }

    private fun readStrings(values: Properties, prefix: String): List<String> =
        (0 until MAX_LYRICS_PROVIDERS)
            .mapNotNull { values.getProperty("$prefix.$it")?.trim()?.takeIf(String::isNotEmpty) }

    private fun readFloats(values: Properties, prefix: String, limit: Int): List<Float> =
        (0 until limit).mapNotNull { values.getProperty("$prefix.$it")?.toFloatOrNull() }

    private fun readKeybinds(values: Properties): List<DesktopKeybind> =
        (0 until DesktopKeybindAction.entries.size).mapNotNull { index ->
            val actionName = values.getProperty("keybind.$index.action") ?: return@mapNotNull null
            val action = DesktopKeybindAction.entries.firstOrNull { it.name == actionName } ?: return@mapNotNull null
            val keys = values.getProperty("keybind.$index.keys")
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                .orEmpty()
            DesktopKeybind(
                action = action,
                virtualKeys = keys,
                repeatWhileHeld = values.getProperty("keybind.$index.repeat")?.toBooleanStrictOrNull() ?: false,
            )
        }

    private fun String.toPathOrNull(): Path? = runCatching { Path.of(this).absolute().normalize() }.getOrNull()

    private fun normalizeSettingsHttpsUrl(value: String): String? {
        val candidate = value.trim()
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (candidate.length > MAX_SETTINGS_URL_LENGTH) return null
        return candidate.trimEnd('/')
    }

    private fun sanitizeDesktopCanvasCacheLimit(value: Int): Int = when {
        value == 0 -> 0
        else -> value.coerceIn(64, 8_192)
    }

    private companion object {
        const val ARGB_MAX = 0xFFFF_FFFFL
        const val MAX_LIBRARY_FOLDERS = 64
        const val MAX_LYRICS_PROVIDERS = 32
        val LEGACY_REMOVED_LYRICS_PROVIDER_IDS = setOf("paxsenix", "paxsenix-netease", "paxsenix-youtube")
        const val MAX_TRANSLATION_LANGUAGE_LENGTH = 64
        const val MAX_TRANSLATION_MODEL_LENGTH = 128
        const val MAX_TRANSLATION_PROMPT_LENGTH = 4_000
        const val MAX_SETTINGS_URL_LENGTH = 2_048
        const val DEFAULT_TRANSLATION_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val MAX_EQUALIZER_BANDS = 32
        const val MAX_EQUALIZER_PRESET_LENGTH = 80
        const val MIN_EQUALIZER_GAIN_DB = -20f
        const val MAX_EQUALIZER_GAIN_DB = 20f
        const val MAX_AUDIO_OUTPUT_DEVICE_ID_LENGTH = 1_024
        const val MIN_WINDOWS_VIRTUAL_KEY = 1
        const val MAX_WINDOWS_VIRTUAL_KEY = 0xFF
        const val WRITE_DEBOUNCE_MILLIS = 250L
        val states = ConcurrentHashMap<Path, SharedState>()
        val flusher = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "desktop-settings-flusher").apply { isDaemon = true }
        }
    }

    private class SharedState(initial: DesktopSettings) {
        val lock = ReentrantLock()
        var current: DesktopSettings = initial
        var pendingWrite: ScheduledFuture<*>? = null
    }
}
