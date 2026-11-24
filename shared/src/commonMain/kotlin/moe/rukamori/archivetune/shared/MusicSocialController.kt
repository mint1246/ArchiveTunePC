/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.sdk.lifecycle.SdkLifecycle
import moe.rukamori.archivetune.social.AndroidSocialManager
import moe.rukamori.archivetune.social.AndroidSocialState
import moe.rukamori.archivetune.social.KtorSocialClient
import moe.rukamori.archivetune.social.NoOpSocialRealtimeTransport
import moe.rukamori.archivetune.social.SocialAccountState
import moe.rukamori.archivetune.social.SocialApi
import moe.rukamori.archivetune.social.SocialChatMessage
import moe.rukamori.archivetune.social.SocialPresence
import moe.rukamori.archivetune.social.SocialSessionCoordinator
import moe.rukamori.archivetune.social.SocialSessionStore
import moe.rukamori.archivetune.social.SocialSharedPlaylist
import moe.rukamori.archivetune.social.SocialSharedQueue
import moe.rukamori.archivetune.social.SocialSharedSong
import moe.rukamori.archivetune.social.SocialStatisticsSnapshot
import moe.rukamori.archivetune.social.SocialUser
import moe.rukamori.archivetune.social.StoredSocialSession

data class MusicSocialUser(val id: String, val name: String, val username: String)

data class MusicSocialState(
    val signingIn: Boolean = false,
    val account: MusicSocialUser? = null,
    val friends: List<MusicSocialUser> = emptyList(),
    val error: String? = null,
)

private class MemorySocialSessionStore : SocialSessionStore {
    private var current: StoredSocialSession? = null
    override suspend fun read(): StoredSocialSession? = current
    override suspend fun write(session: StoredSocialSession) { current = session }
    override suspend fun clear() { current = null }
}

/**
 * Delegates Discord sign-in, Keychain session persistence, Friends, Requests, Chat, Presence,
 * and Friend Statistics to `social-sdk` ([KtorSocialClient]) and `social-session` ([AndroidSocialManager]).
 */
class MusicSocialController private constructor(
    private val manager: AndroidSocialManager?,
    private val coordinator: SocialSessionCoordinator?,
    scope: CoroutineScope,
    private val ownedHttp: HttpClient? = null,
) : AutoCloseable {
    constructor(
        coordinator: SocialSessionCoordinator,
        scope: CoroutineScope,
        ownedHttp: HttpClient? = null,
    ) : this(
        manager = null,
        coordinator = coordinator,
        scope = scope,
        ownedHttp = ownedHttp,
    )

    constructor(
        http: HttpClient,
        scope: CoroutineScope,
        login: suspend (url: String, nonce: String) -> String,
        nonce: () -> String,
        api: SocialApi = KtorSocialClient(http),
        sessionStore: SocialSessionStore? = null,
    ) : this(
        manager = AndroidSocialManager(
            scope = scope,
            api = api,
            sessionStore = sessionStore ?: MemorySocialSessionStore(),
            login = {
                val state = nonce().also { require(it.length >= 16) { "Invalid OAuth nonce" } }
                val authUrl = api.discordLoginUrl(49152, state)
                val code = login(authUrl, state)
                api.exchangeAuthCode(code, state)
            },
            realtime = NoOpSocialRealtimeTransport,
        ),
        coordinator = null,
        scope = scope,
        ownedHttp = http,
    )

    private val lifecycle = SdkLifecycle.forScope(scope)
    private val socialScope = lifecycle.scope()
    private val mutableState = MutableStateFlow(MusicSocialState())
    val state: StateFlow<MusicSocialState> = mutableState.asStateFlow()
    val sdkState: StateFlow<AndroidSocialState> = manager?.state ?: coordinator!!.state

    init {
        ownedHttp?.let { client -> lifecycle.register(AutoCloseable { client.close() }) }
        manager?.let { lifecycle.register(it) }
        coordinator?.let { lifecycle.register(it) }
        socialScope.launch {
            sdkState.collect { social ->
                mutableState.value = MusicSocialState(
                    signingIn = social.account is SocialAccountState.SigningIn,
                    account = (social.account as? SocialAccountState.SignedIn)?.user?.toMusicSocialUser(),
                    friends = social.friends.map { it.toMusicSocialUser() },
                    error = social.error,
                )
            }
        }
        if (manager != null) {
            socialScope.launch { manager.restore() }
        }
    }

    fun signIn() {
        if (manager != null) socialScope.launch { manager.signIn() }
        else coordinator?.signIn()
    }

    fun refresh() {
        if (manager != null) socialScope.launch { manager.refresh() }
        else coordinator?.refresh()
    }

    fun signOut() {
        if (manager != null) socialScope.launch { manager.logout() }
        else coordinator?.signOut()
    }

    suspend fun searchUsers(query: String): Result<List<SocialUser>> =
        manager?.search(query) ?: Result.failure(UnsupportedOperationException("Search unavailable"))

    suspend fun sendFriendRequest(userId: String): Result<Unit> =
        manager?.sendFriendRequest(userId) ?: Result.failure(UnsupportedOperationException("Requests unavailable"))

    suspend fun acceptFriendRequest(userId: String): Result<Unit> =
        manager?.acceptFriendRequest(userId) ?: Result.failure(UnsupportedOperationException("Requests unavailable"))

    suspend fun declineFriendRequest(userId: String): Result<Unit> =
        manager?.declineFriendRequest(userId) ?: Result.failure(UnsupportedOperationException("Requests unavailable"))

    suspend fun removeFriend(userId: String): Result<Unit> =
        manager?.removeFriend(userId) ?: Result.failure(UnsupportedOperationException("Remove unavailable"))

    suspend fun blockUser(userId: String): Result<Unit> =
        manager?.block(userId) ?: Result.failure(UnsupportedOperationException("Block unavailable"))

    suspend fun loadChat(userId: String): Result<List<SocialChatMessage>> =
        manager?.loadChat(userId) ?: Result.failure(UnsupportedOperationException("Chat unavailable"))

    suspend fun sendText(userId: String, text: String): Result<SocialChatMessage> =
        manager?.sendText(userId, text) ?: Result.failure(UnsupportedOperationException("Chat unavailable"))

    suspend fun sendSong(userId: String, song: SocialSharedSong): Result<SocialChatMessage> =
        manager?.sendSong(userId, song) ?: Result.failure(UnsupportedOperationException("Chat unavailable"))

    suspend fun sendQueue(userId: String, queue: SocialSharedQueue): Result<SocialChatMessage> =
        manager?.sendQueue(userId, queue) ?: Result.failure(UnsupportedOperationException("Chat unavailable"))

    suspend fun sendPlaylist(userId: String, playlist: SocialSharedPlaylist): Result<SocialChatMessage> =
        manager?.sendPlaylist(userId, playlist) ?: Result.failure(UnsupportedOperationException("Chat unavailable"))

    suspend fun loadFriendStatistics(userId: String): Result<SocialStatisticsSnapshot> =
        manager?.loadFriendStatistics(userId) ?: Result.failure(UnsupportedOperationException("Stats unavailable"))

    fun publishPresence(presence: SocialPresence) {
        manager?.publishPresence(presence)
    }

    override fun close() = lifecycle.close()

    private fun SocialUser.toMusicSocialUser() = MusicSocialUser(
        id = id,
        name = name,
        username = username,
    )
}
