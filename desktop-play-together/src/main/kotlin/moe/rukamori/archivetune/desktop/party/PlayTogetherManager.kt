package moe.rukamori.archivetune.desktop.party

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.exists
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.RepeatMode
import org.json.JSONArray
import org.json.JSONObject
import tel.schich.libdatachannel.DataChannel
import tel.schich.libdatachannel.DataChannelCallback
import tel.schich.libdatachannel.GatheringState
import tel.schich.libdatachannel.LibDataChannelArchDetect
import tel.schich.libdatachannel.PeerConnection
import tel.schich.libdatachannel.PeerConnectionConfiguration
import tel.schich.libdatachannel.PeerState
import tel.schich.libdatachannel.SessionDescriptionType

/**
 * Zero-owned-infrastructure listen-together coordinator for the desktop app.
 *
 * The host exposes a loopback-only signaling websocket through a Cloudflare Quick Tunnel. The
 * websocket exchanges the WebRTC offer/answer and remains available as a fallback. Once ICE can
 * establish a direct route, playback state moves over a peer-to-peer WebRTC DataChannel. Audio is
 * never transported here: every ArchiveTune client resolves and plays the media locally.
 */
class PlayTogetherManager(
    private val playback: PlaybackController,
    private val cacheDirectory: Path,
    private val logger: Logger,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("ArchiveTunePlayTogether"))
    private val _state = MutableStateFlow(PlayTogetherState())
    val state: StateFlow<PlayTogetherState> = _state.asStateFlow()

    private val hostPeers = ConcurrentHashMap<String, HostPeer>()
    private var stopHostServer: (() -> Unit)? = null
    private var tunnelProcess: Process? = null
    private var tunnelLogJob: Job? = null
    private var hostBroadcastJob: Job? = null
    private var guestSyncJob: Job? = null

    @Volatile private var guestSocket: WebSocket? = null
    @Volatile private var guestSignalOpen = false
    @Volatile private var guestPeer: PeerConnection? = null
    @Volatile private var guestChannel: DataChannel? = null

    private var lastGuestRttMs = 0L
    private var pingSentAtNs = 0L
    private val closed = AtomicBoolean(false)

    suspend fun startHosting(): Result<String> = runCatching {
        ensureOpen()
        leaveInternal()
        _state.value = PlayTogetherState(
            mode = PartyMode.HOST,
            phase = PartyPhase.STARTING,
            transport = PartyTransport.SIGNALING,
            message = "Starting listening party…",
        )

        val snapshot = playback.state.value
        require(snapshot.queue.none { it.source is PlaybackSource.LocalFile }) {
            "Listening parties currently support streamed songs only, not local files."
        }

        val token = randomToken()
        val port = reserveLoopbackPort()
        startHostServer(port, token)

        val cloudflared = ensureCloudflared()
        val publicBaseUrl = startQuickTunnel(cloudflared, port)
        val invite = "$publicBaseUrl/party?token=$token"

        _state.value = PlayTogetherState(
            mode = PartyMode.HOST,
            phase = PartyPhase.CONNECTED,
            transport = PartyTransport.SIGNALING,
            invite = invite,
            participantCount = 1,
            message = "Party ready",
        )
        startHostBroadcastLoop()
        invite
    }.onFailure { error ->
        logger.log(Level.WARNING, "Could not start listening party", error)
        leaveInternal()
        _state.value = PlayTogetherState(
            mode = PartyMode.NONE,
            phase = PartyPhase.ERROR,
            message = error.message ?: "Could not start listening party",
        )
    }

    suspend fun join(inviteText: String): Result<Unit> = runCatching {
        ensureOpen()
        leaveInternal()
        _state.value = PlayTogetherState(
            mode = PartyMode.GUEST,
            phase = PartyPhase.STARTING,
            transport = PartyTransport.SIGNALING,
            message = "Joining listening party…",
        )

        val wsUri = parseInvite(inviteText)
        val opened = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Throwable>()
        val listener = GuestWebSocketListener(opened, failed)
        val socket = withContext(Dispatchers.IO) {
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(wsUri, listener)
                .join()
        }
        guestSocket = socket

        withTimeout(CONNECTION_TIMEOUT_MS) {
            while (!opened.isCompleted) {
                if (failed.isCompleted) throw failed.await()
                delay(25)
            }
            opened.await()
        }

        _state.value = PlayTogetherState(
            mode = PartyMode.GUEST,
            phase = PartyPhase.CONNECTED,
            transport = PartyTransport.RELAY,
            message = "Connected — negotiating direct P2P",
            participantCount = 2,
        )
        startGuestPingLoop()
    }.onFailure { error ->
        logger.log(Level.WARNING, "Could not join listening party", error)
        leaveInternal()
        _state.value = PlayTogetherState(
            mode = PartyMode.NONE,
            phase = PartyPhase.ERROR,
            message = error.message ?: "Could not join listening party",
        )
    }

    fun leave() {
        leaveInternal()
        _state.value = PlayTogetherState()
    }

    private fun startHostServer(port: Int, token: String) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(WebSockets)
            routing {
                webSocket("/party") {
                    if (call.request.queryParameters["token"] != token) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid party token"))
                        return@webSocket
                    }

                    val hostPeer = HostPeer(id = randomToken(), signalSession = this)
                    hostPeers[hostPeer.id] = hostPeer
                    updateHostParticipantCount()
                    runCatching { send(Frame.Text(encodeFullSnapshot(playback.state.value))) }
                    startHostRtc(hostPeer)

                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            handleHostSignalMessage(hostPeer, frame.readText())
                        }
                    } finally {
                        hostPeer.signalSession = null
                        if (!hostPeer.directOpen) removeHostPeer(hostPeer)
                        updateHostParticipantCount()
                    }
                }
            }
        }
        server.start(wait = false)
        stopHostServer = { server.stop(250, 1_500) }
    }

    private fun startHostRtc(hostPeer: HostPeer) {
        runCatching {
            ensureRtcInitialized()
            val peer = PeerConnection.createPeer(RTC_CONFIGURATION)
            hostPeer.peer = peer

            peer.onGatheringStateChange.register { pc, state ->
                if (state == GatheringState.RTC_GATHERING_COMPLETE) {
                    val offer = runCatching { pc.localDescription() }.getOrNull() ?: return@register
                    scope.launch {
                        sendSignal(
                            hostPeer,
                            JSONObject().put("type", "offer").put("sdp", offer).toString(),
                        )
                    }
                }
            }
            peer.onStateChange.register { _, state ->
                when (state) {
                    PeerState.RTC_FAILED,
                    PeerState.RTC_DISCONNECTED,
                    PeerState.RTC_CLOSED,
                    -> {
                        hostPeer.directOpen = false
                        updateHostParticipantCount()
                    }
                    else -> Unit
                }
            }

            val channel = peer.createDataChannel(DATA_CHANNEL_LABEL)
            hostPeer.channel = channel
            bindHostDataChannel(hostPeer, channel)
        }.onFailure { error ->
            logger.log(Level.FINE, "WebRTC unavailable for a party peer; keeping Quick Tunnel fallback", error)
            hostPeer.directOpen = false
            updateHostParticipantCount()
        }
    }

    private fun bindHostDataChannel(hostPeer: HostPeer, channel: DataChannel) {
        channel.onOpen.register {
            hostPeer.directOpen = true
            updateHostParticipantCount()
            scope.launch {
                sendHostPeer(hostPeer, encodeFullSnapshot(playback.state.value))
            }
        }
        channel.onMessage.register(
            DataChannelCallback.Message.handleText { _, text ->
                scope.launch { handleHostDirectMessage(hostPeer, text) }
            },
        )
        channel.onClosed.register {
            hostPeer.directOpen = false
            updateHostParticipantCount()
            if (hostPeer.signalSession == null) scope.launch { removeHostPeer(hostPeer) }
        }
        channel.onError.register { _, error ->
            logger.fine("Party P2P data channel error: $error")
            hostPeer.directOpen = false
            updateHostParticipantCount()
        }
        if (channel.isOpen) {
            hostPeer.directOpen = true
            updateHostParticipantCount()
        }
    }

    private suspend fun handleHostSignalMessage(hostPeer: HostPeer, text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (message.optString("type")) {
            "answer" -> {
                val sdp = message.optString("sdp")
                if (sdp.isNotBlank()) {
                    runCatching { hostPeer.peer?.setAnswer(sdp) }
                        .onFailure { logger.log(Level.FINE, "Could not apply WebRTC answer", it) }
                }
            }
            "ping" -> {
                val nonce = message.optLong("nonce", 0L)
                sendSignal(hostPeer, pongMessage(nonce))
            }
        }
    }

    private suspend fun handleHostDirectMessage(hostPeer: HostPeer, text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.optString("type") == "ping") {
            val nonce = message.optLong("nonce", 0L)
            runCatching { hostPeer.channel?.sendMessage(pongMessage(nonce)) }
        }
    }

    private fun startHostBroadcastLoop() {
        hostBroadcastJob?.cancel()
        hostBroadcastJob = scope.launch {
            var lastQueueSignature: String? = null
            while (isActive && _state.value.mode == PartyMode.HOST) {
                val snapshot = playback.state.value
                val signature = queueSignature(snapshot)
                val message = if (signature != lastQueueSignature) {
                    lastQueueSignature = signature
                    encodeFullSnapshot(snapshot)
                } else {
                    encodeTransportSnapshot(snapshot)
                }
                broadcastToGuests(message)
                delay(HOST_BROADCAST_INTERVAL_MS)
            }
        }
    }

    private fun startGuestPingLoop() {
        guestSyncJob?.cancel()
        guestSyncJob = scope.launch {
            while (isActive && _state.value.mode == PartyMode.GUEST) {
                val nonce = System.nanoTime()
                pingSentAtNs = nonce
                sendGuestMessage(JSONObject().put("type", "ping").put("nonce", nonce).toString())
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private suspend fun broadcastToGuests(text: String) {
        hostPeers.values.toList().forEach { hostPeer -> sendHostPeer(hostPeer, text) }
        updateHostParticipantCount()
    }

    private suspend fun sendHostPeer(hostPeer: HostPeer, text: String) {
        val channel = hostPeer.channel
        if (hostPeer.directOpen && channel != null && channel.isOpen) {
            val sent = runCatching { channel.sendMessage(text) }.isSuccess
            if (sent) return
            hostPeer.directOpen = false
            updateHostParticipantCount()
        }
        sendSignal(hostPeer, text)
    }

    private suspend fun sendSignal(hostPeer: HostPeer, text: String) {
        val session = hostPeer.signalSession ?: return
        runCatching { session.send(Frame.Text(text)) }
            .onFailure {
                hostPeer.signalSession = null
                if (!hostPeer.directOpen) removeHostPeer(hostPeer)
            }
    }

    private fun removeHostPeer(hostPeer: HostPeer) {
        if (hostPeers.remove(hostPeer.id, hostPeer)) {
            runCatching { hostPeer.peer?.close() }
            hostPeer.peer = null
            hostPeer.channel = null
            updateHostParticipantCount()
        }
    }

    private fun updateHostParticipantCount() {
        val current = _state.value
        if (current.mode != PartyMode.HOST) return

        val directPeers = hostPeers.values.count { it.directOpen && it.channel?.isOpen == true }
        val transport = when {
            hostPeers.isEmpty() -> PartyTransport.SIGNALING
            directPeers == hostPeers.size -> PartyTransport.DIRECT
            directPeers == 0 -> PartyTransport.RELAY
            else -> PartyTransport.MIXED
        }
        _state.value = current.copy(
            participantCount = 1 + hostPeers.size,
            directPeerCount = directPeers,
            transport = transport,
        )
    }

    private fun handleGuestSignalMessage(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (message.optString("type")) {
            "offer" -> {
                val sdp = message.optString("sdp")
                if (sdp.isNotBlank()) startGuestRtc(sdp)
            }
            else -> scope.launch {
                runCatching { applyRemoteMessage(message) }
                    .onFailure { logger.log(Level.WARNING, "Ignoring invalid party message", it) }
            }
        }
    }

    private fun startGuestRtc(offerSdp: String) {
        runCatching {
            ensureRtcInitialized()
            guestPeer?.close()
            guestPeer = null
            guestChannel = null

            val peer = PeerConnection.createPeer(RTC_CONFIGURATION)
            guestPeer = peer
            peer.onDataChannel.register { _, channel -> bindGuestDataChannel(channel) }
            peer.onGatheringStateChange.register { pc, state ->
                if (state == GatheringState.RTC_GATHERING_COMPLETE) {
                    val answer = runCatching { pc.localDescription() }.getOrNull() ?: return@register
                    guestSocket?.sendText(
                        JSONObject().put("type", "answer").put("sdp", answer).toString(),
                        true,
                    )
                }
            }
            peer.onStateChange.register { _, state ->
                if (state == PeerState.RTC_FAILED || state == PeerState.RTC_DISCONNECTED || state == PeerState.RTC_CLOSED) {
                    markGuestRelay()
                }
            }
            peer.setRemoteDescription(offerSdp, SessionDescriptionType.OFFER)
        }.onFailure { error ->
            logger.log(Level.FINE, "Could not establish direct WebRTC; using Quick Tunnel fallback", error)
            markGuestRelay()
        }
    }

    private fun bindGuestDataChannel(channel: DataChannel) {
        guestChannel = channel
        channel.onOpen.register { markGuestDirect(channel) }
        channel.onMessage.register(
            DataChannelCallback.Message.handleText { _, text ->
                scope.launch {
                    runCatching { applyRemoteMessage(JSONObject(text)) }
                        .onFailure { logger.log(Level.WARNING, "Ignoring invalid P2P party message", it) }
                }
            },
        )
        channel.onClosed.register {
            if (guestChannel === channel) guestChannel = null
            markGuestRelay()
        }
        channel.onError.register { _, error ->
            logger.fine("Party P2P data channel error: $error")
            if (guestChannel === channel) guestChannel = null
            markGuestRelay()
        }
        if (channel.isOpen) markGuestDirect(channel)
    }

    private fun markGuestDirect(channel: DataChannel) {
        guestChannel = channel
        val current = _state.value
        if (current.mode == PartyMode.GUEST) {
            _state.value = current.copy(
                phase = PartyPhase.CONNECTED,
                transport = PartyTransport.DIRECT,
                directPeerCount = 1,
                message = "Connected directly P2P",
            )
        }
        scope.launch { sendGuestMessage(JSONObject().put("type", "ping").put("nonce", System.nanoTime()).toString()) }
    }

    private fun markGuestRelay() {
        val current = _state.value
        if (current.mode != PartyMode.GUEST) return
        if (guestSignalOpen) {
            _state.value = current.copy(
                phase = PartyPhase.CONNECTED,
                transport = PartyTransport.RELAY,
                directPeerCount = 0,
                message = "Connected through Quick Tunnel fallback",
            )
        } else if (guestChannel?.isOpen != true) {
            _state.value = current.copy(
                phase = PartyPhase.ERROR,
                transport = PartyTransport.NONE,
                directPeerCount = 0,
                message = "Party disconnected",
            )
        }
    }

    private fun sendGuestMessage(text: String) {
        val channel = guestChannel
        if (channel != null && channel.isOpen) {
            if (runCatching { channel.sendMessage(text) }.isSuccess) return
            guestChannel = null
            markGuestRelay()
        }
        if (guestSignalOpen) guestSocket?.sendText(text, true)
    }

    private suspend fun applyRemoteMessage(message: JSONObject) {
        when (message.optString("type")) {
            "full" -> applyFullSnapshot(message)
            "transport" -> applyTransportSnapshot(message)
            "pong" -> {
                val nonce = message.optLong("nonce", 0L)
                if (nonce != 0L && nonce == pingSentAtNs) {
                    lastGuestRttMs = ((System.nanoTime() - nonce) / 1_000_000L).coerceAtLeast(0L)
                    val current = _state.value
                    if (current.mode == PartyMode.GUEST) _state.value = current.copy(roundTripMs = lastGuestRttMs)
                }
            }
        }
    }

    private suspend fun applyFullSnapshot(message: JSONObject) {
        val queueJson = message.getJSONArray("queue")
        val queue = buildList(queueJson.length()) {
            for (index in 0 until queueJson.length()) add(decodePlaybackItem(queueJson.getJSONObject(index)))
        }
        if (queue.isEmpty()) return

        val index = message.optInt("currentIndex", 0).coerceIn(0, queue.lastIndex)
        playback.replaceQueue(
            items = queue,
            startIndex = index,
            startPositionMs = correctedRemotePosition(message),
            title = message.optString("queueTitle").takeIf(String::isNotBlank),
            playWhenReady = message.optBoolean("playWhenReady", false),
        )
        applyModes(message)
    }

    private suspend fun applyTransportSnapshot(message: JSONObject) {
        val local = playback.state.value
        val remoteIndex = message.optInt("currentIndex", -1)
        if (remoteIndex < 0 || local.queue.isEmpty()) return

        val target = correctedRemotePosition(message)
        if (local.currentIndex != remoteIndex && remoteIndex in local.queue.indices) {
            playback.seekToItem(remoteIndex, target)
        } else if (remoteIndex == local.currentIndex && abs(local.positionMs - target) >= SEEK_CORRECTION_THRESHOLD_MS) {
            playback.seekTo(target)
        }

        val shouldPlay = message.optBoolean("playWhenReady", false)
        if (shouldPlay != playback.state.value.playWhenReady) {
            if (shouldPlay) playback.play() else playback.pause()
        }
        applyModes(message)
    }

    private suspend fun applyModes(message: JSONObject) {
        val local = playback.state.value
        val repeat = runCatching { RepeatMode.valueOf(message.optString("repeatMode", RepeatMode.OFF.name)) }
            .getOrDefault(RepeatMode.OFF)
        val shuffle = message.optBoolean("shuffleEnabled", false)
        val speed = message.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 4f)
        if (local.repeatMode != repeat) playback.setRepeatMode(repeat)
        if (local.shuffleEnabled != shuffle) playback.setShuffleEnabled(shuffle)
        if (abs(local.speed - speed) > 0.001f) playback.setSpeed(speed)
    }

    private fun correctedRemotePosition(message: JSONObject): Long {
        val position = message.optLong("positionMs", 0L)
        val playing = message.optBoolean("playWhenReady", false)
        return if (playing) position + lastGuestRttMs / 2L else position
    }

    private suspend fun ensureCloudflared(): Path = withContext(Dispatchers.IO) {
        val tools = cacheDirectory.resolve("play-together")
        Files.createDirectories(tools)
        val executable = tools.resolve(if (isWindows()) "cloudflared.exe" else "cloudflared")
        if (executable.exists()) return@withContext executable

        require(isWindows()) { "Automatic Quick Tunnel setup is currently packaged for Windows only." }

        _state.value = _state.value.copy(message = "Downloading Cloudflare tunnel helper…")
        val temporary = executable.resolveSibling("${executable.fileName}.part")
        val request = java.net.http.HttpRequest.newBuilder(CLOUDFLARED_WINDOWS_AMD64_URI)
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build()
        val response = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(temporary))
        require(response.statusCode() in 200..299) { "Cloudflare helper download failed (${response.statusCode()})" }
        try {
            Files.move(temporary, executable, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temporary, executable, StandardCopyOption.REPLACE_EXISTING)
        }
        executable
    }

    private suspend fun startQuickTunnel(cloudflared: Path, port: Int): String {
        val process = ProcessBuilder(
            cloudflared.toAbsolutePath().toString(),
            "tunnel",
            "--no-autoupdate",
            "--url",
            "http://127.0.0.1:$port",
        )
            .redirectErrorStream(true)
            .start()
        tunnelProcess = process

        val publicUrl = CompletableDeferred<String>()
        tunnelLogJob = scope.launch(Dispatchers.IO) {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (isActive) {
                    val line = reader.readLine() ?: break
                    logger.info("cloudflared: $line")
                    QUICK_TUNNEL_URL.find(line)?.value?.let { url ->
                        if (!publicUrl.isCompleted) publicUrl.complete(url)
                    }
                }
            }
            if (!publicUrl.isCompleted) {
                publicUrl.completeExceptionally(IllegalStateException("Cloudflare Quick Tunnel exited before publishing an address"))
            }
        }

        return withTimeout(TUNNEL_START_TIMEOUT_MS) { publicUrl.await() }
    }

    private fun leaveInternal() {
        hostBroadcastJob?.cancel()
        hostBroadcastJob = null
        guestSyncJob?.cancel()
        guestSyncJob = null

        guestSignalOpen = false
        guestSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "Leaving party")
        guestSocket = null
        guestChannel = null
        runCatching { guestPeer?.close() }
        guestPeer = null

        hostPeers.values.toList().forEach { hostPeer ->
            hostPeer.signalSession?.let { session ->
                scope.launch { runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "Party ended")) } }
            }
            hostPeer.signalSession = null
            runCatching { hostPeer.peer?.close() }
            hostPeer.peer = null
            hostPeer.channel = null
        }
        hostPeers.clear()

        stopHostServer?.invoke()
        stopHostServer = null
        tunnelLogJob?.cancel()
        tunnelLogJob = null
        tunnelProcess?.destroy()
        tunnelProcess = null
        lastGuestRttMs = 0L
        pingSentAtNs = 0L
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        leaveInternal()
        scope.cancel()
    }

    private fun ensureOpen() {
        check(!closed.get()) { "PlayTogetherManager is closed" }
    }

    private fun ensureRtcInitialized() {
        if (rtcInitialized.get()) return
        synchronized(rtcInitLock) {
            if (rtcInitialized.get()) return
            LibDataChannelArchDetect.initialize()
            rtcInitialized.set(true)
        }
    }

    private inner class GuestWebSocketListener(
        private val opened: CompletableDeferred<Unit>,
        private val failed: CompletableDeferred<Throwable>,
    ) : WebSocket.Listener {
        private val textBuffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            guestSignalOpen = true
            opened.complete(Unit)
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            textBuffer.append(data)
            if (last) {
                val complete = textBuffer.toString()
                textBuffer.setLength(0)
                handleGuestSignalMessage(complete)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            guestSignalOpen = false
            if (guestChannel?.isOpen != true) markGuestRelay()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            guestSignalOpen = false
            if (!opened.isCompleted && !failed.isCompleted) failed.complete(error)
            logger.log(Level.WARNING, "Listening party signaling websocket failed", error)
            if (guestChannel?.isOpen != true) markGuestRelay()
        }
    }

    private class HostPeer(
        val id: String,
        @Volatile var signalSession: DefaultWebSocketServerSession?,
    ) {
        @Volatile var peer: PeerConnection? = null
        @Volatile var channel: DataChannel? = null
        @Volatile var directOpen: Boolean = false
    }

    companion object {
        private val CLOUDFLARED_WINDOWS_AMD64_URI =
            URI.create("https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe")
        private val QUICK_TUNNEL_URL = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com", RegexOption.IGNORE_CASE)
        private val RTC_CONFIGURATION = PeerConnectionConfiguration.DEFAULT.withIceServers(
            PeerConnectionConfiguration.uris(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302",
            ),
        )
        private val rtcInitialized = AtomicBoolean(false)
        private val rtcInitLock = Any()

        private const val DATA_CHANNEL_LABEL = "archivetune-party"
        private const val HOST_BROADCAST_INTERVAL_MS = 250L
        private const val PING_INTERVAL_MS = 5_000L
        private const val SEEK_CORRECTION_THRESHOLD_MS = 450L
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val TUNNEL_START_TIMEOUT_MS = 45_000L

        fun parseInvite(text: String): URI {
            val raw = text.trim()
            require(raw.isNotBlank()) { "Paste a listening-party invite first." }
            val uri = URI.create(raw)
            require(uri.host?.endsWith(".trycloudflare.com", ignoreCase = true) == true) {
                "That is not an ArchiveTune Quick Tunnel invite."
            }
            require(uri.rawQuery?.split('&')?.any { it.startsWith("token=") && it.length > 10 } == true) {
                "The party invite is missing its access token."
            }
            val scheme = when (uri.scheme.lowercase()) {
                "https", "wss" -> "wss"
                "http", "ws" -> "ws"
                else -> error("Unsupported party invite scheme")
            }
            val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/party"
            return URI(scheme, uri.userInfo, uri.host, uri.port, path, uri.rawQuery, null)
        }

        private fun randomToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun reserveLoopbackPort(): Int =
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }

        private fun isWindows(): Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)

        private fun queueSignature(snapshot: PlaybackSnapshot): String = buildString {
            append(snapshot.queueTitle)
            snapshot.queue.forEach { append('|').append(it.metadata.id) }
        }

        private fun pongMessage(nonce: Long): String =
            JSONObject().put("type", "pong").put("nonce", nonce).toString()

        private fun encodeFullSnapshot(snapshot: PlaybackSnapshot): String = JSONObject()
            .put("type", "full")
            .put("queueTitle", snapshot.queueTitle ?: "")
            .put("currentIndex", snapshot.currentIndex)
            .put("positionMs", snapshot.positionMs)
            .put("playWhenReady", snapshot.playWhenReady)
            .put("repeatMode", snapshot.repeatMode.name)
            .put("shuffleEnabled", snapshot.shuffleEnabled)
            .put("speed", snapshot.speed.toDouble())
            .put("queue", JSONArray().apply { snapshot.queue.forEach { put(encodePlaybackItem(it)) } })
            .toString()

        private fun encodeTransportSnapshot(snapshot: PlaybackSnapshot): String = JSONObject()
            .put("type", "transport")
            .put("currentIndex", snapshot.currentIndex)
            .put("positionMs", snapshot.positionMs)
            .put("playWhenReady", snapshot.playWhenReady)
            .put("repeatMode", snapshot.repeatMode.name)
            .put("shuffleEnabled", snapshot.shuffleEnabled)
            .put("speed", snapshot.speed.toDouble())
            .toString()

        private fun encodePlaybackItem(item: PlaybackItem): JSONObject {
            val metadata = item.metadata
            return JSONObject()
                .put("id", metadata.id)
                .put("title", metadata.title)
                .put("artists", JSONArray().apply {
                    metadata.artists.forEach { artist ->
                        put(JSONObject().put("id", artist.id ?: "").put("name", artist.name))
                    }
                })
                .put("albumId", metadata.album?.id ?: "")
                .put("albumTitle", metadata.album?.title ?: "")
                .put("artworkUri", metadata.artworkUri ?: "")
                .put("durationHintMs", metadata.durationHintMs ?: JSONObject.NULL)
                .put("explicit", metadata.explicit)
                .put("isMusicVideo", metadata.isMusicVideo)
        }

        private fun decodePlaybackItem(json: JSONObject): PlaybackItem {
            val artistsJson = json.optJSONArray("artists") ?: JSONArray()
            val artists = buildList {
                for (index in 0 until artistsJson.length()) {
                    val artist = artistsJson.getJSONObject(index)
                    add(
                        PlaybackArtist(
                            id = artist.optString("id").takeIf(String::isNotBlank),
                            name = artist.optString("name"),
                        ),
                    )
                }
            }
            val albumTitle = json.optString("albumTitle")
            val metadata = PlaybackMetadata(
                id = json.getString("id"),
                title = json.optString("title"),
                artists = artists,
                album = albumTitle.takeIf(String::isNotBlank)?.let {
                    PlaybackAlbum(id = json.optString("albumId").takeIf(String::isNotBlank), title = it)
                },
                artworkUri = json.optString("artworkUri").takeIf(String::isNotBlank),
                durationHintMs = if (json.isNull("durationHintMs")) null else json.optLong("durationHintMs"),
                explicit = json.optBoolean("explicit", false),
                isMusicVideo = json.optBoolean("isMusicVideo", false),
            )
            return PlaybackItem(metadata = metadata, source = PlaybackSource.Deferred(metadata.id))
        }
    }
}

enum class PartyMode { NONE, HOST, GUEST }
enum class PartyPhase { IDLE, STARTING, CONNECTED, ERROR }
enum class PartyTransport { NONE, SIGNALING, DIRECT, RELAY, MIXED }

data class PlayTogetherState(
    val mode: PartyMode = PartyMode.NONE,
    val phase: PartyPhase = PartyPhase.IDLE,
    val transport: PartyTransport = PartyTransport.NONE,
    val invite: String? = null,
    val participantCount: Int = 0,
    val directPeerCount: Int = 0,
    val roundTripMs: Long? = null,
    val message: String? = null,
)
