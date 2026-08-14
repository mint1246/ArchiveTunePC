package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.runBlocking
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
    fun `expired sessions never flow to backend and restore is marked expired`() = runBlocking {
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
        assertTrue(restored is DesktopAccountState.Expired)
        assertEquals(PlaybackAuthState.EMPTY, manager.currentAuthState())
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
