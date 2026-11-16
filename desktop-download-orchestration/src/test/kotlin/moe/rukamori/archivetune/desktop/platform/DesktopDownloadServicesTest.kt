package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient

class DesktopDownloadServicesTest {
    @Test
    fun `headless service uses configured directory and persists directory changes`() {
        val root = Files.createTempDirectory("download-sdk-test")
        val initialDirectory = root.resolve("downloads")
        val replacementDirectory = root.resolve("new-downloads")
        val backend = ArchiveTuneJvmClient(root.resolve("backend-cache").toFile())
        DesktopSettingsStore(root.resolve("settings.json")).use { settings ->
            DesktopDownloadServices(settings, initialDirectory, backend).use { downloads ->
                assertEquals(initialDirectory, downloads.downloadDirectory)
                assertTrue(downloads.changeDirectory(replacementDirectory).isSuccess)
                assertEquals(replacementDirectory, downloads.downloadDirectory)
                assertEquals(replacementDirectory, settings.snapshot().downloadLocation)
                assertTrue(Files.isDirectory(replacementDirectory))
            }
        }
        DesktopSettingsStore(root.resolve("settings.json")).use { restored ->
            assertEquals(replacementDirectory, restored.snapshot().downloadLocation)
        }
    }
}
