/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.coroutineContext

internal data class SocialLoginCallback(
    val code: String?,
    val error: String?,
)

class AndroidSocialLogin(
    private val api: SocialApi,
    private val openBrowser: suspend (String) -> Unit,
    private val timeoutMillis: Long = 3 * 60_000L,
) {
    suspend fun signIn(): StoredSocialSession {
        val nonce = randomNonce()
        return withContext(Dispatchers.IO) {
            ServerSocket().use { server ->
                server.reuseAddress = true
                // The cloud callback deliberately targets 127.0.0.1, so bind IPv4 explicitly.
                server.bind(InetSocketAddress("127.0.0.1", 0), 2)
                server.soTimeout = 1_000

                openBrowser(api.discordLoginUrl(server.localPort, nonce))
                val deadline = System.currentTimeMillis() + timeoutMillis
                while (System.currentTimeMillis() < deadline) {
                    coroutineContext.ensureActive()
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    socket.use { connection ->
                        connection.soTimeout = 5_000
                        val requestLine = connection.getInputStream()
                            .bufferedReader(Charsets.US_ASCII)
                            .readLine()
                            .orEmpty()
                        val target = requestLine.split(' ').getOrNull(1)
                        val callback = runCatching {
                            requireNotNull(target) { "ArchiveTune social callback was malformed" }
                            parseSocialLoginCallbackTarget(target, nonce)
                        }
                        callback.fold(
                            onSuccess = { value ->
                                if (!value.error.isNullOrBlank()) {
                                    writeBrowserResponse(connection.getOutputStream(), "ArchiveTune sign-in was cancelled.")
                                    throw IllegalStateException(value.error)
                                }
                                val code = value.code?.takeIf(String::isNotBlank)
                                    ?: throw IllegalStateException("Discord sign-in did not return an authorization code")
                                writeBrowserResponse(connection.getOutputStream(), "ArchiveTune sign-in complete. You can return to the app.")
                                return@withContext api.exchangeAuthCode(code, nonce)
                            },
                            onFailure = {
                                writeBrowserResponse(connection.getOutputStream(), "This callback does not belong to the current ArchiveTune sign-in.", 400)
                            },
                        )
                    }
                }
                throw IllegalStateException("Discord sign-in timed out")
            }
        }
    }

    private fun writeBrowserResponse(
        output: OutputStream,
        message: String,
        status: Int = 200,
    ) {
        val body = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="font-family:sans-serif;background:#111;color:#fff;padding:32px"><p>$message</p></body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Bad Request"
        output.write(
            "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.US_ASCII),
        )
        output.write(body)
        output.flush()
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

internal fun parseSocialLoginCallbackTarget(
    target: String,
    expectedNonce: String,
): SocialLoginCallback {
    val uri = URI("http://127.0.0.1$target")
    require(uri.path == "/archivetune-auth") { "Unexpected ArchiveTune social callback target" }
    val query = buildMap {
        uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).forEach { pair ->
            val separator = pair.indexOf('=')
            val key = if (separator >= 0) pair.substring(0, separator) else pair
            val value = if (separator >= 0) pair.substring(separator + 1) else ""
            put(
                URLDecoder.decode(key, Charsets.UTF_8.name()),
                URLDecoder.decode(value, Charsets.UTF_8.name()),
            )
        }
    }
    require(query["nonce"] == expectedNonce) { "Discord login callback did not match this sign-in" }
    return SocialLoginCallback(
        code = query["code"],
        error = query["error"],
    )
}
