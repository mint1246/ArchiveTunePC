package example

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.sdk.lifecycle.SdkLifecycle
import moe.rukamori.archivetune.social.AndroidSocialApi
import moe.rukamori.archivetune.social.AndroidSocialManager
import moe.rukamori.archivetune.social.SocialAccountState
import moe.rukamori.archivetune.social.SocialPresence
import moe.rukamori.archivetune.social.SocialRealtimeTransport
import moe.rukamori.archivetune.social.SocialSessionStore
import moe.rukamori.archivetune.social.StoredSocialSession

fun main() = runBlocking {
    val store = object : SocialSessionStore {
        override suspend fun read(): StoredSocialSession? = null
        override suspend fun write(session: StoredSocialSession) = Unit
        override suspend fun clear() = Unit
    }
    val transport = object : SocialRealtimeTransport {
        override suspend fun connect(
            token: String,
            deviceId: String,
            onConnected: suspend () -> Unit,
            onMessage: (String) -> Unit,
        ) = Unit
        override suspend fun publishPresence(presence: SocialPresence) = Unit
        override fun disconnect() = Unit
        override fun close() = Unit
    }
    SdkLifecycle(coroutineContext).use { lifecycle ->
        val session = lifecycle.register(AndroidSocialManager(
            scope = lifecycle.scope(),
            api = AndroidSocialApi("http://127.0.0.1"),
            sessionStore = store,
            login = { error("Configure a login adapter before signing in") },
            realtime = transport,
        ))
        session.restore()
        check(session.state.value.account is SocialAccountState.SignedOut)
        println("Initialized and closed a headless social session")
    }
}
