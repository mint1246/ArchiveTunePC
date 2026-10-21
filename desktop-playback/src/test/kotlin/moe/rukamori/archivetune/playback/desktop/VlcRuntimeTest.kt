/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VlcRuntimeTest {
    @Test
    fun `detects Windows and Linux without mistaking Darwin for Windows`() {
        assertEquals(VlcRuntimeOs.WINDOWS, VlcRuntimeOs.fromOsName("Windows 11"))
        assertEquals(VlcRuntimeOs.LINUX, VlcRuntimeOs.fromOsName("Linux"))
        assertEquals(VlcRuntimeOs.OTHER, VlcRuntimeOs.fromOsName("Darwin"))
    }

    @Test
    fun `linux libvlc resolves split library and plugin directories`() = withRuntimeDirectory { root ->
        Files.createFile(root.resolve("libvlc.so"))
        Files.createFile(root.resolve("libvlccore.so"))
        val pluginDirectory = Files.createDirectories(root.resolve("vlc/plugins"))

        val layout = assertNotNull(VlcRuntimeLocator.inspectDirectory(root, VlcRuntimeOs.LINUX))
        assertEquals(root.resolve("libvlc.so"), layout.libVlc)
        assertEquals(root.resolve("libvlccore.so"), layout.libVlcCore)
        assertEquals(pluginDirectory, layout.plugins)
        assertEquals(layout, VlcRuntimeLocator.find(VlcRuntimeConfiguration(directory = root)))
        assertNull(VlcRuntimeLocator.inspectDirectory(root, VlcRuntimeOs.WINDOWS))
    }

    @Test
    fun `linux runtime accepts versioned libraries and bundled plugins`() = withRuntimeDirectory { root ->
        Files.createFile(root.resolve("libvlc.so.5"))
        Files.createFile(root.resolve("libvlccore.so.9"))
        val plugins = Files.createDirectories(root.resolve("plugins"))
        val layout = assertNotNull(VlcRuntimeLocator.inspectDirectory(root, VlcRuntimeOs.LINUX))
        assertEquals(root.resolve("libvlc.so.5"), layout.libVlc)
        assertEquals(root.resolve("libvlccore.so.9"), layout.libVlcCore)
        assertEquals(plugins, layout.plugins)
    }

    @Test
    fun `windows runtime continues to require dlls and plugins`() = withRuntimeDirectory { root ->
        Files.createFile(root.resolve("libvlc.dll"))
        Files.createFile(root.resolve("libvlccore.dll"))
        assertNull(VlcRuntimeLocator.inspectDirectory(root, VlcRuntimeOs.WINDOWS))
        Files.createDirectories(root.resolve("plugins"))
        assertNotNull(VlcRuntimeLocator.inspectDirectory(root, VlcRuntimeOs.WINDOWS))
        assertNull(VlcRuntimeLocator.inspectDirectory(root, VlcRuntimeOs.LINUX))
    }

    @Test
    fun `native VLC audio output uses OS specific backend`() = withRuntimeDirectory { root ->
        val layout = VlcRuntimeLayout(root, root, root, root)
        assertTrue("--aout=directsound" in layout.mediaPlayerFactoryOptions(VlcRuntimeOs.WINDOWS))
        assertFalse(layout.mediaPlayerFactoryOptions(VlcRuntimeOs.LINUX).any { it.startsWith("--aout=") })
        assertFalse(layout.mediaPlayerFactoryOptions(VlcRuntimeOs.OTHER).any { it.startsWith("--aout=") })
    }

    @Test
    fun `private Windows VLC pitch ABI is inaccessible from Linux`() {
        if (VlcRuntimeOs.current() == VlcRuntimeOs.LINUX) {
            assertFalse(VlcPitchBridge.isSupportedOnCurrentPlatform())
        }
    }

    @Test
    fun `linux system libVLC is found when installed`() {
        if (VlcRuntimeOs.current() != VlcRuntimeOs.LINUX) return
        val systemLib = listOf(Path.of("/usr/lib/libvlc.so"), Path.of("/usr/lib/x86_64-linux-gnu/libvlc.so"))
        if (systemLib.none(Files::isRegularFile)) return
        val layout = assertNotNull(VlcRuntimeLocator.find())
        assertTrue(Files.isRegularFile(layout.libVlc))
        assertTrue(Files.isRegularFile(layout.libVlcCore))
        assertTrue(Files.isDirectory(layout.plugins))
    }

    private inline fun withRuntimeDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("archivetune-vlc-test-")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
