package moe.rukamori.archivetune.social

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SocialLoginTest {
    @Test
    fun callbackRequiresMatchingNonceAndDecodesCode() {
        val callback = parseSocialLoginCallbackTarget(
            "/archivetune-auth?nonce=abc123&code=hello%2Bworld%2Fok", "abc123",
        )
        assertEquals("hello+world/ok", callback.code)
        assertNull(callback.error)
        assertFailsWith<IllegalArgumentException> {
            parseSocialLoginCallbackTarget("/archivetune-auth?nonce=wrong&code=hello", "abc123")
        }
    }

    @Test
    fun exchangesNonceCheckedLoopbackCallback() = runBlocking {
        val service = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        service.createContext("/v1/auth/exchange") { exchange ->
            val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val body = JSONObject().put("token", request.getString("code")).put("expiresAt", 12345).toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        service.start()
        try {
            val api = AndroidSocialApi("http://127.0.0.1:${service.address.port}")
            val login = AndroidSocialLogin(api, openBrowser = { url ->
                val parameters = URI(url).rawQuery.split('&').associate { part ->
                    val pair = part.split('=', limit = 2)
                    pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8.name())
                }
                launch(Dispatchers.IO) {
                    Socket("127.0.0.1", parameters.getValue("port").toInt()).use { socket ->
                        val target = "/archivetune-auth?nonce=${parameters.getValue("nonce")}&code=fixture-code"
                        socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                        socket.getInputStream().readBytes()
                    }
                }
            })
            val session = withTimeout(10_000) { login.signIn() }
            assertEquals("fixture-code", session.token)
            assertEquals(12345L, session.expiresAt)
        } finally {
            service.stop(0)
        }
    }
}
