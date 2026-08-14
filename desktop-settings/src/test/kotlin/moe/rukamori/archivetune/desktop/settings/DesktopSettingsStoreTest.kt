package moe.rukamori.archivetune.desktop.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class DesktopSettingsStoreTest {
    @Test
    fun persistsTypedSettingsAcrossRestart() {
        val root = Files.createTempDirectory("archivetune-settings-")
        val file = root.resolve("config/settings.properties")
        val expected =
            DesktopSettings(
                themeMode = DesktopThemeMode.DARK,
                accentSeedArgb = 0xFF33_6699L,
                volume = 0.65f,
                speed = 1.25f,
                lastDestination = DesktopLastDestination.LIBRARY,
                windowPlacement = DesktopWindowPlacement(1_440f, 900f, 40f, 50f, maximized = true),
                libraryFolders = listOf(root.resolve("Music"), root.resolve("Archive")),
                downloadLocation = root.resolve("Downloads"),
            )

        DesktopSettingsStore(file).save(expected)

        assertEquals(expected, DesktopSettingsStore(file).snapshot())
    }

    @Test
    fun corruptedFileFallsBackToDefaults() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        Files.writeString(file, "not a properties file\\uZZZZ")

        assertEquals(DesktopSettings(), DesktopSettingsStore(file).snapshot())
    }

    @Test
    fun concurrentUpdatesFromSeparateStoresAreNotLost() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        val writers = 12
        val ready = CountDownLatch(writers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(writers)
        try {
            repeat(writers) {
                executor.submit {
                    val store = DesktopSettingsStore(file)
                    ready.countDown()
                    start.await()
                    store.update { settings -> settings.copy(volume = settings.volume - 0.01f) }
                }
            }
            ready.await()
            start.countDown()
        } finally {
            executor.shutdown()
            while (!executor.isTerminated) Thread.sleep(10)
        }

        assertEquals(0.88f, DesktopSettingsStore(file).snapshot().volume, 0.0001f)
        assertFalse(Files.list(file.parent).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".tmp") } })
    }
}
