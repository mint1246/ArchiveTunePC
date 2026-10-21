package moe.rukamori.archivetune.desktopauth

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses a temporary keyring entry containing synthetic bytes, never an existing account session. */
class SecretServiceIntegrationTest {
    @Test
    fun `Linux desktop keyring stores restores and deletes a session`() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux", ignoreCase = true))
        assumeTrue(System.getenv("ARCHIVETUNE_TEST_SECRET_SERVICE") == "1")
        val directory = Files.createTempDirectory("archivetune-keyring-smoke-")
        val path = directory.resolve("synthetic-session")
        val store = secureSessionStore(path)
        var written = false
        try {
            assertNull(store.read())
            val payload = byteArrayOf(0, 1, 2, 13, 10, -1, 127)
            store.write(payload)
            written = true
            assertArrayEquals(payload, store.read())
            assertFalse("Keyring session must not be written to a plaintext file", Files.exists(path))
            store.delete()
            written = false
            assertNull(store.read())
        } finally {
            try {
                if (written) store.delete()
            } finally {
                Files.deleteIfExists(directory)
            }
        }
    }
}
