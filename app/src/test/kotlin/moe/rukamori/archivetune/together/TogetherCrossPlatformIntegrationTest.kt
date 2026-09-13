/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class TogetherCrossPlatformIntegrationTest {
    @Test
    fun desktopGuestJoinsAndroidHostAndUsesPermissionsOverRealWebSocket() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = Channel<TogetherServerEvent>(Channel.UNLIMITED)
        val server =
            TogetherServer(
                scope = scope,
                sessionId = SESSION_ID,
                sessionKey = SESSION_KEY,
                hostDisplayName = "Phone host",
                initialSettings = TogetherRoomSettings(),
                allowDesktopProtocol = true,
            ).also { it.onEvent = { event -> events.trySend(event) } }
        val port = freePort()
        val client = HttpClient(OkHttp) { install(WebSockets) }

        try {
            server.start(
                port = port,
                host = "127.0.0.1",
                webSocketPath = "/party",
                accessToken = SESSION_KEY,
            )

            client.webSocket("ws://127.0.0.1:$port/party?token=$SESSION_KEY") {
                val initialPermissions = receiveJsonOfType("permissions", timeoutMs = 5_000)
                assertTrue(initialPermissions["canEditQueue"]!!.jsonPrimitive.booleanOrNull == true)
                assertTrue(initialPermissions["canControlPlayback"]!!.jsonPrimitive.booleanOrNull == true)

                val joined = events.receiveServerEvent<TogetherServerEvent.ParticipantJoined>()
                assertFalse(joined.participant.isHost)

                val roomState =
                    TogetherRoomState(
                        sessionId = SESSION_ID,
                        hostId = "host",
                        queue = listOf(TRACK),
                        queueHash = "rev-1",
                        currentIndex = 0,
                        isPlaying = true,
                        positionMs = 12_345,
                        repeatMode = 2,
                    )
                server.broadcastRoomState(roomState)

                val full = receiveJsonOfType("full")
                assertEquals("rev-1", full["queueRevision"]!!.jsonPrimitive.contentOrNull)
                assertEquals("song-1", full["queue"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.contentOrNull)
                assertEquals(2, server.currentParticipants().size)

                send("""{"type":"set_playing","playWhenReady":false}""")
                val pause = events.receiveServerEvent<TogetherServerEvent.ControlRequested>()
                assertEquals(ControlAction.Pause, pause.request.action)

                send("""{"type":"queue_replace","startIndex":0,"items":[{"id":"song-2","title":"Second","artists":[{"id":"","name":"Artist"}],"durationHintMs":180000}]}""")
                assertNull(events.receiveMatchingOrNull<TogetherServerEvent.ReplaceQueueRequested>(350))

                val songChangeAllowed = TogetherGuestPermissions(canChangeSong = true)
                server.updateParticipantPermissions(joined.participant.id, songChangeAllowed)
                val allowedFrame = receiveJsonOfType("permissions")
                assertTrue(allowedFrame["canChangeSong"]!!.jsonPrimitive.booleanOrNull == true)

                send("""{"type":"queue_replace","startIndex":0,"items":[{"id":"song-2","title":"Second","artists":[{"id":"","name":"Artist"}],"durationHintMs":180000}]}""")
                val replacement = events.receiveServerEvent<TogetherServerEvent.ReplaceQueueRequested>()
                assertEquals("song-2", replacement.tracks.single().id)
                assertEquals(0, replacement.startIndex)

                val denied =
                    TogetherGuestPermissions(
                        canEditQueue = false,
                        canControlPlayback = false,
                        canChangeSong = false,
                        canSeek = false,
                    )
                server.updateParticipantPermissions(joined.participant.id, denied)
                val deniedFrame = receiveJsonOfType("permissions")
                assertFalse(deniedFrame["canControlPlayback"]!!.jsonPrimitive.booleanOrNull ?: true)

                send("""{"type":"set_playing","playWhenReady":true}""")
                assertNull(events.receiveMatchingOrNull<TogetherServerEvent.ControlRequested>(350))
            }

            events.receiveServerEvent<TogetherServerEvent.ParticipantLeft>()
            assertNull(events.receiveMatchingOrNull<TogetherServerEvent.Error>(250))
        } finally {
            client.close()
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun androidGuestJoinsDesktopHostAndControlsItOverRealWebSocket() = runBlocking {
        val serverFrames = Channel<JsonObject>(Channel.UNLIMITED)
        val port = freePort()
        val desktopServer =
            embeddedServer(CIO, port = port, host = "127.0.0.1") {
                install(io.ktor.server.websocket.WebSockets)
                routing {
                    webSocket("/party") {
                        send(
                            TogetherDesktopPartyProtocol.encodeFull(
                                TogetherRoomState(
                                    sessionId = SESSION_ID,
                                    hostId = TogetherDesktopPartyProtocol.DESKTOP_HOST_ID,
                                    queue = listOf(TRACK),
                                    queueHash = "desktop-rev",
                                    currentIndex = 0,
                                    isPlaying = true,
                                    positionMs = 4_200,
                                ),
                            ),
                        )
                        send(
                            TogetherDesktopPartyProtocol.encodePermissions(
                                TogetherGuestPermissions(
                                    canEditQueue = true,
                                    canControlPlayback = true,
                                    canChangeSong = true,
                                    canSeek = true,
                                ),
                            ),
                        )
                        for (frame in incoming) {
                            val text = (frame as? Frame.Text)?.readText() ?: continue
                            val parsed = TogetherDesktopPartyProtocol.parse(text) ?: continue
                            serverFrames.trySend(parsed)
                            if (TogetherDesktopPartyProtocol.type(parsed) == "ping") {
                                send(TogetherDesktopPartyProtocol.encodePong(TogetherDesktopPartyProtocol.nonce(parsed)))
                            }
                        }
                    }
                }
            }.also { it.start(wait = false) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var clock = 10_000L
        val client =
            TogetherClient(
                externalScope = scope,
                clientId = "integration-android",
                elapsedRealtimeMs = { clock++ },
            )
        val events = Channel<TogetherClientEvent>(Channel.UNLIMITED)
        val eventJob: Job = scope.launch { client.events.collect { events.send(it) } }

        try {
            client.connect(
                wsUrl = "ws://127.0.0.1:$port/party",
                sessionId = SESSION_ID,
                sessionKey = SESSION_KEY,
                displayName = "Android",
            )

            val welcome = events.receiveClientEvent<TogetherClientEvent.Welcome>()
            val room = events.receiveUntilRoomState { state ->
                state.queue.singleOrNull()?.id == "song-1" &&
                    state.permissionsFor(welcome.welcome.participantId).canSeek
            }
            assertEquals(4_200, room.state.positionMs)
            assertTrue(room.state.isPlaying)

            val hello = serverFrames.receiveJsonOfType("client_hello")
            assertEquals(SESSION_ID, hello["sessionId"]!!.jsonPrimitive.contentOrNull)

            client.requestControl(SESSION_ID, ControlAction.SeekTo(9_876))
            val seek = serverFrames.receiveJsonOfType("seek")
            assertEquals(9_876L, seek["positionMs"]!!.jsonPrimitive.longOrNull)

            client.requestAddTrack(SESSION_ID, TRACK.copy(id = "song-3"), AddTrackMode.ADD_TO_QUEUE)
            val add = serverFrames.receiveJsonOfType("queue_add")
            assertEquals("desktop-rev", add["queueRevision"]!!.jsonPrimitive.contentOrNull)
            assertEquals("song-3", add["items"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.contentOrNull)
        } finally {
            client.disconnect()
            eventJob.cancel()
            scope.cancel()
            desktopServer.stop(250, 1_500)
        }
    }

    private suspend inline fun <reified T : TogetherServerEvent> Channel<TogetherServerEvent>.receiveServerEvent(): T =
        withTimeout(5_000) {
            while (true) {
                val event = receive()
                if (event is T) return@withTimeout event
            }
            error("unreachable")
        }

    private suspend inline fun <reified T : TogetherServerEvent> Channel<TogetherServerEvent>.receiveMatchingOrNull(
        timeoutMs: Long,
    ): T? =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val event = receive()
                if (event is T) return@withTimeoutOrNull event
            }
            null
        }

    private suspend inline fun <reified T : TogetherClientEvent> Channel<TogetherClientEvent>.receiveClientEvent(): T =
        withTimeout(5_000) {
            while (true) {
                val event = receive()
                if (event is T) return@withTimeout event
            }
            error("unreachable")
        }

    private suspend fun Channel<TogetherClientEvent>.receiveUntilRoomState(
        predicate: (TogetherRoomState) -> Boolean,
    ): TogetherClientEvent.RoomState =
        withTimeout(5_000) {
            while (true) {
                val event = receive()
                if (event is TogetherClientEvent.RoomState && predicate(event.state)) return@withTimeout event
            }
            error("unreachable")
        }

    private suspend fun DefaultClientWebSocketSession.receiveJsonOfType(
        type: String,
        timeoutMs: Long = 5_000,
    ): JsonObject =
        withTimeout(timeoutMs) {
            while (true) {
                val text = (incoming.receive() as? Frame.Text)?.readText() ?: continue
                val parsed = TogetherDesktopPartyProtocol.parse(text) ?: continue
                if (TogetherDesktopPartyProtocol.type(parsed) == type) return@withTimeout parsed
            }
            error("unreachable")
        }

    private suspend fun Channel<JsonObject>.receiveJsonOfType(type: String): JsonObject =
        withTimeout(5_000) {
            while (true) {
                val parsed = receive()
                if (TogetherDesktopPartyProtocol.type(parsed) == type) return@withTimeout parsed
            }
            error("unreachable")
        }

    private fun freePort(): Int =
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }

    private companion object {
        const val SESSION_ID = "integration-session"
        const val SESSION_KEY = "integration-key"
        val TRACK = TogetherTrack(id = "song-1", title = "First", artists = listOf("Artist"), durationSec = 180)
    }
}
