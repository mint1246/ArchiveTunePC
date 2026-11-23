/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lightweight multiplatform REST social coordinator for hosts (such as iOS) that manage
 * sign-in, account lookup, and friends list without full realtime chat/presence sockets.
 */
class SocialSessionCoordinator(
    private val api: SocialApi,
    private val scope: CoroutineScope,
    private val authorize: suspend (loginUrl: String, nonce: String) -> String,
    private val nonceProvider: () -> String,
    private val callbackPort: Int = 49152,
    private val sessionStore: SocialSessionStore = InMemorySocialSessionStore(),
) : AutoCloseable {
    private val _state = MutableStateFlow(AndroidSocialState())
    val state: StateFlow<AndroidSocialState> = _state.asStateFlow()

    private var activeToken: String? = null
    private var sessionGeneration: Long = 0L
    private var loginJob: Job? = null
    private var refreshJob: Job? = null
    private var closed = false

    fun signIn() {
        if (closed || loginJob?.isActive == true) return
        val generation = ++sessionGeneration
        _state.update { it.copy(account = SocialAccountState.SigningIn, error = null) }
        loginJob = scope.launch {
            try {
                val nonce = nonceProvider()
                val loginUrl = api.discordLoginUrl(callbackPort, nonce)
                val code = authorize(loginUrl, nonce)
                val session = api.exchangeAuthCode(code, nonce)
                if (closed || generation != sessionGeneration) return@launch
                activeToken = session.token
                sessionStore.write(session)
                refreshWithToken(session.token, generation)
            } catch (cancelled: CancellationException) {
                if (generation == sessionGeneration) {
                    _state.update { it.copy(account = SocialAccountState.SignedOut) }
                }
                throw cancelled
            } catch (_: Throwable) {
                if (generation == sessionGeneration) {
                    activeToken = null
                    runCatching { sessionStore.clear() }
                    _state.value = AndroidSocialState(error = "Sign in could not complete. Please try again.")
                }
            }
        }
    }

    fun refresh() {
        if (closed) return
        val generation = sessionGeneration
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val token = activeToken ?: runCatching { sessionStore.read()?.token }.getOrNull() ?: return@launch
            try {
                refreshWithToken(token, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!closed && generation == sessionGeneration) {
                    _state.update { it.copy(refreshing = false, error = "Friends could not connect. Try again.") }
                }
            }
        }
    }

    fun signOut() {
        val generation = ++sessionGeneration
        val tokenToRevoke = activeToken
        activeToken = null
        loginJob?.cancel()
        refreshJob?.cancel()
        _state.value = AndroidSocialState()
        scope.launch {
            if (generation == sessionGeneration) {
                runCatching { sessionStore.clear() }
            }
            if (tokenToRevoke != null) {
                runCatching { api.logout(tokenToRevoke) }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        ++sessionGeneration
        activeToken = null
        loginJob?.cancel()
        refreshJob?.cancel()
    }

    private suspend fun refreshWithToken(token: String, generation: Long) {
        _state.update { it.copy(refreshing = true, error = null) }
        val account = api.me(token)
        val friends = api.friends(token)
        if (closed || generation != sessionGeneration) return
        _state.value = AndroidSocialState(
            account = SocialAccountState.SignedIn(account),
            friends = friends,
            refreshing = false,
            error = null,
        )
    }
}
