/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.sdk.settings

import moe.rukamori.archivetune.desktop.settings.DesktopKeybind
import moe.rukamori.archivetune.desktop.settings.DesktopKeybindAction
import moe.rukamori.archivetune.desktop.settings.DesktopSettings
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DesktopSettingsSessionTest {
    @Test
    fun inMemorySessionAppliesDomainMutationsWithoutDisk() {
        val session = DesktopSettingsSession.inMemory(
            DesktopSettings(lyricsProviderIds = listOf("lrclib")),
        )

        session.assignKeybind(DesktopKeybind(DesktopKeybindAction.PLAY_PAUSE, listOf(0x20)))
        session.setCrossfadeEnabled(true)
        session.setCrossfadeDurationSeconds(6.3f)
        session.applyEqualizerPreset("Flat", 0f, listOf(0f, 0f))
        session.updateEqualizerBand(bandIndex = 1, bandCount = 3, gainDb = 4f)

        val rejected = session.toggleLyricsProvider("lrclib", listOf("lrclib", "kugou"))
        assertEquals(LyricsProviderToggleResult.EmptySelectionNotAllowed, rejected)

        val current = session.snapshot()
        assertEquals(listOf(DesktopKeybind(DesktopKeybindAction.PLAY_PAUSE, listOf(0x20))), current.keybinds)
        assertTrue(current.crossfadeEnabled)
        assertEquals(6.5f, current.crossfadeDurationSeconds, 0.0001f)
        assertEquals(listOf(0f, 4f, 0f), current.equalizer.bandGainsDb)
        assertEquals(listOf("lrclib"), current.lyricsProviderIds)
    }

    @Test
    fun forStoreBorrowsStoreWithoutClosingItOnSessionClose() {
        val file = Files.createTempDirectory("archivetune-sdk-settings-").resolve("desktop-settings.properties")
        val store = DesktopSettingsStore(file)
        val session = DesktopSettingsSession.forStore(store)

        session.toggleSponsorBlockCategory("sponsor")
        session.setCrossfadeDurationSeconds(3.5f)
        session.close()

        // Borrowed store remains usable after session.close() and still owns its flush/close lifecycle.
        store.update { it.copy(volume = 0.7f) }
        store.close()

        val reloaded = DesktopSettingsStore(file).snapshot()
        assertFalse("sponsor" in reloaded.sponsorBlockCategories)
        assertEquals(3.5f, reloaded.crossfadeDurationSeconds, 0.0001f)
        assertEquals(0.7f, reloaded.volume, 0.0001f)
    }

    @Test
    fun ownedStoreBackendFlushesAndClosesStoreOnSessionClose() {
        val file = Files.createTempDirectory("archivetune-sdk-settings-owned-").resolve("desktop-settings.properties")
        val store = DesktopSettingsStore(file)
        val session = DesktopSettingsSession(StoreDesktopSettingsBackend(store, ownsStore = true))

        session.setCrossfadeDurationSeconds(4.5f)
        session.close()

        assertTrue(Files.exists(file))
        assertEquals(4.5f, DesktopSettingsStore(file).snapshot().crossfadeDurationSeconds, 0.0001f)
    }

    @Test
    fun sessionCloseDelegatesToAutoCloseableBackend() {
        var closedCount = 0
        val backend = object : DesktopSettingsBackend, AutoCloseable {
            private var current = DesktopSettings()
            override fun snapshot(): DesktopSettings = current
            override fun update(transform: (DesktopSettings) -> DesktopSettings): DesktopSettings {
                current = DesktopSettingsPolicy.sanitize(transform(current))
                return current
            }
            override fun save(settings: DesktopSettings) {
                current = DesktopSettingsPolicy.sanitize(settings)
            }
            override fun close() {
                closedCount += 1
            }
        }

        val session = DesktopSettingsSession(backend)
        session.close()
        assertEquals(1, closedCount)
    }
}
