/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The same two proof values Android's BotGuard generator produces in one minting cycle. */
data class DesktopPoTokenResult(
    val playerToken: String,
    val sessionToken: String,
)

/** Injectable so auth/session tests never need a browser or network. */
fun interface DesktopPlaybackPoTokenProvider {
    suspend fun mint(
        videoId: String,
        sessionId: String,
    ): DesktopPoTokenResult?
}

/**
 * Desktop implementation of ArchiveTune Android's BotGuard WebView flow.
 *
 * Chromium is intentionally short-lived: it starts only for a cache miss, evaluates the same
 * po_token.html helper used by Android, mints the session + per-video tokens, then exits. This keeps
 * the browser's working set out of ArchiveTune's steady-state memory footprint.
 */
class ChromiumBotGuardPoTokenProvider(
    private val browserFinder: () -> Path? = ::findSupportedChromiumBrowser,
    private val tempRoot: Path = Paths.get(System.getProperty("java.io.tmpdir")),
) : DesktopPlaybackPoTokenProvider {
    private val mutex = Mutex()
    private var cached: CachedTokens? = null

    override suspend fun mint(
        videoId: String,
        sessionId: String,
    ): DesktopPoTokenResult? {
        if (videoId.isBlank() || sessionId.isBlank()) return null
        return mutex.withLock {
            cached
                ?.takeIf {
                    it.videoId == videoId &&
                        it.sessionId == sessionId &&
                        it.expiresAtMillis > System.currentTimeMillis() + TOKEN_EXPIRY_SAFETY_MS
                }?.result
                ?: try {
                    withTimeout(COLD_START_TIMEOUT_MS) {
                        mintOneShot(videoId, sessionId)
                    }?.also { minted ->
                        cached = minted
                    }?.result
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
        }
    }

    private suspend fun mintOneShot(
        videoId: String,
        sessionId: String,
    ): CachedTokens? =
        withContext(Dispatchers.IO) {
            val browser = browserFinder() ?: return@withContext null
            Files.createDirectories(tempRoot)
            val profile = Files.createTempDirectory(tempRoot, "archivetune-potoken-")
            var process: Process? = null
            var pageClient: CdpClient? = null
            try {
                val challenge = parseCreateChallenge(postBotGuard(CREATE_URL, JSONArray().put(REQUEST_KEY).toString()))
                process =
                    ProcessBuilder(botGuardBrowserCommand(browser, profile))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .also { it.outputStream.close() }

                val endpoint = waitForDevTools(profile)
                val pageTarget = waitForPageTarget(endpoint.port)
                pageClient = CdpClient.connect(pageTarget.webSocketDebuggerUrl)
                pageClient.call("Runtime.enable")
                pageClient.call("Page.enable")

                val frameId =
                    pageClient
                        .call("Page.getFrameTree")
                        .getJSONObject("frameTree")
                        .getJSONObject("frame")
                        .getString("id")
                val html =
                    requireNotNull(javaClass.getResourceAsStream("/po_token.html")) {
                        "Desktop BotGuard resource is missing"
                    }.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                pageClient.call(
                    "Page.setDocumentContent",
                    JSONObject()
                        .put("frameId", frameId)
                        .put("html", html),
                    timeoutSeconds = CDP_EVALUATION_TIMEOUT_SECONDS,
                )

                val botguardResponse =
                    pageClient.evaluateAwait(
                        """
                        (async function() {
                            const result = await runBotGuard($challenge);
                            globalThis.webPoSignalOutput = result.webPoSignalOutput;
                            return result.botguardResponse;
                        })()
                        """.trimIndent(),
                    )?.toString()?.takeIf(String::isNotBlank)
                        ?: return@withContext null

                val generateBody = JSONArray().put(REQUEST_KEY).put(botguardResponse).toString()
                val (integrityToken, lifetimeSeconds) = parseIntegrityToken(postBotGuard(GENERATE_IT_URL, generateBody))
                pageClient.evaluateAwait(
                    """
                    (async function() {
                        globalThis.integrityToken = ${integrityToken.toJsUint8Array()};
                        await createPoTokenMinter(globalThis.webPoSignalOutput, globalThis.integrityToken);
                        return true;
                    })()
                    """.trimIndent(),
                )

                val sessionToken = pageClient.mintPoToken(sessionId)
                val playerToken = pageClient.mintPoToken(videoId)
                val expiresAtMillis =
                    System.currentTimeMillis() +
                        (lifetimeSeconds.coerceAtLeast(MIN_TOKEN_LIFETIME_SECONDS) * 1_000L)
                CachedTokens(
                    sessionId = sessionId,
                    videoId = videoId,
                    result = DesktopPoTokenResult(playerToken = playerToken, sessionToken = sessionToken),
                    expiresAtMillis = expiresAtMillis,
                )
            } finally {
                pageClient?.close()
                stopManagedBrowser(process)
                cleanupProfile(profile)
            }
        }

    private fun postBotGuard(
        url: String,
        jsonBody: String,
    ): String {
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .header("User-Agent", BOTGUARD_USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json+protobuf")
                .header("x-goog-api-key", API_KEY)
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build()
        val response = HTTP.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "BotGuard HTTP ${response.statusCode()}" }
        return response.body()
    }

    private suspend fun waitForDevTools(profile: Path): DevToolsEndpoint {
        val activePort = profile.resolve("DevToolsActivePort")
        val deadlineNanos = System.nanoTime() + BROWSER_STARTUP_TIMEOUT.toNanos()
        while (System.nanoTime() < deadlineNanos) {
            if (Files.isRegularFile(activePort)) {
                val lines = runCatching { Files.readAllLines(activePort) }.getOrNull()
                val port = lines?.getOrNull(0)?.trim()?.toIntOrNull()
                val browserPath = lines?.getOrNull(1)?.trim()
                if (port != null && !browserPath.isNullOrBlank()) {
                    return DevToolsEndpoint(port)
                }
            }
            delay(100L)
        }
        throw IllegalStateException("Desktop BotGuard browser did not start")
    }

    private suspend fun waitForPageTarget(port: Int): PageTarget {
        val deadlineNanos = System.nanoTime() + BROWSER_STARTUP_TIMEOUT.toNanos()
        while (System.nanoTime() < deadlineNanos) {
            findPageTarget(port)?.let { return it }
            delay(100L)
        }
        throw IllegalStateException("Desktop BotGuard page did not start")
    }

    private fun findPageTarget(port: Int): PageTarget? {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/json/list"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
        val response = HTTP.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        val targets = JSONArray(response.body())
        return (0 until targets.length())
            .asSequence()
            .mapNotNull(targets::optJSONObject)
            .filter { it.optString("type") == "page" }
            .firstNotNullOfOrNull { target ->
                target.optString("webSocketDebuggerUrl")
                    .takeIf(String::isNotBlank)
                    ?.let(::PageTarget)
            }
    }

    private fun CdpClient.evaluateAwait(expression: String): Any? {
        val response =
            call(
                "Runtime.evaluate",
                JSONObject()
                    .put("expression", expression)
                    .put("awaitPromise", true)
                    .put("returnByValue", true),
                timeoutSeconds = CDP_EVALUATION_TIMEOUT_SECONDS,
            )
        response.optJSONObject("exceptionDetails")?.let { details ->
            val text = details.optString("text", "JavaScript evaluation failed")
            val exception = details.optJSONObject("exception")?.optString("description")
            throw IllegalStateException(exception?.takeIf(String::isNotBlank) ?: text)
        }
        return response.optJSONObject("result")?.opt("value")
    }

    private fun CdpClient.mintPoToken(identifier: String): String {
        val csv =
            evaluateAwait(
                """
                (async function() {
                    const token = await obtainPoToken(${identifier.toJsUint8Array()});
                    return Array.from(token).join(',');
                })()
                """.trimIndent(),
            )?.toString()?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("BotGuard returned an empty token")
        val bytes = csv.split(',').map { it.trim().toInt().toByte() }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_')
    }

    private fun stopManagedBrowser(process: Process?) {
        val root = process?.toHandle() ?: return
        val handles = root.descendants().toList() + root
        handles.filter(ProcessHandle::isAlive).forEach { runCatching { it.destroy() } }
        val deadline = System.nanoTime() + PROCESS_EXIT_GRACE.toNanos()
        while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(PROCESS_EXIT_POLL_MILLIS)
        }
        handles.filter(ProcessHandle::isAlive).forEach { runCatching { it.destroyForcibly() } }
    }

    private fun cleanupProfile(profile: Path) {
        repeat(PROFILE_DELETE_ATTEMPTS) {
            if (!Files.exists(profile)) return
            runCatching {
                Files.walk(profile).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
            if (!Files.exists(profile)) return
            Thread.sleep(PROFILE_DELETE_RETRY_MILLIS)
        }
    }

    private data class CachedTokens(
        val sessionId: String,
        val videoId: String,
        val result: DesktopPoTokenResult,
        val expiresAtMillis: Long,
    )

    private data class DevToolsEndpoint(val port: Int)
    private data class PageTarget(val webSocketDebuggerUrl: String)

    private companion object {
        private val HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
        private const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val BOTGUARD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val HTTP_TIMEOUT_SECONDS = 20L
        private const val CDP_EVALUATION_TIMEOUT_SECONDS = 25L
        private const val COLD_START_TIMEOUT_MS = 45_000L
        private const val TOKEN_EXPIRY_SAFETY_MS = 10 * 60 * 1_000L
        private const val MIN_TOKEN_LIFETIME_SECONDS = 60L
        private val BROWSER_STARTUP_TIMEOUT = Duration.ofSeconds(15)
        private val PROCESS_EXIT_GRACE = Duration.ofSeconds(2)
        private const val PROCESS_EXIT_POLL_MILLIS = 50L
        private const val PROFILE_DELETE_ATTEMPTS = 20
        private const val PROFILE_DELETE_RETRY_MILLIS = 100L
    }
}

internal fun botGuardBrowserCommand(
    browser: Path,
    profile: Path,
): List<String> =
    listOf(
        browser.toString(),
        "--headless=new",
        "--user-data-dir=$profile",
        "--profile-directory=Default",
        "--remote-debugging-port=0",
        "--remote-debugging-address=127.0.0.1",
        "--disable-sync",
        "--disable-background-mode",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-features=EnableBoundSessionCredentials",
        "--user-agent=$BOTGUARD_BROWSER_USER_AGENT",
        "about:blank",
    )

private fun parseCreateChallenge(rawResponse: String): String {
    val outer = JSONArray(rawResponse)
    val challenge =
        if (outer.length() > 1 && outer.opt(1) is String) {
            JSONArray(descramble(outer.getString(1)))
        } else {
            outer.getJSONArray(0)
        }
    val interpreterJs = challenge.optJSONArray(1)?.firstStringOrNull()
    val interpreterUrl = challenge.optJSONArray(2)?.firstStringOrNull()
    return JSONObject()
        .put("program", challenge.getString(4))
        .put("globalName", challenge.getString(5))
        .put(
            "interpreterJavascript",
            JSONObject()
                .put("privateDoNotAccessOrElseSafeScriptWrappedValue", interpreterJs ?: JSONObject.NULL)
                .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", interpreterUrl ?: JSONObject.NULL),
        ).toString()
}

private fun parseIntegrityToken(rawResponse: String): Pair<ByteArray, Long> {
    val response = JSONArray(rawResponse)
    return decodeYouTubeBase64(response.getString(0)) to response.getLong(1)
}

private fun JSONArray.firstStringOrNull(): String? =
    (0 until length()).firstNotNullOfOrNull { index -> opt(index) as? String }

private fun descramble(payload: String): String =
    decodeYouTubeBase64(payload)
        .map { (it.toInt() + 97).toByte() }
        .toByteArray()
        .toString(StandardCharsets.UTF_8)

private fun decodeYouTubeBase64(value: String): ByteArray {
    val normalized = value.replace('-', '+').replace('_', '/').replace('.', '=')
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    return Base64.getDecoder().decode(padded)
}

private fun ByteArray.toJsUint8Array(): String =
    "new Uint8Array([${joinToString(",") { (it.toInt() and 0xFF).toString() }}])"

private fun String.toJsUint8Array(): String = toByteArray(StandardCharsets.UTF_8).toJsUint8Array()

private const val BOTGUARD_BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
