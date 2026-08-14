/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import java.nio.file.Path
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
) {
    fun createDirectories(): AppPaths = apply {
        listOf(config, data, database, cache, logs, downloads).forEach(Files::createDirectories)
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
                    fromRoots(
                        configRoot = roaming.resolve(AppDirectoryName),
                        dataRoot = local.resolve("$AppDirectoryName/data"),
                        cacheRoot = local.resolve("$AppDirectoryName/cache"),
                        logsRoot = local.resolve("$AppDirectoryName/logs"),
                        downloadsRoot = home.resolve("Music/$AppDirectoryName"),
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
        ): AppPaths =
            AppPaths(
                config = configRoot.absolute().normalize(),
                data = dataRoot.absolute().normalize(),
                database = dataRoot.resolve("database").absolute().normalize(),
                cache = cacheRoot.absolute().normalize(),
                logs = logsRoot.absolute().normalize(),
                downloads = downloadsRoot.absolute().normalize(),
            )

        private fun Map<String, String>.value(name: String): String? =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        private fun systemProperties(): Map<String, String> =
            System.getProperties().stringPropertyNames().associateWith(System::getProperty)
    }
}
