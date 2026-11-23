/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import io.ktor.http.URLProtocol
import io.ktor.http.Url

data class SocialLoginCallback(
    val code: String?,
    val error: String?,
)

/**
 * Validates and parses a relative loopback callback path (e.g. `/archivetune-auth?code=...&nonce=...`).
 */
fun parseSocialLoginCallbackTarget(
    target: String,
    expectedNonce: String,
): SocialLoginCallback {
    require(target.length <= 16_384 && expectedNonce.isNotBlank()) { "Invalid sign-in callback" }
    val url = Url("http://127.0.0.1$target")
    require(url.encodedPath == "/archivetune-auth") { "Unexpected ArchiveTune social callback target" }
    require(url.parameters.getAll("nonce") == listOf(expectedNonce)) {
        "Discord login callback did not match this sign-in"
    }
    return SocialLoginCallback(
        code = url.parameters["code"],
        error = url.parameters["error"],
    )
}

/**
 * Validates a full localhost redirect URL intercepted by an ephemeral iOS `WKWebView` sign-in modal
 * and extracts the single authorization code for [expectedNonce].
 */
fun parseLocalhostDiscordCallbackCode(
    targetUrl: String,
    expectedNonce: String,
    expectedPort: Int = 49152,
): String {
    require(targetUrl.length <= 16_384 && expectedNonce.isNotBlank()) { "Invalid sign-in callback" }
    val url = Url(targetUrl)
    require(
        url.protocol == URLProtocol.HTTP &&
            url.host == "127.0.0.1" &&
            url.port == expectedPort &&
            url.encodedPath == "/archivetune-auth" &&
            url.user == null &&
            url.password == null &&
            url.parameters.getAll("nonce") == listOf(expectedNonce) &&
            url.parameters.getAll("code")?.size == 1 &&
            url.parameters["error"] == null,
    ) { "Sign-in callback rejected" }
    return requireNotNull(url.parameters["code"]?.takeIf { it.isNotBlank() && it.length <= 4096 }) {
        "Sign-in callback did not contain a code"
    }
}
