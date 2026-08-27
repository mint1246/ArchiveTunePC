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
import java.security.MessageDigest
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
 * briefly. This flow keeps a dedicated ArchiveTune-owned Chromium profile so the browser can retain
 * and renew its own Google session. Browser sync stays disabled and Google sign-in happens without
 * DevTools/remote debugging.
 * After the user confirms that sign-in is complete, ArchiveTune closes only that isolated browser
 * process tree and briefly relaunches the same profile with local DevTools enabled to read cookies
 * applicable to YouTube.
 *
 * The user's normal browser profile is never opened, inspected, or modified by this class.
 */
fun interface DesktopInteractiveLoginSource {
    suspend fun acquireSession(): Result<YouTubeSessionImport>

    fun completeLogin(): Boolean = false

    fun clearSession(): Result<Unit> = Result.success(Unit)
}

class ManagedChromiumLoginSource(
    private val profile: Path = Paths.get(System.getProperty("java.io.tmpdir"), "archivetune-youtube-profile"),
    private val browserFinder: () -> Path? = ::findSupportedChromiumBrowser,
    private val legacyBrowserFinder: () -> Path? = ::findLegacyManagedChromiumBrowser,
    private val loginTimeout: Duration = Duration.ofMinutes(10),
    private val startupTimeout: Duration = Duration.ofSeconds(20),
    private val pollDelayMillis: Long = 750L,
) : DesktopInteractiveLoginSource, DesktopSessionCookieRefresher {
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

    override fun clearSession(): Result<Unit> =
        runCatching {
            check(pendingCompletion.get() == null) { "Cannot clear the managed browser profile while sign-in is running" }
            check(deleteManagedProfile(profile)) { "Could not remove the managed YouTube browser profile" }
        }

    override suspend fun refresh(previous: YouTubeSessionImport): Result<YouTubeSessionImport> =
        withContext(Dispatchers.IO) {
            try {
                if (!Files.isDirectory(profile)) {
                    return@withContext Result.failure(IllegalStateException("Managed YouTube browser profile is unavailable"))
                }
                val previousFingerprint = cookieFingerprint(previous.cookieHeader)
                if (previousFingerprint !in readProfileSessionFingerprints()) {
                    return@withContext Result.failure(IllegalStateException("Managed browser profile belongs to a different YouTube session"))
                }
                val browser = resolveManagedProfileBrowser()
                    ?: return@withContext Result.failure(IllegalStateException("No supported Chromium browser was found"))
                val refreshed = extractBrowserSession(browser, refreshPage = true)
                writeProfileSessionFingerprints(previousFingerprint, cookieFingerprint(refreshed.cookieHeader))
                writeProfileBrowser(browser)
                Result.success(refreshed)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        }

    private suspend fun acquireSessionOrThrow(): YouTubeSessionImport {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "Managed YouTube sign-in currently requires Windows"
        }
        val browser = resolveManagedProfileBrowser()
            ?: throw IllegalStateException("No supported Chromium browser was found")
        profile.parent?.let(Files::createDirectories)
        Files.createDirectories(profile)
        prepareManagedBrowserProfile(browser, profile)
        writeProfileBrowser(browser)
        var loginProcess: Process? = null
        val completion = CompletableDeferred<Unit>()
        check(pendingCompletion.compareAndSet(null, completion)) { "A managed browser sign-in is already running" }

        try {
            val startedProcess = ProcessBuilder(managedLoginBrowserCommand(browser, profile))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .also { process -> process.outputStream.close() }
            loginProcess = startedProcess

            awaitManagedLoginCompletion(
                completion = completion,
                managedProcess = startedProcess,
                loginTimeout = loginTimeout,
                pollDelayMillis = pollDelayMillis,
                isBrowserRunning = { startedProcess.isAlive || managedProfileBrowserProcesses().isNotEmpty() },
            )

            stopManagedBrowser(loginProcess)
            loginProcess = null
            delay(PROFILE_RELEASE_DELAY_MILLIS)
            val session = extractBrowserSession(browser, refreshPage = true)
            writeProfileSessionFingerprints(cookieFingerprint(session.cookieHeader))
            return session
        } finally {
            stopManagedBrowser(loginProcess)
            pendingCompletion.compareAndSet(completion, null)
        }
    }

    private suspend fun extractBrowserSession(browser: Path, refreshPage: Boolean): YouTubeSessionImport {
        prepareManagedBrowserProfile(browser, profile)
        Files.deleteIfExists(profile.resolve("DevToolsActivePort"))
        var extractionProcess: Process? = null
        var browserClient: CdpClient? = null
        var pageClient: CdpClient? = null
        try {
            extractionProcess = ProcessBuilder(managedExtractionBrowserCommand(browser, profile))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .also { process -> process.outputStream.close() }

            val endpoint = waitForDevTools(profile)
            browserClient = CdpClient.connect(endpoint.browserWebSocketUrl)
            val pageTarget = waitForPageTarget(endpoint.port)
            pageClient = CdpClient.connect(pageTarget.webSocketDebuggerUrl)
            if (refreshPage) {
                pageClient.call("Page.navigate", JSONObject().put("url", MANAGED_MUSIC_URL))
                waitForDocumentReady(pageClient)
                delay(SESSION_SETTLE_DELAY_MILLIS)
            }
            val cookieDeadlineNanos = System.nanoTime() + SESSION_COOKIE_TIMEOUT.toNanos()
            while (true) {
                val cookies =
                    pageClient.call(
                        "Network.getCookies",
                        JSONObject().put("urls", JSONArray(YOUTUBE_COOKIE_URLS)),
                    ).getJSONArray("cookies")
                sessionFromYouTubeCookies(cookies)?.let { return it }
                if (System.nanoTime() >= cookieDeadlineNanos) break
                delay(SESSION_COOKIE_POLL_MILLIS)
            }
            throw IllegalStateException("The managed browser no longer has a usable YouTube Music session")
        } finally {
            pageClient?.close()
            runCatching { browserClient?.call("Browser.close") }
            browserClient?.close()
            stopManagedBrowser(extractionProcess)
        }
    }

    private suspend fun waitForDocumentReady(pageClient: CdpClient) {
        val deadlineNanos = System.nanoTime() + PAGE_LOAD_TIMEOUT.toNanos()
        while (System.nanoTime() < deadlineNanos) {
            val readyState =
                runCatching {
                    pageClient.call(
                        "Runtime.evaluate",
                        JSONObject()
                            .put("expression", "document.readyState")
                            .put("returnByValue", true),
                    ).optJSONObject("result")
                        ?.optString("value")
                }.getOrNull()
            if (readyState == "complete" || readyState == "interactive") return
            delay(150L)
        }
    }

    private fun stopManagedBrowser(process: Process?) {
        val root = process?.toHandle() ?: return
        val closeRequested = mutableSetOf<Long>()
        val gracefulDeadlineNanos = System.nanoTime() + GRACEFUL_BROWSER_EXIT.toNanos()
        while (System.nanoTime() < gracefulDeadlineNanos) {
            val browserHandles =
                (listOf(root) + managedProfileBrowserProcesses())
                    .filter(ProcessHandle::isAlive)
                    .distinctBy(ProcessHandle::pid)
            if (browserHandles.isEmpty()) break
            val newlyDiscovered = browserHandles.filter { closeRequested.add(it.pid()) }
            if (newlyDiscovered.isNotEmpty()) closeManagedBrowserWindows(newlyDiscovered)
            Thread.sleep(PROCESS_EXIT_POLL_MILLIS)
        }

        var handles = ownedManagedProcesses(root)
        handles.filter(ProcessHandle::isAlive).forEach { handle -> runCatching { handle.destroy() } }

        val deadlineNanos = System.nanoTime() + PROCESS_EXIT_GRACE.toNanos()
        while (System.nanoTime() < deadlineNanos) {
            handles = ownedManagedProcesses(root)
            if (handles.none(ProcessHandle::isAlive)) break
            Thread.sleep(PROCESS_EXIT_POLL_MILLIS)
        }
        handles = ownedManagedProcesses(root)
        handles.filter(ProcessHandle::isAlive).forEach { handle -> runCatching { handle.destroyForcibly() } }
    }

    private fun ownedManagedProcesses(root: ProcessHandle): List<ProcessHandle> {
        val descendants = runCatching { root.descendants().use { it.toList() } }.getOrDefault(emptyList())
        return (descendants + managedProfileProcesses() + root).distinctBy(ProcessHandle::pid)
    }

    private fun closeManagedBrowserWindows(handles: List<ProcessHandle>) {
        val processIds = handles.map(ProcessHandle::pid).distinct()
        if (processIds.isEmpty()) return
        runCatching {
            ProcessBuilder(managedBrowserCloseCommand(processIds))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(POWERSHELL_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun managedProfileProcesses(browserRootsOnly: Boolean = false): List<ProcessHandle> {
        val profileArgument = "--user-data-dir=${profile.toAbsolutePath().normalize()}"
        return ProcessHandle.allProcesses().use { processes ->
            processes
                .filter(ProcessHandle::isAlive)
                .filter { process ->
                    val arguments = process.info().arguments().orElse(emptyArray())
                    val usesProfile = arguments.any { argument -> argument.equals(profileArgument, ignoreCase = true) }
                    usesProfile && (!browserRootsOnly || isChromiumBrowserRoot(arguments))
                }.toList()
        }
    }

    private fun managedProfileBrowserProcesses(): List<ProcessHandle> = managedProfileProcesses(browserRootsOnly = true)

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

    private fun deleteManagedProfile(profile: Path): Boolean {
        repeat(PROFILE_DELETE_ATTEMPTS) {
            if (!Files.exists(profile)) return true
            runCatching {
                Files.walk(profile).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
            if (!Files.exists(profile)) return true
            Thread.sleep(PROFILE_DELETE_RETRY_MILLIS)
        }
        return !Files.exists(profile)
    }

    private fun readProfileSessionFingerprints(): Set<String> =
        runCatching {
            Files.readAllLines(profile.resolve(PROFILE_SESSION_MARKER))
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
        }.getOrDefault(emptySet())

    private fun readProfileBrowser(): Path? =
        runCatching {
            Files.readString(profile.resolve(PROFILE_BROWSER_MARKER))
                .trim()
                .takeIf(String::isNotBlank)
                ?.let(Paths::get)
        }.getOrNull()

    private fun resolveManagedProfileBrowser(): Path? {
        val browserMarker = profile.resolve(PROFILE_BROWSER_MARKER)
        if (Files.exists(browserMarker)) {
            return readProfileBrowser()
                ?.takeIf(Files::isRegularFile)
                ?: throw IllegalStateException("The Chromium browser used for this YouTube session is no longer available")
        }

        // Profiles created before browser-path tracking always used the old Edge-first finder.
        // Preserve that browser for existing sessions so app-bound cookies are not opened by a
        // different Chromium product. Fresh profiles use the user's default Chromium browser.
        if (Files.isRegularFile(profile.resolve(PROFILE_SESSION_MARKER))) {
            return legacyBrowserFinder() ?: browserFinder()
        }
        return browserFinder()
    }

    private fun writeProfileSessionFingerprints(vararg fingerprints: String) {
        Files.writeString(
            profile.resolve(PROFILE_SESSION_MARKER),
            fingerprints.distinct().joinToString("\n", postfix = "\n"),
        )
    }

    private fun writeProfileBrowser(browser: Path) {
        Files.writeString(
            profile.resolve(PROFILE_BROWSER_MARKER),
            browser.toAbsolutePath().normalize().toString(),
        )
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
        private const val PROFILE_SESSION_MARKER = ".archivetune-session-fingerprints"
        private const val PROFILE_BROWSER_MARKER = ".archivetune-browser-path"
        private const val PROFILE_RELEASE_DELAY_MILLIS = 250L
        private const val SESSION_SETTLE_DELAY_MILLIS = 1_500L
        private const val SESSION_COOKIE_POLL_MILLIS = 250L
        private const val PROCESS_EXIT_POLL_MILLIS = 50L
        private const val POWERSHELL_CLOSE_TIMEOUT_SECONDS = 5L
        private val GRACEFUL_BROWSER_EXIT = Duration.ofSeconds(10)
        private val PROCESS_EXIT_GRACE = Duration.ofSeconds(2)
        private val PAGE_LOAD_TIMEOUT = Duration.ofSeconds(10)
        private val SESSION_COOKIE_TIMEOUT = Duration.ofSeconds(10)
    }
}

internal fun managedBrowserCloseCommand(processIds: List<Long>): List<String> {
    val ids = processIds.joinToString(",")
    return listOf(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-WindowStyle",
        "Hidden",
        "-Command",
        "Get-Process -Id $ids -ErrorAction SilentlyContinue | ForEach-Object { \$_.CloseMainWindow() | Out-Null }",
    )
}

internal suspend fun awaitManagedLoginCompletion(
    completion: CompletableDeferred<Unit>,
    managedProcess: Process,
    loginTimeout: Duration,
    pollDelayMillis: Long,
    isBrowserRunning: () -> Boolean = managedProcess::isAlive,
    browserCloseGrace: Duration = Duration.ofSeconds(2),
) {
    val deadlineNanos = System.nanoTime() + loginTimeout.toNanos()
    var browserMissingSinceNanos: Long? = null
    while (true) {
        if (completion.isCompleted) return
        if (isBrowserRunning()) {
            browserMissingSinceNanos = null
        } else {
            // completeLogin() may race with the browser's final process exit. Give an already-won
            // completion signal precedence rather than reporting that successful confirmation as a close.
            if (completion.isCompleted) return
            val now = System.nanoTime()
            val missingSince = browserMissingSinceNanos ?: now.also { browserMissingSinceNanos = it }
            if (now - missingSince >= browserCloseGrace.toNanos().coerceAtLeast(0L)) {
                throw IllegalStateException("The ArchiveTune sign-in browser was closed before sign-in was completed")
            }
        }
        if (System.nanoTime() >= deadlineNanos) {
            throw IllegalStateException("Timed out waiting for YouTube Music sign-in")
        }
        delay(pollDelayMillis.coerceAtLeast(1L))
    }
}

internal fun managedLoginBrowserCommand(browser: Path, profile: Path): List<String> =
    listOf(
        browser.toString(),
        "--user-data-dir=$profile",
        "--profile-directory=Default",
        "--disable-sync",
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
        "--headless=new",
        "--remote-debugging-port=0",
        "--remote-debugging-address=127.0.0.1",
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
    findDefaultChromiumBrowser()?.let { return it }

    val roots =
        listOfNotNull(
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)"),
            System.getenv("LOCALAPPDATA"),
        ).distinct()
    val relativeCandidates =
        listOf(
            listOf("Google", "Chrome", "Application", "chrome.exe"),
            listOf("Microsoft", "Edge", "Application", "msedge.exe"),
            listOf("BraveSoftware", "Brave-Browser", "Application", "brave.exe"),
            listOf("Vivaldi", "Application", "vivaldi.exe"),
            listOf("Chromium", "Application", "chrome.exe"),
            listOf("Programs", "Opera", "opera.exe"),
            listOf("Programs", "Opera GX", "opera.exe"),
        )
    return relativeCandidates
        .asSequence()
        .flatMap { relative -> roots.asSequence().map { root -> Paths.get(root, *relative.toTypedArray()) } }
        .distinct()
        .firstOrNull(Files::isRegularFile)
}

internal fun findLegacyManagedChromiumBrowser(): Path? {
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

internal fun findDefaultChromiumBrowser(
    registryQuery: (String, String?) -> String? = ::queryWindowsRegistryString,
): Path? {
    val progId = registryQuery(DEFAULT_HTTPS_USER_CHOICE_KEY, "ProgId")?.trim().orEmpty()
    if (progId.isBlank()) return null
    val command = registryQuery("HKEY_CLASSES_ROOT\\$progId\\shell\\open\\command", null) ?: return null
    val executable = executableFromBrowserCommand(command) ?: return null
    return executable
        .takeIf(::isSupportedChromiumExecutable)
        ?.takeIf(Files::isRegularFile)
}

internal fun executableFromBrowserCommand(command: String): Path? {
    val trimmed = command.trim()
    if (trimmed.isBlank()) return null
    val rawPath =
        if (trimmed.startsWith('"')) {
            val closingQuote = trimmed.indexOf('"', startIndex = 1)
            if (closingQuote <= 1) return null
            trimmed.substring(1, closingQuote)
        } else {
            val exeEnd = trimmed.indexOf(".exe", ignoreCase = true)
            if (exeEnd < 0) return null
            trimmed.substring(0, exeEnd + 4)
        }
    val expanded = ENVIRONMENT_REFERENCE.replace(rawPath) { match ->
        System.getenv(match.groupValues[1]) ?: match.value
    }
    return runCatching { Paths.get(expanded) }.getOrNull()
}

internal fun isSupportedChromiumExecutable(path: Path): Boolean =
    path.fileName
        ?.toString()
        ?.lowercase() in SUPPORTED_CHROMIUM_EXECUTABLES

internal fun prepareManagedBrowserProfile(browser: Path, profile: Path) {
    if (!browser.fileName.toString().equals("vivaldi.exe", ignoreCase = true)) return

    Files.createDirectories(profile)
    Files.createDirectories(profile.resolve("Default"))
    updateJsonFile(profile.resolve("Local State")) { root ->
        root.objectValue("browser")
            .put("first_run_finished", true)
    }
    updateJsonFile(profile.resolve("Default").resolve("Preferences")) { root ->
        root.objectValue("vivaldi")
            .objectValue("startup")
            .put("has_seen_feature", 1)
            .put("has_seen_welcome_page", true)
    }
    val firstRun = profile.resolve("First Run")
    if (!Files.exists(firstRun)) Files.createFile(firstRun)
}

private fun updateJsonFile(path: Path, update: (JSONObject) -> Unit) {
    val root =
        if (Files.isRegularFile(path)) {
            runCatching { JSONObject(Files.readString(path)) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
    update(root)
    Files.writeString(path, root.toString())
}

private fun JSONObject.objectValue(name: String): JSONObject =
    optJSONObject(name) ?: JSONObject().also { put(name, it) }

internal fun isChromiumBrowserRoot(arguments: Array<String>): Boolean =
    arguments.none { it.startsWith("--type=", ignoreCase = true) }

private fun queryWindowsRegistryString(key: String, valueName: String?): String? =
    runCatching {
        val command = buildList {
            add("reg.exe")
            add("query")
            add(key)
            if (valueName == null) {
                add("/ve")
            } else {
                add("/v")
                add(valueName)
            }
        }
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
                .also { it.outputStream.close() }
        if (!process.waitFor(REGISTRY_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) return@runCatching null
        val output = process.inputStream.bufferedReader().use { it.readText() }
        REGISTRY_STRING_VALUE.findAll(output)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

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
private val SUPPORTED_CHROMIUM_EXECUTABLES =
    setOf("chrome.exe", "msedge.exe", "brave.exe", "vivaldi.exe", "chromium.exe", "opera.exe", "opera_gx.exe")
private val ENVIRONMENT_REFERENCE = Regex("%([^%]+)%")
private val REGISTRY_STRING_VALUE = Regex("(?m)^\\s*.+?\\s+REG_(?:SZ|EXPAND_SZ)\\s+(.+?)\\s*$")
private const val DEFAULT_HTTPS_USER_CHOICE_KEY =
    "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\https\\UserChoice"
private const val REGISTRY_QUERY_TIMEOUT_SECONDS = 2L
private const val MANAGED_MUSIC_URL = "https://music.youtube.com/"
private const val MANAGED_LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

private fun cookieFingerprint(cookieHeader: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(cookieHeader.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
