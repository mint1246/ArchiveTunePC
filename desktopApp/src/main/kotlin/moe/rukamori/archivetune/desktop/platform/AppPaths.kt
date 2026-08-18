/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.absolute
import kotlin.io.path.pathString

/** Platform-correct runtime locations. No mutable application data belongs beside the executable. */
data class AppPaths(
    val config: Path,
    val data: Path,
    val database: Path,
    val cache: Path,
    val logs: Path,
    val downloads: Path,
    internal val legacyData: Path? = null,
) {
    fun createDirectories(): AppPaths = apply {
        migrateLegacyDataIfNeeded()
        listOf(config, data, database, cache, logs, downloads).forEach(Files::createDirectories)
    }

    private fun migrateLegacyDataIfNeeded() {
        val source = legacyData?.absolute()?.normalize() ?: return
        if (source == data || !Files.isDirectory(source)) return

        val marker = data.resolve(".legacy-local-data-migrated")
        if (Files.exists(marker)) return

        try {
            Files.createDirectories(data)
            Files.walk(source).use { entries ->
                entries.forEach { sourcePath ->
                    val target = data.resolve(source.relativize(sourcePath))
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(target)
                    } else if (!Files.exists(target)) {
                        target.parent?.let(Files::createDirectories)
                        Files.copy(sourcePath, target)
                    }
                }
            }
            Files.writeString(
                marker,
                "ArchiveTune persistent data migrated from the legacy per-user install directory.",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        } catch (_: IOException) {
            // Best-effort compatibility path only. A bad legacy file must never prevent startup;
            // without the marker, the migration will retry on the next launch.
        }
    }

    fun logSummary(): String =
        "config=${config.pathString}, data=${data.pathString}, cache=${cache.pathString}, " +
            "logs=${logs.pathString}, downloads=${downloads.pathString}"

    companion object {
        private const val AppDirectoryName = "ArchiveTune"

        fun resolve(
            environment: Map<String, String> = System.getenv(),
            properties: Map<String, String> = systemProperties(),
        ): AppPaths {
            val home = Path.of(properties["user.home"].orEmpty().ifBlank { "." }).absolute()
            val override = environment.value("ARCHIVETUNE_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
            if (override != null) {
                val root = override.absolute()
                return fromRoots(
                    configRoot = root.resolve("config"),
                    dataRoot = root.resolve("data"),
                    cacheRoot = root.resolve("cache"),
                    logsRoot = root.resolve("logs"),
                    downloadsRoot = root.resolve("downloads"),
                )
            }

            return when {
                properties["os.name"].orEmpty().contains("Windows", ignoreCase = true) -> {
                    val roaming = environment.value("APPDATA")?.let(Path::of)
                        ?: home.resolve("AppData/Roaming")
                    val local = environment.value("LOCALAPPDATA")?.let(Path::of)
                        ?: home.resolve("AppData/Local")
                    // jpackage's default per-user install directory is %LOCALAPPDATA%/ArchiveTune.
                    // Never put mutable state there: an MSI major upgrade removes the previous
                    // product before installing the replacement and can therefore remove it too.
                    val persistentRoot = local.resolve("Rukamori/$AppDirectoryName")
                    fromRoots(
                        configRoot = roaming.resolve(AppDirectoryName),
                        dataRoot = persistentRoot.resolve("data"),
                        cacheRoot = persistentRoot.resolve("cache"),
                        logsRoot = persistentRoot.resolve("logs"),
                        downloadsRoot = home.resolve("Music/$AppDirectoryName"),
                        legacyDataRoot = local.resolve("$AppDirectoryName/data"),
                    )
                }

                properties["os.name"].orEmpty().contains("Mac", ignoreCase = true) -> {
                    val applicationSupport = home.resolve("Library/Application Support/$AppDirectoryName")
                    fromRoots(
                        configRoot = applicationSupport.resolve("config"),
                        dataRoot = applicationSupport.resolve("data"),
                        cacheRoot = home.resolve("Library/Caches/$AppDirectoryName"),
                        logsRoot = home.resolve("Library/Logs/$AppDirectoryName"),
                        downloadsRoot = home.resolve("Music/$AppDirectoryName"),
                    )
                }

                else -> {
                    val configHome = environment.value("XDG_CONFIG_HOME")?.let(Path::of)
                        ?: home.resolve(".config")
                    val dataHome = environment.value("XDG_DATA_HOME")?.let(Path::of)
                        ?: home.resolve(".local/share")
                    val cacheHome = environment.value("XDG_CACHE_HOME")?.let(Path::of)
                        ?: home.resolve(".cache")
                    val stateHome = environment.value("XDG_STATE_HOME")?.let(Path::of)
                        ?: home.resolve(".local/state")
                    fromRoots(
                        configRoot = configHome.resolve("archivetune"),
                        dataRoot = dataHome.resolve("archivetune"),
                        cacheRoot = cacheHome.resolve("archivetune"),
                        logsRoot = stateHome.resolve("archivetune/logs"),
                        downloadsRoot = home.resolve("Music/$AppDirectoryName"),
                    )
                }
            }
        }

        private fun fromRoots(
            configRoot: Path,
            dataRoot: Path,
            cacheRoot: Path,
            logsRoot: Path,
            downloadsRoot: Path,
            legacyDataRoot: Path? = null,
        ): AppPaths =
            AppPaths(
                config = configRoot.absolute().normalize(),
                data = dataRoot.absolute().normalize(),
                database = dataRoot.resolve("database").absolute().normalize(),
                cache = cacheRoot.absolute().normalize(),
                logs = logsRoot.absolute().normalize(),
                downloads = downloadsRoot.absolute().normalize(),
                legacyData = legacyDataRoot?.absolute()?.normalize(),
            )

        private fun Map<String, String>.value(name: String): String? =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        private fun systemProperties(): Map<String, String> =
            System.getProperties().stringPropertyNames().associateWith(System::getProperty)
    }
}
