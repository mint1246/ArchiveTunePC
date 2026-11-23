package moe.rukamori.archivetune.shared

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.sdk.lifecycle.SdkLifecycle
import moe.rukamori.archivetune.social.AndroidSocialState
import moe.rukamori.archivetune.social.KtorSocialClient
import moe.rukamori.archivetune.social.SocialAccountState
import moe.rukamori.archivetune.social.SocialApi
import moe.rukamori.archivetune.social.SocialSessionCoordinator
import moe.rukamori.archivetune.social.SocialUser

data class MusicSocialUser(val id: String, val name: String, val username: String)

data class MusicSocialState(
    val signingIn: Boolean = false,
    val account: MusicSocialUser? = null,
    val friends: List<MusicSocialUser> = emptyList(),
    val error: String? = null,
)

/**
 * Delegates Discord sign-in, session management, and Friends REST calls to `social-sdk`
 * ([KtorSocialClient]) and `social-session` ([SocialSessionCoordinator]).
 */
class MusicSocialController(
    private val coordinator: SocialSessionCoordinator,
    scope: CoroutineScope,
    private val ownedHttp: HttpClient? = null,
) : AutoCloseable {
    constructor(
        http: HttpClient,
        scope: CoroutineScope,
        login: suspend (url: String, nonce: String) -> String,
        nonce: () -> String,
        api: SocialApi = KtorSocialClient(http),
    ) : this(
        coordinator = SocialSessionCoordinator(
            api = api,
            scope = scope,
            authorize = login,
            nonceProvider = nonce,
        ),
        scope = scope,
        ownedHttp = http,
    )

    private val lifecycle = SdkLifecycle.forScope(scope)
    private val mutableState = MutableStateFlow(MusicSocialState())
    val state: StateFlow<MusicSocialState> = mutableState.asStateFlow()
    val sdkState: StateFlow<AndroidSocialState> = coordinator.state

    init {
        ownedHttp?.let { client -> lifecycle.register(AutoCloseable { client.close() }) }
        lifecycle.register(coordinator)
        lifecycle.scope().launch {
            coordinator.state.collect { social ->
                mutableState.value = MusicSocialState(
                    signingIn = social.account is SocialAccountState.SigningIn,
                    account = (social.account as? SocialAccountState.SignedIn)?.user?.toMusicSocialUser(),
                    friends = social.friends.map { it.toMusicSocialUser() },
                    error = social.error,
                )
            }
        }
    }

    fun signIn() = coordinator.signIn()
    fun refresh() = coordinator.refresh()
    fun signOut() = coordinator.signOut()

    override fun close() = lifecycle.close()

    private fun SocialUser.toMusicSocialUser() = MusicSocialUser(
        id = id,
        name = name,
        username = username,
    )
}
