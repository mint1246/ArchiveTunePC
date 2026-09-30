package moe.rukamori.archivetune.desktop.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamCacheSessionTest {
    @Test
    fun `resolved stream is cacheable only in the account session that started resolution`() {
        val originalSession = "account-a-fingerprint"
        val switchedSession = "account-b-fingerprint"

        assertTrue(isSameStreamCacheSession(originalSession, originalSession))
        assertFalse(isSameStreamCacheSession(originalSession, switchedSession))
    }
}
