package moe.rukamori.archivetune.social

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.net.ServerSocket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals

class SocialRealtimeTest {
    @Test
    fun exchangesPresenceAndMessagesOverWebSocket() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val received = Channel<String>(1)
        val messages = Channel<String>(1)
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/v1/live") {
                    received.send(call.request.headers["Authorization"].orEmpty())
                    send(Frame.Text("server-message"))
                    val frame = incoming.receive() as Frame.Text
                    received.send(frame.readText())
                }
            }
        }.start(wait = false)
        val transport = AndroidSocialRealtime(AndroidSocialApi("http://127.0.0.1:$port"))
        try {
            val connection = launch {
                transport.connect("fixture-token", "device", {
                    transport.publishPresence(SocialPresence(songId = "song", partyJoinable = true))
                }, { messages.trySend(it) })
            }
            withTimeout(10_000) {
                assertEquals("Bearer fixture-token", received.receive())
                assertEquals("server-message", messages.receive())
                val payload = JSONObject(received.receive())
                assertEquals("presence.update", payload.getString("type"))
                assertEquals("song", payload.getJSONObject("presence").getString("songId"))
                connection.join()
            }
        } finally {
            transport.close()
            server.stop(0, 1_000)
            received.close()
            messages.close()
        }
    }
}
