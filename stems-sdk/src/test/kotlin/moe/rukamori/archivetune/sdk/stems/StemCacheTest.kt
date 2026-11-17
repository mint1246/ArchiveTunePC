/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.stems

import java.nio.file.Files
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StemCacheTest {
    @Test
    fun generatedSixStemSet_installsAndValidatesPortableManifestForOpusAndWav() {
        val root = Files.createTempDirectory("archivetune-sdk-stem-cache").toFile()
        val generatedOpus = Files.createTempDirectory("archivetune-sdk-stem-opus").toFile()
        val generatedWav = Files.createTempDirectory("archivetune-sdk-stem-wav").toFile()
        try {
            writeSixStems(generatedOpus, StemEncoding.OGG_OPUS)
            writeSixStems(generatedWav, StemEncoding.PCM16_WAV)
            val cache = StemCache(root, minStemBytes = 8)

            val opusEntry = cache.installGeneratedSet("song-opus", 183_000L, "Song Opus", generatedOpus)
            assertEquals(StemEncoding.OGG_OPUS, opusEntry.encoding)
            StemSource.workerOrder.forEach { source ->
                assertTrue(opusEntry.file(source).isFile)
                assertTrue(opusEntry.file(source).length() > 8L)
            }
            val opusManifest = Properties().apply {
                opusEntry.directory.resolve("manifest.properties").inputStream().use(::load)
            }
            assertEquals(STEM_OPUS_CACHE_FORMAT, opusManifest.getProperty("format"))
            assertEquals(STEM_CACHE_VERSION, opusManifest.getProperty("model"))
            assertEquals("drums,bass,other,vocals,guitar,piano", opusManifest.getProperty("stems"))

            val wavEntry = cache.installGeneratedSet("song-wav", 90_000L, "Song Wav", generatedWav)
            assertEquals(StemEncoding.PCM16_WAV, wavEntry.encoding)
            assertEquals(wavEntry.directory, cache.find("song-wav", 90_000L)?.directory)
        } finally {
            root.deleteRecursively()
            generatedOpus.deleteRecursively()
            generatedWav.deleteRecursively()
        }
    }

    @Test
    fun incompleteOrCorruptCache_isNeverAdvertisedAsPlayable() {
        val root = Files.createTempDirectory("archivetune-sdk-stem-invalid").toFile()
        val generated = Files.createTempDirectory("archivetune-sdk-stem-invalid-generated").toFile()
        try {
            writeSixStems(generated, StemEncoding.OGG_OPUS)
            generated.resolve("piano.opus").delete()
            val cache = StemCache(root, minStemBytes = 8)
            val install = runCatching { cache.installGeneratedSet("song", 1_000L, null, generated) }
            assertTrue(install.isFailure)
            assertNull(cache.find("song", 1_000L))

            writeSixStems(generated, StemEncoding.OGG_OPUS)
            val valid = cache.installGeneratedSet("song", 1_000L, null, generated)
            valid.directory.resolve("guitar.opus").writeBytes(byteArrayOf(1))
            assertNull(cache.find("song", 1_000L))
        } finally {
            root.deleteRecursively()
            generated.deleteRecursively()
        }
    }

    @Test
    fun cacheIdentity_changesWithTrackDurationAndIsStable() {
        val root = Files.createTempDirectory("archivetune-sdk-stem-key").toFile()
        try {
            val cache = StemCache(root)
            val first = cache.cacheKey("song", 123_000L)
            assertEquals(first, cache.cacheKey("song", 123_000L))
            assertNotEquals(first, cache.cacheKey("song", 124_000L))
            assertNotEquals(first, cache.cacheKey("other", 123_000L))
            assertEquals(32, first.length)
            assertFalse(first.contains("song"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeSixStems(directory: java.io.File, encoding: StemEncoding) {
        StemSource.workerOrder.forEachIndexed { index, source ->
            directory.resolve("${source.cacheName}.${encoding.extension}")
                .writeBytes(ByteArray(32) { (index + 1).toByte() })
        }
    }
}
