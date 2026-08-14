/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

data class MpvConfiguration(
    val executable: String = MpvExecutableLocator.find(),
    val startupTimeoutMs: Long = 8_000L,
    val shutdownTimeoutMs: Long = 2_000L,
    val additionalArguments: List<String> = emptyList(),
)

object MpvExecutableLocator {
    /**
     * Lookup order supports development, packaged Compose distributions, and a system mpv install.
     * No developer-machine path is embedded in the application.
     */
    fun find(): String {
        System.getProperty("archivetune.mpv.path")
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        System.getenv("ARCHIVETUNE_MPV_PATH")
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        val appDirectory =
            System.getProperty("archivetune.app.dir")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: Path.of(System.getProperty("user.dir"))
        val composeResourcesDirectory =
            System.getProperty("compose.application.resources.dir")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
        val executableName = if (isWindows()) "mpv.exe" else "mpv"
        val architecture = normalizeArchitecture(System.getProperty("os.arch"))
        val os = normalizeOs(System.getProperty("os.name"))
        val candidates =
            listOfNotNull(
                // Compose Desktop exposes appResourcesRootDir here in packaged apps.
                composeResourcesDirectory?.let {
                    it.resolve("native").resolve("$os-$architecture").resolve(executableName)
                },
                appDirectory.resolve("native").resolve("$os-$architecture").resolve(executableName),
                appDirectory.resolve("resources").resolve("native").resolve("$os-$architecture").resolve(executableName),
                appDirectory.resolve("native").resolve(executableName),
                appDirectory.resolve(executableName),
            )
        return candidates.firstOrNull(Files::isRegularFile)?.toAbsolutePath()?.toString() ?: executableName
    }

    internal fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

    private fun normalizeOs(value: String): String =
        when {
            value.lowercase(Locale.ROOT).contains("win") -> "windows"
            value.lowercase(Locale.ROOT).contains("mac") -> "macos"
            else -> "linux"
        }

    private fun normalizeArchitecture(value: String): String =
        when (value.lowercase(Locale.ROOT)) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]"), "")
        }
}
