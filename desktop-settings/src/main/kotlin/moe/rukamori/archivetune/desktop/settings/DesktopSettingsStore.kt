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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.absolute
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

enum class DesktopThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class DesktopLastDestination {
    HOME,
    EXPLORE,
    SEARCH,
    LIBRARY,
    DOWNLOADS,
    SETTINGS,
}

data class DesktopWindowPlacement(
    val widthDp: Float = 1_280f,
    val heightDp: Float = 800f,
    val xDp: Float? = null,
    val yDp: Float? = null,
    val maximized: Boolean = false,
)

/** Non-secret desktop preferences. Paths are supplied by the host application. */
data class DesktopSettings(
    val themeMode: DesktopThemeMode = DesktopThemeMode.SYSTEM,
    /** Optional unsigned ARGB color value, stored as a Long to avoid UI toolkit dependencies. */
    val accentSeedArgb: Long? = null,
    val volume: Float = 1f,
    val speed: Float = 1f,
    val lastDestination: DesktopLastDestination = DesktopLastDestination.HOME,
    val windowPlacement: DesktopWindowPlacement = DesktopWindowPlacement(),
    val libraryFolders: List<Path> = emptyList(),
    val downloadLocation: Path? = null,
)

/**
 * Atomic, process-local concurrent-safe settings storage. Pass a file under the host's config
 * directory (for example, `AppPaths.config.resolve("desktop-settings.properties")`).
 */
class DesktopSettingsStore(file: Path) {
    private val file = file.absolute().normalize()
    private val lock = locks.computeIfAbsent(this.file) { ReentrantLock() }
    private var current = load()

    fun snapshot(): DesktopSettings = lock.withLock {
        current = load()
        current
    }

    fun save(settings: DesktopSettings) = lock.withLock {
        current = settings.sanitized()
        write(current)
    }

    /** Reads the latest saved state while locked, preventing lost updates between store instances. */
    fun update(transform: (DesktopSettings) -> DesktopSettings): DesktopSettings = lock.withLock {
        current = transform(load()).sanitized()
        write(current)
        current
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
            setProperty("navigation.lastDestination", settings.lastDestination.name)
            setProperty("window.widthDp", settings.windowPlacement.widthDp.toString())
            setProperty("window.heightDp", settings.windowPlacement.heightDp.toString())
            settings.windowPlacement.xDp?.let { setProperty("window.xDp", it.toString()) }
            settings.windowPlacement.yDp?.let { setProperty("window.yDp", it.toString()) }
            setProperty("window.maximized", settings.windowPlacement.maximized.toString())
            settings.libraryFolders.forEachIndexed { index, path -> setProperty("library.folder.$index", path.toString()) }
            settings.downloadLocation?.let { setProperty("downloads.location", it.toString()) }
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
        windowPlacement = windowPlacement.sanitized(),
        libraryFolders = libraryFolders.map(Path::absolute).map(Path::normalize).distinct().take(MAX_LIBRARY_FOLDERS),
        downloadLocation = downloadLocation?.absolute()?.normalize(),
    )

    private fun DesktopWindowPlacement.sanitized(): DesktopWindowPlacement = copy(
        widthDp = widthDp.finiteOr(1_280f).coerceIn(640f, 5_120f),
        heightDp = heightDp.finiteOr(800f).coerceIn(480f, 2_880f),
        xDp = xDp?.takeIf(Float::isFinite),
        yDp = yDp?.takeIf(Float::isFinite),
    )

    private fun Float.finiteOr(default: Float): Float = takeIf(Float::isFinite) ?: default

    private fun <T : Enum<T>> Properties.enumValue(key: String, default: T): T =
        default.declaringJavaClass.enumConstants.firstOrNull { it.name == getProperty(key) } ?: default

    private fun readPaths(values: Properties, prefix: String): List<Path> =
        (0 until MAX_LIBRARY_FOLDERS)
            .mapNotNull { values.getProperty("$prefix.$it")?.toPathOrNull() }

    private fun String.toPathOrNull(): Path? = runCatching { Path.of(this).absolute().normalize() }.getOrNull()

    private companion object {
        const val ARGB_MAX = 0xFFFF_FFFFL
        const val MAX_LIBRARY_FOLDERS = 64
        val locks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
