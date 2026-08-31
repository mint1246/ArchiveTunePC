package moe.rukamori.archivetune.desktop.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.util.Properties
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
                lastDestination = DesktopLastDestination.STATISTICS,
                windowPlacement = DesktopWindowPlacement(1_440f, 900f, 40f, 50f, maximized = true),
                libraryFolders = listOf(root.resolve("Music"), root.resolve("Archive")),
                downloadLocation = root.resolve("Downloads"),
            )

        DesktopSettingsStore(file).save(expected)

        assertEquals(expected, DesktopSettingsStore(file).snapshot())
    }

    @Test
    fun accentDefaultsToBrandAndResetRemovesPersistedSeed() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        val store = DesktopSettingsStore(file)

        assertEquals(null, store.snapshot().accentSeedArgb)
        store.save(store.snapshot().copy(accentSeedArgb = 0xFF4D_7CFEL))
        assertEquals(0xFF4D_7CFEL, DesktopSettingsStore(file).snapshot().accentSeedArgb)

        store.save(store.snapshot().copy(accentSeedArgb = null))

        val persisted = Properties().also { values -> Files.newInputStream(file).use(values::load) }
        assertFalse(persisted.containsKey("appearance.accentArgb"))
        assertEquals(null, DesktopSettingsStore(file).snapshot().accentSeedArgb)
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

    @Test
    fun persistsDesktopPlaybackDownloadAndLyricsDefaults() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        val expected = DesktopSettings(
            repeatMode = DesktopRepeatMode.ALL,
            shuffleEnabled = true,
            audioOutputDeviceId = "headphones-device-id",
            loudnessNormalizationEnabled = true,
            crossfadeEnabled = true,
            crossfadeDurationSeconds = 7.5f,
            crossfadeGaplessAlbumTransitions = false,
            downloadConcurrency = 7,
            partialCleanupPolicy = DesktopPartialCleanupPolicy.KEEP_ON_CANCEL,
            lyricsProviderIds = listOf("lrclib", "betterlyrics"),
            countPartyListeningInStatistics = false,
            keybinds = listOf(
                DesktopKeybind(DesktopKeybindAction.VOLUME_UP, listOf(0x11, 0x12, 0x26), repeatWhileHeld = true),
                DesktopKeybind(DesktopKeybindAction.TOGGLE_LYRICS, listOf(0x11, 0x4C)),
            ),
        )

        DesktopSettingsStore(file).save(expected)

        assertEquals(expected, DesktopSettingsStore(file).snapshot())
        val persisted = Properties().also { values -> Files.newInputStream(file).use(values::load) }
        assertEquals("headphones-device-id", persisted.getProperty("playback.audioOutputDevice"))
        assertEquals("true", persisted.getProperty("playback.loudnessNormalization"))
        assertEquals("true", persisted.getProperty("playback.crossfade.enabled"))
        assertEquals("7.5", persisted.getProperty("playback.crossfade.durationSeconds"))
        assertEquals("false", persisted.getProperty("playback.crossfade.gaplessAlbumTransitions"))
        assertEquals("false", persisted.getProperty("statistics.countPartyListening"))
        assertEquals("VOLUME_UP", persisted.getProperty("keybind.0.action"))
        assertEquals("17,18,38", persisted.getProperty("keybind.0.keys"))
        assertEquals("true", persisted.getProperty("keybind.0.repeat"))
    }

    @Test
    fun sanitizesKeybindsWithoutArtificialChordLengthLimit() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        val longChord = (1..0xFF).toList()
        DesktopSettingsStore(file).save(
            DesktopSettings(
                keybinds = listOf(
                    DesktopKeybind(DesktopKeybindAction.VOLUME_DOWN, longChord + 0 + 300 + 0x11, repeatWhileHeld = true),
                    DesktopKeybind(DesktopKeybindAction.VOLUME_DOWN, listOf(0x41)),
                ),
            ),
        )

        val restored = DesktopSettingsStore(file).snapshot().keybinds
        assertEquals(1, restored.size)
        assertEquals((1..0xFF).toList(), restored.single().virtualKeys)
        assertEquals(true, restored.single().repeatWhileHeld)
    }

    @Test
    fun sanitizesUnsafeDownloadAndLyricsValues() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        DesktopSettingsStore(file).save(
            DesktopSettings(
                downloadConcurrency = 100,
                lyricsProviderIds = listOf("", "lrclib", "lrclib", "  "),
            ),
        )

        val restored = DesktopSettingsStore(file).snapshot()
        assertEquals(8, restored.downloadConcurrency)
        assertEquals(listOf("lrclib"), restored.lyricsProviderIds)
    }

    @Test
    fun sanitizesAudioOutputDeviceId() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        val store = DesktopSettingsStore(file)

        store.save(DesktopSettings(audioOutputDeviceId = "  headphones-device-id  "))
        assertEquals("headphones-device-id", store.snapshot().audioOutputDeviceId)
        assertEquals("headphones-device-id", DesktopSettingsStore(file).snapshot().audioOutputDeviceId)

        store.save(DesktopSettings(audioOutputDeviceId = "   "))
        assertEquals(null, store.snapshot().audioOutputDeviceId)

        store.save(DesktopSettings(audioOutputDeviceId = "x".repeat(2_000)))
        assertEquals(1_024, store.snapshot().audioOutputDeviceId?.length)
    }

    @Test
    fun sanitizesCrossfadeDurationToAndroidCompatibleRange() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        val store = DesktopSettingsStore(file)

        store.save(DesktopSettings(crossfadeDurationSeconds = 99f))
        assertEquals(10f, store.snapshot().crossfadeDurationSeconds, 0.0001f)

        store.save(DesktopSettings(crossfadeDurationSeconds = Float.NaN))
        assertEquals(5f, store.snapshot().crossfadeDurationSeconds, 0.0001f)
    }

    @Test
    fun beatSyncedVisualsPersistAndIntensityIsSanitized() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        val store = DesktopSettingsStore(file)

        store.save(
            DesktopSettings(
                beatSyncedVisualsEnabled = false,
                beatSyncedVisualsIntensity = 4f,
            ),
        )

        val restored = DesktopSettingsStore(file).snapshot()
        assertFalse(restored.beatSyncedVisualsEnabled)
        assertEquals(1f, restored.beatSyncedVisualsIntensity, 0.0001f)
        val persisted = Properties().also { values -> Files.newInputStream(file).use(values::load) }
        assertEquals("false", persisted.getProperty("appearance.beatSyncedVisuals.enabled"))
        assertEquals("1.0", persisted.getProperty("appearance.beatSyncedVisuals.intensity"))
    }

    @Test
    fun partyListeningStatisticsDefaultsOnForOlderSettingsAndPersistsOptOut() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        Files.writeString(file, "playback.volume=0.5\n")

        val store = DesktopSettingsStore(file)
        assertEquals(true, store.snapshot().countPartyListeningInStatistics)

        store.save(store.snapshot().copy(countPartyListeningInStatistics = false))

        val persisted = Properties().also { values -> Files.newInputStream(file).use(values::load) }
        assertEquals("false", persisted.getProperty("statistics.countPartyListening"))
        assertEquals(false, DesktopSettingsStore(file).snapshot().countPartyListeningInStatistics)
    }

    @Test
    fun closeFlushesPendingUpdateToDisk() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        val store = DesktopSettingsStore(file)

        store.update { it.copy(volume = 0.42f) }
        assertEquals(0.42f, store.snapshot().volume, 0.0001f)
        store.close()

        val persisted = Properties().also { values -> Files.newInputStream(file).use(values::load) }
        assertEquals("0.42", persisted.getProperty("playback.volume"))
    }

    @Test
    fun persistsAndSanitizesEqualizerSettings() {
        val file = Files.createTempDirectory("archivetune-settings-").resolve("settings.properties")
        val store = DesktopSettingsStore(file)
        store.save(
            DesktopSettings(
                equalizer = DesktopEqualizerSettings(
                    enabled = true,
                    presetName = "  Rock  ",
                    preampDb = Float.POSITIVE_INFINITY,
                    bandGainsDb = listOf(-99f, -3.5f, Float.NaN, 7.25f, 99f),
                ),
            ),
        )

        val restored = store.snapshot().equalizer
        assertEquals(true, restored.enabled)
        assertEquals("Rock", restored.presetName)
        assertEquals(0f, restored.preampDb, 0.0001f)
        assertEquals(listOf(-20f, -3.5f, 0f, 7.25f, 20f), restored.bandGainsDb)

        val persisted = Properties().also { values -> Files.newInputStream(file).use(values::load) }
        assertEquals("true", persisted.getProperty("playback.equalizer.enabled"))
        assertEquals("Rock", persisted.getProperty("playback.equalizer.preset"))
        assertEquals("7.25", persisted.getProperty("playback.equalizer.band.3"))
    }
}
