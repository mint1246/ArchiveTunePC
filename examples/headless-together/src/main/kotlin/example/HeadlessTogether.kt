package example

import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.together.TogetherClient
import moe.rukamori.archivetune.together.TogetherJoinInfo
import moe.rukamori.archivetune.together.TogetherRoomSettings
import moe.rukamori.archivetune.together.TogetherServer
import moe.rukamori.archivetune.together.TogetherServerEvent

fun main() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val joined = Channel<Unit>(capacity = 1)
    val clock = { System.nanoTime() / 1_000_000L }
    val port = ServerSocket(0).use { it.localPort }
    val server = TogetherServer(
        scope = scope,
        sessionId = "example-session",
        sessionKey = "local-example-key",
        hostDisplayName = "Host",
        initialSettings = TogetherRoomSettings(),
        elapsedRealtimeMs = clock,
    )
    val client = TogetherClient(scope, elapsedRealtimeMs = clock)
    server.onEvent = { event ->
        if (event is TogetherServerEvent.ParticipantJoined) joined.trySend(Unit)
    }
    try {
        server.start(port = port, host = "127.0.0.1")
        client.connect(TogetherJoinInfo("127.0.0.1", port, "example-session", "local-example-key"), "Guest")
        withTimeout(10_000L) { joined.receive() }
        println("Guest joined a local headless session")
    } finally {
        client.close()
        server.stop()
        scope.cancel()
        joined.close()
    }
}
