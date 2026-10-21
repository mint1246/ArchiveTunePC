/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletionException
import java.util.logging.Level
import java.util.logging.Logger

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
    private val tempRoot: Path = defaultBotGuardTempRoot(),
    private val coldStartBudgetMillis: Long = COLD_START_TIMEOUT_MS,
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
                }?.let { return@withLock it.result }
            try {
                // withTimeoutOrNull swallows only this provider's own deadline. A caller timeout or
                // cancellation surfaces from the block as CancellationException and is rethrown below,
                // so an enclosing playback request can still be abandoned.
                val outcome =
                    withTimeoutOrNull(coldStartBudgetMillis) {
                        MintOutcome(mintOneShot(videoId, sessionId))
                    }
                if (outcome == null) {
                    logger.fine("BotGuard token mint exceeded the ${coldStartBudgetMillis}ms cold-start budget")
                    return@withLock null
                }
                val minted = outcome.tokens
                if (minted == null) {
                    logger.fine("BotGuard token mint produced no tokens")
                    return@withLock null
                }
                cached = minted
                minted.result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logger.log(Level.WARNING, "BotGuard token mint failed: ${error.javaClass.simpleName}")
                null
            }
        }
    }

    private suspend fun mintOneShot(
        videoId: String,
        sessionId: String,
    ): CachedTokens? =
        withContext(Dispatchers.IO) {
            val browser = browserFinder() ?: run {
                logger.fine("BotGuard browser was not found")
                return@withContext null
            }
            Files.createDirectories(tempRoot)
            val profile = Files.createTempDirectory(tempRoot, "archivetune-potoken-")
            if (isLinuxOperatingSystem()) {
                // The profile can contain BotGuard state; keep it private to this user even when
                // the configured temp root is broader (for example an XDG cache directory).
                Files.setPosixFilePermissions(profile, PosixFilePermissions.fromString("rwx------"))
            }

            var process: Process? = null
            var pageClient: CdpClient? = null
            try {
                process =
                    ProcessBuilder(botGuardBrowserCommand(browser, profile))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .also { it.outputStream.close() }

                val endpoint = waitForDevTools(profile)
                val pageTarget = waitForPageTarget(endpoint.port)
                checkActive()
                // Fetch the challenge once the browser process is already warming up; this overlaps
                // network latency with Chromium startup instead of paying for both sequentially.
                val challenge = parseCreateChallenge(postBotGuard(CREATE_URL, JSONArray().put(REQUEST_KEY).toString()))
                val client = runInterruptible(Dispatchers.IO) { CdpClient.connect(pageTarget.webSocketDebuggerUrl) }
                pageClient = client
                client.callInterruptibly("Runtime.enable")
                client.callInterruptibly("Page.enable")

                val frameId =
                    client
                        .callInterruptibly("Page.getFrameTree")
                        .getJSONObject("frameTree")
                        .getJSONObject("frame")
                        .getString("id")
                val html =
                    requireNotNull(javaClass.getResourceAsStream("/po_token.html")) {
                        "Desktop BotGuard resource is missing"
                    }.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                client.callInterruptibly(
                    "Page.setDocumentContent",
                    JSONObject()
                        .put("frameId", frameId)
                        .put("html", html),
                )

                val botguardResponse =
                    client.evaluateAwait(
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
                checkActive()
                client.evaluateAwait(
                    """
                    (async function() {
                        globalThis.integrityToken = ${integrityToken.toJsUint8Array()};
                        await createPoTokenMinter(globalThis.webPoSignalOutput, globalThis.integrityToken);
                        return true;
                    })()
                    """.trimIndent(),
                )

                val sessionToken = client.mintPoToken(sessionId)
                val playerToken = client.mintPoToken(videoId)
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
                stopManagedBrowser(process, profile)
                cleanupProfile(profile)
            }
        }

    /** Fails fast between blocking browser/DevTools stages once the mint deadline has passed. */
    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
    }

    private suspend fun postBotGuard(
        url: String,
        jsonBody: String,
    ): String {
        checkActive()
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
        val response = sendAsync(request)
        check(response.statusCode() == 200) { "BotGuard HTTP ${response.statusCode()}" }
        return response.body()
    }

    /**
     * Awaits the HTTP exchange as a cancellable coroutine.
     *
     * `HttpClient.send` blocks the calling thread until the request finishes, which makes the
     * provider's cold-start deadline ineffective on a slow network. Awaiting the future instead lets
     * `withTimeoutOrNull` and an enclosing playback timeout cancel the mint immediately.
     */
    private suspend fun sendAsync(request: HttpRequest): HttpResponse<String> =
        suspendCancellableCoroutine { continuation ->
            val future = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { response, error ->
                if (!continuation.isActive) return@whenComplete
                if (error == null) {
                    continuation.resume(response) { _, _, _ -> }
                } else {
                    continuation.resumeWith(Result.failure((error as? CompletionException)?.cause ?: error))
                }
            }
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

    private suspend fun findPageTarget(port: Int): PageTarget? {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/json/list"))
                .timeout(DEVTOOLS_LIST_TIMEOUT)
                .GET()
                .build()
        // DevToolsActivePort is written slightly before the HTTP endpoint starts accepting
        // connections. Treat that startup window as "not ready yet" instead of aborting the mint.
        val response = try {
            sendAsync(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        if (response.statusCode() !in 200..299) return null
        val targets = runCatching { JSONArray(response.body()) }.getOrNull() ?: return null
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

    private suspend fun CdpClient.callInterruptibly(
        method: String,
        params: JSONObject? = null,
    ): JSONObject =
        // CdpClient.call blocks on a CompletableFuture. Running it interruptibly lets the provider's
        // deadline (or a cancelled playback request) stop waiting instead of pinning the IO thread.
        runInterruptible(Dispatchers.IO) {
            call(method, params, timeoutSeconds = CDP_EVALUATION_TIMEOUT_SECONDS)
        }

    private suspend fun CdpClient.evaluateAwait(expression: String): Any? {
        val response =
            callInterruptibly(
                "Runtime.evaluate",
                JSONObject()
                    .put("expression", expression)
                    .put("awaitPromise", true)
                    .put("returnByValue", true),
            )
        response.optJSONObject("exceptionDetails")?.let { details ->
            val text = details.optString("text", "JavaScript evaluation failed")
            val exception = details.optJSONObject("exception")?.optString("description")
            throw IllegalStateException(exception?.takeIf(String::isNotBlank) ?: text)
        }
        return response.optJSONObject("result")?.opt("value")
    }

    private suspend fun CdpClient.mintPoToken(identifier: String): String {
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
        return encodePoToken(csv)
    }

    private fun stopManagedBrowser(
        process: Process?,
        profile: Path,
    ) {
        val root = process?.toHandle()
        val processTree =
            buildList {
                root?.let { handle ->
                    add(handle)
                    runCatching { handle.descendants().use { it.toList() } }.getOrDefault(emptyList()).let(::addAll)
                }
            }
        // Chromium can reparent helper/zygote processes on Linux. Match the dedicated profile
        // argument as well so no browser process survives and keeps the temporary profile locked.
        val profileHandles =
            if (isLinuxOperatingSystem()) {
                val profileArgument = "--user-data-dir=${profile.toAbsolutePath().normalize()}"
                runCatching {
                    ProcessHandle.allProcesses().use { processes ->
                        processes
                            .filter(ProcessHandle::isAlive)
                            .filter { handle ->
                                handle.info().arguments().orElse(emptyArray()).any { argument ->
                                    argument.equals(profileArgument, ignoreCase = true)
                                }
                            }.toList()
                    }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        val handles = (processTree + profileHandles).distinctBy(ProcessHandle::pid)
        if (handles.isEmpty()) return
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

    /** Distinguishes "budget expired" (null outcome) from "mint ran and found nothing". */
    private data class MintOutcome(val tokens: CachedTokens?)

    private data class DevToolsEndpoint(val port: Int)
    private data class PageTarget(val webSocketDebuggerUrl: String)

    private companion object {
        private val logger = Logger.getLogger("moe.rukamori.archivetune.desktop.auth.botguard")
        private val HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(HTTP_CONNECT_TIMEOUT_SECONDS)).build()
        private const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
        private const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val BOTGUARD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

        // The backend caps playback-auth preparation at 15s (PLAYBACK_AUTH_PREP_TIMEOUT_MILLIS in
        // ArchiveTuneJvmClient). This provider keeps its pre-existing cold-start safety net, but
        // caller cancellation now propagates, so an enclosing playback deadline can abandon the mint
        // immediately instead of waiting for this budget. Runtime evidence from the opt-in smoke test
        // should drive any future reduction.
        private const val COLD_START_TIMEOUT_MS = 45_000L
        private const val HTTP_CONNECT_TIMEOUT_SECONDS = 5L
        private const val HTTP_TIMEOUT_SECONDS = 20L
        private const val CDP_EVALUATION_TIMEOUT_SECONDS = 25L
        private val BROWSER_STARTUP_TIMEOUT = Duration.ofSeconds(15)
        private val DEVTOOLS_LIST_TIMEOUT = Duration.ofSeconds(3)
        private const val TOKEN_EXPIRY_SAFETY_MS = 10 * 60 * 1_000L
        private const val MIN_TOKEN_LIFETIME_SECONDS = 60L
        private val PROCESS_EXIT_GRACE = Duration.ofSeconds(2)
        private const val PROCESS_EXIT_POLL_MILLIS = 50L
        private const val PROFILE_DELETE_ATTEMPTS = 20
        private const val PROFILE_DELETE_RETRY_MILLIS = 100L
    }
}

internal fun botGuardBrowserCommand(
    browser: Path,
    profile: Path,
    isLinux: Boolean = isLinuxOperatingSystem(),
    runningAsRoot: Boolean = isRunningAsRoot(),
): List<String> =
    buildList {
        add(browser.toString())
        add("--headless=new")
        add("--user-data-dir=$profile")
        add("--profile-directory=Default")
        add("--remote-debugging-port=0")
        add("--remote-debugging-address=127.0.0.1")
        add("--disable-sync")
        add("--disable-background-mode")
        add("--no-first-run")
        add("--no-default-browser-check")
        add("--disable-features=EnableBoundSessionCredentials")
        if (isLinux) {
            // Containers and some distros ship a tiny /dev/shm; Chromium's renderer would be killed
            // mid-mint. The BotGuard helper only needs JavaScript, never GPU compositing, and the
            // throwaway profile must not reach for a desktop keyring prompt.
            add("--disable-dev-shm-usage")
            add("--disable-gpu")
            add("--password-store=basic")
            // Running as root is common in dev containers, where Chromium otherwise refuses to start.
            if (runningAsRoot) add("--no-sandbox")
        }
        add("--user-agent=$BOTGUARD_BROWSER_USER_AGENT")
        add("about:blank")
    }

/**
 * Keeps BotGuard's throwaway profile out of shared `/tmp`.
 *
 * Snap and Flatpak builds mount private temporary directories, and some hardened distros mount
 * `/tmp` noexec or wipe it aggressively. The user cache directory survives that and is still
 * per-user by construction. Windows and macOS keep the JVM temporary directory.
 */
internal fun defaultBotGuardTempRoot(): Path {
    val systemTemp = Paths.get(System.getProperty("java.io.tmpdir", "."))
    if (!isLinuxOperatingSystem()) return systemTemp
    val cacheRoot =
        System.getenv("XDG_CACHE_HOME")
            ?.takeIf(String::isNotBlank)
            ?.let(Paths::get)
            ?: System.getenv("HOME")
                ?.takeIf(String::isNotBlank)
                ?.let { Paths.get(it, ".cache") }
            ?: return systemTemp
    val usable =
        runCatching {
            Files.isDirectory(cacheRoot) && Files.isWritable(cacheRoot)
        }.getOrDefault(false)
    return if (usable) cacheRoot.resolve("ArchiveTune").resolve("botguard") else systemTemp
}

/** Chromium refuses to run as root unless `--no-sandbox` is passed explicitly. */
internal fun isRunningAsRoot(): Boolean {
    if (!isLinuxOperatingSystem()) return false
    val uid =
        runCatching {
            Files.getAttribute(Paths.get("/proc/self"), "unix:uid") as? Int
        }.getOrNull()
    if (uid != null) return uid == 0
    return System.getProperty("user.name").equals("root", ignoreCase = true)
}

internal fun parseCreateChallenge(rawResponse: String): String {
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

internal fun parseIntegrityToken(rawResponse: String): Pair<ByteArray, Long> {
    val response = JSONArray(rawResponse)
    return decodeYouTubeBase64(response.getString(0)) to response.getLong(1)
}

/** Mirrors Android's `commaSeparatedBytesToBase64` and its YouTube-specific URL-safe alphabet. */
internal fun encodePoToken(commaSeparatedBytes: String): String {
    val bytes =
        commaSeparatedBytes
            .split(',')
            .map { it.trim().toInt().toByte() }
            .toByteArray()
    return Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_')
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
