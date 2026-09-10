/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidStemMixStoreTest {
    @Test
    fun perTrackMix_roundTripsAcrossStoreInstances() {
        val root = Files.createTempDirectory("archivetune-stem-mix").toFile()
        try {
            val file = root.resolve("mixes.properties")
            val controls =
                AndroidStemControls()
                    .withControl(AndroidStemSource.VOCALS, AndroidStemChannelControl(gain = 0.35f, muted = true))
                    .withControl(AndroidStemSource.GUITAR, AndroidStemChannelControl(gain = 0.75f, solo = true))
            AndroidStemMixStore(file).save("track/with:odd?chars", controls)

            assertEquals(controls, AndroidStemMixStore(file).load("track/with:odd?chars"))
            assertEquals(AndroidStemControls(), AndroidStemMixStore(file).load("other-track"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun defaultMix_removesPersistedOverride() {
        val root = Files.createTempDirectory("archivetune-stem-clear").toFile()
        try {
            val store = AndroidStemMixStore(root.resolve("mixes.properties"))
            store.save(
                "track",
                AndroidStemControls().withControl(
                    AndroidStemSource.DRUMS,
                    AndroidStemChannelControl(gain = 0.2f),
                ),
            )
            store.save("track", AndroidStemControls())

            assertEquals(AndroidStemControls(), store.load("track"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oldestMixes_arePrunedToBoundedStore() {
        val root = Files.createTempDirectory("archivetune-stem-prune").toFile()
        try {
            var now = 10L
            val store = AndroidStemMixStore(root.resolve("mixes.properties"), maxEntries = 2) { now++ }
            val changed =
                AndroidStemControls().withControl(
                    AndroidStemSource.PIANO,
                    AndroidStemChannelControl(gain = 0.5f),
                )
            store.save("oldest", changed)
            store.save("middle", changed)
            store.save("newest", changed)

            assertEquals(AndroidStemControls(), store.load("oldest"))
            assertEquals(changed, store.load("middle"))
            assertEquals(changed, store.load("newest"))
        } finally {
            root.deleteRecursively()
        }
    }
}
