/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import android.content.Context
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
import moe.rukamori.archivetune.together.TogetherQuickTunnelInvite
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class AndroidSocialManager(
    private val scope: CoroutineScope,
    private val api: SocialApi,
    private val sessionStore: SocialSessionStore,
    private val login: AndroidSocialLogin,
    private val realtime: SocialRealtimeTransport,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val deviceId: String = UUID.randomUUID().toString(),
) : AutoCloseable {
    private val _state = MutableStateFlow(AndroidSocialState())
    val state: StateFlow<AndroidSocialState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SocialRealtimeEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SocialRealtimeEvent> = _events.asSharedFlow()
    private val closed = AtomicBoolean(false)
    private var session: StoredSocialSession? = null
    private var realtimeJob: Job? = null
    @Volatile private var latestPresence = SocialPresence()

    suspend fun restore() {
        if (closed.get()) return
        val stored = runCatching { sessionStore.read() }.getOrNull() ?: return
        if (stored.expiresAt <= nowMillis()) {
            runCatching { sessionStore.clear() }
            return
        }
        runCatching { activate(stored) }.onFailure { error ->
            if (error is SocialApiException && error.statusCode == 401) {
                session = null
                runCatching { sessionStore.clear() }
                _state.value = AndroidSocialState()
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
            it.copy(account = SocialAccountState.SignedOut, error = error.message ?: "Discord sign-in failed")
        }
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
        runCatching {
            val (friends, requests, presence) = coroutineScope {
                val friends = async { api.friends(current.token) }
                val requests = async { api.requests(current.token) }
                val presence = async { api.presence(current.token) }
                Triple(friends.await(), requests.await(), presence.await())
            }
            if (session?.token != current.token) return@runCatching
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
        val invite =
            TogetherQuickTunnelInvite.decode(partyUrl)
                ?: return Result.failure(IllegalArgumentException("Party invite must be a Cloudflare Quick Tunnel URL"))
        return withSession { api.sendPartyInvite(it.token, userId, invite.inviteUrl) }
    }

    suspend fun loadChat(userId: String): Result<List<SocialChatMessage>> {
        val current = session ?: return signedOutFailure()
        _state.update { it.copy(loadingChats = it.loadingChats + userId, error = null) }
        return runCatching { api.chatMessages(current.token, userId) }
            .onSuccess { messages ->
                if (session?.token == current.token) {
                    _state.update {
                        it.copy(
                            chats = it.chats + (userId to normalizeMessages(messages)),
                            loadingChats = it.loadingChats - userId,
                        )
                    }
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

    suspend fun loadFriendStatistics(userId: String): Result<SocialStatisticsSnapshot> {
        val current = session ?: return signedOutFailure()
        _state.update { it.copy(loadingFriendStatistics = it.loadingFriendStatistics + userId, error = null) }
        return runCatching { api.friendStatistics(current.token, userId) }
            .onSuccess { snapshot ->
                if (session?.token == current.token) {
                    _state.update {
                        it.copy(
                            friendStatistics = it.friendStatistics + (userId to snapshot),
                            loadingFriendStatistics = it.loadingFriendStatistics - userId,
                        )
                    }
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
        if (session == null || closed.get()) return
        scope.launch { runCatching { realtime.publishPresence(presence) } }
    }

    fun dismissPartyInvite(invite: SocialPartyInvite) {
        _state.update { current ->
            current.copy(partyInvites = current.partyInvites.filterNot { it == invite })
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopRealtime()
        realtime.close()
    }

    private suspend fun activate(stored: StoredSocialSession) {
        val user = api.me(stored.token)
        if (closed.get()) return
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
        realtime.disconnect()
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
                        put(userId, parseSocialPresence(entry.optJSONObject("presence") ?: JSONObject()))
                    }
                }
                _state.update { it.copy(presence = it.presence + updates) }
            }
            "friend_presence" -> {
                val userId = value.optString("userId").takeIf(String::isNotBlank) ?: return
                val presence = value.optJSONObject("presence")?.let(::parseSocialPresence) ?: return
                _state.update { it.copy(presence = it.presence + (userId to presence)) }
            }
            "friend_request" -> {
                value.optJSONObject("from")?.let(::parseSocialUser)?.let {
                    _events.tryEmit(SocialRealtimeEvent.FriendRequest(it))
                }
                scope.launch { refresh() }
            }
            "friend_accepted" -> {
                value.optJSONObject("user")?.let(::parseSocialUser)?.let {
                    _events.tryEmit(SocialRealtimeEvent.FriendAccepted(it))
                }
                scope.launch { refresh() }
            }
            "friend_removed" -> scope.launch { refresh() }
            "party_invite" -> {
                val sender = value.optJSONObject("sender")?.let(::parseSocialUser) ?: return
                val partyUrl = value.optString("partyUrl").takeIf(String::isNotBlank) ?: return
                val invite = SocialPartyInvite(sender, partyUrl, value.optLong("sentAt", nowMillis()))
                _state.update { current ->
                    current.copy(
                        partyInvites = (listOf(invite) + current.partyInvites)
                            .distinctBy(SocialPartyInvite::partyUrl)
                            .take(10),
                    )
                }
                _events.tryEmit(SocialRealtimeEvent.PartyInvite(invite))
            }
            "message" -> {
                val message = value.optJSONObject("message")?.let(::parseSocialChatMessage) ?: return
                val ownUserId = (_state.value.account as? SocialAccountState.SignedIn)?.user?.id ?: return
                val friendId = if (message.senderId == ownUserId) message.receiverId else message.senderId
                appendChatMessage(friendId, message)
                _events.tryEmit(SocialRealtimeEvent.ChatMessage(message))
            }
        }
    }

    private suspend fun sendMessage(
        userId: String,
        message: SocialOutgoingMessage,
    ): Result<SocialChatMessage> {
        val current = session ?: return signedOutFailure()
        return runCatching { api.sendChatMessage(current.token, userId, message) }
            .onSuccess { sent -> appendChatMessage(userId, sent) }
            .onFailure { error -> _state.update { it.copy(error = error.message ?: "Could not send message") } }
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
        return runCatching { block(current) }.onFailure { error ->
            _state.update { it.copy(error = error.message) }
        }
    }

    private fun <T> signedOutFailure(): Result<T> =
        Result.failure(IllegalStateException("Sign in with Discord first"))

    companion object {
        fun create(
            context: Context,
            scope: CoroutineScope,
            baseUrl: String = AndroidSocialApi.DEFAULT_BASE_URL,
        ): AndroidSocialManager {
            val api = AndroidSocialApi(baseUrl)
            return AndroidSocialManager(
                scope = scope,
                api = api,
                sessionStore = AndroidSocialSessionStore(context),
                login = AndroidSocialLogin(context, api),
                realtime = AndroidSocialRealtime(api),
            )
        }
    }
}

internal fun normalizeMessages(messages: List<SocialChatMessage>): List<SocialChatMessage> =
    messages.distinctBy(SocialChatMessage::id).sortedBy(SocialChatMessage::createdAt)
