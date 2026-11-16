package moe.rukamori.archivetune.together

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherOnlineApiTest {
    @Test
    fun `create session authenticates and decodes response headlessly`() = runBlocking {
        val request = CompletableDeferred<Pair<String?, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/together/sessions") { exchange ->
            request.complete(exchange.requestHeaders.getFirst("Authorization") to exchange.requestBody.bufferedReader().readText())
            val body = """{"sessionId":"session","code":"code","hostKey":"host","guestKey":"guest","wsUrl":"wss://example.invalid","settings":{}}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            TogetherOnlineApi("http://127.0.0.1:${server.address.port}", "test-token").use { api ->
                val response = api.createSession("Host", TogetherRoomSettings())
                val observed = withTimeout(5_000) { request.await() }
                assertEquals("Bearer test-token", observed.first)
                assertTrue(observed.second.contains("\"hostDisplayName\":\"Host\""))
                assertEquals("session", response.sessionId)
                assertEquals("guest", response.guestKey)
            }
        } finally {
            server.stop(0)
        }
    }
}
