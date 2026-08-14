/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppPathsTest {
    @Test
    fun `windows locations separate roaming configuration from local application data`() {
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
        assertTrue(paths.data.toString().replace('\\', '/').endsWith("AppData/Local/ArchiveTune/data"))
        assertTrue(paths.logs.toString().replace('\\', '/').endsWith("AppData/Local/ArchiveTune/logs"))
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
}
