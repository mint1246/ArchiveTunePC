package moe.rukamori.archivetune.desktop.social

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

class DesktopSocialManager(
    private val scope: CoroutineScope,
    private val api: DesktopSocialApi,
    private val sessionStore: DesktopSocialSessionStore,
) : AutoCloseable {
    private val login = DesktopDiscordLogin(api)
    private val _state = MutableStateFlow(DesktopSocialState())
    val state: StateFlow<DesktopSocialState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SocialRealtimeEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SocialRealtimeEvent> = _events.asSharedFlow()
    private val closed = AtomicBoolean(false)
    private val deviceId = randomId(12)
    private var session: StoredSocialSession? = null
    private var realtimeJob: Job? = null
    @Volatile private var realtime: DesktopSocialRealtime? = null
    @Volatile private var latestPresence = SocialPresence()

    suspend fun restore() {
        if (closed.get()) return
        val stored = runCatching(sessionStore::read).getOrNull() ?: return
        if (stored.expiresAt <= System.currentTimeMillis()) {
            runCatching(sessionStore::delete)
            return
        }
        runCatching { activate(stored) }
            .onFailure { error ->
                if (error is SocialApiException && error.statusCode == 401) {
                    session = null
                    runCatching(sessionStore::delete)
                    _state.value = DesktopSocialState()
                } else {
                    _state.update { it.copy(error = error.message ?: "Could not restore ArchiveTune social session") }
                }
            }
    }

    suspend fun signIn(): Result<Unit> = runCatching {
        _state.update { it.copy(account = SocialAccountState.SigningIn, error = null) }
        val stored = login.signIn()
        sessionStore.write(stored)
        activate(stored)
    }.onFailure { error ->
        _state.update {
            it.copy(
                account = SocialAccountState.SignedOut,
                error = error.message ?: "Discord sign-in failed",
            )
        }
    }

    suspend fun logout() {
        val current = session
        session = null
        stopRealtime()
        current?.let { runCatching { api.logout(it.token) } }
        runCatching(sessionStore::delete)
        _state.value = DesktopSocialState()
    }

    suspend fun refresh() {
        val current = session ?: return
        _state.update { it.copy(refreshing = true, error = null) }
        runCatching {
            val (friends, requests, presence) = coroutineScope {
                val friends = async { api.friends(current.token) }
                val requests = async { api.requests(current.token) }
                val presence = async { api.presence(current.token) }
                Triple(friends.await(), requests.await(), presence.await())
            }
            _state.update {
                it.copy(
                    friends = friends,
                    incomingRequests = requests.first,
                    outgoingRequests = requests.second,
                    presence = presence,
                    refreshing = false,
                )
            }
        }.onFailure { error ->
            _state.update {
                it.copy(refreshing = false, error = error.message ?: "Could not refresh friends")
            }
        }
    }

    suspend fun search(query: String): Result<List<SocialUser>> =
        withSession { api.search(it.token, query.trim()) }

    suspend fun sendFriendRequest(userId: String): Result<Unit> =
        withSession {
            api.sendFriendRequest(it.token, userId)
            refresh()
        }

    suspend fun acceptFriendRequest(userId: String): Result<Unit> =
        withSession {
            api.acceptFriendRequest(it.token, userId)
            refresh()
        }

    suspend fun declineFriendRequest(userId: String): Result<Unit> =
        withSession {
            api.declineFriendRequest(it.token, userId)
            refresh()
        }

    suspend fun removeFriend(userId: String): Result<Unit> =
        withSession {
            api.removeFriend(it.token, userId)
            refresh()
        }

    suspend fun block(userId: String): Result<Unit> =
        withSession {
            api.block(it.token, userId)
            refresh()
        }

    suspend fun sendPartyInvite(userId: String, partyUrl: String): Result<Unit> =
        withSession { api.sendPartyInvite(it.token, userId, partyUrl) }

    suspend fun uploadStatistics(snapshot: SocialStatisticsSnapshot): Result<Unit> =
        withSession { api.uploadStatistics(it.token, snapshot) }

    suspend fun loadFriendStatistics(userId: String): Result<SocialStatisticsSnapshot> {
        val current = session ?: return Result.failure(IllegalStateException("Sign in with Discord first"))
        _state.update { it.copy(loadingFriendStatistics = it.loadingFriendStatistics + userId, error = null) }
        return runCatching { api.friendStatistics(current.token, userId) }
            .onSuccess { snapshot ->
                _state.update {
                    it.copy(
                        friendStatistics = it.friendStatistics + (userId to snapshot),
                        loadingFriendStatistics = it.loadingFriendStatistics - userId,
                    )
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        loadingFriendStatistics = it.loadingFriendStatistics - userId,
                        error = error.message ?: "Could not load friend statistics",
                    )
                }
            }
    }

    suspend fun loadChat(userId: String): Result<List<SocialChatMessage>> {
        val current = session ?: return Result.failure(IllegalStateException("Sign in with Discord first"))
        _state.update { it.copy(loadingChats = it.loadingChats + userId, error = null) }
        return runCatching { api.chatMessages(current.token, userId) }
            .onSuccess { messages ->
                _state.update {
                    it.copy(
                        chats = it.chats + (userId to messages),
                        loadingChats = it.loadingChats - userId,
                    )
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        loadingChats = it.loadingChats - userId,
                        error = error.message ?: "Could not load chat",
                    )
                }
            }
    }

    suspend fun sendText(userId: String, text: String): Result<SocialChatMessage> =
        sendChatMessage(
            userId,
            SocialChatMessageKind.TEXT,
            JSONObject().put("text", text.trim()),
        )

    suspend fun sendSong(userId: String, song: SocialSharedSong): Result<SocialChatMessage> =
        sendChatMessage(
            userId,
            SocialChatMessageKind.SONG,
            song.toJson(),
        )

    suspend fun sendQueue(userId: String, queue: SocialSharedQueue): Result<SocialChatMessage> =
        sendChatMessage(
            userId,
            SocialChatMessageKind.QUEUE,
            queue.toJson(),
        )

    suspend fun sendPlaylist(userId: String, playlist: SocialSharedPlaylist): Result<SocialChatMessage> =
        sendChatMessage(
            userId,
            SocialChatMessageKind.PLAYLIST,
            playlist.toJson(),
        )

    fun dismissPartyInvite(invite: SocialPartyInvite) {
        _state.update { current ->
            current.copy(partyInvites = current.partyInvites.filterNot { it == invite })
        }
    }

    fun publishPresence(presence: SocialPresence) {
        latestPresence = presence
        realtime?.publishPresence(presence)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopRealtime()
    }

    private suspend fun activate(stored: StoredSocialSession) {
        val user = api.me(stored.token)
        session = stored
        _state.update { it.copy(account = SocialAccountState.SignedIn(user), error = null) }
        refresh()
        startRealtime(stored.token)
    }

    private fun startRealtime(token: String) {
        stopRealtime()
        realtimeJob = scope.launch {
            var retryDelay = 1_000L
            while (!closed.get() && session?.token == token) {
                val connection = DesktopSocialRealtime(api, token, deviceId, ::handleRealtimeMessage)
                realtime = connection
                try {
                    connection.connect()
                    retryDelay = 1_000L
                    connection.publishPresence(latestPresence)
                    connection.awaitClose()
                } catch (_: Throwable) {
                    // REST data stays authoritative; realtime reconnects with bounded backoff.
                } finally {
                    if (realtime === connection) realtime = null
                    connection.close()
                }
                if (!closed.get() && session?.token == token) {
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private fun stopRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        realtime?.close()
        realtime = null
    }

    private fun handleRealtimeMessage(text: String) {
        val value = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (value.optString("type")) {
            "presence.initial" -> {
                val friends = value.optJSONArray("friends") ?: return
                val updates = buildMap {
                    for (index in 0 until friends.length()) {
                        val entry = friends.optJSONObject(index) ?: continue
                        val userId = entry.optString("userId").takeIf(String::isNotBlank) ?: continue
                        put(userId, parsePresence(entry.optJSONObject("presence") ?: JSONObject()))
                    }
                }
                _state.update { it.copy(presence = it.presence + updates) }
            }
            "friend_presence" -> {
                val userId = value.optString("userId").takeIf(String::isNotBlank) ?: return
                val presence = parsePresence(value.optJSONObject("presence") ?: return)
                _state.update { it.copy(presence = it.presence + (userId to presence)) }
            }
            "friend_request" -> {
                value.optJSONObject("from")?.let(::parseUser)?.let { _events.tryEmit(SocialRealtimeEvent.FriendRequest(it)) }
                scope.launch { refresh() }
            }
            "friend_accepted" -> {
                value.optJSONObject("user")?.let(::parseUser)?.let { _events.tryEmit(SocialRealtimeEvent.FriendAccepted(it)) }
                scope.launch { refresh() }
            }
            "friend_removed" -> scope.launch { refresh() }
            "party_invite" -> {
                val sender = value.optJSONObject("sender")?.let(::parseUser) ?: return
                val partyUrl = value.optString("partyUrl").takeIf(String::isNotBlank) ?: return
                val invite = SocialPartyInvite(sender, partyUrl, value.optLong("sentAt", System.currentTimeMillis()))
                _state.update { current ->
                    current.copy(
                        partyInvites = (listOf(invite) + current.partyInvites)
                            .distinctBy { it.partyUrl }
                            .take(10),
                    )
                }
                _events.tryEmit(SocialRealtimeEvent.PartyInvite(invite))
            }
            "message" -> {
                val message = value.optJSONObject("message")?.let(::parseChatMessage) ?: return
                val currentUserId = (state.value.account as? SocialAccountState.SignedIn)?.user?.id ?: return
                val friendId = if (message.senderId == currentUserId) message.receiverId else message.senderId
                appendChatMessage(friendId, message)
                _events.tryEmit(SocialRealtimeEvent.ChatMessage(message))
            }
        }
    }

    private suspend fun sendChatMessage(
        userId: String,
        kind: SocialChatMessageKind,
        payload: JSONObject,
    ): Result<SocialChatMessage> {
        val current = session ?: return Result.failure(IllegalStateException("Sign in with Discord first"))
        return runCatching { api.sendChatMessage(current.token, userId, kind, payload) }
            .onSuccess { message -> appendChatMessage(userId, message) }
            .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not send message") } }
    }

    private fun appendChatMessage(userId: String, message: SocialChatMessage) {
        _state.update { current ->
            val messages = (current.chats[userId].orEmpty() + message)
                .distinctBy(SocialChatMessage::id)
                .sortedBy(SocialChatMessage::createdAt)
            current.copy(chats = current.chats + (userId to messages))
        }
    }

    private suspend fun <T> withSession(block: suspend (StoredSocialSession) -> T): Result<T> {
        val current = session ?: return Result.failure(IllegalStateException("Sign in with Discord first"))
        return runCatching { block(current) }.onFailure { error ->
            _state.update { it.copy(error = error.message) }
        }
    }

    private fun randomId(bytes: Int): String {
        val value = ByteArray(bytes)
        SecureRandom().nextBytes(value)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}
