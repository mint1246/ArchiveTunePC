package moe.rukamori.archivetune.desktop.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.desktopauth.SecureSessionStore

class DesktopPaxsenixCredentialServiceTest {
    @Test
    fun `setting and clearing credentials updates configured state`() {
        val store = MemoryCredentials()
        val service = DesktopPaxsenixCredentialService(store)
        try {
            service.setApiKey("  example-key  ")
            assertTrue(service.hasApiKey())
            assertContentEquals("example-key".toByteArray(), store.value)
            assertTrue(store.lastWrite!!.all { it == 0.toByte() })
            service.setApiKey(null)
            assertFalse(service.hasApiKey())
            assertNull(store.value)
        } finally {
            service.setApiKey(null)
        }
    }

    private class MemoryCredentials : SecureSessionStore {
        var value: ByteArray? = null
        var lastWrite: ByteArray? = null

        override fun read(): ByteArray? = value?.copyOf()

        override fun write(value: ByteArray) {
            lastWrite = value
            this.value = value.copyOf()
        }

        override fun delete() {
            value = null
        }
    }
}
