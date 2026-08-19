/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.settings

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
    DOWNLOADS,
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
    val volume: Float = 1f,
    val speed: Float = 1f,
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
                volume = values.getProperty("playback.volume")?.toFloatOrNull() ?: 1f,
                speed = values.getProperty("playback.speed")?.toFloatOrNull() ?: 1f,
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
            ).sanitized()
        }.getOrDefault(DesktopSettings())
    }

    private fun write(settings: DesktopSettings) {
        val parent = checkNotNull(file.parent) { "Settings file must have a parent directory" }
        Files.createDirectories(parent)
        val values = Properties().apply {
            setProperty("appearance.theme", settings.themeMode.name)
            settings.accentSeedArgb?.let { setProperty("appearance.accentArgb", it.toString()) }
            setProperty("playback.volume", settings.volume.toString())
            setProperty("playback.speed", settings.speed.toString())
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
        volume = volume.finiteOr(1f).coerceIn(0f, 1f),
        speed = speed.finiteOr(1f).coerceIn(0.25f, 4f),
        equalizer = equalizer.sanitized(),
        audioOutputDeviceId = audioOutputDeviceId?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_AUDIO_OUTPUT_DEVICE_ID_LENGTH),
        downloadConcurrency = downloadConcurrency.coerceIn(1, 8),
        windowPlacement = windowPlacement.sanitized(),
        libraryFolders = libraryFolders.map(Path::absolute).map(Path::normalize).distinct().take(MAX_LIBRARY_FOLDERS),
        downloadLocation = downloadLocation?.absolute()?.normalize(),
        lyricsProviderIds = lyricsProviderIds.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_LYRICS_PROVIDERS),
    )

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

    private fun String.toPathOrNull(): Path? = runCatching { Path.of(this).absolute().normalize() }.getOrNull()

    private companion object {
        const val ARGB_MAX = 0xFFFF_FFFFL
        const val MAX_LIBRARY_FOLDERS = 64
        const val MAX_LYRICS_PROVIDERS = 32
        const val MAX_EQUALIZER_BANDS = 32
        const val MAX_EQUALIZER_PRESET_LENGTH = 80
        const val MIN_EQUALIZER_GAIN_DB = -20f
        const val MAX_EQUALIZER_GAIN_DB = 20f
        const val MAX_AUDIO_OUTPUT_DEVICE_ID_LENGTH = 1_024
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
