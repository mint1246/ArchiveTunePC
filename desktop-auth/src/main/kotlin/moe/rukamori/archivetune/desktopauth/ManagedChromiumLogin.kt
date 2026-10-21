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
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
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
 * applicable to YouTube. On Linux the reliable completion path is the user closing the dedicated
 * window: the wait observes that normal Chromium shutdown (the same flush path as `Browser.close`)
 * before reading cookies. Windows keeps the confirmation button plus graceful window close.
 *
 * The user's normal browser profile is never opened, inspected, or modified by this class.
 *
 * Every acquire/refresh/clear operation also holds an exclusive OS file lease next to the managed
 * profile. That lease is separate from the in-memory guard so a second ArchiveTune process using
 * the same profile refuses the operation instead of killing this process's live browser as an
 * orphan, and it stays outside the profile directory so clearSession cannot unlink it.
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
    private val pendingOperation = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val pendingInteractiveLogin = AtomicReference<CompletableDeferred<Unit>?>(null)

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

    /**
     * Confirms interactive sign-in only while [acquireSession] is actually waiting for the user.
     * Refresh/clear also reserve [pendingOperation], but those background operations have no user
     * interaction to confirm, so they must not consume the "Done signing in" signal.
     *
     * On Linux the UI finalizes by closing the dedicated window. If a caller still requests
     * completion, it only *requests* the finish: the wait continues until Chromium exits through
     * its normal shutdown path. Windows keeps its immediate completion plus window-close handoff.
     */
    override fun completeLogin(): Boolean = pendingInteractiveLogin.get()?.complete(Unit) ?: false

    override fun clearSession(): Result<Unit> =
        runCatching {
            // Reserve ownership before touching processes or the profile so a concurrent
            // acquireSession cannot start while cleanup is running.
            val completion = CompletableDeferred<Unit>()
            check(pendingOperation.compareAndSet(null, completion)) {
                "Cannot clear the managed browser profile while sign-in is running"
            }
            var lease: ManagedProfileLease? = null
            try {
                lease = requireManagedProfileLease()
                // A crashed earlier run can leave browser helpers behind. They keep the profile
                // locked (SingletonLock/SingletonSocket) and can resurrect data after the
                // directory is removed.
                stopManagedBrowser(null)
                check(deleteManagedProfile(profile)) { "Could not remove the managed YouTube browser profile" }
            } finally {
                lease?.close()
                pendingOperation.compareAndSet(completion, null)
            }
        }

    override suspend fun refresh(previous: YouTubeSessionImport): Result<YouTubeSessionImport> =
        withContext(Dispatchers.IO) {
            val completion = CompletableDeferred<Unit>()
            if (!pendingOperation.compareAndSet(null, completion)) {
                return@withContext Result.failure(IllegalStateException("Managed browser sign-in is already running"))
            }
            var lease: ManagedProfileLease? = null
            try {
                // The cross-instance lease is taken before inspecting or mutating the profile so a
                // second ArchiveTune instance can never treat this instance's live browser as an
                // orphan and kill it.
                lease = requireManagedProfileLease()
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
            } finally {
                lease?.close()
                pendingOperation.compareAndSet(completion, null)
            }
        }

    private suspend fun acquireSessionOrThrow(): YouTubeSessionImport {
        check(isWindowsOperatingSystem() || isLinuxOperatingSystem()) {
            "Managed YouTube sign-in requires Windows or Linux"
        }

        // Reserve ownership *before* resolving/cleaning/mutating the profile. Otherwise a second
        // concurrent acquire could pass the guard only after this call already killed the first
        // login's browser (or vice versa).
        val completion = CompletableDeferred<Unit>()
        check(pendingOperation.compareAndSet(null, completion)) { "A managed browser sign-in is already running" }
        var loginProcess: Process? = null
        var lease: ManagedProfileLease? = null
        try {
            // The lease lives outside the profile directory and is held for the complete acquire,
            // browser shutdown, and cookie-read sequence. Another ArchiveTune instance sharing
            // this profile must refuse the operation instead of killing this live browser as an
            // "orphan", and clearSession must not be able to unlink the live lock by deleting the
            // profile.
            lease = requireManagedProfileLease()
            requireGraphicalLoginSession()
            val browser = resolveManagedProfileBrowser()
                ?: throw ManagedBrowserFailure(ManagedBrowserFailureCategory.BROWSER_NOT_FOUND)

            // Brave/Chromium can leave helpers behind if a previous ArchiveTune run crashed. They
            // keep the dedicated profile locked and would make the next launch silently hand off
            // to an old process (or exit immediately), so clean up our own profile before starting.
            try {
                stopOrphanedManagedBrowserProcesses()
                profile.parent?.let(Files::createDirectories)
                Files.createDirectories(profile)
                if (isLinuxOperatingSystem()) {
                    Files.setPosixFilePermissions(profile, PosixFilePermissions.fromString("rwx------"))
                }
                prepareManagedBrowserProfile(browser, profile)
                writeProfileBrowser(browser)
                clearStaleManagedProfileLockFiles(profile)
            } catch (failure: IOException) {
                throw ManagedBrowserFailure(classifyManagedBrowserFailure(failure.message), cause = failure)
            } catch (failure: SecurityException) {
                throw ManagedBrowserFailure(ManagedBrowserFailureCategory.PERMISSION_DENIED, cause = failure)
            }

            // Browser stderr is discarded rather than captured: Chromium can print the sign-in URL
            // and account/session material there. Failures expose only a fixed category instead.
            val startedProcess = startManagedBrowser(managedLoginBrowserCommand(browser, profile))
            loginProcess = startedProcess

            // Only this interactive wait may consume "Done signing in". Background refresh and
            // clear also reserve the operation guard but have nothing for the user to confirm.
            val loginCompletion = CompletableDeferred<Unit>()
            check(pendingInteractiveLogin.compareAndSet(null, loginCompletion)) {
                "A managed browser sign-in is already running"
            }
            val linuxNormalClose = isLinuxOperatingSystem()
            try {
                awaitManagedLoginCompletion(
                    completion = loginCompletion,
                    managedProcess = startedProcess,
                    loginTimeout = loginTimeout,
                    pollDelayMillis = pollDelayMillis,
                    isBrowserRunning = { startedProcess.isAlive || managedProfileBrowserProcesses().isNotEmpty() },
                    completeOnBrowserExit = linuxNormalClose,
                    completionExitTimeout = if (linuxNormalClose) LINUX_WINDOW_CLOSE_TIMEOUT else Duration.ZERO,
                )
            } finally {
                pendingInteractiveLogin.compareAndSet(loginCompletion, null)
            }

            if (linuxNormalClose) {
                // Success on Linux means the dedicated window was closed by the user and Chromium
                // ran its normal shutdown path (the same path Browser.close uses to flush the
                // cookie store). Never SIGTERM the root here; only clean up helpers that outlived
                // that normal root exit after giving them time to finish on their own.
                loginProcess = null
                awaitProcessExit(managedProfileProcesses(), LINUX_HELPER_EXIT_GRACE, PROCESS_EXIT_POLL_MILLIS)
                stopManagedBrowser(null)
            } else {
                // Windows keeps its existing CloseMainWindow-based graceful path.
                stopManagedBrowser(loginProcess)
                loginProcess = null
            }
            confirmManagedProfileReleased()
            delay(PROFILE_RELEASE_DELAY_MILLIS)
            val session = extractBrowserSession(browser, refreshPage = true)
            writeProfileSessionFingerprints(cookieFingerprint(session.cookieHeader))
            return session
        } finally {
            try {
                // Never run profile-wide orphan cleanup without the lease: when acquisition failed
                // because another instance owns the profile, its live browser must not be killed here.
                if (lease != null) stopManagedBrowser(loginProcess)
            } finally {
                lease?.close()
                pendingOperation.compareAndSet(completion, null)
            }
        }
    }

    private suspend fun extractBrowserSession(browser: Path, refreshPage: Boolean): YouTubeSessionImport {
        // Callers reserve pendingOperation for the whole operation, so no concurrent sign-in can
        // own this profile. Any remaining browser process is stale from a crashed run.
        try {
            stopOrphanedManagedBrowserProcesses()
            prepareManagedBrowserProfile(browser, profile)
            // A stale DevToolsActivePort from an earlier launch would point the CDP client at a
            // dead port. Remove it after the browser owning the profile has fully exited.
            Files.deleteIfExists(profile.resolve("DevToolsActivePort"))
        } catch (failure: IOException) {
            throw ManagedBrowserFailure(classifyManagedBrowserFailure(failure.message), cause = failure)
        } catch (failure: SecurityException) {
            throw ManagedBrowserFailure(ManagedBrowserFailureCategory.PERMISSION_DENIED, cause = failure)
        }
        var extractionProcess: Process? = null
        var browserClient: CdpClient? = null
        var pageClient: CdpClient? = null
        try {
            extractionProcess = startManagedBrowser(managedExtractionBrowserCommand(browser, profile))

            val endpoint = waitForDevTools(profile, extractionProcess)
            browserClient = CdpClient.connect(endpoint.browserWebSocketUrl)
            val pageTarget = waitForPageTarget(endpoint.port, extractionProcess)
            pageClient = CdpClient.connect(pageTarget.webSocketDebuggerUrl)
            if (refreshPage) {
                pageClient.call("Page.navigate", JSONObject().put("url", MANAGED_MUSIC_URL))
                waitForDocumentReady(pageClient)
                delay(SESSION_SETTLE_DELAY_MILLIS)
            }
            // Android's login WebView reads these from the live page before importing; the backend
            // uses them to bootstrap account context while cookie validation remains mandatory.
            val browserContext = readYouTubeSessionContext(pageClient)
            val cookieDeadlineNanos = System.nanoTime() + SESSION_COOKIE_TIMEOUT.toNanos()
            while (true) {
                val cookies = readBrowserCookies(pageClient)
                sessionFromYouTubeCookies(cookies)
                    ?.withManagedBrowserContext(browserContext)
                    ?.let { return it }
                if (System.nanoTime() >= cookieDeadlineNanos) break
                delay(SESSION_COOKIE_POLL_MILLIS)
            }
            throw ManagedBrowserFailure(ManagedBrowserFailureCategory.SESSION_NOT_FOUND)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: ManagedBrowserFailure) {
            throw failure
        } catch (failure: IllegalStateException) {
            throw ManagedBrowserFailure(ManagedBrowserFailureCategory.UNKNOWN, cause = failure)
        } finally {
            pageClient?.close()
            runCatching { browserClient?.call("Browser.close") }
            browserClient?.close()
            stopManagedBrowser(extractionProcess)
        }
    }

    /**
     * Reads the cookies a request to the Music root would carry. `Network.getCookies` with the
     * Music URL is the closest CDP equivalent to the Cookie header YouTube receives and includes
     * HttpOnly cookies. The full-store fallback is only for builds that reject URL-scoped reads;
     * [sessionFromYouTubeCookies] re-applies the Music domain/path filter either way.
     */
    private fun readBrowserCookies(pageClient: CdpClient): JSONArray =
        runCatching {
            pageClient
                .call(
                    "Network.getCookies",
                    JSONObject().put("urls", JSONArray().put(MANAGED_MUSIC_URL)),
                ).getJSONArray("cookies")
        }.recoverCatching {
            pageClient.call("Storage.getCookies").getJSONArray("cookies")
        }.getOrThrow()

    /**
     * Reads the same live `ytcfg` values Android's login WebView captures. Music may expose
     * VISITOR_DATA before DATASYNC_ID appears, so poll briefly; both are optional and cookie
     * validation still decides whether the session is accepted.
     */
    private suspend fun readYouTubeSessionContext(pageClient: CdpClient): YouTubeBrowserContext? {
        val deadlineNanos = System.nanoTime() + SESSION_CONTEXT_TIMEOUT.toNanos()
        while (System.nanoTime() < deadlineNanos) {
            val context =
                runCatching {
                    val value =
                        pageClient
                            .call(
                                "Runtime.evaluate",
                                JSONObject()
                                    .put("expression", YOUTUBE_SESSION_CONTEXT_SCRIPT)
                                    .put("returnByValue", true),
                            ).optJSONObject("result")
                            ?.opt("value")
                    browserContextFromEvaluationValue(value)
                }.getOrNull()
            if (context != null) return context
            delay(SESSION_CONTEXT_POLL_MILLIS)
        }
        return null
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
        val root = process?.toHandle()
        if (isLinuxOperatingSystem()) {
            // Snapshot descendants while the root is still alive; Chromium helpers may be
            // reparented to init once the browser exits.
            val descendants =
                root
                    ?.let { handle -> runCatching { handle.descendants().use { it.toList() } }.getOrDefault(emptyList()) }
                    .orEmpty()
            if (root != null) {
                // Linux has no cheap "close main window" equivalent. SIGTERM to the browser root
                // asks Chromium's own shutdown path to run, which flushes the cookie store and
                // closes renderer/network/storage helpers in order. Terminating every helper at
                // once can lose a cookie rotation that happened seconds before the user confirmed
                // sign-in, which then surfaces as "Google could not validate that session".
                runCatching { root.destroy() }
                awaitProcessExit(listOf(root), GRACEFUL_BROWSER_EXIT, PROCESS_EXIT_POLL_MILLIS)
            }

            val remaining =
                (descendants + listOfNotNull(root) + managedProfileProcesses())
                    .filter(ProcessHandle::isAlive)
                    .distinctBy(ProcessHandle::pid)
            terminateManagedProcesses(remaining, PROCESS_EXIT_GRACE, PROCESS_EXIT_POLL_MILLIS)
            // Helpers can be reparented while the root is shutting down, so rescan the profile
            // argument rather than trusting only the original process tree.
            terminateManagedProcesses(
                managedProfileProcesses().filter(ProcessHandle::isAlive),
                PROCESS_EXIT_GRACE,
                PROCESS_EXIT_POLL_MILLIS,
            )
            awaitProcessExit(managedProfileProcesses(), PROCESS_EXIT_GRACE, PROCESS_EXIT_POLL_MILLIS)
            if (managedProfileProcesses().none(ProcessHandle::isAlive)) {
                clearStaleManagedProfileLockFiles(profile)
            }
            return
        }

        val closeRequested = mutableSetOf<Long>()
        val gracefulDeadlineNanos = System.nanoTime() + GRACEFUL_BROWSER_EXIT.toNanos()
        while (System.nanoTime() < gracefulDeadlineNanos) {
            val browserHandles =
                (listOfNotNull(root) + managedProfileBrowserProcesses())
                    .filter(ProcessHandle::isAlive)
                    .distinctBy(ProcessHandle::pid)
            if (browserHandles.isEmpty()) break
            val newlyDiscovered = browserHandles.filter { closeRequested.add(it.pid()) }
            if (newlyDiscovered.isNotEmpty()) closeManagedBrowserWindows(newlyDiscovered)
            Thread.sleep(PROCESS_EXIT_POLL_MILLIS)
        }

        val handles =
            (
                (root?.let(::ownedManagedProcesses) ?: emptyList()) + managedProfileProcesses()
            ).filter(ProcessHandle::isAlive)
                .distinctBy(ProcessHandle::pid)
        terminateManagedProcesses(handles, PROCESS_EXIT_GRACE, PROCESS_EXIT_POLL_MILLIS)
        if (managedProfileProcesses().none(ProcessHandle::isAlive)) {
            clearStaleManagedProfileLockFiles(profile)
        }
    }

    private fun stopOrphanedManagedBrowserProcesses() {
        if (managedProfileProcesses().none(ProcessHandle::isAlive)) {
            clearStaleManagedProfileLockFiles(profile)
            return
        }
        stopManagedBrowser(null)
        if (managedProfileProcesses().any(ProcessHandle::isAlive)) {
            throw ManagedBrowserFailure(
                ManagedBrowserFailureCategory.PROFILE_LOCKED,
                "The ArchiveTune browser profile is still in use by another process",
            )
        }
    }

    private fun confirmManagedProfileReleased() {
        if (managedProfileProcesses().any(ProcessHandle::isAlive)) {
            throw ManagedBrowserFailure(
                ManagedBrowserFailureCategory.BROWSER_EXITED,
                "The ArchiveTune sign-in browser did not exit before cookie extraction",
            )
        }
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

    private fun managedProfileProcesses(browserRootsOnly: Boolean = false): List<ProcessHandle> =
        findManagedBrowserProcesses(profile, browserRootsOnly)

    private fun managedProfileBrowserProcesses(): List<ProcessHandle> =
        findManagedBrowserProcesses(profile, browserRootsOnly = true)

    /**
     * Fails with the fixed PROFILE_LOCKED category while another ArchiveTune process (or another
     * source instance in this process) owns the profile lease. Callers must acquire this before
     * scanning for orphans or touching the browser/profile.
     */
    private fun requireManagedProfileLease(): ManagedProfileLease =
        acquireManagedProfileLease(profile)
            ?: throw ManagedBrowserFailure(ManagedBrowserFailureCategory.PROFILE_LOCKED)

    private suspend fun waitForDevTools(profile: Path, browserProcess: Process?): ManagedDevToolsEndpoint =
        awaitManagedDevToolsEndpoint(
            profile = profile,
            timeout = startupTimeout,
            pollDelayMillis = 100L,
            isBrowserRunning = { browserProcess?.isAlive != false },
        )

    private suspend fun waitForPageTarget(port: Int, browserProcess: Process?): ManagedPageTarget =
        awaitManagedPageTarget(
            port = port,
            timeout = startupTimeout,
            pollDelayMillis = pollDelayMillis,
            isBrowserRunning = { browserProcess?.isAlive != false },
        )

    private fun requireGraphicalLoginSession() {
        if (!isLinuxOperatingSystem()) return
        val hasX11 = !System.getenv("DISPLAY").isNullOrBlank()
        val hasWayland = !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
        if (!hasX11 && !hasWayland) {
            throw ManagedBrowserFailure(ManagedBrowserFailureCategory.DISPLAY_UNAVAILABLE)
        }
    }

    private fun startManagedBrowser(command: List<String>): Process =
        try {
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                // Never persist Chromium stderr: it can contain sign-in URLs and account data.
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .also { process -> process.outputStream.close() }
        } catch (failure: IOException) {
            throw ManagedBrowserFailure(classifyManagedBrowserFailure(failure.message), cause = failure)
        } catch (failure: SecurityException) {
            throw ManagedBrowserFailure(ManagedBrowserFailureCategory.PERMISSION_DENIED, cause = failure)
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
                ?: throw ManagedBrowserFailure(
                    ManagedBrowserFailureCategory.BROWSER_NOT_FOUND,
                    "The Chromium browser used for this YouTube session is no longer available",
                )
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

    private companion object {
        private const val PROFILE_DELETE_ATTEMPTS = 20
        private const val PROFILE_DELETE_RETRY_MILLIS = 100L
        private const val PROFILE_SESSION_MARKER = ".archivetune-session-fingerprints"
        private const val PROFILE_BROWSER_MARKER = ".archivetune-browser-path"
        private const val PROFILE_RELEASE_DELAY_MILLIS = 250L
        private const val SESSION_SETTLE_DELAY_MILLIS = 1_500L
        private const val SESSION_COOKIE_POLL_MILLIS = 250L
        private const val SESSION_CONTEXT_POLL_MILLIS = 200L
        private const val PROCESS_EXIT_POLL_MILLIS = 50L
        private const val POWERSHELL_CLOSE_TIMEOUT_SECONDS = 5L
        private val GRACEFUL_BROWSER_EXIT = Duration.ofSeconds(10)
        private val PROCESS_EXIT_GRACE = Duration.ofSeconds(2)
        private val LINUX_WINDOW_CLOSE_TIMEOUT = Duration.ofSeconds(30)
        private val LINUX_HELPER_EXIT_GRACE = Duration.ofSeconds(10)
        private val PAGE_LOAD_TIMEOUT = Duration.ofSeconds(10)
        private val SESSION_COOKIE_TIMEOUT = Duration.ofSeconds(10)
        private val SESSION_CONTEXT_TIMEOUT = Duration.ofSeconds(5)
    }
}

internal data class ManagedDevToolsEndpoint(
    val port: Int,
    val browserWebSocketUrl: String,
)

internal data class ManagedPageTarget(
    val webSocketDebuggerUrl: String,
)

/** Optional live page context captured the same way Android's login WebView captures it. */
internal data class YouTubeBrowserContext(
    val visitorData: String?,
    val dataSyncId: String?,
)

internal fun browserContextFromEvaluationValue(value: Any?): YouTubeBrowserContext? {
    val payload =
        when (value) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: return null
    val visitorData = payload.optionalText("visitorData")
    val dataSyncId = payload.optionalText("dataSyncId")
    if (visitorData == null && dataSyncId == null) return null
    return YouTubeBrowserContext(visitorData = visitorData, dataSyncId = dataSyncId)
}

private fun JSONObject.optionalText(key: String): String? {
    val raw = opt(key) ?: return null
    if (raw == JSONObject.NULL) return null
    return raw.toString().trim().takeIf(String::isNotEmpty)
}

internal fun YouTubeSessionImport.withManagedBrowserContext(context: YouTubeBrowserContext?): YouTubeSessionImport =
    if (context == null) {
        this
    } else {
        copy(
            visitorData = context.visitorData ?: visitorData,
            dataSyncId = context.dataSyncId ?: dataSyncId,
        )
    }

/**
 * Parses Chromium's `DevToolsActivePort` file: the first line is the loopback port, the second the
 * browser WebSocket path. Partial files are ignored so the caller keeps polling instead of
 * connecting to a half-written endpoint.
 */
internal fun readManagedDevToolsEndpoint(profile: Path): ManagedDevToolsEndpoint? {
    val activePort = profile.resolve("DevToolsActivePort")
    if (!Files.isRegularFile(activePort)) return null
    val lines = runCatching { Files.readAllLines(activePort) }.getOrNull() ?: return null
    val port = lines.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
    val browserPath = lines.getOrNull(1)?.trim()?.takeIf { it.startsWith('/') } ?: return null
    return ManagedDevToolsEndpoint(port, "ws://127.0.0.1:$port$browserPath")
}

internal suspend fun awaitManagedDevToolsEndpoint(
    profile: Path,
    timeout: Duration,
    pollDelayMillis: Long = 100L,
    isBrowserRunning: () -> Boolean = { true },
): ManagedDevToolsEndpoint {
    val deadlineNanos = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadlineNanos) {
        readManagedDevToolsEndpoint(profile)?.let { return it }
        if (!isBrowserRunning()) {
            throw ManagedBrowserFailure(
                ManagedBrowserFailureCategory.DEVTOOLS_UNAVAILABLE,
                "The ArchiveTune cookie reader exited before DevTools became available",
            )
        }
        delay(pollDelayMillis.coerceAtLeast(1L))
    }
    throw ManagedBrowserFailure(ManagedBrowserFailureCategory.DEVTOOLS_UNAVAILABLE)
}

internal suspend fun awaitManagedPageTarget(
    port: Int,
    timeout: Duration,
    pollDelayMillis: Long,
    findTarget: (Int) -> ManagedPageTarget? = ::findManagedPageTarget,
    isBrowserRunning: () -> Boolean = { true },
): ManagedPageTarget {
    val deadlineNanos = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadlineNanos) {
        findTarget(port)?.let { return it }
        if (!isBrowserRunning()) {
            throw ManagedBrowserFailure(
                ManagedBrowserFailureCategory.DEVTOOLS_UNAVAILABLE,
                "The ArchiveTune cookie reader exited before its page became available",
            )
        }
        delay(pollDelayMillis.coerceAtLeast(1L))
    }
    throw ManagedBrowserFailure(ManagedBrowserFailureCategory.DEVTOOLS_UNAVAILABLE)
}

/**
 * Queries the loopback DevTools HTTP endpoint. A connection-refused while Chromium is still
 * starting must be treated as "not ready yet"; letting it escape aborts extraction even though the
 * port file already appeared.
 */
internal fun findManagedPageTarget(port: Int): ManagedPageTarget? =
    runCatching {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/json/list"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
        val response = MANAGED_HTTP.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        val targets = JSONArray(response.body())
        (0 until targets.length())
            .asSequence()
            .mapNotNull { targets.optJSONObject(it) }
            .filter { it.optString("type") == "page" }
            .firstNotNullOfOrNull { target ->
                target.optString("webSocketDebuggerUrl")
                    .takeIf(String::isNotBlank)
                    ?.let { ManagedPageTarget(it) }
            }
    }.getOrNull()

/**
 * True when a process command line points at exactly this managed profile. Chromium passes
 * `--user-data-dir` to its root and helper processes; matching normalized and real paths avoids
 * both symlinked launch paths and accidental prefix matches such as `/profile-copy`.
 */
internal fun usesManagedProfile(arguments: Array<String>, profile: Path): Boolean =
    usesManagedProfile(arguments, managedProfilePaths(profile))

private fun managedProfilePaths(profile: Path): Set<Path> =
    buildSet {
        add(profile.toAbsolutePath().normalize())
        runCatching { profile.toRealPath() }.getOrNull()?.let(::add)
    }

private fun usesManagedProfile(arguments: Array<String>, expected: Set<Path>): Boolean {
    for (index in arguments.indices) {
        val argument = arguments[index]
        val value =
            when {
                argument.startsWith("--user-data-dir=", ignoreCase = true) -> argument.substringAfter('=')
                argument.equals("--user-data-dir", ignoreCase = true) -> arguments.getOrNull(index + 1)
                else -> null
            } ?: continue
        if (value.isBlank()) continue
        val candidate = runCatching { Paths.get(value).toAbsolutePath().normalize() }.getOrNull() ?: continue
        if (candidate in expected) return true
        val realCandidate = runCatching { candidate.toRealPath() }.getOrNull()
        if (realCandidate != null && realCandidate in expected) return true
    }
    return false
}

internal fun findManagedBrowserProcesses(
    profile: Path,
    browserRootsOnly: Boolean = false,
): List<ProcessHandle> {
    val expected = managedProfilePaths(profile)
    return ProcessHandle.allProcesses().use { processes ->
        processes
            .filter(ProcessHandle::isAlive)
            .filter { process ->
                val arguments = process.info().arguments().orElse(emptyArray())
                usesManagedProfile(arguments, expected) && (!browserRootsOnly || isChromiumBrowserRoot(arguments))
            }.toList()
    }
}

/**
 * Sends a graceful termination request, waits for Chromium to flush and exit, then escalates only
 * for processes that are still alive. Returns whether every target is gone.
 */
internal fun terminateManagedProcesses(
    handles: Collection<ProcessHandle>,
    gracefulTimeout: Duration,
    pollIntervalMillis: Long = 25L,
): Boolean {
    val targets = handles.filter(ProcessHandle::isAlive).distinctBy(ProcessHandle::pid)
    targets.forEach { handle -> runCatching { handle.destroy() } }
    if (awaitProcessExit(targets, gracefulTimeout, pollIntervalMillis)) return true
    targets.filter(ProcessHandle::isAlive).forEach { handle -> runCatching { handle.destroyForcibly() } }
    return awaitProcessExit(targets, Duration.ofSeconds(2), pollIntervalMillis)
}

internal fun awaitProcessExit(
    handles: Collection<ProcessHandle>,
    timeout: Duration,
    pollIntervalMillis: Long = 25L,
): Boolean {
    val deadlineNanos = System.nanoTime() + timeout.toNanos().coerceAtLeast(0L)
    while (true) {
        if (handles.none(ProcessHandle::isAlive)) return true
        if (System.nanoTime() >= deadlineNanos) return false
        Thread.sleep(pollIntervalMillis.coerceAtLeast(1L))
    }
}

/**
 * Removes lock files left by a killed Chromium. Only call this after confirming that no process is
 * still using the profile: deleting a live process's lock would let a second browser open the same
 * cookie store.
 */
internal fun clearStaleManagedProfileLockFiles(profile: Path) {
    MANAGED_PROFILE_LOCK_FILES.forEach { name ->
        runCatching { Files.deleteIfExists(profile.resolve(name)) }
    }
}

/**
 * Location of the cross-instance lease. It must stay *outside* the profile directory: clearSession
 * deletes that directory while holding the lease, and unlinking a held lock on Linux would let a
 * second instance create a fresh inode and acquire a second "exclusive" lock.
 *
 * The identity is canonicalized through the filesystem so two spellings of the same profile (for
 * example a symlinked data directory) resolve to one lock file. When the profile directory has just
 * been deleted, the deepest existing ancestor is canonicalized and the remaining components are
 * appended, so the lease keeps the same identity across delete/recreate.
 */
internal fun managedProfileLeasePath(profile: Path): Path {
    val canonical = canonicalizeManagedProfilePath(profile.toAbsolutePath().normalize())
    val name =
        requireNotNull(canonical.fileName?.toString()?.takeIf(String::isNotBlank)) {
            "Managed browser profile has no directory name"
        }
    val parent = canonical.parent ?: Paths.get(".").toAbsolutePath().normalize()
    return parent.resolve(".$name.archivetune-lease")
}

private fun canonicalizeManagedProfilePath(path: Path): Path {
    var existing: Path? = path
    val missing = ArrayDeque<Path>()
    while (existing != null) {
        if (Files.exists(existing)) break
        existing.fileName?.let { missing.addFirst(it) }
        existing = existing.parent
    }
    val base = existing ?: return path
    var resolved = runCatching { base.toRealPath() }.getOrElse { base }
    missing.forEach { component -> resolved = resolved.resolve(component) }
    return resolved
}

/**
 * Takes the cross-instance exclusive lease for [profile]. Returns null when another process or
 * source instance already owns it; OS/security failures surface as fixed redacted categories.
 *
 * The lease file is intentionally never deleted, including on release: removing the inode while
 * another instance might be about to open it would split the lock into two unrelated files.
 * [java.nio.channels.FileLock] is released automatically if the process dies.
 */
internal fun acquireManagedProfileLease(profile: Path): ManagedProfileLease? {
    val path = managedProfileLeasePath(profile)
    try {
        path.parent?.let(Files::createDirectories)
        val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            // Same-JVM channels throw instead of returning null for an overlapping lock, while a
            // lock held by another process makes tryLock return null. Both mean "contended".
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (lock == null) {
                channel.close()
                return null
            }
            if (isLinuxOperatingSystem()) {
                runCatching {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
                }
            }
            return ManagedProfileLease(channel = channel, lock = lock, path = path)
        } catch (failure: Throwable) {
            runCatching { channel.close() }
            throw failure
        }
    } catch (failure: IOException) {
        throw ManagedBrowserFailure(classifyManagedBrowserFailure(failure.message), cause = failure)
    } catch (failure: SecurityException) {
        throw ManagedBrowserFailure(ManagedBrowserFailureCategory.PERMISSION_DENIED, cause = failure)
    }
}

/** Exclusive profile lease; closing it never deletes the lock file inode. */
internal class ManagedProfileLease internal constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    val path: Path,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }
}

internal enum class ManagedBrowserFailureCategory {
    DISPLAY_UNAVAILABLE,
    PROFILE_LOCKED,
    PERMISSION_DENIED,
    BROWSER_EXITED,
    BROWSER_NOT_FOUND,
    TIMEOUT,
    SESSION_NOT_FOUND,
    DEVTOOLS_UNAVAILABLE,
    UNKNOWN,
}

/**
 * Failure shown to the UI. The message is a fixed category text; raw browser stderr is never
 * captured or appended because it can contain sign-in URLs, cookies, or account identifiers.
 */
internal class ManagedBrowserFailure(
    val category: ManagedBrowserFailureCategory,
    message: String = managedBrowserFailureMessage(category),
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun managedBrowserFailureMessage(category: ManagedBrowserFailureCategory): String =
    when (category) {
        ManagedBrowserFailureCategory.DISPLAY_UNAVAILABLE ->
            "The sign-in browser could not open a graphical display"
        ManagedBrowserFailureCategory.PROFILE_LOCKED ->
            "The ArchiveTune browser profile is still in use by another process"
        ManagedBrowserFailureCategory.PERMISSION_DENIED ->
            "The browser could not access the ArchiveTune profile"
        ManagedBrowserFailureCategory.BROWSER_EXITED ->
            "The ArchiveTune sign-in browser exited before sign-in completed"
        ManagedBrowserFailureCategory.BROWSER_NOT_FOUND ->
            "No supported Chromium browser was found"
        ManagedBrowserFailureCategory.TIMEOUT ->
            "Timed out waiting for YouTube Music sign-in"
        ManagedBrowserFailureCategory.SESSION_NOT_FOUND ->
            "The managed browser no longer has a usable YouTube Music session"
        ManagedBrowserFailureCategory.DEVTOOLS_UNAVAILABLE ->
            "The ArchiveTune cookie reader could not start"
        ManagedBrowserFailureCategory.UNKNOWN ->
            "The managed browser sign-in failed"
    }

/**
 * Maps a diagnostic string to a fixed category. Only the category is returned to the UI, so a
 * synthetic secret or URL in the input must never appear in the resulting failure message.
 */
internal fun classifyManagedBrowserFailure(detail: String?): ManagedBrowserFailureCategory {
    val text = detail?.lowercase().orEmpty()
    return when {
        text.contains("cannot open display") ||
            text.contains("can't open display") ||
            text.contains("missing x server") ||
            text.contains("x display") ||
            text.contains("wayland") ||
            text.contains("ozone") -> ManagedBrowserFailureCategory.DISPLAY_UNAVAILABLE
        text.contains("singleton") ||
            text.contains("already in use") ||
            (text.contains("profile") && text.contains("locked")) -> ManagedBrowserFailureCategory.PROFILE_LOCKED
        text.contains("permission") ||
            text.contains("access denied") ||
            text.contains("operation not permitted") ||
            text.contains("read-only file system") -> ManagedBrowserFailureCategory.PERMISSION_DENIED
        text.contains("no such file") ||
            text.contains("not found") -> ManagedBrowserFailureCategory.BROWSER_NOT_FOUND
        text.contains("exited") ||
            text.contains("closed") ||
            text.contains("killed") ||
            text.contains("terminated") -> ManagedBrowserFailureCategory.BROWSER_EXITED
        else -> ManagedBrowserFailureCategory.UNKNOWN
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
    completeOnBrowserExit: Boolean = false,
    completionExitTimeout: Duration = Duration.ZERO,
) {
    val deadlineNanos = System.nanoTime() + loginTimeout.toNanos()
    val completionExitNanos = completionExitTimeout.toNanos().coerceAtLeast(0L)
    val browserCloseGraceNanos = browserCloseGrace.toNanos().coerceAtLeast(0L)
    var browserMissingSinceNanos: Long? = null
    var completionObservedSinceNanos: Long? = null
    while (true) {
        val now = System.nanoTime()
        val completionDone = completion.isCompleted
        // Default (Windows and legacy callers): "Done signing in" finalizes immediately. Linux
        // passes completeOnBrowserExit with a positive completionExitTimeout so the click only
        // *requests* completion and the wait continues until Chromium exits through its normal
        // window-close path and flushes cookies.
        if (completionDone && (!completeOnBrowserExit || completionExitNanos == 0L)) return
        if (isBrowserRunning()) {
            browserMissingSinceNanos = null
        } else {
            val missingSince = browserMissingSinceNanos ?: now.also { browserMissingSinceNanos = it }
            val missingForNanos = now - missingSince
            if (completeOnBrowserExit) {
                // A Linux wrapper/launcher may exit before its detached browser root becomes
                // visible. Keep the same missing-duration grace used for early-close detection so
                // that a briefly absent then running browser cannot finalize the import.
                if (missingForNanos >= browserCloseGraceNanos) return
            } else {
                // completeLogin() may race with the browser's final process exit. Give an already-won
                // completion signal precedence rather than reporting that successful confirmation as a close.
                if (completionDone) return
                if (missingForNanos >= browserCloseGraceNanos) {
                    throw ManagedBrowserFailure(
                        ManagedBrowserFailureCategory.BROWSER_EXITED,
                        "The ArchiveTune sign-in browser was closed before sign-in was completed",
                    )
                }
            }
        }
        if (completionDone) {
            val observed = completionObservedSinceNanos ?: now.also { completionObservedSinceNanos = it }
            if (now - observed >= completionExitNanos) {
                throw ManagedBrowserFailure(
                    ManagedBrowserFailureCategory.TIMEOUT,
                    "Close the ArchiveTune sign-in browser window to finish sign-in",
                )
            }
        }
        if (now >= deadlineNanos) {
            throw ManagedBrowserFailure(ManagedBrowserFailureCategory.TIMEOUT)
        }
        delay(pollDelayMillis.coerceAtLeast(1L))
    }
}

internal fun managedLoginBrowserCommand(browser: Path, profile: Path): List<String> =
    buildList {
        add(browser.toString())
        add("--user-data-dir=$profile")
        add("--profile-directory=Default")
        add("--disable-sync")
        if (isLinuxOperatingSystem()) add("--password-store=gnome-libsecret")
        add("--disable-background-mode")
        add("--no-first-run")
        add("--no-default-browser-check")
        add(MANAGED_LOGIN_URL)
    }

internal fun managedExtractionBrowserCommand(browser: Path, profile: Path): List<String> =
    buildList {
        add(browser.toString())
        add("--user-data-dir=$profile")
        add("--profile-directory=Default")
        add("--disable-sync")
        if (isLinuxOperatingSystem()) add("--password-store=gnome-libsecret")
        add("--headless=new")
        add("--remote-debugging-port=0")
        add("--remote-debugging-address=127.0.0.1")
        add("--disable-background-mode")
        add("--no-first-run")
        add("--no-default-browser-check")
        add("about:blank")
    }

internal fun sessionFromYouTubeCookies(cookies: JSONArray): YouTubeSessionImport? {
    val merged = linkedMapOf<String, BrowserCookie>()
    for (index in 0 until cookies.length()) {
        val raw = cookies.optJSONObject(index) ?: continue
        // A partitioned cookie is only sent inside its partition's embedded context. It must not
        // replace the top-level Music cookie of the same name.
        if (raw.opt("partitionKey") != null && raw.opt("partitionKey") != JSONObject.NULL) continue
        val name = raw.optString("name").trim()
        val value = raw.optString("value")
        val domain = raw.optString("domain").trim().lowercase()
        val path = raw.optString("path", "/").ifBlank { "/" }
        if (name.isBlank() || value.isBlank() || !name.matches(COOKIE_NAME)) continue
        if (!value.isSafeCookieValue()) continue
        // Mirror the Cookie header a browser sends to https://music.youtube.com/:
        //  * `.youtube.com` domain cookies apply, host-only `music.youtube.com` applies,
        //  * host-only `www.youtube.com`/`youtube.com` cookies do not,
        //  * only cookies whose path is a prefix of the request path `/` are applicable.
        if (!domain.appliesToYouTubeMusicRoot()) continue
        if (path != "/") continue
        val expiresSeconds = raw.optDouble("expires", -1.0)
        val expiresAtMillis =
            expiresSeconds
                .takeIf { it.isFinite() && it > 0.0 }
                ?.let { (it * 1_000.0).toLong() }
        val candidate = BrowserCookie(name, value, domain, path, expiresAtMillis, index)
        val previous = merged[name]
        if (previous == null || COOKIE_SPECIFICITY.compare(candidate, previous) > 0) {
            merged[name] = candidate
        }
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
    if (isLinuxOperatingSystem()) return findLinuxChromiumBrowser()
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

/** Linux browser launchers are found through PATH; Brave is the preferred managed browser. */
internal fun findLinuxChromiumBrowser(
    path: String = System.getenv("PATH").orEmpty(),
): Path? {
    val searchDirs = path.split(':').filter(String::isNotBlank).map(Paths::get)
    return LINUX_CHROMIUM_EXECUTABLES.asSequence()
        .flatMap { name -> searchDirs.asSequence().map { it.resolve(name) } }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}

internal fun isWindowsOperatingSystem(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

internal fun isLinuxOperatingSystem(): Boolean =
    System.getProperty("os.name").startsWith("Linux", ignoreCase = true)

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
    if (!isVivaldiExecutable(browser)) return

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
    val domain: String,
    val path: String,
    val expiresAtMillis: Long?,
    val sourceIndex: Int,
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

private fun String.appliesToYouTubeMusicRoot(): Boolean {
    val bare = removePrefix(".")
    if (bare != "youtube.com" && bare != "music.youtube.com") return false
    // A host-only youtube.com cookie (no leading dot) is never sent to music.youtube.com.
    return bare == "music.youtube.com" || startsWith(".")
}

internal fun isVivaldiExecutable(browser: Path): Boolean =
    browser.fileName?.toString()?.lowercase()?.startsWith("vivaldi") == true

private val YOUTUBE_SESSION_CONTEXT_SCRIPT =
    """
    (() => {
      try {
        const cfg = globalThis.ytcfg;
        if (!cfg) return null;
        const read = (key) => {
          if (typeof cfg.get === "function") {
            const value = cfg.get(key);
            if (value !== undefined && value !== null && String(value).length) return String(value);
          }
          const data = cfg.data_ || {};
          const value = data[key];
          return value === undefined || value === null ? null : String(value);
        };
        return { visitorData: read("VISITOR_DATA"), dataSyncId: read("DATASYNC_ID") };
      } catch (_) {
        return null;
      }
    })()
    """.trimIndent()

private fun String.isSafeCookieValue(): Boolean =
    isNotEmpty() && all { character -> character.code in 0x21..0x7E && character != ';' && character != ',' }

private val COOKIE_SPECIFICITY =
    compareBy<BrowserCookie> { it.domain.length }
        .thenBy { it.path.length }
        .thenBy { it.expiresAtMillis ?: Long.MAX_VALUE }
        .thenBy { it.sourceIndex }
private val COOKIE_NAME = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")
private val LOGIN_COOKIE_NAMES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID", "APISID")
private val MANAGED_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
private val MANAGED_PROFILE_LOCK_FILES =
    listOf("SingletonLock", "SingletonSocket", "SingletonCookie", "DevToolsActivePort")
private val SUPPORTED_CHROMIUM_EXECUTABLES =
    setOf("chrome.exe", "msedge.exe", "brave.exe", "vivaldi.exe", "chromium.exe", "opera.exe", "opera_gx.exe")
private val LINUX_CHROMIUM_EXECUTABLES =
    listOf("brave", "brave-browser", "brave-browser-stable", "chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "vivaldi", "vivaldi-stable", "microsoft-edge")
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
