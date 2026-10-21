/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppPathsTest {
    @Test
    fun `windows locations keep persistent state outside the per-user install directory`() {
        val paths = AppPaths.resolve(
            environment = mapOf(
                "APPDATA" to "C:/Users/listener/AppData/Roaming",
                "LOCALAPPDATA" to "C:/Users/listener/AppData/Local",
            ),
            properties = mapOf(
                "os.name" to "Windows 11",
                "user.home" to "C:/Users/listener",
            ),
        )

        assertTrue(paths.config.toString().replace('\\', '/').endsWith("AppData/Roaming/ArchiveTune"))
        assertTrue(paths.data.toString().replace('\\', '/').endsWith("AppData/Local/Rukamori/ArchiveTune/data"))
        assertTrue(paths.cache.toString().replace('\\', '/').endsWith("AppData/Local/Rukamori/ArchiveTune/cache"))
        assertTrue(paths.logs.toString().replace('\\', '/').endsWith("AppData/Local/Rukamori/ArchiveTune/logs"))
        assertTrue(paths.downloads.toString().replace('\\', '/').endsWith("Music/ArchiveTune"))
    }

    @Test
    fun `linux ignores empty or relative XDG paths instead of writing under cwd`() {
        val paths = AppPaths.resolve(
            environment = mapOf(
                "XDG_CONFIG_HOME" to "",
                "XDG_DATA_HOME" to "relative/data",
                "XDG_CACHE_HOME" to "/var/tmp/archivetune-cache",
                "XDG_STATE_HOME" to "  ",
            ),
            properties = mapOf("os.name" to "Linux", "user.home" to "/home/listener"),
        )

        assertEquals("/home/listener/.config/archivetune", paths.config.toString())
        assertEquals("/home/listener/.local/share/archivetune", paths.data.toString())
        assertEquals("/var/tmp/archivetune-cache/archivetune", paths.cache.toString())
        assertEquals("/home/listener/.local/state/archivetune/logs", paths.logs.toString())
    }

    @Test
    fun `explicit home override keeps all mutable state under one controlled root`() {
        val override = Path.of(System.getProperty("java.io.tmpdir"), "ArchiveTune-Test").toAbsolutePath().normalize()
        val paths = AppPaths.resolve(
            environment = mapOf("ARCHIVETUNE_HOME" to override.toString()),
            properties = mapOf("os.name" to "Windows 11", "user.home" to "C:/Users/listener"),
        )

        assertEquals(override, paths.config.parent)
        assertEquals(paths.config.parent, paths.data.parent)
        assertEquals(paths.data.parent, paths.cache.parent)
        assertEquals(paths.cache.parent, paths.logs.parent)
        assertEquals(paths.logs.parent, paths.downloads.parent)
    }

    @Test
    fun `legacy windows data is copied once into the persistent root`() {
        val root = Files.createTempDirectory("archivetune-paths-test")
        try {
            val legacy = root.resolve("legacy/data")
            val persistent = root.resolve("roaming/ArchiveTune/data")
            Files.createDirectories(legacy.resolve("database"))
            Files.writeString(legacy.resolve("youtube-session.dpapi"), "encrypted-session")
            Files.writeString(legacy.resolve("database/library.db"), "library")

            val paths = AppPaths(
                config = root.resolve("roaming/ArchiveTune"),
                data = persistent,
                database = persistent.resolve("database"),
                cache = root.resolve("local/cache"),
                logs = root.resolve("local/logs"),
                downloads = root.resolve("Music/ArchiveTune"),
                legacyData = legacy,
            ).createDirectories()

            assertEquals("encrypted-session", Files.readString(paths.data.resolve("youtube-session.dpapi")))
            assertEquals("library", Files.readString(paths.database.resolve("library.db")))
            assertTrue(Files.exists(paths.data.resolve(".legacy-local-data-migrated")))

            Files.writeString(paths.data.resolve("youtube-session.dpapi"), "new-session")
            paths.createDirectories()
            assertEquals("new-session", Files.readString(paths.data.resolve("youtube-session.dpapi")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
