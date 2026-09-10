/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import java.nio.file.Files
import moe.rukamori.archivetune.modelruntime.AndroidStemModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStemStorageTest {
    @Test
    fun measureAndClear_keepStemModelAndOtherModelsIndependent() {
        val root = Files.createTempDirectory("archivetune-android-stem-storage").toFile()
        val stems = root.resolve("stems")
        val models = root.resolve("models")
        try {
            stems.resolve("track-a").mkdirs()
            models.mkdirs()
            stems.resolve("track-a/vocals.wav").writeBytes(ByteArray(1_500))
            stems.resolve("track-a/drums.wav").writeBytes(ByteArray(2_500))
            models.resolve(AndroidStemModelSpec.FILE).writeBytes(ByteArray(4_000))
            models.resolve("chordmini-2e1d-v1.onnx").writeBytes(ByteArray(7_000))
            models.resolve(".${AndroidStemModelSpec.FILE}.download.part").writeBytes(ByteArray(500))

            val storage = AndroidStemStorage(stems, models)
            assertEquals(
                AndroidStemStorageUsage(stemsBytes = 4_000L, modelBytes = 4_500L),
                storage.measure(),
            )

            assertEquals(4_000L, storage.clearStems())
            assertTrue(stems.isDirectory)
            assertEquals(0L, storage.measure().stemsBytes)
            assertTrue(models.resolve(AndroidStemModelSpec.FILE).isFile)

            assertEquals(4_500L, storage.clearStemModel())
            assertFalse(models.resolve(AndroidStemModelSpec.FILE).exists())
            assertFalse(models.resolve(".${AndroidStemModelSpec.FILE}.download.part").exists())
            assertTrue(models.resolve("chordmini-2e1d-v1.onnx").isFile)
            assertEquals(0L, storage.measure().modelBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun formatStorageBytes_matchesDesktopPresentation() {
        assertEquals("0 B", formatAndroidStemStorageBytes(0L))
        assertEquals("1.0 KB", formatAndroidStemStorageBytes(1_024L))
        assertEquals("1.0 MB", formatAndroidStemStorageBytes(1_024L * 1_024L))
        assertEquals("1.00 GB", formatAndroidStemStorageBytes(1_024L * 1_024L * 1_024L))
    }
}
