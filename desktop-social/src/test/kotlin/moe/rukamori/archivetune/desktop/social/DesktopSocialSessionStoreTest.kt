package moe.rukamori.archivetune.desktop.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.desktopauth.SecureSessionStore

class DesktopSocialSessionStoreTest {
    @Test
    fun `social token round trips only through secure store bytes`() {
        val memory = MemorySecureStore()
        val store = DesktopSocialSessionStore(memory)
        val expected = StoredSocialSession("ats_secret", 123456789L)

        store.write(expected)

        assertEquals(expected, store.read())
        store.delete()
        assertNull(store.read())
    }

    private class MemorySecureStore : SecureSessionStore {
        var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) {
            this.value = value.copyOf()
        }
        override fun delete() {
            value = null
        }
    }
}
