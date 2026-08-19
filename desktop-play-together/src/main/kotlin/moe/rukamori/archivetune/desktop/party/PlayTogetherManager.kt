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
import java.util.concurrent.atomic.AtomicReference
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val guestSignal = AtomicReference(GuestSignal())
    @Volatile private var guestPeer: PeerConnection? = null
    @Volatile private var guestChannel: DataChannel? = null

    private var lastGuestRttMs = 0L
    private var pingSentAtNs = 0L
    private val lifecycleMutex = Mutex()
    @Volatile private var sessionGeneration = 0L
    private var modeOverride: PartyModeOverride? = null
    private val closed = AtomicBoolean(false)

    suspend fun startHosting(): Result<String> {
        var generation: Long? = null
        return try {
            ensureOpen()
            val snapshot = playback.state.value
            require(snapshot.queue.none { it.source is PlaybackSource.LocalFile }) {
                "Listening parties currently support streamed songs only, not local files."
            }
            val currentGeneration = beginSession(PartyMode.HOST, "Starting listening party…")
            generation = currentGeneration

            val token = randomToken()
            val port = reserveLoopbackPort()
            startHostServer(port, token, currentGeneration)

            val cloudflared = ensureCloudflared(currentGeneration)
            ensureCurrentSession(currentGeneration)
            val publicBaseUrl = startQuickTunnel(cloudflared, port, currentGeneration)
            ensureCurrentSession(currentGeneration)
            val invite = "$publicBaseUrl/party?token=$token"
            check(
                publishConnectedSession(
                    currentGeneration,
                    PlayTogetherState(
                        mode = PartyMode.HOST,
                        phase = PartyPhase.CONNECTED,
                        transport = PartyTransport.SIGNALING,
                        invite = invite,
                        participantCount = 1,
                        message = "Party ready",
                    ),
                ),
            ) { "Party session was replaced" }
            Result.success(invite)
        } catch (error: Throwable) {
            logger.log(Level.WARNING, "Could not start listening party", error)
            val failureState = errorState(error.message ?: "Could not start listening party")
            generation?.let { finishSession(it, failureState) }
                ?: run { if (_state.value.mode == PartyMode.NONE) _state.value = failureState }
            Result.failure(error)
        }
    }

    suspend fun join(inviteText: String): Result<Unit> {
        var generation: Long? = null
        return try {
            ensureOpen()
            val wsUri = parseInvite(inviteText)
            val currentGeneration = beginSession(PartyMode.GUEST, "Joining listening party…")
            generation = currentGeneration
            val opened = CompletableDeferred<Unit>()
            val failed = CompletableDeferred<Throwable>()
            val listener = GuestWebSocketListener(currentGeneration, opened, failed)
            val socket = withContext(Dispatchers.IO) {
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(wsUri, listener)
                    .join()
            }
            publishGuestSocket(currentGeneration, socket)

            withTimeout(CONNECTION_TIMEOUT_MS) {
                while (!opened.isCompleted) {
                    ensureCurrentSession(currentGeneration)
                    if (failed.isCompleted) throw failed.await()
                    delay(25)
                }
                opened.await()
            }
            ensureCurrentSession(currentGeneration)
            check(
                publishConnectedSession(
                    currentGeneration,
                    PlayTogetherState(
                        mode = PartyMode.GUEST,
                        phase = PartyPhase.CONNECTED,
                        transport = PartyTransport.RELAY,
                        message = "Connected — negotiating direct P2P",
                        participantCount = 2,
                    ),
                ),
            ) { "Party session was replaced" }
            Result.success(Unit)
        } catch (error: Throwable) {
            logger.log(Level.WARNING, "Could not join listening party", error)
            val failureState = errorState(error.message ?: "Could not join listening party")
            generation?.let { finishSession(it, failureState) }
                ?: run { if (_state.value.mode == PartyMode.NONE) _state.value = failureState }
            Result.failure(error)
        }
    }

    fun leave() {
        val generation = sessionGeneration
        scope.launch { finishSession(generation, PlayTogetherState()) }
    }

    private suspend fun startHostServer(port: Int, token: String, generation: Long) {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(WebSockets)
            routing {
                webSocket("/party") {
                    if (!isCurrentSession(generation)) {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Party session ended"))
                        return@webSocket
                    }
                    if (call.request.queryParameters["token"] != token) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid party token"))
                        return@webSocket
                    }

                    val hostPeer = HostPeer(id = randomToken(), generation = generation, signalSession = this)
                    val accepted = lifecycleMutex.withLock {
                        if (!isCurrentSession(generation)) false
                        else {
                            hostPeers[hostPeer.id] = hostPeer
                            true
                        }
                    }
                    if (!accepted) {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Party session ended"))
                        return@webSocket
                    }
                    updateHostParticipantCount()
                    runCatching { send(Frame.Text(encodeFullSnapshot(playback.state.value))) }
                    runCatching { send(Frame.Text(encodePermissions(hostPeer))) }
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
        val stop = { server.stop(250, 1_500) }
        val published = lifecycleMutex.withLock {
            if (!isCurrentSession(generation)) false
            else {
                stopHostServer = stop
                true
            }
        }
        if (!published) {
            stop()
            error("Party session was replaced")
        }
    }

    private suspend fun publishGuestSocket(generation: Long, socket: WebSocket) {
        val published = lifecycleMutex.withLock {
            if (!isCurrentSession(generation)) false
            else {
                val current = guestSignal.get()
                when {
                    current.generation == generation && current.socket === socket -> true
                    current.generation == generation && current.socket == null -> {
                        guestSignal.set(GuestSignal(generation = generation, socket = socket, open = false))
                        true
                    }
                    else -> false
                }
            }
        }
        if (!published) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "Superseded party session")
            error("Party session was replaced")
        }
    }

    private suspend fun startHostRtc(hostPeer: HostPeer) {
        if (!isCurrentSession(hostPeer.generation)) return
        var createdPeer: PeerConnection? = null
        var createdChannel: DataChannel? = null
        try {
            ensureRtcInitialized()
            val peer = PeerConnection.createPeer(RTC_CONFIGURATION)
            createdPeer = peer
            val peerPublished = lifecycleMutex.withLock {
                if (!isCurrentSession(hostPeer.generation) || hostPeers[hostPeer.id] !== hostPeer) false
                else {
                    hostPeer.peer = peer
                    true
                }
            }
            if (!peerPublished) {
                peer.close()
                return
            }

            peer.onGatheringStateChange.register { pc, state ->
                if (isCurrentSession(hostPeer.generation) && state == GatheringState.RTC_GATHERING_COMPLETE) {
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
                if (!isCurrentSession(hostPeer.generation)) return@register
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

            ensureCurrentSession(hostPeer.generation)
            val channel = peer.createDataChannel(DATA_CHANNEL_LABEL)
            createdChannel = channel
            val channelPublished = lifecycleMutex.withLock {
                if (
                    !isCurrentSession(hostPeer.generation) ||
                    hostPeers[hostPeer.id] !== hostPeer ||
                    hostPeer.peer !== peer
                ) {
                    false
                } else {
                    hostPeer.channel = channel
                    true
                }
            }
            if (!channelPublished) {
                channel.close()
                peer.close()
                return
            }
            bindHostDataChannel(hostPeer, channel)
        } catch (error: Throwable) {
            runCatching { createdChannel?.close() }
            runCatching { createdPeer?.close() }
            lifecycleMutex.withLock {
                if (hostPeer.channel === createdChannel) hostPeer.channel = null
                if (hostPeer.peer === createdPeer) hostPeer.peer = null
            }
            logger.log(Level.FINE, "WebRTC unavailable for a party peer; keeping Quick Tunnel fallback", error)
            if (isCurrentSession(hostPeer.generation)) {
                hostPeer.directOpen = false
                updateHostParticipantCount()
            }
        }
    }

    private fun bindHostDataChannel(hostPeer: HostPeer, channel: DataChannel) {
        channel.onOpen.register {
            if (!isCurrentSession(hostPeer.generation)) return@register
            hostPeer.directOpen = true
            updateHostParticipantCount()
            scope.launch {
                sendHostPeer(hostPeer, encodeFullSnapshot(playback.state.value))
                sendHostPeer(hostPeer, encodePermissions(hostPeer))
            }
        }
        channel.onMessage.register(
            DataChannelCallback.Message.handleText { _, text ->
                if (!isCurrentSession(hostPeer.generation)) return@handleText
                scope.launch { handleHostDirectMessage(hostPeer, text) }
            },
        )
        channel.onClosed.register {
            if (!isCurrentSession(hostPeer.generation)) return@register
            hostPeer.directOpen = false
            updateHostParticipantCount()
            if (hostPeer.signalSession == null) scope.launch { removeHostPeer(hostPeer) }
        }
        channel.onError.register { _, error ->
            if (!isCurrentSession(hostPeer.generation)) return@register
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
        if (!isCurrentSession(hostPeer.generation) || text.length > MAX_QUEUE_MESSAGE_CHARS) return
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (message.optString("type")) {
            "answer" -> {
                val sdp = message.optString("sdp")
                if (sdp.isNotBlank()) {
                    runCatching { hostPeer.peer?.setAnswer(sdp) }
                        .onFailure { logger.log(Level.FINE, "Could not apply WebRTC answer", it) }
                }
            }
            else -> handleHostGuestMessage(hostPeer, message, direct = false)
        }
    }

    private suspend fun handleHostDirectMessage(hostPeer: HostPeer, text: String) {
        if (!isCurrentSession(hostPeer.generation) || text.length > MAX_QUEUE_MESSAGE_CHARS) return
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        handleHostGuestMessage(hostPeer, message, direct = true)
    }

    private suspend fun handleHostGuestMessage(hostPeer: HostPeer, message: JSONObject, direct: Boolean) {
        if (message.toString().length > MAX_QUEUE_MESSAGE_CHARS) return
        when (message.optString("type")) {
            "ping" -> {
                val nonce = message.optLong("nonce", 0L)
                if (direct) {
                    runCatching { hostPeer.channel?.sendMessage(pongMessage(nonce)) }
                } else {
                    sendSignal(hostPeer, pongMessage(nonce))
                }
            }
            "queue_add" -> {
                val itemsJson = message.optJSONArray("items") ?: return
                if (itemsJson.length() !in 1..MAX_QUEUE_REQUEST_ITEMS) return
                val items = runCatching {
                    buildList(itemsJson.length()) {
                        for (index in 0 until itemsJson.length()) {
                            val item = decodePlaybackItem(itemsJson.getJSONObject(index))
                            require(item.metadata.id.length in 1..MAX_MEDIA_ID_LENGTH)
                            add(item)
                        }
                    }
                }.getOrNull() ?: return
                var resyncSnapshot: PlaybackSnapshot? = null
                withCurrentSessionMutation(hostPeer.generation) {
                    if (!hostPeer.canEditQueue) return@withCurrentSessionMutation
                    val snapshot = playback.state.value
                    if (message.optString("queueRevision") != queueSignature(snapshot)) {
                        resyncSnapshot = snapshot
                        return@withCurrentSessionMutation
                    }
                    playback.enqueue(items)
                }
                resyncSnapshot?.let { sendHostPeer(hostPeer, encodeFullSnapshot(it)) }
            }
            "queue_remove" -> {
                val index = message.optInt("logicalIndex", -1)
                val expectedId = message.optString("expectedId")
                var resyncSnapshot: PlaybackSnapshot? = null
                withCurrentSessionMutation(hostPeer.generation) {
                    if (!hostPeer.canEditQueue) return@withCurrentSessionMutation
                    val snapshot = playback.state.value
                    if (message.optString("queueRevision") != queueSignature(snapshot)) {
                        resyncSnapshot = snapshot
                        return@withCurrentSessionMutation
                    }
                    // Editing the queue does not include changing the current song. Removing the
                    // current item would implicitly skip it, so only the host may do that.
                    if (index !in snapshot.queue.indices || index == snapshot.currentIndex) return@withCurrentSessionMutation
                    if (expectedId.isBlank() || snapshot.queue[index].metadata.id != expectedId) {
                        resyncSnapshot = snapshot
                        return@withCurrentSessionMutation
                    }
                    playback.removeQueueItem(index)
                }
                resyncSnapshot?.let { sendHostPeer(hostPeer, encodeFullSnapshot(it)) }
            }
        }
    }

    /** Called only while [lifecycleMutex] owns and has validated [generation]. */
    private fun installHostBroadcastLoopLocked(generation: Long) {
        hostBroadcastJob?.cancel()
        hostBroadcastJob = scope.launch {
            var lastQueueSignature: String? = null
            while (isActive && isCurrentSession(generation) && _state.value.mode == PartyMode.HOST) {
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

    /** Called only while [lifecycleMutex] owns and has validated [generation]. */
    private fun installGuestPingLoopLocked(generation: Long) {
        guestSyncJob?.cancel()
        guestSyncJob = scope.launch {
            while (isActive && isCurrentSession(generation) && _state.value.mode == PartyMode.GUEST) {
                val nonce = System.nanoTime()
                pingSentAtNs = nonce
                sendGuestMessage(generation, JSONObject().put("type", "ping").put("nonce", nonce).toString())
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private suspend fun broadcastToGuests(text: String) {
        hostPeers.values.toList().forEach { hostPeer -> sendHostPeer(hostPeer, text) }
        updateHostParticipantCount()
    }

    private suspend fun sendHostPeer(hostPeer: HostPeer, text: String) {
        if (!isCurrentSession(hostPeer.generation)) return
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
        if (!isCurrentSession(hostPeer.generation)) return
        val session = hostPeer.signalSession ?: return
        runCatching { session.send(Frame.Text(text)) }
            .onFailure {
                hostPeer.signalSession = null
                if (!hostPeer.directOpen) removeHostPeer(hostPeer)
            }
    }

    private fun removeHostPeer(hostPeer: HostPeer) {
        if (!isCurrentSession(hostPeer.generation)) return
        if (hostPeers.remove(hostPeer.id, hostPeer)) {
            runCatching { hostPeer.peer?.close() }
            hostPeer.peer = null
            hostPeer.channel = null
            updateHostParticipantCount()
        }
    }

    private fun updateHostParticipantCount() {
        val generation = sessionGeneration
        val currentPeers = hostPeers.values.filter { it.generation == generation }
        val directPeers = currentPeers.count { it.directOpen && it.channel?.isOpen == true }
        val transport = when {
            currentPeers.isEmpty() -> PartyTransport.SIGNALING
            directPeers == currentPeers.size -> PartyTransport.DIRECT
            directPeers == 0 -> PartyTransport.RELAY
            else -> PartyTransport.MIXED
        }
        val participants = currentPeers
            .sortedBy(HostPeer::id)
            .map { peer ->
                PartyParticipant(
                    id = peer.id,
                    name = "Guest ${peer.id.take(4)}",
                    canEditQueue = peer.canEditQueue,
                )
            }
        _state.update { current ->
            if (generation != sessionGeneration || current.mode != PartyMode.HOST) current else current.copy(
                participantCount = 1 + currentPeers.size,
                directPeerCount = directPeers,
                transport = transport,
                participants = participants,
            )
        }
    }

    fun setGuestQueueEditing(peerId: String, enabled: Boolean) {
        if (_state.value.mode != PartyMode.HOST) return
        val hostPeer = hostPeers[peerId] ?: return
        hostPeer.canEditQueue = enabled
        updateHostParticipantCount()
        scope.launch { sendHostPeer(hostPeer, encodePermissions(hostPeer)) }
    }

    suspend fun addToQueue(items: List<PlaybackItem>): Boolean {
        if (items.isEmpty()) return false
        val current = _state.value
        val generation = sessionGeneration
        return when (current.mode) {
            PartyMode.GUEST -> {
                if (current.phase != PartyPhase.CONNECTED || !current.canEditQueue || items.any { it.source is PlaybackSource.LocalFile }) {
                    false
                } else {
                    sendGuestMessage(
                        generation,
                        JSONObject()
                            .put("type", "queue_add")
                            .put("queueRevision", current.queueRevision ?: "")
                            .put(
                                "items",
                                JSONArray().apply {
                                    items.take(MAX_QUEUE_REQUEST_ITEMS).forEach { put(encodePlaybackItem(it)) }
                                },
                            )
                            .toString(),
                    )
                }
            }
            PartyMode.HOST -> {
                if (items.any { it.source is PlaybackSource.LocalFile }) false
                else {
                    playback.enqueue(items)
                    true
                }
            }
            PartyMode.NONE -> {
                playback.enqueue(items)
                true
            }
        }
    }

    suspend fun removeFromQueue(logicalIndex: Int): Boolean {
        val snapshot = playback.state.value
        if (logicalIndex !in snapshot.queue.indices) return false
        val current = _state.value
        val generation = sessionGeneration
        return when (current.mode) {
            PartyMode.GUEST -> {
                if (current.phase != PartyPhase.CONNECTED || !current.canEditQueue || logicalIndex == snapshot.currentIndex) {
                    false
                } else {
                    sendGuestMessage(
                        generation,
                        JSONObject()
                            .put("type", "queue_remove")
                            .put("queueRevision", current.queueRevision ?: "")
                            .put("logicalIndex", logicalIndex)
                            .put("expectedId", snapshot.queue[logicalIndex].metadata.id)
                            .toString(),
                    )
                }
            }
            PartyMode.HOST, PartyMode.NONE -> {
                playback.removeQueueItem(logicalIndex)
                true
            }
        }
    }

    private fun handleGuestSignalMessage(generation: Long, text: String) {
        if (!isCurrentSession(generation) || text.length > MAX_QUEUE_MESSAGE_CHARS) return
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (message.optString("type")) {
            "offer" -> {
                val sdp = message.optString("sdp")
                if (sdp.isNotBlank()) scope.launch { startGuestRtc(generation, sdp) }
            }
            else -> scope.launch {
                runCatching { applyRemoteMessage(generation, message) }
                    .onFailure { logger.log(Level.WARNING, "Ignoring invalid party message", it) }
            }
        }
    }

    private suspend fun startGuestRtc(generation: Long, offerSdp: String) {
        if (!isCurrentSession(generation)) return
        try {
            ensureRtcInitialized()
            val peer = PeerConnection.createPeer(RTC_CONFIGURATION)
            val published = lifecycleMutex.withLock {
                if (!isCurrentSession(generation)) false
                else {
                    runCatching { guestChannel?.close() }
                    guestChannel = null
                    runCatching { guestPeer?.close() }
                    guestPeer = peer
                    true
                }
            }
            if (!published) {
                peer.close()
                return
            }

            peer.onDataChannel.register { _, channel ->
                scope.launch { bindGuestDataChannel(generation, channel) }
            }
            peer.onGatheringStateChange.register { pc, state ->
                if (isCurrentSession(generation) && state == GatheringState.RTC_GATHERING_COMPLETE) {
                    val answer = runCatching { pc.localDescription() }.getOrNull() ?: return@register
                    sendGuestSignal(
                        generation,
                        JSONObject().put("type", "answer").put("sdp", answer).toString(),
                    )
                }
            }
            peer.onStateChange.register { _, state ->
                if (!isCurrentSession(generation)) return@register
                if (state == PeerState.RTC_FAILED || state == PeerState.RTC_DISCONNECTED || state == PeerState.RTC_CLOSED) {
                    markGuestRelay(generation)
                }
            }
            ensureCurrentSession(generation)
            peer.setRemoteDescription(offerSdp, SessionDescriptionType.OFFER)
        } catch (error: Throwable) {
            logger.log(Level.FINE, "Could not establish direct WebRTC; using Quick Tunnel fallback", error)
            markGuestRelay(generation)
        }
    }

    private suspend fun bindGuestDataChannel(generation: Long, channel: DataChannel) {
        val published = lifecycleMutex.withLock {
            if (!isCurrentSession(generation) || guestPeer !== channel.peer()) false
            else {
                runCatching { guestChannel?.takeIf { it !== channel }?.close() }
                guestChannel = channel
                true
            }
        }
        if (!published) {
            runCatching { channel.close() }
            return
        }
        channel.onOpen.register { markGuestDirect(generation, channel) }
        channel.onMessage.register(
            DataChannelCallback.Message.handleText { _, text ->
                if (!isCurrentSession(generation) || text.length > MAX_QUEUE_MESSAGE_CHARS) return@handleText
                scope.launch {
                    runCatching { applyRemoteMessage(generation, JSONObject(text)) }
                        .onFailure { logger.log(Level.WARNING, "Ignoring invalid P2P party message", it) }
                }
            },
        )
        channel.onClosed.register {
            if (!isCurrentSession(generation)) return@register
            if (guestChannel === channel) guestChannel = null
            markGuestRelay(generation)
        }
        channel.onError.register { _, error ->
            if (!isCurrentSession(generation)) return@register
            logger.fine("Party P2P data channel error: $error")
            if (guestChannel === channel) guestChannel = null
            markGuestRelay(generation)
        }
        if (channel.isOpen) markGuestDirect(generation, channel)
    }

    private fun markGuestDirect(generation: Long, channel: DataChannel) {
        if (!isCurrentSession(generation) || guestChannel !== channel) return
        _state.update { current ->
            if (!isCurrentSession(generation) || current.mode != PartyMode.GUEST) current else current.copy(
                phase = PartyPhase.CONNECTED,
                transport = PartyTransport.DIRECT,
                directPeerCount = 1,
                message = "Connected directly P2P",
            )
        }
        scope.launch {
            sendGuestMessage(
                generation,
                JSONObject().put("type", "ping").put("nonce", System.nanoTime()).toString(),
            )
        }
    }

    private fun markGuestRelay(generation: Long) {
        if (!isCurrentSession(generation)) return
        val signal = guestSignal.get()
        if (signal.generation == generation && signal.open) {
            _state.update { current ->
                if (!isCurrentSession(generation) || current.mode != PartyMode.GUEST) current else current.copy(
                    phase = PartyPhase.CONNECTED,
                    transport = PartyTransport.RELAY,
                    directPeerCount = 0,
                    message = "Connected through Quick Tunnel fallback",
                )
            }
        } else if (guestChannel?.isOpen != true) {
            if (_state.value.mode != PartyMode.GUEST) return
            scope.launch { finishSession(generation, errorState("Party disconnected")) }
        }
    }

    private fun sendGuestSignal(generation: Long, text: String): Boolean {
        if (!isCurrentSession(generation)) return false
        val signal = guestSignal.get()
        if (signal.generation != generation || !signal.open) return false
        return runCatching { signal.socket?.sendText(text, true) != null }.getOrDefault(false)
    }

    private fun sendGuestMessage(generation: Long, text: String): Boolean {
        if (!isCurrentSession(generation)) return false
        val channel = guestChannel
        if (channel != null && channel.isOpen) {
            if (runCatching { channel.sendMessage(text) }.isSuccess) return true
            if (!isCurrentSession(generation)) return false
            if (guestChannel === channel) guestChannel = null
            markGuestRelay(generation)
        }
        return sendGuestSignal(generation, text)
    }

    private suspend fun applyRemoteMessage(generation: Long, message: JSONObject) {
        if (!isCurrentSession(generation)) return
        when (message.optString("type")) {
            "full" -> applyFullSnapshot(generation, message)
            "transport" -> applyTransportSnapshot(generation, message)
            "permissions" -> {
                _state.update { current ->
                    if (!isCurrentSession(generation) || current.mode != PartyMode.GUEST) current else current.copy(
                        canEditQueue = message.optBoolean("canEditQueue", false),
                    )
                }
            }
            "pong" -> {
                val nonce = message.optLong("nonce", 0L)
                if (nonce != 0L && nonce == pingSentAtNs) {
                    lastGuestRttMs = ((System.nanoTime() - nonce) / 1_000_000L).coerceAtLeast(0L)
                    _state.update { current ->
                        if (!isCurrentSession(generation) || current.mode != PartyMode.GUEST) current else current.copy(roundTripMs = lastGuestRttMs)
                    }
                }
            }
        }
    }

    private suspend fun applyFullSnapshot(generation: Long, message: JSONObject) {
        if (!isCurrentSession(generation)) return
        val queueJson = message.getJSONArray("queue")
        val queue = buildList(queueJson.length()) {
            for (index in 0 until queueJson.length()) add(decodePlaybackItem(queueJson.getJSONObject(index)))
        }
        if (queue.isEmpty()) return

        val index = message.optInt("currentIndex", 0).coerceIn(0, queue.lastIndex)
        withCurrentSessionMutation(generation) {
            playback.replaceQueue(
                items = queue,
                startIndex = index,
                startPositionMs = correctedRemotePosition(message),
                title = message.optString("queueTitle").takeIf(String::isNotBlank),
                playWhenReady = message.optBoolean("playWhenReady", false),
            )
            _state.update { current ->
                if (current.mode != PartyMode.GUEST) current else current.copy(
                    queueRevision = message.optString("queueRevision").takeIf(String::isNotBlank),
                )
            }
            applyModesLocked(message)
        }
    }

    private suspend fun applyTransportSnapshot(generation: Long, message: JSONObject) {
        val remoteIndex = message.optInt("currentIndex", -1)
        if (remoteIndex < 0) return
        withCurrentSessionMutation(generation) {
            val local = playback.state.value
            if (local.queue.isEmpty()) return@withCurrentSessionMutation
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
            applyModesLocked(message)
        }
    }

    private suspend fun applyModesLocked(message: JSONObject) {
        val local = playback.state.value
        val repeat = runCatching { RepeatMode.valueOf(message.optString("repeatMode", RepeatMode.OFF.name)) }
            .getOrDefault(RepeatMode.OFF)
        val speed = message.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 4f)
        if (local.repeatMode != repeat) playback.setRepeatMode(repeat)
        if (local.shuffleEnabled) playback.setShuffleEnabled(false)
        if (abs(local.speed - speed) > 0.001f) playback.setSpeed(speed)
    }

    private fun correctedRemotePosition(message: JSONObject): Long {
        val position = message.optLong("positionMs", 0L)
        val playing = message.optBoolean("playWhenReady", false)
        return if (playing) position + lastGuestRttMs / 2L else position
    }

    internal suspend fun beginSession(mode: PartyMode, message: String): Long = lifecycleMutex.withLock {
        ensureOpen()
        sessionGeneration += 1L
        val generation = sessionGeneration

        // Invalidate the previous session before its socket/RTC callbacks can observe the new one.
        leaveInternal()
        restoreModeOverrideLocked()

        val snapshot = playback.state.value
        modeOverride = PartyModeOverride.capture(generation, mode, snapshot)
        _state.value = PlayTogetherState(
            mode = mode,
            phase = PartyPhase.STARTING,
            transport = PartyTransport.SIGNALING,
            message = message,
        )
        if (snapshot.shuffleEnabled) playback.setShuffleEnabled(false)
        generation
    }

    internal suspend fun finishSession(generation: Long, terminalState: PlayTogetherState) {
        lifecycleMutex.withLock {
            if (!isCurrentSession(generation)) return
            sessionGeneration += 1L
            leaveInternal()
            restoreModeOverrideLocked()
            _state.value = terminalState
        }
    }

    internal suspend fun publishConnectedSession(generation: Long, connectedState: PlayTogetherState): Boolean =
        lifecycleMutex.withLock {
            val current = _state.value
            val currentMode = current.mode
            if (
                !isCurrentSession(generation) ||
                connectedState.phase != PartyPhase.CONNECTED ||
                connectedState.mode != currentMode
            ) {
                false
            }
            else {
                // Permissions/full snapshots and even RTC can arrive before join() reaches this
                // final publication. Merge with that richer state instead of resetting it to the
                // default relay/no-permission values.
                _state.value = mergeConnectedState(current, connectedState)
                // Installation is part of the same lifecycle linearization point. A stale startup
                // can therefore never resume later and cancel/replace a newer session's loop.
                when (currentMode) {
                    PartyMode.HOST -> installHostBroadcastLoopLocked(generation)
                    PartyMode.GUEST -> installGuestPingLoopLocked(generation)
                    PartyMode.NONE -> return@withLock false
                }
                true
            }
        }

    internal suspend fun withCurrentSessionMutation(
        generation: Long,
        mutation: suspend () -> Unit,
    ): Boolean = lifecycleMutex.withLock {
        if (!isCurrentSession(generation)) false
        else {
            mutation()
            true
        }
    }

    private suspend fun restoreModeOverrideLocked() {
        val override = modeOverride ?: return
        modeOverride = null
        val current = playback.state.value
        if (override.role == PartyMode.GUEST) {
            override.speed?.let { if (abs(current.speed - it) > 0.001f) playback.setSpeed(it) }
            override.repeatMode?.let { if (current.repeatMode != it) playback.setRepeatMode(it) }
        }
        if (playback.state.value.shuffleEnabled != override.shuffleEnabled) {
            playback.setShuffleEnabled(override.shuffleEnabled)
        }
    }

    private fun isCurrentSession(generation: Long): Boolean =
        generation != 0L && generation == sessionGeneration && _state.value.mode != PartyMode.NONE

    private fun ensureCurrentSession(generation: Long) {
        check(isCurrentSession(generation)) { "Party session was replaced" }
    }

    private fun errorState(message: String) = PlayTogetherState(
        mode = PartyMode.NONE,
        phase = PartyPhase.ERROR,
        message = message,
    )

    private suspend fun ensureCloudflared(generation: Long): Path = withContext(Dispatchers.IO) {
        ensureCurrentSession(generation)
        val tools = cacheDirectory.resolve("play-together")
        Files.createDirectories(tools)
        val executable = tools.resolve(if (isWindows()) "cloudflared.exe" else "cloudflared")
        if (executable.exists()) return@withContext executable

        require(isWindows()) { "Automatic Quick Tunnel setup is currently packaged for Windows only." }

        if (isCurrentSession(generation)) {
            _state.update { current -> current.copy(message = "Downloading Cloudflare tunnel helper…") }
        }
        val temporary = cloudflaredTemporaryPath(executable, generation)
        try {
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
            ensureCurrentSession(generation)
            try {
                Files.move(temporary, executable, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temporary, executable, StandardCopyOption.REPLACE_EXISTING)
            }
            ensureCurrentSession(generation)
            executable
        } finally {
            cleanupCloudflaredTemporaryFile(temporary)
        }
    }

    private suspend fun startQuickTunnel(cloudflared: Path, port: Int, generation: Long): String {
        ensureCurrentSession(generation)
        val process = ProcessBuilder(
            cloudflared.toAbsolutePath().toString(),
            "tunnel",
            "--no-autoupdate",
            "--url",
            "http://127.0.0.1:$port",
        )
            .redirectErrorStream(true)
            .start()
        if (!isCurrentSession(generation)) {
            process.destroy()
            error("Party session was replaced")
        }
        val publicUrl = CompletableDeferred<String>()
        val logJob = scope.launch(Dispatchers.IO) {
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

        val published = lifecycleMutex.withLock {
            if (!isCurrentSession(generation)) false
            else {
                tunnelProcess = process
                tunnelLogJob = logJob
                true
            }
        }
        if (!published) {
            logJob.cancel()
            process.destroy()
            error("Party session was replaced")
        }

        val url = withTimeout(TUNNEL_START_TIMEOUT_MS) { publicUrl.await() }
        ensureCurrentSession(generation)
        return url
    }

    private fun leaveInternal() {
        hostBroadcastJob?.cancel()
        hostBroadcastJob = null
        guestSyncJob?.cancel()
        guestSyncJob = null

        val staleSignal = guestSignal.getAndSet(GuestSignal(generation = sessionGeneration))
        staleSignal.socket?.sendClose(WebSocket.NORMAL_CLOSURE, "Leaving party")
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
        runBlocking {
            lifecycleMutex.withLock {
                sessionGeneration += 1L
                leaveInternal()
                restoreModeOverrideLocked()
                _state.value = PlayTogetherState()
            }
        }
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
        private val generation: Long,
        private val opened: CompletableDeferred<Unit>,
        private val failed: CompletableDeferred<Throwable>,
    ) : WebSocket.Listener {
        private val textBuffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            if (!isCurrentSession(generation)) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Party session ended")
                return
            }
            val updated = guestSignal.updateAndGet { current ->
                if (
                    current.generation == generation &&
                    (current.socket == null || current.socket === webSocket)
                ) {
                    GuestSignal(generation = generation, socket = webSocket, open = true)
                } else {
                    current
                }
            }
            if (updated.generation != generation || updated.socket !== webSocket || !updated.open) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Party session ended")
                return
            }
            opened.complete(Unit)
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (!isCurrentSession(generation)) return null
            textBuffer.append(data)
            if (last) {
                val complete = textBuffer.toString()
                textBuffer.setLength(0)
                handleGuestSignalMessage(generation, complete)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (!isCurrentSession(generation)) return null
            guestSignal.updateAndGet { current ->
                if (current.generation == generation && current.socket === webSocket) current.copy(open = false) else current
            }
            if (guestChannel?.isOpen != true) markGuestRelay(generation)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            if (!isCurrentSession(generation)) return
            guestSignal.updateAndGet { current ->
                if (current.generation == generation && current.socket === webSocket) current.copy(open = false) else current
            }
            if (!opened.isCompleted && !failed.isCompleted) failed.complete(error)
            logger.log(Level.WARNING, "Listening party signaling websocket failed", error)
            if (guestChannel?.isOpen != true) markGuestRelay(generation)
        }
    }

    private data class GuestSignal(
        val generation: Long = 0L,
        val socket: WebSocket? = null,
        val open: Boolean = false,
    )

    private data class PartyModeOverride(
        val generation: Long,
        val role: PartyMode,
        val shuffleEnabled: Boolean,
        val speed: Float?,
        val repeatMode: RepeatMode?,
    ) {
        companion object {
            fun capture(
                generation: Long,
                role: PartyMode,
                snapshot: PlaybackSnapshot,
            ): PartyModeOverride = PartyModeOverride(
                generation = generation,
                role = role,
                shuffleEnabled = snapshot.shuffleEnabled,
                speed = snapshot.speed.takeIf { role == PartyMode.GUEST },
                repeatMode = snapshot.repeatMode.takeIf { role == PartyMode.GUEST },
            )
        }
    }

    private class HostPeer(
        val id: String,
        val generation: Long,
        @Volatile var signalSession: DefaultWebSocketServerSession?,
    ) {
        @Volatile var peer: PeerConnection? = null
        @Volatile var channel: DataChannel? = null
        @Volatile var directOpen: Boolean = false
        @Volatile var canEditQueue: Boolean = true
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
        private const val MAX_QUEUE_REQUEST_ITEMS = 50
        private const val MAX_QUEUE_MESSAGE_CHARS = 64 * 1024
        private const val MAX_MEDIA_ID_LENGTH = 256

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

        internal fun mergeConnectedState(
            current: PlayTogetherState,
            connected: PlayTogetherState,
        ): PlayTogetherState {
            require(current.mode == connected.mode && connected.phase == PartyPhase.CONNECTED)
            val alreadyNegotiatedTransport =
                current.phase == PartyPhase.CONNECTED ||
                    current.directPeerCount > 0 ||
                    current.participants.isNotEmpty()
            return current.copy(
                phase = PartyPhase.CONNECTED,
                transport = if (alreadyNegotiatedTransport) current.transport else connected.transport,
                invite = connected.invite ?: current.invite,
                participantCount = maxOf(current.participantCount, connected.participantCount),
                directPeerCount = if (alreadyNegotiatedTransport) current.directPeerCount else connected.directPeerCount,
                message = if (current.phase == PartyPhase.CONNECTED && !current.message.isNullOrBlank()) {
                    current.message
                } else {
                    connected.message ?: current.message
                },
            )
        }

        internal fun queueSignature(snapshot: PlaybackSnapshot): String {
            val entries = snapshot.queueEntries
            if (entries.size == snapshot.queue.size && entries.isNotEmpty()) {
                return buildString {
                    append(snapshot.queueTitle)
                    entries.forEach { entry ->
                        append('|')
                            .append(entry.id)
                            .append('@')
                            .append(entry.logicalIndex)
                            .append(':')
                            .append(entry.item.metadata.id)
                    }
                }
            }
            return fallbackQueueSignature(snapshot.queueTitle, snapshot.queue.map { it.metadata.id })
        }

        internal fun cloudflaredTemporaryPath(executable: Path, generation: Long): Path =
            executable.resolveSibling("${executable.fileName}.part.$generation")

        internal fun cleanupCloudflaredTemporaryFile(temporary: Path): Boolean =
            runCatching { Files.deleteIfExists(temporary) }.getOrDefault(false)

        internal fun fallbackQueueSignature(queueTitle: String?, mediaIds: List<String>): String = buildString {
            append("fallback:")
            appendLengthPrefixed(queueTitle)
            mediaIds.forEach { appendLengthPrefixed(it) }
        }

        private fun StringBuilder.appendLengthPrefixed(value: String?) {
            if (value == null) {
                append("-1:")
            } else {
                append(value.length).append(':').append(value)
            }
        }

        private fun pongMessage(nonce: Long): String =
            JSONObject().put("type", "pong").put("nonce", nonce).toString()

        private fun encodePermissions(hostPeer: HostPeer): String = JSONObject()
            .put("type", "permissions")
            .put("canEditQueue", hostPeer.canEditQueue)
            .toString()

        private fun encodeFullSnapshot(snapshot: PlaybackSnapshot): String = JSONObject()
            .put("type", "full")
            .put("queueRevision", queueSignature(snapshot))
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
    val canEditQueue: Boolean = false,
    val queueRevision: String? = null,
    val participants: List<PartyParticipant> = emptyList(),
) {
    val isConnectedGuest: Boolean
        get() = mode == PartyMode.GUEST && phase == PartyPhase.CONNECTED

    val localTransportCommandsAllowed: Boolean
        get() = !isConnectedGuest

    val persistSpeedAndRepeat: Boolean
        get() = mode != PartyMode.GUEST

    val persistShuffle: Boolean
        get() = mode == PartyMode.NONE
}

data class PartyParticipant(
    val id: String,
    val name: String,
    val canEditQueue: Boolean = true,
)
