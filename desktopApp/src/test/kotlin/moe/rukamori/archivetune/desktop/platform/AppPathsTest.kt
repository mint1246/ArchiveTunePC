/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
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
    fun `explicit home override keeps all mutable state under one controlled root`() {
        val paths = AppPaths.resolve(
            environment = mapOf("ARCHIVETUNE_HOME" to "C:/ArchiveTune-Test"),
            properties = mapOf("os.name" to "Windows 11", "user.home" to "C:/Users/listener"),
        )

        val normalized = paths.config.parent.toString().replace('\\', '/')
        assertEquals("C:/ArchiveTune-Test", normalized)
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
