package moe.rukamori.archivetune.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class MusicSocialUser(val id: String, val name: String, val username: String)
data class MusicSocialState(
    val signingIn: Boolean = false,
    val account: MusicSocialUser? = null,
    val friends: List<MusicSocialUser> = emptyList(),
    val error: String? = null,
)

/** Discord session is process-owned and never enters the music metadata/settings store. */
class MusicSocialController(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val login: suspend (url: String, nonce: String) -> String,
    private val nonce: () -> String,
) {
    private val mutableState = MutableStateFlow(MusicSocialState())
    val state: StateFlow<MusicSocialState> = mutableState.asStateFlow()
    private var token: String? = null
    private var loginJob: Job? = null
    private val base = "https://archivetune-social.archivetune.workers.dev"

    fun signIn() {
        if (loginJob?.isActive == true) return
        mutableState.update { it.copy(signingIn = true, error = null) }
        loginJob = scope.launch {
            try {
                val challenge = nonce()
                val url = URLBuilder(base + "/auth/discord/start").apply {
                    parameters.append("port", "49152")
                    parameters.append("nonce", challenge)
                }.buildString()
                val code = login(url, challenge)
                val response = http.post(base + "/v1/auth/exchange") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("code", code); put("nonce", challenge) }.toString())
                }
                check(response.status.value in 200..299)
                val session = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                token = session["token"]?.jsonPrimitive?.content ?: error("Missing session")
                refreshNow()
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(signingIn = false) }
                throw cancelled
            } catch (_: Exception) {
                token = null
                mutableState.value = MusicSocialState(error = "Sign in could not complete. Please try again.")
            }
        }
    }

    fun refresh() { scope.launch { try { refreshNow() } catch (_: Exception) {
        mutableState.update { it.copy(error = "Friends could not connect. Try again.") }
    } } }

    private suspend fun refreshNow() {
        val auth = token ?: return
        suspend fun get(path: String): JsonObject {
            val response = http.get(base + path) { bearerAuth(auth) }
            check(response.status.value in 200..299)
            return Json.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        val account = user(get("/v1/me")["user"]?.jsonObject ?: error("Missing user"))
        val friends = (get("/v1/friends")["friends"] as? JsonArray).orEmpty()
            .take(500).mapNotNull { (it as? JsonObject)?.let(::user) }
        mutableState.value = MusicSocialState(account = account, friends = friends)
    }

    fun signOut() {
        val auth = token
        token = null
        loginJob?.cancel()
        mutableState.value = MusicSocialState()
        if (auth != null) scope.launch { try { http.post(base + "/v1/auth/logout") { bearerAuth(auth) } } catch (_: Exception) {} }
    }

    private fun user(value: JsonObject): MusicSocialUser = MusicSocialUser(
        value["id"]?.jsonPrimitive?.content.orEmpty(),
        value["name"]?.jsonPrimitive?.content ?: value["displayName"]?.jsonPrimitive?.content.orEmpty(),
        value["username"]?.jsonPrimitive?.content.orEmpty(),
    )
}
