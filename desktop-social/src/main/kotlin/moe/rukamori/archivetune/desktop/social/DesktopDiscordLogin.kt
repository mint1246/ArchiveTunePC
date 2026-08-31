package moe.rukamori.archivetune.desktop.social

import java.awt.Desktop
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopDiscordLogin(
    private val api: DesktopSocialApi,
) {
    suspend fun signIn(): StoredSocialSession = withContext(Dispatchers.IO) {
        val nonce = randomNonce()
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = LOGIN_TIMEOUT_MS
            val loginUri = api.discordLoginUrl(server.localPort, nonce)
            check(Desktop.isDesktopSupported()) { "System browser is unavailable" }
            Desktop.getDesktop().browse(loginUri)

            server.accept().use { socket ->
                socket.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                val requestLine = reader.readLine()?.take(MAX_REQUEST_LINE_LENGTH)
                    ?: error("Discord login callback was empty")
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val target = requestLine.split(' ').getOrNull(1) ?: error("Discord login callback was malformed")
                val callback = URI.create("http://127.0.0.1$target")
                require(callback.path == "/archivetune-auth") { "Unexpected Discord login callback" }
                val query = parseQuery(callback.rawQuery)
                require(query["nonce"] == nonce) { "Discord login callback did not match this sign-in" }
                val failure = query["error"]
                val code = query["code"]
                val page = if (failure == null && !code.isNullOrBlank()) {
                    "ArchiveTune sign-in complete. You can close this tab."
                } else {
                    "ArchiveTune sign-in was cancelled. You can close this tab."
                }
                writeBrowserResponse(socket.getOutputStream(), page)
                if (failure != null) error(failure)
                require(!code.isNullOrBlank()) { "Discord login did not return an authorization code" }
                api.exchangeAuthCode(code, nonce)
            }
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .associate { part ->
                val split = part.indexOf('=')
                val key = if (split >= 0) part.substring(0, split) else part
                val value = if (split >= 0) part.substring(split + 1) else ""
                URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }

    private fun writeBrowserResponse(output: java.io.OutputStream, message: String) {
        val body = """
            <!doctype html>
            <meta charset="utf-8">
            <title>ArchiveTune</title>
            <style>body{font:16px system-ui;background:#111;color:#eee;display:grid;place-items:center;height:100vh;margin:0}</style>
            <p>$message</p>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val headers = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        output.write(headers)
        output.write(body)
        output.flush()
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val LOGIN_TIMEOUT_MS = 5 * 60_000
        const val MAX_REQUEST_LINE_LENGTH = 8_192
    }
}
