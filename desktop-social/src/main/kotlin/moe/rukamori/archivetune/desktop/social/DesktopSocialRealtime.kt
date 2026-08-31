package moe.rukamori.archivetune.desktop.social

import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class DesktopSocialRealtime(
    private val api: DesktopSocialApi,
    private val token: String,
    private val deviceId: String,
    private val onMessage: (String) -> Unit,
) : AutoCloseable {
    private val closed = CompletableDeferred<Unit>()
    private val isClosed = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null

    suspend fun connect() {
        val listener = Listener()
        val future = api.httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .header("authorization", "Bearer $token")
            .buildAsync(api.liveUri(deviceId), listener)
        socket = withContext(Dispatchers.IO) { future.get(25, TimeUnit.SECONDS) }
    }

    suspend fun awaitClose() = closed.await()

    fun publishPresence(presence: SocialPresence) {
        val value = JSONObject()
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
        socket?.sendText(value.toString(), true)
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "ArchiveTune social closing")
        closed.complete(Unit)
    }

    private inner class Listener : WebSocket.Listener {
        private val text = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val value = text.toString()
                text.setLength(0)
                onMessage(value)
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            socket = null
            closed.complete(Unit)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            socket = null
            closed.complete(Unit)
        }
    }
}
