package moe.rukamori.archivetune.desktopauth

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `linux secret service keeps session values out of process arguments and round trips bytes`() {
        val runner = FakeSecretToolRunner()
        val store = SecretServiceSessionStore(Files.createTempDirectory("secret-store-test").resolve("session.dpapi"), runner)
        val session = byteArrayOf(0, 1, 2, 13, 10, -1, 127, 0)
        store.write(session)
        assertArrayEquals(session, requireNotNull(store.read()))
        assertFalse(runner.commands.joinToString(" ").contains("session.dpapi"))
        assertFalse(runner.commands.joinToString(" ").contains("AAEC"))
        assertTrue(runner.commands.all { it.contains("application") && it.contains("session-key") })
        assertFalse(runner.commands.any { cmd -> cmd.any { it.contains("/tmp/") || it.contains("secret-store-test") } })
        store.delete()
        assertNull(store.read())
    }

    @Test
    fun `secret service lookup distinguishes missing entries from inaccessible keyring`() {
        val path = Files.createTempDirectory("secret-store-failure").resolve("session")
        val runner = FakeSecretToolRunner()
        val store = SecretServiceSessionStore(path, runner)
        assertNull(store.read())

        runner.unavailable = true
        assertThrows(SecureStoreUnavailableException::class.java) { store.read() }
        assertThrows(SecureStoreUnavailableException::class.java) { store.write(byteArrayOf(1)) }
        assertThrows(SecureStoreUnavailableException::class.java) { store.delete() }
    }

    @Test
    fun `linux uses the secret service backend and keeps Windows DPAPI unchanged`() {
        val path = Files.createTempDirectory("session-factory").resolve("session.dpapi")
        val store = secureSessionStore(path)
        if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
            assertTrue(store is SecretServiceSessionStore)
            assertFalse(Files.exists(path))
        } else if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            assertTrue(store is DpapiFileSessionStore)
        }
    }

    private class FakeSecretToolRunner : SecretToolRunner {
        var unavailable = false
        val commands = mutableListOf<List<String>>()
        private var stored: ByteArray? = null

        override fun execute(arguments: List<String>, input: ByteArray?): SecretToolResponse {
            commands.add(arguments)
            if (unavailable) return SecretToolResponse(status = 1, hadErrorOutput = true)
            return when (arguments.first()) {
                "store" -> {
                    stored = requireNotNull(input).copyOf()
                    SecretToolResponse(status = 0)
                }
                "lookup" -> stored?.let { SecretToolResponse(status = 0, output = it.copyOf()) }
                    ?: SecretToolResponse(status = 1)
                "clear" -> {
                    val hadValue = stored != null
                    stored = null
                    SecretToolResponse(status = if (hadValue) 0 else 1)
                }
                else -> error("Unknown secret-tool command")
            }
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
