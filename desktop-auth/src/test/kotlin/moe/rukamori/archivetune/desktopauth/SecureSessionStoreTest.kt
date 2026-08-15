package moe.rukamori.archivetune.desktopauth

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureSessionStoreTest {
    @Test
    fun `replacement leaves only the final ciphertext and cleans temporary file`() {
        val directory = Files.createTempDirectory("archivetune-session-store")
        try {
            val path = directory.resolve("session.bin")
            val store = DpapiFileSessionStore(path, IdentityProtector())

            store.write("first".toByteArray())
            store.write("second".toByteArray())

            assertArrayEquals("second".toByteArray(), requireNotNull(store.read()))
            assertTrue(Files.isRegularFile(path))
            assertFalse(
                Files.list(directory).use { files ->
                    files.anyMatch { file ->
                        val name = file.fileName.toString()
                        name.startsWith(".session.bin.") && name.endsWith(".tmp")
                    }
                },
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `protector failure keeps the previously committed ciphertext`() {
        val directory = Files.createTempDirectory("archivetune-session-store")
        try {
            val protector = IdentityProtector()
            val path = directory.resolve("session.bin")
            val store = DpapiFileSessionStore(path, protector)
            store.write("stable".toByteArray())
            protector.failProtect = true

            assertThrows(SecureStoreUnavailableException::class.java) {
                store.write("replacement".toByteArray())
            }
            assertArrayEquals("stable".toByteArray(), requireNotNull(store.read()))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class IdentityProtector : DpapiProtector {
        var failProtect: Boolean = false

        override fun protect(plainText: ByteArray): ByteArray {
            check(!failProtect) { "simulated DPAPI failure" }
            return plainText.copyOf()
        }

        override fun unprotect(cipherText: ByteArray): ByteArray = cipherText.copyOf()
    }
}
