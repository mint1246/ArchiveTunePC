package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class ManagedChromiumLoginTest {
    @Test
    fun `cdp cookie extraction keeps only YouTube applicable cookies`() {
        val cookies =
            JSONArray(
                """[
                  {"name":"SAPISID","value":"google-value","domain":".google.com","path":"/","expires":1900000000},
                  {"name":"SAPISID","value":"youtube-value","domain":".youtube.com","path":"/","expires":1900000000},
                  {"name":"PREF","value":"pref","domain":"music.youtube.com","path":"/","expires":1900000100},
                  {"name":"OTHER","value":"blocked","domain":"example.com","path":"/","expires":1900000200}
                ]""",
            )

        val session = sessionFromYouTubeCookies(cookies)
        assertNotNull(session)

        assertEquals("SAPISID=youtube-value; PREF=pref", session!!.cookieHeader)
        assertEquals(1_900_000_000_000L, session.expiresAtEpochMillis)
        assertFalse(session.cookieHeader.contains("google-value"))
        assertFalse(session.cookieHeader.contains("blocked"))
    }

    @Test
    fun `cdp cookie extraction ignores sibling host-only and non-root cookies`() {
        val cookies =
            JSONArray(
                """[
                  {"name":"SAPISID","value":"sibling-host-only","domain":"www.youtube.com","path":"/","expires":1900000000},
                  {"name":"SAPISID","value":"youtube-root-only","domain":"youtube.com","path":"/","expires":1900000000},
                  {"name":"SAPISID","value":"root-domain","domain":".youtube.com","path":"/","expires":1900000000},
                  {"name":"PREF","value":"nested-path","domain":".youtube.com","path":"/music","expires":1900000100},
                  {"name":"PREF","value":"root-path","domain":".youtube.com","path":"/","expires":1900000100},
                  {"name":"__Secure-3PSIDTS","value":"partitioned","domain":".youtube.com","path":"/","expires":1900000100,"partitionKey":{"topLevelSite":"https://example.com"}}
                ]""",
            )

        val session = sessionFromYouTubeCookies(cookies)

        assertNotNull(session)
        assertEquals("SAPISID=root-domain; PREF=root-path", session!!.cookieHeader)
        assertFalse(session.cookieHeader.contains("sibling-host-only"))
        assertFalse(session.cookieHeader.contains("youtube-root-only"))
        assertFalse(session.cookieHeader.contains("partitioned"))
    }

    @Test
    fun `cdp cookie extraction prefers the cookie sent to the music host`() {
        val cookies =
            JSONArray(
                """[
                  {"name":"SAPISID","value":"login","domain":".youtube.com","path":"/","expires":1900000000},
                  {"name":"PREF","value":"generic","domain":".youtube.com","path":"/","expires":1900000000},
                  {"name":"PREF","value":"music-host","domain":"music.youtube.com","path":"/","expires":1900000000}
                ]""",
            )

        val session = sessionFromYouTubeCookies(cookies)

        assertNotNull(session)
        assertEquals("SAPISID=login; PREF=music-host", session!!.cookieHeader)
        assertFalse(session.cookieHeader.contains("PREF=generic"))
    }

    @Test
    fun `live ytcfg values are preserved without replacing cookie material`() {
        val context =
            browserContextFromEvaluationValue(
                JSONObject()
                    .put("visitorData", "visitor-value")
                    .put("dataSyncId", "channel-id||account-id"),
            )

        assertNotNull(context)
        val session =
            YouTubeSessionImport(cookieHeader = "SAPISID=cookie-value")
                .withManagedBrowserContext(context)

        assertEquals("visitor-value", session.visitorData)
        assertEquals("channel-id||account-id", session.dataSyncId)
        assertEquals("SAPISID=cookie-value", session.cookieHeader)
        assertEquals("visitor-value", session.copy(visitorData = "existing").withManagedBrowserContext(context).visitorData)
    }

    @Test
    fun `blank ytcfg context is ignored`() {
        assertNull(browserContextFromEvaluationValue(JSONObject().put("visitorData", " ").put("dataSyncId", "")))
        assertNull(
            browserContextFromEvaluationValue(
                JSONObject().put("visitorData", JSONObject.NULL).put("dataSyncId", JSONObject.NULL),
            ),
        )
        assertNull(browserContextFromEvaluationValue(null))
    }

    @Test
    fun `browser failure diagnostics never echo raw stderr secrets or urls`() {
        val raw = "SAPISID=synthetic-secret https://accounts.google.com/signin profile is locked"

        val category = classifyManagedBrowserFailure(raw)
        val message = managedBrowserFailureMessage(category)

        assertEquals(ManagedBrowserFailureCategory.PROFILE_LOCKED, category)
        assertFalse(message.contains("SAPISID"))
        assertFalse(message.contains("synthetic-secret"))
        assertFalse(message.contains("https://"))
        assertFalse(ManagedBrowserFailure(category).message.orEmpty().contains("synthetic-secret"))
    }

    @Test
    fun `browser failure categories are fixed for display profile permission and exit`() {
        assertEquals(
            ManagedBrowserFailureCategory.DISPLAY_UNAVAILABLE,
            classifyManagedBrowserFailure("cannot open display :0"),
        )
        assertEquals(
            ManagedBrowserFailureCategory.PROFILE_LOCKED,
            classifyManagedBrowserFailure("Failed to create a ProcessSingleton for your profile"),
        )
        assertEquals(
            ManagedBrowserFailureCategory.PERMISSION_DENIED,
            classifyManagedBrowserFailure("Permission denied"),
        )
        assertEquals(
            ManagedBrowserFailureCategory.BROWSER_EXITED,
            classifyManagedBrowserFailure("browser exited"),
        )
        assertEquals(
            ManagedBrowserFailureCategory.UNKNOWN,
            classifyManagedBrowserFailure("SAPISID=synthetic-secret https://accounts.google.com"),
        )
    }

    @Test
    fun `login browser disables sync without remote debugging`() {
        val browser = Paths.get("C:\\Browser\\msedge.exe")
        val profile = Paths.get("C:\\Temp\\archivetune-login-test")
        val command = managedLoginBrowserCommand(browser, profile)

        assertEquals(browser.toString(), command.first())
        assertTrue(command.contains("--user-data-dir=$profile"))
        assertTrue(command.contains("--disable-sync"))
        assertFalse(command.contains("--disable-features=EnableBoundSessionCredentials"))
        assertFalse(command.any { it.startsWith("--remote-debugging") })
        assertFalse(command.any { it.contains("Microsoft\\Edge\\User Data") })
    }

    @Test
    fun `cookie reader reuses isolated profile with local debugging only after login`() {
        val browser = Paths.get("C:\\Browser\\msedge.exe")
        val profile = Paths.get("C:\\Temp\\archivetune-login-test")
        val command = managedExtractionBrowserCommand(browser, profile)

        assertEquals(browser.toString(), command.first())
        assertTrue(command.contains("--user-data-dir=$profile"))
        assertTrue(command.contains("--disable-sync"))
        assertTrue(command.contains("--headless=new"))
        assertTrue(command.contains("--remote-debugging-port=0"))
        assertTrue(command.contains("--remote-debugging-address=127.0.0.1"))
        assertFalse(command.contains("--disable-features=EnableBoundSessionCredentials"))
        assertEquals("about:blank", command.last())
    }

    @Test
    fun `linux prefers installed Brave and uses an isolated managed profile`() {
        assumeTrue(isLinuxOperatingSystem())
        val directory = Files.createTempDirectory("archivetune-linux-chromium")
        try {
            val chrome = Files.createFile(directory.resolve("chromium"))
            val brave = Files.createFile(directory.resolve("brave"))
            chrome.toFile().setExecutable(true)
            brave.toFile().setExecutable(true)
            assertEquals(brave, findLinuxChromiumBrowser(directory.toString()))

            val profile = directory.resolve("managed-profile")
            val login = managedLoginBrowserCommand(brave, profile)
            val extraction = managedExtractionBrowserCommand(brave, profile)
            assertTrue(login.contains("--user-data-dir=$profile"))
            assertFalse(login.any { it.startsWith("--remote-debugging") })
            assertTrue(extraction.contains("--remote-debugging-port=0"))
            assertTrue(extraction.contains("--remote-debugging-address=127.0.0.1"))
            assertTrue(login.contains("--password-store=gnome-libsecret"))
            assertTrue(extraction.contains("--password-store=gnome-libsecret"))
            // Login and extraction must use the same cookie encryption backend; otherwise the
            // second launch cannot decrypt the session cookies written by the first.
            assertEquals(
                login.first { it.startsWith("--password-store=") },
                extraction.first { it.startsWith("--password-store=") },
            )
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `linux vivaldi wrapper is recognized for welcome suppression`() {
        val root = Files.createTempDirectory("archivetune-linux-vivaldi")
        try {
            assertTrue(isVivaldiExecutable(Paths.get("/usr/bin/vivaldi-stable")))
            assertTrue(isVivaldiExecutable(Paths.get("vivaldi")))
            assertFalse(isVivaldiExecutable(Paths.get("/usr/bin/brave")))

            val profile = root.resolve("profile")
            prepareManagedBrowserProfile(Paths.get("/usr/bin/vivaldi-stable"), profile)
            assertTrue(Files.isRegularFile(profile.resolve("First Run")))
            assertTrue(Files.isRegularFile(profile.resolve("Local State")))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `managed browser close command is hidden and contains only selected process ids`() {
        val command = managedBrowserCloseCommand(listOf(12L, 34L))

        assertTrue(command.contains("Hidden"))
        assertTrue(command.last().contains("Get-Process -Id 12,34"))
        assertTrue(command.last().contains("CloseMainWindow"))
    }

    @Test
    fun `default Vivaldi browser is preferred when it is Chromium compatible`() {
        val root = Files.createTempDirectory("archivetune-browser-test")
        try {
            val browser = Files.createFile(root.resolve("vivaldi.exe"))
            val selected =
                findDefaultChromiumBrowser { key, valueName ->
                    when {
                        valueName == "ProgId" -> "VivaldiHTM.Test"
                        key.endsWith("VivaldiHTM.Test\\shell\\open\\command") -> "\"$browser\" --single-argument %1"
                        else -> null
                    }
                }

            assertEquals(browser, selected)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `non Chromium default browser is ignored`() {
        val root = Files.createTempDirectory("archivetune-browser-test")
        try {
            val browser = Files.createFile(root.resolve("firefox.exe"))
            val selected =
                findDefaultChromiumBrowser { key, valueName ->
                    when {
                        valueName == "ProgId" -> "FirefoxURL.Test"
                        key.endsWith("FirefoxURL.Test\\shell\\open\\command") -> "\"$browser\" -osint -url %1"
                        else -> null
                    }
                }

            assertEquals(null, selected)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `browser command parser keeps executable path separate from arguments`() {
        assertEquals(
            Paths.get("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"),
            executableFromBrowserCommand("\"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" --single-argument %1"),
        )
        assertEquals(
            Paths.get("C:\\Browser\\msedge.exe"),
            executableFromBrowserCommand("C:\\Browser\\msedge.exe -- %1"),
        )
    }

    @Test
    fun `chromium helper subprocesses do not count as the browser window`() {
        assertTrue(
            isChromiumBrowserRoot(
                arrayOf("--user-data-dir=C:\\Temp\\profile", "--profile-directory=Default"),
            ),
        )
        assertFalse(
            isChromiumBrowserRoot(
                arrayOf("--type=crashpad-handler", "--user-data-dir=C:\\Temp\\profile"),
            ),
        )
        assertFalse(
            isChromiumBrowserRoot(
                arrayOf("--type=gpu-process", "--user-data-dir=C:\\Temp\\profile"),
            ),
        )
    }

    @Test
    fun `vivaldi managed profile skips the welcome setup without discarding existing preferences`() {
        val root = Files.createTempDirectory("archivetune-vivaldi-profile-test")
        try {
            val profile = root.resolve("profile")
            Files.createDirectories(profile.resolve("Default"))
            Files.writeString(
                profile.resolve("Default").resolve("Preferences"),
                """{"existing":{"keep":true},"vivaldi":{"startup":{"custom":"value"}}}""",
            )
            Files.writeString(
                profile.resolve("Local State"),
                """{"existing":{"keep":true}}""",
            )

            prepareManagedBrowserProfile(Paths.get("vivaldi.exe"), profile)

            val preferences = JSONObject(Files.readString(profile.resolve("Default").resolve("Preferences")))
            val localState = JSONObject(Files.readString(profile.resolve("Local State")))
            assertTrue(preferences.getJSONObject("existing").getBoolean("keep"))
            assertEquals("value", preferences.getJSONObject("vivaldi").getJSONObject("startup").getString("custom"))
            assertEquals(1, preferences.getJSONObject("vivaldi").getJSONObject("startup").getInt("has_seen_feature"))
            assertTrue(preferences.getJSONObject("vivaldi").getJSONObject("startup").getBoolean("has_seen_welcome_page"))
            assertTrue(localState.getJSONObject("existing").getBoolean("keep"))
            assertTrue(localState.getJSONObject("browser").getBoolean("first_run_finished"))
            assertTrue(Files.isRegularFile(profile.resolve("First Run")))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `non Vivaldi managed profile is not preconfigured`() {
        val root = Files.createTempDirectory("archivetune-chrome-profile-test")
        try {
            val profile = root.resolve("profile")
            prepareManagedBrowserProfile(Paths.get("C:\\Browser\\chrome.exe"), profile)
            assertFalse(Files.exists(profile))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `managed login fails promptly when its browser process exits early`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val process = FakeProcess(alive = false)

        val failure = try {
            awaitManagedLoginCompletion(
                completion,
                process,
                Duration.ofMinutes(10),
                pollDelayMillis = 5L,
                browserCloseGrace = Duration.ZERO,
            )
            null
        } catch (expected: IllegalStateException) {
            expected
        }

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("sign-in browser was closed"))
        assertTrue(process.aliveChecks.get() <= 2)
    }

    @Test
    fun `successful confirmation wins when it happens before managed browser exit`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val process = FakeProcess(alive = true)
        completion.complete(Unit)
        process.alive = false

        awaitManagedLoginCompletion(completion, process, Duration.ofMinutes(10), pollDelayMillis = 5L)

        assertEquals(0, process.aliveChecks.get())
    }

    @Test
    fun `linux completion waits for the normal browser exit instead of terminating it`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val process = FakeProcess(alive = true)
        completion.complete(Unit)

        val waiter =
            async {
                awaitManagedLoginCompletion(
                    completion = completion,
                    managedProcess = process,
                    loginTimeout = Duration.ofSeconds(5),
                    pollDelayMillis = 5L,
                    browserCloseGrace = Duration.ZERO,
                    completeOnBrowserExit = true,
                    completionExitTimeout = Duration.ofSeconds(2),
                )
            }
        delay(40L)

        assertFalse("Completion must wait for the user to close the window", waiter.isCompleted)
        assertTrue("The completion wait must not kill the browser", process.isAlive)

        process.alive = false
        waiter.await()
        assertFalse(process.isAlive)
    }

    @Test
    fun `linux normal window close finalizes without the Done signal`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val process = FakeProcess(alive = false)

        awaitManagedLoginCompletion(
            completion = completion,
            managedProcess = process,
            loginTimeout = Duration.ofSeconds(2),
            pollDelayMillis = 5L,
            browserCloseGrace = Duration.ZERO,
            completeOnBrowserExit = true,
            completionExitTimeout = Duration.ofSeconds(2),
        )

        assertTrue("A normal window close must not consume the Done signal", completion.complete(Unit))
    }

    @Test
    fun `linux launcher handoff gap does not finalize the import`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val browserRunning = AtomicBoolean(false)

        val waiter =
            async {
                awaitManagedLoginCompletion(
                    completion = completion,
                    managedProcess = FakeProcess(alive = true),
                    loginTimeout = Duration.ofSeconds(5),
                    pollDelayMillis = 5L,
                    isBrowserRunning = browserRunning::get,
                    browserCloseGrace = Duration.ofSeconds(1),
                    completeOnBrowserExit = true,
                    completionExitTimeout = Duration.ofSeconds(2),
                )
            }
        delay(40L)
        assertFalse("A briefly absent launcher must not finalize the import", waiter.isCompleted)

        browserRunning.set(true)
        delay(100L)
        assertFalse("A handoff browser that becomes visible must keep the wait running", waiter.isCompleted)

        browserRunning.set(false)
        waiter.await()
    }

    @Test
    fun `linux completion fails without killing a still open browser`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val process = FakeProcess(alive = true)
        completion.complete(Unit)

        val failure =
            try {
                awaitManagedLoginCompletion(
                    completion = completion,
                    managedProcess = process,
                    loginTimeout = Duration.ofSeconds(2),
                    pollDelayMillis = 5L,
                    completeOnBrowserExit = true,
                    completionExitTimeout = Duration.ofMillis(30),
                )
                null
            } catch (expected: IllegalStateException) {
                expected
            }

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("Close the ArchiveTune sign-in browser window"))
        assertTrue("The aborted wait must leave termination to the cleanup path", process.isAlive)
    }

    @Test
    fun `launcher exit does not fail while handed off browser is running`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val launcher = FakeProcess(alive = false)
        val waiter = async {
            awaitManagedLoginCompletion(
                completion = completion,
                managedProcess = launcher,
                loginTimeout = Duration.ofSeconds(2),
                pollDelayMillis = 5L,
                isBrowserRunning = { true },
            )
        }
        delay(20L)

        assertFalse(waiter.isCompleted)
        completion.complete(Unit)
        waiter.await()
    }

    @Test
    fun `brief launcher handoff gap does not report the browser as closed`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val launcher = FakeProcess(alive = false)
        val checks = AtomicInteger(0)
        val waiter = async {
            awaitManagedLoginCompletion(
                completion = completion,
                managedProcess = launcher,
                loginTimeout = Duration.ofSeconds(2),
                pollDelayMillis = 5L,
                isBrowserRunning = { checks.incrementAndGet() >= 3 },
                browserCloseGrace = Duration.ofMillis(100),
            )
        }
        delay(40L)

        assertFalse(waiter.isCompleted)
        completion.complete(Unit)
        waiter.await()
    }

    @Test
    fun `cancelling login wait stops process polling`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val process = FakeProcess(alive = true)
        val waiter = launch {
            awaitManagedLoginCompletion(
                completion,
                process,
                Duration.ofMinutes(10),
                pollDelayMillis = 5L,
                browserCloseGrace = Duration.ZERO,
            )
        }
        while (process.aliveChecks.get() == 0) delay(1L)

        waiter.cancelAndJoin()
        val checksAfterCancellation = process.aliveChecks.get()
        delay(20L)

        assertEquals(checksAfterCancellation, process.aliveChecks.get())
    }

    @Test
    fun `unrelated browser exit cannot complete or fail managed login`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val managed = FakeProcess(alive = true)
        val unrelated = FakeProcess(alive = false)
        val waiter = async {
            awaitManagedLoginCompletion(completion, managed, Duration.ofSeconds(2), pollDelayMillis = 5L)
        }
        delay(20L)

        assertFalse(waiter.isCompleted)
        assertEquals(0, unrelated.aliveChecks.get())
        completion.complete(Unit)
        waiter.await()
    }

    @Test
    fun `managed process exit wins if confirmation has not happened yet`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val process = FakeProcess(alive = true)
        process.alive = false

        val failure = try {
            awaitManagedLoginCompletion(completion, process, Duration.ofMinutes(10), pollDelayMillis = 5L)
            null
        } catch (expected: IllegalStateException) {
            expected
        }

        assertNotNull(failure)
        assertTrue(completion.complete(Unit))
    }

    @Test
    fun `managed profile matching requires the exact profile path`() {
        val root = Files.createTempDirectory("archivetune-managed-profile-match")
        try {
            val profile = Files.createDirectories(root.resolve("profile"))
            val sibling = Files.createDirectories(root.resolve("profile-copy"))

            assertTrue(usesManagedProfile(arrayOf("--user-data-dir=$profile", "--type=renderer"), profile))
            assertTrue(usesManagedProfile(arrayOf("--user-data-dir", profile.toString()), profile))
            assertFalse(usesManagedProfile(arrayOf("--user-data-dir=$sibling"), profile))
            assertFalse(usesManagedProfile(arrayOf("--user-data-dir=$sibling/profile"), profile))
            assertFalse(usesManagedProfile(arrayOf("--user-data-dir="), profile))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `stale browser locks are removed without deleting profile data`() {
        val profile = Files.createTempDirectory("archivetune-stale-locks")
        try {
            listOf("SingletonLock", "SingletonSocket", "SingletonCookie", "DevToolsActivePort").forEach { name ->
                Files.writeString(profile.resolve(name), "stale")
            }
            Files.writeString(profile.resolve("Preferences"), "{}")

            clearStaleManagedProfileLockFiles(profile)

            listOf("SingletonLock", "SingletonSocket", "SingletonCookie", "DevToolsActivePort").forEach { name ->
                assertFalse(Files.exists(profile.resolve(name)))
            }
            assertEquals("{}", Files.readString(profile.resolve("Preferences")))
        } finally {
            Files.walk(profile).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `profile lease is exclusive across source instances and survives profile deletion`() {
        val root = Files.createTempDirectory("archivetune-profile-lease")
        try {
            val profile = Files.createDirectories(root.resolve("profile"))
            val lease = requireNotNull(acquireManagedProfileLease(profile)) { "First lease was not granted" }
            val leasePath = managedProfileLeasePath(profile)
            try {
                assertFalse("The lease must live outside the profile directory", leasePath.startsWith(profile))
                if (isLinuxOperatingSystem()) {
                    assertEquals(
                        "The lease file must not be readable by other users",
                        "rw-------",
                        PosixFilePermissions.toString(Files.getPosixFilePermissions(leasePath)),
                    )
                }
                // clearSession deletes the profile while holding the lease. That must not unlink
                // the live lock inode, or a second instance would create a new file and "win".
                deleteRecursively(profile)
                assertFalse(Files.exists(profile))
                assertEquals("Lease identity must survive profile deletion", leasePath, managedProfileLeasePath(profile))
                assertNull("A second lease must not be granted while the first is held", acquireManagedProfileLease(profile))
                assertTrue("Deleting the profile must not delete the lease", Files.exists(leasePath))
            } finally {
                lease.close()
            }

            val reacquired = requireNotNull(acquireManagedProfileLease(profile)) { "Released lease was not reacquired" }
            reacquired.close()
            assertTrue("Releasing the lease must not delete its inode", Files.exists(leasePath))
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `lease identity resolves symlink aliases and survives recreation`() {
        assumeTrue("Symbolic-link aliases require Linux", isLinuxOperatingSystem())
        val root = Files.createTempDirectory("archivetune-lease-alias")
        try {
            val realProfile = Files.createDirectories(root.resolve("real-profile"))
            val alias = Files.createSymbolicLink(root.resolve("alias-profile"), realProfile)
            val secondAlias = Files.createSymbolicLink(root.resolve("other-alias"), realProfile)

            val canonicalLease = managedProfileLeasePath(realProfile)
            assertEquals(canonicalLease, managedProfileLeasePath(alias))
            assertEquals(canonicalLease, managedProfileLeasePath(secondAlias))

            val lease = requireNotNull(acquireManagedProfileLease(realProfile)) { "First lease was not granted" }
            try {
                assertNull("Symlink alias must contend with the real profile", acquireManagedProfileLease(alias))
                assertNull("Second symlink alias must contend with the real profile", acquireManagedProfileLease(secondAlias))
            } finally {
                lease.close()
            }

            // Recreating the profile through either spelling must not create a second lease.
            deleteRecursively(realProfile)
            Files.createDirectories(realProfile)
            assertEquals(canonicalLease, managedProfileLeasePath(alias))
            requireNotNull(acquireManagedProfileLease(alias)).close()
            assertTrue(Files.exists(canonicalLease))
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `source refuses to clear a profile leased by another instance`() {
        val root = Files.createTempDirectory("archivetune-source-lease")
        try {
            val profile = Files.createDirectories(root.resolve("profile"))
            val marker = profile.resolve("Preferences")
            Files.writeString(marker, "{}")
            val lease = requireNotNull(acquireManagedProfileLease(profile)) { "First lease was not granted" }
            try {
                // A second source instance has its own in-memory guard, so only the OS lease can
                // stop it from killing the first instance's browser and deleting its profile.
                val second = ManagedChromiumLoginSource(profile = profile)

                val failure = second.clearSession().exceptionOrNull()

                assertTrue("Contended clear must fail", failure is ManagedBrowserFailure)
                assertEquals(
                    ManagedBrowserFailureCategory.PROFILE_LOCKED,
                    (failure as ManagedBrowserFailure).category,
                )
                assertEquals(managedBrowserFailureMessage(ManagedBrowserFailureCategory.PROFILE_LOCKED), failure.message)
                assertFalse("Contended clear must not complete a managed sign-in", second.completeLogin())
                assertEquals("Contended clear must not mutate the profile", "{}", Files.readString(marker))
            } finally {
                lease.close()
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `contended acquire does not kill the live profile process`() = runBlocking {
        assumeTrue("Requires Linux process arguments", isLinuxOperatingSystem())
        val root = Files.createTempDirectory("archivetune-contended-acquire")
        var liveProcess: Process? = null
        try {
            val profile = Files.createDirectories(root.resolve("profile"))
            val lease = requireNotNull(acquireManagedProfileLease(profile)) { "First lease was not granted" }
            try {
                liveProcess =
                    ProcessBuilder("bash", "-c", "while :; do sleep 1; done", "--user-data-dir=$profile")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
                while (findManagedBrowserProcesses(profile).isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(25L)
                }
                assertTrue(
                    "Synthetic profile process was not visible to the orphan scan",
                    findManagedBrowserProcesses(profile).isNotEmpty(),
                )

                // A second source cannot see the first instance's in-memory guard. It must fail on
                // the lease before its cleanup path can treat this live process as an orphan.
                val failure = ManagedChromiumLoginSource(profile = profile).acquireSession().exceptionOrNull()

                assertTrue("Contended acquire must fail", failure is ManagedBrowserFailure)
                assertEquals(
                    ManagedBrowserFailureCategory.PROFILE_LOCKED,
                    (failure as ManagedBrowserFailure).category,
                )
                assertTrue("Contended acquire killed the live profile process", liveProcess!!.isAlive)
            } finally {
                lease.close()
            }
        } finally {
            liveProcess?.destroyForcibly()
            deleteRecursively(root)
        }
    }

    @Test
    fun `source complete login stays false without an interactive wait`() {
        val root = Files.createTempDirectory("archivetune-complete-login")
        try {
            val source = ManagedChromiumLoginSource(profile = root.resolve("profile"))
            assertFalse(source.completeLogin())
            assertFalse(source.completeLogin())
        } finally {
            deleteRecursively(root)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `devtools endpoint ignores partial port files`() {
        val profile = Files.createTempDirectory("archivetune-devtools-port")
        try {
            assertNull(readManagedDevToolsEndpoint(profile))

            Files.writeString(profile.resolve("DevToolsActivePort"), "43210\n")
            assertNull(readManagedDevToolsEndpoint(profile))

            Files.writeString(profile.resolve("DevToolsActivePort"), "0\n/devtools/browser/id\n")
            assertNull(readManagedDevToolsEndpoint(profile))

            Files.writeString(profile.resolve("DevToolsActivePort"), "43210\nnot-a-websocket-path\n")
            assertNull(readManagedDevToolsEndpoint(profile))

            Files.writeString(profile.resolve("DevToolsActivePort"), "43210\n/devtools/browser/id\n")
            assertEquals(
                ManagedDevToolsEndpoint(43210, "ws://127.0.0.1:43210/devtools/browser/id"),
                readManagedDevToolsEndpoint(profile),
            )
        } finally {
            Files.walk(profile).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `closed devtools http endpoint is reported as not ready`() {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(findManagedPageTarget(port))
    }

    @Test
    fun `page target polling retries until chromium finishes starting`() = runBlocking {
        val attempts = AtomicInteger()
        val target =
            awaitManagedPageTarget(
                port = 9222,
                timeout = Duration.ofSeconds(2),
                pollDelayMillis = 2L,
                findTarget = { index ->
                    assertEquals(9222, index)
                    if (attempts.incrementAndGet() < 3) null else ManagedPageTarget("ws://127.0.0.1/target")
                },
            )

        assertEquals("ws://127.0.0.1/target", target.webSocketDebuggerUrl)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `browser termination escalates only after graceful timeout`() {
        val graceful = FakeProcessHandle(pid = 11L)
        val stubborn = FakeProcessHandle(pid = 12L, ignoresDestroy = true)

        val released = terminateManagedProcesses(listOf(graceful, stubborn), Duration.ofMillis(20), pollIntervalMillis = 2L)

        assertTrue(released)
        assertEquals(1, graceful.destroyCalls.get())
        assertEquals(0, graceful.forceCalls.get())
        assertFalse(graceful.isAlive)
        assertEquals(1, stubborn.destroyCalls.get())
        assertEquals(1, stubborn.forceCalls.get())
        assertFalse(stubborn.isAlive)
    }

    private class FakeProcessHandle(
        private val pid: Long,
        private var alive: Boolean = true,
        private val ignoresDestroy: Boolean = false,
    ) : ProcessHandle {
        val destroyCalls = AtomicInteger()
        val forceCalls = AtomicInteger()

        override fun pid(): Long = pid

        override fun info(): ProcessHandle.Info =
            object : ProcessHandle.Info {
                override fun command(): Optional<String> = Optional.empty()
                override fun commandLine(): Optional<String> = Optional.empty()
                override fun arguments(): Optional<Array<String>> = Optional.empty()
                override fun startInstant(): Optional<java.time.Instant> = Optional.empty()
                override fun totalCpuDuration(): Optional<Duration> = Optional.empty()
                override fun user(): Optional<String> = Optional.empty()
            }

        override fun parent(): Optional<ProcessHandle> = Optional.empty()
        override fun children(): Stream<ProcessHandle> = Stream.empty()
        override fun descendants(): Stream<ProcessHandle> = Stream.empty()
        override fun supportsNormalTermination(): Boolean = true
        override fun isAlive(): Boolean = alive

        override fun destroy(): Boolean {
            destroyCalls.incrementAndGet()
            if (!ignoresDestroy) alive = false
            return true
        }

        override fun destroyForcibly(): Boolean {
            forceCalls.incrementAndGet()
            alive = false
            return true
        }

        override fun onExit(): CompletableFuture<ProcessHandle> = CompletableFuture()
        override fun compareTo(other: ProcessHandle): Int = pid().compareTo(other.pid())
    }

    private class FakeProcess(alive: Boolean) : Process() {
        @Volatile var alive: Boolean = alive
        val aliveChecks = AtomicInteger(0)
        private val output = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(ByteArray(0))
        private val error = ByteArrayInputStream(ByteArray(0))

        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = error
        override fun waitFor(): Int {
            while (alive) Thread.sleep(1L)
            return 0
        }
        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return 0
        }
        override fun destroy() { alive = false }
        override fun isAlive(): Boolean {
            aliveChecks.incrementAndGet()
            return alive
        }
        override fun destroyForcibly(): Process = apply { alive = false }
    }
}
