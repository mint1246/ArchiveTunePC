package example

import java.nio.file.Files
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import moe.rukamori.archivetune.sdk.settings.DesktopSettingsSession

fun main() {
    val directory = Files.createTempDirectory("archivetune-settings-example-")
    val file = directory.resolve("desktop-settings.properties")
    try {
        DesktopSettingsStore(file).use { store ->
            DesktopSettingsSession.forStore(store).use { settings ->
                settings.setCrossfadeEnabled(true)
                settings.setCrossfadeDurationSeconds(3.4f)
                val snapshot = settings.snapshot()
                check(snapshot.crossfadeEnabled)
                check(snapshot.crossfadeDurationSeconds == 3.5f)
            }
        }
        val properties = Files.readString(file)
        check("playback.crossfade.enabled=true" in properties)
        check("playback.crossfade.durationSeconds=3.5" in properties)
        println("Saved an enabled 3.5-second crossfade without launching the desktop app")
    } finally {
        Files.deleteIfExists(file)
        Files.delete(directory)
    }
}
