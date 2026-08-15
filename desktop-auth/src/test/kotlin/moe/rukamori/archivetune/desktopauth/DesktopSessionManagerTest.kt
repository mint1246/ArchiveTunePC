package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class DesktopSessionManagerTest {
    @Test
    fun `import rejects headers without a YouTube login cookie`() = runBlocking {
        val manager = manager()

        val result = manager.importAndValidate(YouTubeSessionImport(cookieHeader = "VISITOR_INFO1_LIVE=abc"))

        assertEquals(SessionImportResult.Rejected(SessionImportFailure.MISSING_LOGIN_COOKIE), result)
    }

    @Test
    fun `import validates stores and exposes only a redacted diagnostic`() = runBlocking {
        val store = MemoryStore()
        val manager = manager(store = store)
        val cookie = "SAPISID=secret-sapisid; SID=secret-sid"

        val result = manager.importAndValidate(YouTubeSessionImport(cookieHeader = cookie, visitorData = "visitor"))

        assertTrue(result is SessionImportResult.Success)
        assertEquals("Example", (manager.state.value as DesktopAccountState.SignedIn).account.name)
        assertEquals(cookie, manager.currentAuthState().cookie)
        assertFalse(manager.diagnostic.toString().contains("secret-sapisid"))
        assertFalse(manager.diagnostic.toString().contains("secret-sid"))
        assertTrue(manager.diagnostic.hasDataSyncId)
        assertTrue(store.value!!.isNotEmpty())
    }

    @Test
    fun `past browser expiry is rejected at import but restored sessions are revalidated not pre-expired`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC)
        val store = MemoryStore()
        val manager = manager(store = store, clock = clock)
        val imported =
            manager.importAndValidate(
                YouTubeSessionImport(
                    cookieHeader = "SAPISID=secret",
                    expiresAtEpochMillis = 999,
                ),
            )

        assertEquals(SessionImportResult.Rejected(SessionImportFailure.EXPIRED), imported)
        assertEquals(PlaybackAuthState.EMPTY, manager.currentAuthState())

        store.value =
            StoredDesktopSessionCodec.encode(
                StoredDesktopSession(
                    PlaybackAuthState(cookie = "SAPISID=secret", dataSyncId = "sync"),
                    DesktopAccount("Example", null, null),
                    expiresAtEpochMillis = 999,
                ),
            )
        val restored = manager.restore()
        assertTrue(restored is DesktopAccountState.SignedIn)
        assertEquals("SAPISID=secret", manager.currentAuthState().cookie)

        assertTrue(manager.refreshRestoredSession())
        assertTrue(manager.state.value is DesktopAccountState.SignedIn)
    }

    @Test
    fun `server auth rejection expires a restored session`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC)
        val store = MemoryStore()
        store.value = StoredDesktopSessionCodec.encode(
            StoredDesktopSession(
                authState = PlaybackAuthState(cookie = "SAPISID=dead"),
                account = DesktopAccount("Dead", null, null),
                expiresAtEpochMillis = null,
            ),
        )
        val manager = DesktopSessionManager(
            secureStore = store,
            verifier = DesktopSessionVerifier {
                Result.failure(IllegalStateException("Failed to get account info - user may not be logged in"))
            },
            clock = clock,
        )
        manager.restore()
        assertTrue(manager.state.value is DesktopAccountState.SignedIn)

        assertFalse(manager.refreshRestoredSession())
        assertTrue(manager.state.value is DesktopAccountState.Expired)
        assertEquals(PlaybackAuthState.EMPTY, manager.currentAuthState())
    }

    @Test
    fun `transient verification failure keeps the session for offline use`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC)
        val store = MemoryStore()
        store.value = StoredDesktopSessionCodec.encode(
            StoredDesktopSession(
                authState = PlaybackAuthState(cookie = "SAPISID=known"),
                account = DesktopAccount("Known", null, null),
                expiresAtEpochMillis = null,
            ),
        )
        val manager = DesktopSessionManager(
            secureStore = store,
            verifier = DesktopSessionVerifier { Result.failure(java.io.IOException("offline")) },
            clock = clock,
        )
        manager.restore()

        assertFalse(manager.refreshRestoredSession())
        assertTrue(manager.state.value is DesktopAccountState.SignedIn)
        assertEquals("SAPISID=known", manager.currentAuthState().cookie)
    }

    @Test
    fun `logout deletes encrypted payload and clears session provider`() = runBlocking {
        val store = MemoryStore()
        val manager = manager(store = store)
        manager.importAndValidate(YouTubeSessionImport(cookieHeader = "SAPISID=secret"))

        assertTrue(manager.logout().isSuccess)
        assertNull(store.value)
        assertEquals(DesktopAccountState.SignedOut, manager.state.value)
        assertEquals(PlaybackAuthState.EMPTY, manager.currentAuthState())
    }

    @Test
    fun `browser handoff uses official login page and never claims a callback`() {
        var opened: URI? = null
        val manager = manager(browser = SystemBrowserLauncher { uri -> opened = uri; Result.success(Unit) })

        val result = manager.beginBrowserLogin().getOrThrow()

        assertEquals("accounts.google.com", opened!!.host)
        assertTrue(result.requiresManualSessionImport)
    }

    @Test
    fun `json import supplies only the internally derived cookie header to validation`() = runBlocking {
        var observed: PlaybackAuthState? = null
        val manager =
            DesktopSessionManager(
                secureStore = MemoryStore(),
                verifier = DesktopSessionVerifier { candidate ->
                    observed = candidate
                    Result.success(VerifiedDesktopSession(candidate.copy(dataSyncId = "sync"), DesktopAccount("Example", null, null)))
                },
                browserLauncher = SystemBrowserLauncher { Result.success(Unit) },
            )

        val result = manager.importCookieJson("""[{"name":"SAPISID","value":"secret","domain":"youtube.com"}]""")

        assertTrue(result is CookieJsonSessionImportResult.Success)
        assertEquals("SAPISID=secret", observed!!.cookie)
        assertNull(observed!!.visitorData)
        assertNull(observed!!.dataSyncId)
        assertNull(observed!!.poToken)
    }

    @Test
    fun `secure store failure fails closed`() = runBlocking {
        val manager = manager(store = object : SecureSessionStore {
            override fun read(): ByteArray? = throw SecureStoreUnavailableException("no dpapi")
            override fun write(value: ByteArray) = throw SecureStoreUnavailableException("no dpapi")
            override fun delete() = throw SecureStoreUnavailableException("no dpapi")
        })

        assertTrue(manager.restore() is DesktopAccountState.Unavailable)
        val result = manager.importAndValidate(YouTubeSessionImport(cookieHeader = "SAPISID=secret"))
        assertEquals(SessionImportResult.Rejected(SessionImportFailure.SECURE_STORAGE_UNAVAILABLE), result)
    }

    @Test
    fun `verifier success without a login cookie is rejected and never stored`() = runBlocking {
        val store = MemoryStore()
        val manager =
            DesktopSessionManager(
                secureStore = store,
                verifier = DesktopSessionVerifier {
                    Result.success(
                        VerifiedDesktopSession(
                            authState = PlaybackAuthState.EMPTY,
                            account = DesktopAccount("Untrusted", null, null),
                        ),
                    )
                },
            )

        val result = manager.importAndValidate(YouTubeSessionImport(cookieHeader = "SAPISID=candidate"))

        assertEquals(SessionImportResult.Rejected(SessionImportFailure.VALIDATION_FAILED), result)
        assertNull(store.value)
        assertEquals(PlaybackAuthState.EMPTY, manager.currentAuthState())
        assertEquals(DesktopAccountState.SignedOut, manager.state.value)
    }

    @Test
    fun `refresh verifier cannot replace an existing session with anonymous credentials`() = runBlocking {
        val store = MemoryStore().apply {
            value = StoredDesktopSessionCodec.encode(
                StoredDesktopSession(
                    authState = PlaybackAuthState(cookie = "SAPISID=known"),
                    account = DesktopAccount("Known", null, null),
                    expiresAtEpochMillis = null,
                ),
            )
        }
        val manager = DesktopSessionManager(
            secureStore = store,
            verifier = DesktopSessionVerifier {
                Result.success(VerifiedDesktopSession(PlaybackAuthState.EMPTY, DesktopAccount("Bad", null, null)))
            },
        )
        manager.restore()

        assertFalse(manager.refreshRestoredSession())
        assertEquals("SAPISID=known", manager.currentAuthState().cookie)
        assertEquals("SAPISID=known", StoredDesktopSessionCodec.decode(store.value!!).authState.cookie)
        assertTrue(manager.state.value is DesktopAccountState.SignedIn)
    }

    @Test
    fun `request refresh revalidates once and persists verifier updates`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC)
        val store = MemoryStore()
        store.value = StoredDesktopSessionCodec.encode(
            StoredDesktopSession(
                authState = PlaybackAuthState(cookie = "SAPISID=old", dataSyncId = "sync"),
                account = DesktopAccount("Old", null, null),
                expiresAtEpochMillis = null,
            ),
        )
        val calls = AtomicInteger()
        val manager = DesktopSessionManager(
            secureStore = store,
            verifier = DesktopSessionVerifier { candidate ->
                calls.incrementAndGet()
                Result.success(VerifiedDesktopSession(candidate.copy(cookie = "SAPISID=new"), DesktopAccount("New", null, null)))
            },
            clock = clock,
        )

        manager.restore()
        assertTrue(manager.refreshRestoredSession())
        assertEquals("SAPISID=new", manager.currentAuthState().cookie)
        assertEquals("SAPISID=new", StoredDesktopSessionCodec.decode(store.value!!).authState.cookie)
        assertEquals(1, calls.get())
    }

    @Test
    fun `concurrent refresh requests are serialized`() = runBlocking {
        val store = MemoryStore()
        store.value = StoredDesktopSessionCodec.encode(
            StoredDesktopSession(PlaybackAuthState(cookie = "SAPISID=old"), DesktopAccount("Example", null, null), null),
        )
        val calls = AtomicInteger()
        val manager = DesktopSessionManager(
            secureStore = store,
            verifier = DesktopSessionVerifier { candidate ->
                calls.incrementAndGet()
                delay(25)
                Result.success(VerifiedDesktopSession(candidate, DesktopAccount("Example", null, null)))
            },
        )
        manager.restore()

        List(8) { async { manager.refreshIfNeeded() } }.awaitAll()

        assertEquals(1, calls.get())
    }

    @Test
    fun `expired session can be renewed by an authorized cookie source`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC)
        val store = MemoryStore()
        store.value = StoredDesktopSessionCodec.encode(
            StoredDesktopSession(PlaybackAuthState(cookie = "SAPISID=old"), DesktopAccount("Old", null, null), 9_999),
        )
        var supplied: YouTubeSessionImport? = null
        val manager = DesktopSessionManager(
            secureStore = store,
            verifier = DesktopSessionVerifier { candidate ->
                Result.success(VerifiedDesktopSession(candidate, DesktopAccount("Fresh", null, null)))
            },
            cookieRefresher = DesktopSessionCookieRefresher {
                supplied = it
                Result.success(YouTubeSessionImport("SAPISID=fresh", expiresAtEpochMillis = 20_000))
            },
            clock = clock,
        )

        assertTrue(manager.restore() is DesktopAccountState.SignedIn)
        assertTrue(manager.refreshRestoredSession())
        assertEquals("SAPISID=old", supplied!!.cookieHeader)
        assertEquals("SAPISID=fresh", manager.currentAuthState().cookie)
        assertTrue(manager.state.value is DesktopAccountState.SignedIn)
        assertEquals(20_000L, StoredDesktopSessionCodec.decode(store.value!!).expiresAtEpochMillis)
    }

    @Test
    fun `near expiry prefers replacement cookie and clears obsolete expiry when unknown`() = runBlocking {
        val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC)
        val store = MemoryStore().apply {
            value = StoredDesktopSessionCodec.encode(
                StoredDesktopSession(
                    PlaybackAuthState(cookie = "SAPISID=old"),
                    DesktopAccount("Old", null, null),
                    expiresAtEpochMillis = 12_000,
                ),
            )
        }
        var verifiedCookie: String? = null
        val refreshCalls = AtomicInteger()
        val manager = DesktopSessionManager(
            secureStore = store,
            verifier = DesktopSessionVerifier { candidate ->
                verifiedCookie = candidate.cookie
                Result.success(VerifiedDesktopSession(candidate, DesktopAccount("Fresh", null, null)))
            },
            cookieRefresher = DesktopSessionCookieRefresher {
                refreshCalls.incrementAndGet()
                Result.success(YouTubeSessionImport("SAPISID=fresh", expiresAtEpochMillis = null))
            },
            clock = clock,
        )

        manager.restore()
        assertTrue(manager.refreshRestoredSession())

        assertEquals(1, refreshCalls.get())
        assertEquals("SAPISID=fresh", verifiedCookie)
        assertEquals("SAPISID=fresh", manager.currentAuthState().cookie)
        assertNull(StoredDesktopSessionCodec.decode(store.value!!).expiresAtEpochMillis)
    }

    private fun manager(
        store: SecureSessionStore = MemoryStore(),
        clock: Clock = Clock.systemUTC(),
        browser: SystemBrowserLauncher = SystemBrowserLauncher { Result.success(Unit) },
    ) =
        DesktopSessionManager(
            secureStore = store,
            verifier =
                DesktopSessionVerifier { candidate ->
                    Result.success(
                        VerifiedDesktopSession(
                            authState = candidate.copy(dataSyncId = "sync-id").normalized(),
                            account = DesktopAccount("Example", "example@example.test", "@example"),
                        ),
                    )
                },
            browserLauncher = browser,
            clock = clock,
        )

    private class MemoryStore : SecureSessionStore {
        var value: ByteArray? = null
        override fun read(): ByteArray? = value
        override fun write(value: ByteArray) {
            this.value = value.copyOf()
        }
        override fun delete() {
            value = null
        }
    }
}
