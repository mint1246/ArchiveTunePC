/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import java.nio.file.Files
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStemCacheTest {
    @Test
    fun generatedSixStemSet_installsAndValidatesPortableManifest() {
        val root = Files.createTempDirectory("archivetune-stem-cache").toFile()
        val generated = Files.createTempDirectory("archivetune-stem-generated").toFile()
        try {
            writeSixStems(generated)
            val cache = AndroidStemCache(root, minStemBytes = 8)
            val installed = cache.installGeneratedSet("song-id", 183_000L, "Song", generated)

            assertEquals("song-id", installed.trackId)
            assertEquals(183_000L, installed.durationMs)
            assertEquals("Song", installed.title)
            AndroidStemSource.workerOrder.forEach { source ->
                assertTrue(installed.file(source).isFile)
                assertTrue(installed.file(source).length() > 8L)
            }
            val manifest = Properties().apply {
                installed.directory.resolve("manifest.properties").inputStream().use(::load)
            }
            assertEquals(ANDROID_STEM_CACHE_FORMAT, manifest.getProperty("format"))
            assertEquals(ANDROID_STEM_CACHE_VERSION, manifest.getProperty("model"))
            assertEquals("drums,bass,other,vocals,guitar,piano", manifest.getProperty("stems"))
            assertEquals(installed.directory, cache.find("song-id", 183_000L)?.directory)
        } finally {
            root.deleteRecursively()
            generated.deleteRecursively()
        }
    }

    @Test
    fun incompleteOrCorruptCache_isNeverAdvertisedAsPlayable() {
        val root = Files.createTempDirectory("archivetune-stem-invalid").toFile()
        val generated = Files.createTempDirectory("archivetune-stem-invalid-generated").toFile()
        try {
            writeSixStems(generated)
            generated.resolve("piano.opus").delete()
            val cache = AndroidStemCache(root, minStemBytes = 8)
            val install = runCatching { cache.installGeneratedSet("song", 1_000L, null, generated) }
            assertTrue(install.isFailure)
            assertNull(cache.find("song", 1_000L))

            writeSixStems(generated)
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
        val root = Files.createTempDirectory("archivetune-stem-key").toFile()
        try {
            val cache = AndroidStemCache(root)
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

    private fun writeSixStems(directory: java.io.File) {
        AndroidStemSource.workerOrder.forEachIndexed { index, source ->
            directory.resolve("${source.cacheName}.opus").writeBytes(ByteArray(32) { (index + 1).toByte() })
        }
    }
}
