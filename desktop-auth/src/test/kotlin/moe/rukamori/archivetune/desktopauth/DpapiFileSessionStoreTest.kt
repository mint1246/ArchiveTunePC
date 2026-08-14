package moe.rukamori.archivetune.desktopauth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class DpapiFileSessionStoreTest {
    @Test
    fun `file store writes only protector output and deletes it on logout`() {
        val file = Files.createTempDirectory("archivetune-auth-").resolve("session.bin")
        val store =
            DpapiFileSessionStore(
                file,
                protector =
                    object : DpapiProtector {
                        override fun protect(plainText: ByteArray) = plainText.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
                        override fun unprotect(cipherText: ByteArray) = protect(cipherText)
                    },
            )
        val secret = "SAPISID=secret".encodeToByteArray()

        store.write(secret)

        assertFalse(Files.readAllBytes(file).decodeToString().contains("SAPISID=secret"))
        assertArrayEquals(secret, store.read())
        store.delete()
        assertNull(store.read())
    }
}
