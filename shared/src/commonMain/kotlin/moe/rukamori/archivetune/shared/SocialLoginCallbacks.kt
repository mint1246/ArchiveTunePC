package moe.rukamori.archivetune.shared

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** Accept only the one localhost redirect owned by the current ephemeral Discord sign-in. */
internal fun discordCallbackCode(target: String, expectedNonce: String): String {
    require(target.length <= 16_384 && expectedNonce.isNotBlank()) { "Invalid sign-in callback" }
    val url = Url(target)
    require(url.protocol == URLProtocol.HTTP && url.host == "127.0.0.1" && url.port == 49152 &&
        url.encodedPath == "/archivetune-auth" && url.user == null && url.password == null &&
        url.parameters.getAll("nonce") == listOf(expectedNonce) &&
        url.parameters.getAll("code")?.size == 1 && url.parameters["error"] == null
    ) { "Sign-in callback rejected" }
    return requireNotNull(url.parameters["code"]?.takeIf { it.isNotBlank() && it.length <= 4096 }) {
        "Sign-in callback did not contain a code"
    }
}
