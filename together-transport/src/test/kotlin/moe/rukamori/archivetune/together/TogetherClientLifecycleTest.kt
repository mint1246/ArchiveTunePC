package moe.rukamori.archivetune.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class TogetherClientLifecycleTest {
    @Test
    fun `closing is idempotent and prevents reuse`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val client = TogetherClient(scope, elapsedRealtimeMs = { 0L })
            client.close()
            client.close()
            assertThrows(IllegalStateException::class.java) {
                client.connect("ws://localhost", "session", "key", "Guest")
            }
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun `online host close is idempotent and prevents reuse`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = TogetherOnlineHost(scope, "session", "key", "host", "Host", TogetherRoomSettings())
            host.close()
            host.close()
            try {
                host.connect("ws://localhost")
                throw AssertionError("closed host accepted a connection")
            } catch (_: IllegalStateException) {
                // Expected: a terminally closed host must not start a new connection.
            }
        } finally {
            scope.cancel()
        }
    }
}
