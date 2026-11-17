package moe.rukamori.archivetune.social

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class SocialApiTest {
    @Test
    fun fetchesAuthenticatedUserWithoutAndroid() = runBlocking {
        val authorization = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/me") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val body = """{"user":{"id":"user","discordId":"discord","username":"name","displayName":"Display"}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val api = AndroidSocialApi("http://127.0.0.1:${server.address.port}")
            val user = api.me("fixture-token")
            assertEquals("Display", user.name)
            assertEquals("Bearer fixture-token", authorization.get())
        } finally {
            server.stop(0)
        }
    }
}
