/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.stems

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class StemMixStoreTest {
    @Test
    fun perTrackMix_roundTripsAcrossStoreInstances() {
        val root = Files.createTempDirectory("archivetune-sdk-stem-mix").toFile()
        try {
            val file = root.resolve("mixes.properties")
            val controls =
                StemControls()
                    .withControl(StemSource.VOCALS, StemChannelControl(gain = 0.35f, muted = true))
                    .withControl(StemSource.GUITAR, StemChannelControl(gain = 0.75f, solo = true))
                    .withControl(StemSource.PIANO, StemChannelControl(gain = 0.6f))
            StemMixStore(file).save("track/with:odd?chars", controls)

            assertEquals(controls, StemMixStore(file).load("track/with:odd?chars"))
            assertEquals(StemControls(), StemMixStore(file).load("other-track"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun defaultMix_removesPersistedOverride() {
        val root = Files.createTempDirectory("archivetune-sdk-stem-clear").toFile()
        try {
            val store = StemMixStore(root.resolve("mixes.properties"))
            store.save(
                "track",
                StemControls().withControl(
                    StemSource.DRUMS,
                    StemChannelControl(gain = 0.2f),
                ),
            )
            store.save("track", StemControls())

            assertEquals(StemControls(), store.load("track"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oldestMixes_arePrunedToBoundedStore() {
        val root = Files.createTempDirectory("archivetune-sdk-stem-prune").toFile()
        try {
            var now = 10L
            val store = StemMixStore(root.resolve("mixes.properties"), maxEntries = 2, nowMs = { now++ })
            val changed =
                StemControls().withControl(
                    StemSource.PIANO,
                    StemChannelControl(gain = 0.5f),
                )
            store.save("oldest", changed)
            store.save("middle", changed)
            store.save("newest", changed)

            assertEquals(StemControls(), store.load("oldest"))
            assertEquals(changed, store.load("middle"))
            assertEquals(changed, store.load("newest"))
        } finally {
            root.deleteRecursively()
        }
    }
}
