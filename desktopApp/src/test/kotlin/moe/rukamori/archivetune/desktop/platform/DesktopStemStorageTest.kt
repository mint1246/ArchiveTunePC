package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopStemStorageTest {
    @Test
    fun `counts generated stems and model cache separately`() {
        val root = createTempDirectory("archivetune-stem-storage")
        try {
            val paths = AppPaths(
                config = root.resolve("config"),
                data = root.resolve("data"),
                database = root.resolve("data/database"),
                cache = root.resolve("cache"),
                logs = root.resolve("logs"),
                downloads = root.resolve("downloads"),
            )
            val stems = DesktopStemStorage.stemsDirectory(paths).resolve("track-a")
            val models = DesktopStemStorage.modelsDirectory(paths)
            Files.createDirectories(stems)
            Files.createDirectories(models)
            Files.write(stems.resolve("vocals.flac"), ByteArray(1_500))
            Files.write(stems.resolve("drums.flac"), ByteArray(2_500))
            Files.write(models.resolve("htdemucs.bin"), ByteArray(4_000))

            val usage = DesktopStemStorage.measure(paths)

            assertEquals(4_000L, usage.stemsBytes)
            assertEquals(4_000L, usage.modelsBytes)
            assertEquals(8_000L, usage.totalBytes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `formats storage sizes for the ui`() {
        assertEquals("0 B", formatStemStorageBytes(0))
        assertEquals("1.0 KB", formatStemStorageBytes(1024))
        assertEquals("1.0 MB", formatStemStorageBytes(1024L * 1024L))
        assertEquals("1.00 GB", formatStemStorageBytes(1024L * 1024L * 1024L))
    }
}
