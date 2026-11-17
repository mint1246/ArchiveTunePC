/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AndroidSocialRealtime(
    private val api: SocialApi,
) : SocialRealtimeTransport {
    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                writeTimeout(15, TimeUnit.SECONDS)
                pingInterval(25, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        install(WebSockets) { pingIntervalMillis = 25_000 }
    }
    private val sendMutex = Mutex()

    @Volatile
    private var session: WebSocketSession? = null

    override suspend fun connect(
        token: String,
        deviceId: String,
        onConnected: suspend () -> Unit,
        onMessage: (String) -> Unit,
    ) {
        client.webSocket(
            urlString = api.liveUrl(deviceId),
            request = { header("Authorization", "Bearer $token") },
        ) {
            session = this
            try {
                onConnected()
                for (frame in incoming) {
                    if (frame is Frame.Text) onMessage(frame.readText())
                }
            } finally {
                if (session === this) session = null
            }
        }
    }

    override suspend fun publishPresence(presence: SocialPresence) {
        val payload = socialPresenceUpdateJson(presence).toString()
        sendMutex.withLock {
            session?.send(Frame.Text(payload))
        }
    }

    override fun disconnect() {
        session?.cancel(CancellationException("ArchiveTune social disconnect"))
        session = null
    }

    override fun close() {
        disconnect()
        client.close()
    }
}

fun socialPresenceUpdateJson(presence: SocialPresence): JSONObject =
    JSONObject()
        .put("type", "presence.update")
        .put(
            "presence",
            JSONObject()
                .putOpt("songId", presence.songId)
                .putOpt("title", presence.title)
                .putOpt("artist", presence.artist)
                .putOpt("artworkUrl", presence.artworkUrl)
                .put("partyJoinable", presence.partyJoinable),
        )
