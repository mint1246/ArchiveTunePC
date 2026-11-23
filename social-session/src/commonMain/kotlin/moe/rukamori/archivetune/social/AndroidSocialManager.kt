/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AndroidSocialManager(
    private val scope: CoroutineScope,
    private val api: SocialApi,
    private val sessionStore: SocialSessionStore,
    private val login: suspend () -> StoredSocialSession,
    private val realtime: SocialRealtimeTransport,
    private val nowMillis: () -> Long = ::socialSessionEpochMillis,
    private val deviceId: String = randomSocialDeviceId(),
) : AutoCloseable {
    private val _state = MutableStateFlow(AndroidSocialState())
    val state: StateFlow<AndroidSocialState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SocialRealtimeEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SocialRealtimeEvent> = _events.asSharedFlow()
    private val lock = SocialSessionLock()
    private var closed = false
    private var session: StoredSocialSession? = null
    private var realtimeJob: Job? = null
    @Volatile private var latestPresence = SocialPresence()

    private fun isClosed(): Boolean = lock.withLock { closed }

    suspend fun restore() {
        if (isClosed()) return
        val stored = runCatching { sessionStore.read() }.getOrNull() ?: return
        if (stored.expiresAt <= nowMillis()) {
            runCatching { sessionStore.clear() }
            return
        }
        try {
            activate(stored)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error is SocialApiException && error.statusCode == 401) {
                session = null
                runCatching { sessionStore.clear() }
                _state.value = AndroidSocialState()
            } else {
                _state.update { it.copy(error = error.message ?: "Could not restore ArchiveTune social session") }
            }
        }
    }

    suspend fun signIn(): Result<Unit> = try {
        _state.update { it.copy(account = SocialAccountState.SigningIn, error = null) }
        val stored = login()
        sessionStore.write(stored)
        activate(stored)
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        _state.update { it.copy(account = SocialAccountState.SignedOut) }
        throw cancelled
    } catch (error: Throwable) {
        _state.update {
            it.copy(account = SocialAccountState.SignedOut, error = error.message ?: "Discord sign-in failed")
        }
        Result.failure(error)
    }

    suspend fun logout() {
        val current = session
        session = null
        stopRealtime()
        current?.let { runCatching { api.logout(it.token) } }
        runCatching { sessionStore.clear() }
        _state.value = AndroidSocialState()
    }

    suspend fun refresh() {
        val current = session ?: return
        _state.update { it.copy(refreshing = true, error = null) }
        try {
            val (friends, requests, presence) = coroutineScope {
                val friends = async { api.friends(current.token) }
                val requests = async { api.requests(current.token) }
                val presence = async { api.presence(current.token) }
                Triple(friends.await(), requests.await(), presence.await())
            }
            if (session?.token != current.token) return
            _state.update {
                it.copy(
                    friends = friends,
                    incomingRequests = requests.first,
                    outgoingRequests = requests.second,
                    presence = presence,
                    refreshing = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (session?.token == current.token) {
                _state.update { it.copy(refreshing = false, error = error.message ?: "Could not refresh friends") }
            }
        }
    }

    suspend fun search(query: String): Result<List<SocialUser>> =
        withSession { api.search(it.token, query.trim()) }

    suspend fun sendFriendRequest(userId: String): Result<Unit> = mutateAndRefresh {
        api.sendFriendRequest(it.token, userId)
    }

    suspend fun acceptFriendRequest(userId: String): Result<Unit> = mutateAndRefresh {
        api.acceptFriendRequest(it.token, userId)
    }

    suspend fun declineFriendRequest(userId: String): Result<Unit> = mutateAndRefresh {
        api.declineFriendRequest(it.token, userId)
    }

    suspend fun removeFriend(userId: String): Result<Unit> = mutateAndRefresh {
        api.removeFriend(it.token, userId)
    }

    suspend fun block(userId: String): Result<Unit> = mutateAndRefresh {
        api.block(it.token, userId)
    }

    suspend fun sendPartyInvite(userId: String, partyUrl: String): Result<Unit> {
        val inviteUrl =
            canonicalizeQuickTunnelInviteUrl(partyUrl)
                ?: return Result.failure(IllegalArgumentException("Party invite must be a Cloudflare Quick Tunnel URL"))
        return withSession { api.sendPartyInvite(it.token, userId, inviteUrl) }
    }

    suspend fun loadChat(userId: String): Result<List<SocialChatMessage>> {
        val current = session ?: return signedOutFailure()
        _state.update { it.copy(loadingChats = it.loadingChats + userId, error = null) }
        return try {
            val messages = api.chatMessages(current.token, userId)
            if (session?.token == current.token) {
                _state.update {
                    it.copy(
                        chats = it.chats + (userId to normalizeMessages(messages)),
                        loadingChats = it.loadingChats - userId,
                    )
                }
            }
            Result.success(messages)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.update {
                it.copy(
                    loadingChats = it.loadingChats - userId,
                    error = error.message ?: "Could not load chat",
                )
            }
            Result.failure(error)
        }
    }

    suspend fun loadFriendStatistics(userId: String): Result<SocialStatisticsSnapshot> {
        val current = session ?: return signedOutFailure()
        _state.update { it.copy(loadingFriendStatistics = it.loadingFriendStatistics + userId, error = null) }
        return try {
            val snapshot = api.friendStatistics(current.token, userId)
            if (session?.token == current.token) {
                _state.update {
                    it.copy(
                        friendStatistics = it.friendStatistics + (userId to snapshot),
                        loadingFriendStatistics = it.loadingFriendStatistics - userId,
                    )
                }
            }
            Result.success(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.update {
                it.copy(
                    loadingFriendStatistics = it.loadingFriendStatistics - userId,
                    error = error.message ?: "Could not load friend statistics",
                )
            }
            Result.failure(error)
        }
    }

    suspend fun sendText(userId: String, text: String): Result<SocialChatMessage> {
        val value = text.trim()
        if (value.isEmpty()) return Result.failure(IllegalArgumentException("Message cannot be empty"))
        if (value.length > 2_000) return Result.failure(IllegalArgumentException("Message is too long"))
        return sendMessage(userId, SocialOutgoingMessage.Text(value))
    }

    suspend fun sendSong(userId: String, song: SocialSharedSong): Result<SocialChatMessage> =
        sendMessage(userId, SocialOutgoingMessage.Song(song))

    suspend fun sendQueue(userId: String, queue: SocialSharedQueue): Result<SocialChatMessage> =
        if (queue.songs.isEmpty()) {
            Result.failure(IllegalArgumentException("Queue cannot be empty"))
        } else {
            sendMessage(userId, SocialOutgoingMessage.Queue(queue.copy(songs = queue.songs.take(50))))
        }

    suspend fun sendPlaylist(userId: String, playlist: SocialSharedPlaylist): Result<SocialChatMessage> =
        sendMessage(userId, SocialOutgoingMessage.Playlist(playlist))

    suspend fun uploadStatistics(snapshot: SocialStatisticsSnapshot): Result<Unit> =
        withSession { api.uploadStatistics(it.token, snapshot) }

    fun publishPresence(presence: SocialPresence) {
        latestPresence = presence
        if (session == null || isClosed()) return
        scope.launch { runCatching { realtime.publishPresence(presence) } }
    }

    fun dismissPartyInvite(invite: SocialPartyInvite) {
        _state.update { current ->
            current.copy(partyInvites = current.partyInvites.filterNot { it == invite })
        }
    }

    override fun close() {
        val shouldClose = lock.withLock {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!shouldClose) return
        stopRealtime()
        realtime.close()
    }

    private suspend fun activate(stored: StoredSocialSession) {
        val user = api.me(stored.token)
        if (isClosed()) return
        session = stored
        _state.update { it.copy(account = SocialAccountState.SignedIn(user), error = null) }
        refresh()
        startRealtime(stored.token)
    }

    private fun startRealtime(token: String) {
        stopRealtime()
        realtimeJob = scope.launch {
            var retryDelay = 1_000L
            while (!isClosed() && session?.token == token) {
                try {
                    realtime.connect(
                        token = token,
                        deviceId = deviceId,
                        onConnected = {
                            retryDelay = 1_000L
                            realtime.publishPresence(latestPresence)
                        },
                        onMessage = ::handleRealtimeMessage,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // REST stays authoritative; reconnect below while the same session is active.
                }
                if (!isClosed() && session?.token == token) {
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private fun stopRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        realtime.disconnect()
    }

    private fun handleRealtimeMessage(text: String) {
        when (val frame = SocialWireJson.parseRealtimeFrame(text, nowMillis)) {
            is SocialRealtimeFrame.InitialPresence -> {
                _state.update { it.copy(presence = it.presence + frame.presence) }
            }
            is SocialRealtimeFrame.FriendPresence -> {
                _state.update { it.copy(presence = it.presence + (frame.userId to frame.presence)) }
            }
            is SocialRealtimeFrame.FriendRequest -> {
                frame.from?.let { _events.tryEmit(SocialRealtimeEvent.FriendRequest(it)) }
                scope.launch { refresh() }
            }
            is SocialRealtimeFrame.FriendAccepted -> {
                frame.user?.let { _events.tryEmit(SocialRealtimeEvent.FriendAccepted(it)) }
                scope.launch { refresh() }
            }
            SocialRealtimeFrame.FriendRemoved -> scope.launch { refresh() }
            is SocialRealtimeFrame.PartyInvite -> {
                _state.update { current ->
                    current.copy(
                        partyInvites = (listOf(frame.invite) + current.partyInvites)
                            .distinctBy(SocialPartyInvite::partyUrl)
                            .take(10),
                    )
                }
                _events.tryEmit(SocialRealtimeEvent.PartyInvite(frame.invite))
            }
            is SocialRealtimeFrame.ChatMessage -> {
                val ownUserId = (_state.value.account as? SocialAccountState.SignedIn)?.user?.id ?: return
                val friendId = if (frame.message.senderId == ownUserId) frame.message.receiverId else frame.message.senderId
                appendChatMessage(friendId, frame.message)
                _events.tryEmit(SocialRealtimeEvent.ChatMessage(frame.message))
            }
            null -> Unit
        }
    }

    private suspend fun sendMessage(
        userId: String,
        message: SocialOutgoingMessage,
    ): Result<SocialChatMessage> {
        val current = session ?: return signedOutFailure()
        return try {
            val sent = api.sendChatMessage(current.token, userId, message)
            appendChatMessage(userId, sent)
            Result.success(sent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message ?: "Could not send message") }
            Result.failure(error)
        }
    }

    private fun appendChatMessage(userId: String, message: SocialChatMessage) {
        _state.update { current ->
            current.copy(
                chats = current.chats + (userId to normalizeMessages(current.chats[userId].orEmpty() + message)),
            )
        }
    }

    private suspend fun mutateAndRefresh(block: suspend (StoredSocialSession) -> Unit): Result<Unit> =
        withSession {
            block(it)
            refresh()
        }

    private suspend fun <T> withSession(block: suspend (StoredSocialSession) -> T): Result<T> {
        val current = session ?: return signedOutFailure()
        return try {
            Result.success(block(current))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message) }
            Result.failure(error)
        }
    }

    private fun <T> signedOutFailure(): Result<T> =
        Result.failure(IllegalStateException("Sign in with Discord first"))
}

internal fun normalizeMessages(messages: List<SocialChatMessage>): List<SocialChatMessage> =
    messages.distinctBy(SocialChatMessage::id).sortedBy(SocialChatMessage::createdAt)
