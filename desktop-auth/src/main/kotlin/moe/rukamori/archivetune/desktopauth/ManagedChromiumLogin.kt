/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Interactive browser login used by desktop as the closest analogue to Android's login WebView.
 *
 * Chromium on Windows can bind Google authentication cookies to the browser/device and rotate them
 * later. A copied Cookie-Editor snapshot cannot perform that rotation, so it may authenticate only
 * briefly. This flow launches a fresh, ArchiveTune-owned Chromium profile with the bound-session
 * feature and browser sync disabled. Google sign-in happens without DevTools/remote debugging.
 * After the user confirms that sign-in is complete, ArchiveTune closes only that isolated browser
 * process tree and briefly relaunches the same profile with local DevTools enabled to read cookies
 * applicable to YouTube.
 *
 * The user's normal browser profile is never opened, inspected, or modified by this class.
 */
fun interface DesktopInteractiveLoginSource {
    suspend fun acquireSession(): Result<YouTubeSessionImport>

    fun completeLogin(): Boolean = false
}

class ManagedChromiumLoginSource(
    private val browserFinder: () -> Path? = ::findSupportedChromiumBrowser,
    private val tempRoot: Path = Paths.get(System.getProperty("java.io.tmpdir")),
    private val loginTimeout: Duration = Duration.ofMinutes(10),
    private val startupTimeout: Duration = Duration.ofSeconds(20),
    private val pollDelayMillis: Long = 750L,
) : DesktopInteractiveLoginSource {
    private val pendingCompletion = AtomicReference<CompletableDeferred<Unit>?>(null)

    override suspend fun acquireSession(): Result<YouTubeSessionImport> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(acquireSessionOrThrow())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        }

    override fun completeLogin(): Boolean = pendingCompletion.get()?.complete(Unit) ?: false

    private suspend fun acquireSessionOrThrow(): YouTubeSessionImport {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "Managed YouTube sign-in currently requires Windows"
        }
        val browser = browserFinder() ?: throw IllegalStateException("Microsoft Edge or Google Chrome was not found")
        Files.createDirectories(tempRoot)
        val profile = Files.createTempDirectory(tempRoot, "archivetune-login-")
        var loginProcess: Process? = null
        var extractionProcess: Process? = null
        var browserClient: CdpClient? = null
        var pageClient: CdpClient? = null
        val completion = CompletableDeferred<Unit>()
        check(pendingCompletion.compareAndSet(null, completion)) { "A managed browser sign-in is already running" }

        try {
            loginProcess = ProcessBuilder(managedLoginBrowserCommand(browser, profile))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .also { process -> process.outputStream.close() }

            val loginDeadlineNanos = System.nanoTime() + loginTimeout.toNanos()
            while (!completion.isCompleted) {
                if (System.nanoTime() >= loginDeadlineNanos) {
                    throw IllegalStateException("Timed out waiting for YouTube Music sign-in")
                }
                delay(pollDelayMillis)
            }

            stopManagedBrowser(loginProcess)
            loginProcess = null
            delay(PROFILE_RELEASE_DELAY_MILLIS)

            extractionProcess = ProcessBuilder(managedExtractionBrowserCommand(browser, profile))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .also { process -> process.outputStream.close() }

            val endpoint = waitForDevTools(profile)
            browserClient = CdpClient.connect(endpoint.browserWebSocketUrl)
            val pageTarget = waitForPageTarget(endpoint.port)
            pageClient = CdpClient.connect(pageTarget.webSocketDebuggerUrl)
            val cookies =
                pageClient.call(
                    "Network.getCookies",
                    JSONObject().put("urls", JSONArray(YOUTUBE_COOKIE_URLS)),
                ).getJSONArray("cookies")

            return sessionFromYouTubeCookies(cookies)
                ?: throw IllegalStateException("YouTube Music sign-in did not produce a usable account session")
        } finally {
            pageClient?.close()
            runCatching { browserClient?.call("Browser.close") }
            browserClient?.close()
            stopManagedBrowser(extractionProcess)
            stopManagedBrowser(loginProcess)
            pendingCompletion.compareAndSet(completion, null)
            cleanupManagedProfile(profile)
        }
    }

    private fun stopManagedBrowser(process: Process?) {
        val root = process?.toHandle() ?: return
        val handles = root.descendants().toList() + root
        handles.filter(ProcessHandle::isAlive).forEach { handle -> runCatching { handle.destroy() } }

        val deadlineNanos = System.nanoTime() + PROCESS_EXIT_GRACE.toNanos()
        while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadlineNanos) {
            Thread.sleep(PROCESS_EXIT_POLL_MILLIS)
        }
        handles.filter(ProcessHandle::isAlive).forEach { handle -> runCatching { handle.destroyForcibly() } }
    }

    private suspend fun waitForDevTools(profile: Path): DevToolsEndpoint {
        val activePort = profile.resolve("DevToolsActivePort")
        val deadlineNanos = System.nanoTime() + startupTimeout.toNanos()
        while (System.nanoTime() < deadlineNanos) {
            if (Files.isRegularFile(activePort)) {
                val lines = runCatching { Files.readAllLines(activePort) }.getOrNull()
                val port = lines?.getOrNull(0)?.trim()?.toIntOrNull()
                val browserPath = lines?.getOrNull(1)?.trim()
                if (port != null && !browserPath.isNullOrBlank()) {
                    return DevToolsEndpoint(
                        port = port,
                        browserWebSocketUrl = "ws://127.0.0.1:$port$browserPath",
                    )
                }
            }
            delay(100L)
        }
        throw IllegalStateException("The ArchiveTune sign-in browser did not start")
    }

    private suspend fun waitForPageTarget(port: Int): PageTarget {
        val deadlineNanos = System.nanoTime() + startupTimeout.toNanos()
        while (System.nanoTime() < deadlineNanos) {
            findPageTarget(port)?.let { return it }
            delay(pollDelayMillis)
        }
        throw IllegalStateException("The ArchiveTune cookie reader did not start")
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
            .mapNotNull { targets.optJSONObject(it) }
            .filter { it.optString("type") == "page" }
            .firstNotNullOfOrNull { target ->
                target.optString("webSocketDebuggerUrl")
                    .takeIf(String::isNotBlank)
                    ?.let { PageTarget(it) }
            }
    }

    private fun cleanupManagedProfile(profile: Path) {
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
        // A browser process can take a moment longer to release its profile. Leaving an isolated
        // temp profile behind is safer than ever targeting unrelated browser processes by name.
    }

    private data class DevToolsEndpoint(
        val port: Int,
        val browserWebSocketUrl: String,
    )

    private data class PageTarget(
        val webSocketDebuggerUrl: String,
    )

    private companion object {
        private val HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        private val YOUTUBE_COOKIE_URLS =
            listOf(
                "https://music.youtube.com/",
                "https://www.youtube.com/",
                "https://youtube.com/",
            )
        private const val PROFILE_DELETE_ATTEMPTS = 20
        private const val PROFILE_DELETE_RETRY_MILLIS = 100L
        private const val PROFILE_RELEASE_DELAY_MILLIS = 250L
        private const val PROCESS_EXIT_POLL_MILLIS = 50L
        private val PROCESS_EXIT_GRACE = Duration.ofSeconds(2)
    }
}

internal fun managedLoginBrowserCommand(browser: Path, profile: Path): List<String> =
    listOf(
        browser.toString(),
        "--user-data-dir=$profile",
        "--profile-directory=Default",
        "--disable-sync",
        "--disable-features=EnableBoundSessionCredentials",
        "--disable-background-mode",
        "--no-first-run",
        "--no-default-browser-check",
        MANAGED_LOGIN_URL,
    )

internal fun managedExtractionBrowserCommand(browser: Path, profile: Path): List<String> =
    listOf(
        browser.toString(),
        "--user-data-dir=$profile",
        "--profile-directory=Default",
        "--disable-sync",
        "--remote-debugging-port=0",
        "--remote-debugging-address=127.0.0.1",
        "--disable-features=EnableBoundSessionCredentials",
        "--disable-background-mode",
        "--no-first-run",
        "--no-default-browser-check",
        "about:blank",
    )

internal fun sessionFromYouTubeCookies(cookies: JSONArray): YouTubeSessionImport? {
    val merged = linkedMapOf<String, BrowserCookie>()
    for (index in 0 until cookies.length()) {
        val raw = cookies.optJSONObject(index) ?: continue
        val name = raw.optString("name").trim()
        val value = raw.optString("value")
        val domain = raw.optString("domain").trim().lowercase()
        val path = raw.optString("path", "/")
        if (name.isBlank() || value.isBlank() || !name.matches(COOKIE_NAME)) continue
        if (!value.isSafeCookieValue()) continue
        if (!domain.removePrefix(".").isYouTubeDomain()) continue
        if (!path.startsWith('/')) continue
        val expiresSeconds = raw.optDouble("expires", -1.0)
        val expiresAtMillis =
            expiresSeconds
                .takeIf { it.isFinite() && it > 0.0 }
                ?.let { (it * 1_000.0).toLong() }
        merged[name] = BrowserCookie(name, value, expiresAtMillis)
    }
    if (merged.isEmpty()) return null

    val header = merged.values.joinToString("; ") { "${it.name}=${it.value}" }
    if (!hasYouTubeLoginCookie(header)) return null
    val expiry =
        merged.values
            .filter { it.name in LOGIN_COOKIE_NAMES }
            .mapNotNull(BrowserCookie::expiresAtMillis)
            .minOrNull()

    return YouTubeSessionImport(
        cookieHeader = header,
        expiresAtEpochMillis = expiry,
    )
}

internal fun findSupportedChromiumBrowser(): Path? {
    val candidates = buildList {
        System.getenv("ProgramFiles(x86)")?.let { root ->
            add(Paths.get(root, "Microsoft", "Edge", "Application", "msedge.exe"))
            add(Paths.get(root, "Google", "Chrome", "Application", "chrome.exe"))
        }
        System.getenv("ProgramFiles")?.let { root ->
            add(Paths.get(root, "Microsoft", "Edge", "Application", "msedge.exe"))
            add(Paths.get(root, "Google", "Chrome", "Application", "chrome.exe"))
        }
        System.getenv("LOCALAPPDATA")?.let { root ->
            add(Paths.get(root, "Microsoft", "Edge", "Application", "msedge.exe"))
            add(Paths.get(root, "Google", "Chrome", "Application", "chrome.exe"))
        }
    }
    return candidates.distinct().firstOrNull(Files::isRegularFile)
}

private data class BrowserCookie(
    val name: String,
    val value: String,
    val expiresAtMillis: Long?,
)

internal class CdpClient private constructor(
    private val socket: WebSocket,
    private val listener: Listener,
) : AutoCloseable {
    private val ids = AtomicLong(0L)

    val isClosed: Boolean
        get() = listener.closed

    fun call(
        method: String,
        params: JSONObject? = null,
        timeoutSeconds: Long = CDP_TIMEOUT_SECONDS,
    ): JSONObject {
        check(!listener.closed) { "DevTools connection is closed" }
        val id = ids.incrementAndGet()
        val response = listener.expect(id)
        val message = JSONObject().put("id", id).put("method", method)
        if (params != null) message.put("params", params)
        socket.sendText(message.toString(), true).get(timeoutSeconds, TimeUnit.SECONDS)
        val payload = response.get(timeoutSeconds, TimeUnit.SECONDS)
        payload.optJSONObject("error")?.let { error ->
            throw IllegalStateException(error.optString("message", "DevTools command failed"))
        }
        return payload.optJSONObject("result") ?: JSONObject()
    }

    override fun close() {
        if (!listener.closed) {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(1, TimeUnit.SECONDS) }
        }
        listener.close()
    }

    private class Listener : WebSocket.Listener {
        private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
        private val text = StringBuilder()
        @Volatile var closed: Boolean = false
            private set

        fun expect(id: Long): CompletableFuture<JSONObject> =
            CompletableFuture<JSONObject>().also { pending[id] = it }

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
            synchronized(text) {
                text.append(data)
                if (last) {
                    val message = runCatching { JSONObject(text.toString()) }.getOrNull()
                    text.setLength(0)
                    val id = message?.optLong("id", -1L) ?: -1L
                    if (id >= 0L) pending.remove(id)?.complete(message)
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*>? {
            close()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            close(error)
        }

        fun close(error: Throwable = IllegalStateException("DevTools connection closed")) {
            if (closed) return
            closed = true
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }
    }

    companion object {
        fun connect(url: String): CdpClient {
            val listener = Listener()
            val socket =
                HttpClient
                    .newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(url), listener)
                    .get(CDP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return CdpClient(socket, listener)
        }

        private const val CDP_TIMEOUT_SECONDS = 5L
    }
}

private fun String.isYouTubeDomain(): Boolean = this == "youtube.com" || endsWith(".youtube.com")

private fun String.isSafeCookieValue(): Boolean =
    isNotEmpty() && all { character -> character.code in 0x21..0x7E && character != ';' && character != ',' }

private val COOKIE_NAME = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")
private val LOGIN_COOKIE_NAMES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID", "APISID")
private const val MANAGED_LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
