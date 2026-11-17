package moe.rukamori.archivetune.account.login

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.utils.hasCompleteYouTubeLoginCookies
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.utils.SavedAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uses the real core cookie rules so the SDK seam cannot drift from app validation. */
private val coreCredentialPolicy =
    object : YouTubeAccountCredentialPolicy {
        override fun hasLoginCookie(cookie: String?): Boolean = hasYouTubeLoginCookie(cookie)

        override fun hasCompleteLoginCookies(cookie: String?): Boolean = hasCompleteYouTubeLoginCookies(cookie)

        override fun normalizeAuthValue(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

        override fun normalizeDataSyncId(value: String?): String? =
            PlaybackAuthState(dataSyncId = value).normalized().dataSyncId
    }

private class FakeBackend(
    private val dataSyncIdResult: () -> Result<String?> = { Result.success("network-sync") },
) : YouTubeAccountBackend {
    val events = mutableListOf<String>()
    val visitors = mutableListOf<String>()
    val dataSyncIds = mutableListOf<String>()

    override fun setAuthState(
        cookie: String?,
        visitorData: String?,
        dataSyncId: String?,
    ) {
        events += "auth:$dataSyncId"
    }

    override suspend fun accountInfo(): Result<YouTubeAccountInfo> {
        events += "info"
        return Result.success(YouTubeAccountInfo("Name", "name@example.invalid", "@name"))
    }

    override suspend fun accountDataSyncId(): Result<String?> {
        events += "dataSyncId"
        return dataSyncIdResult()
    }

    override fun setVisitorData(value: String) {
        visitors += value
    }

    override fun setDataSyncId(value: String) {
        dataSyncIds += value
    }
}

private class FakeStore(
    private val snapshot: YouTubeAccountSnapshot =
        YouTubeAccountSnapshot("SAPISID=x; LOGIN_INFO=y", "visitor", "sync"),
) : YouTubeAccountStore {
    var loginSession: PersistedLoginSession? = null
    var accountSwitch: PersistedAccountSwitch? = null
    var context: Pair<String?, String?>? = null

    override suspend fun persistLoginSession(session: PersistedLoginSession) {
        loginSession = session
    }

    override suspend fun persistAccountSwitch(switch: PersistedAccountSwitch) {
        accountSwitch = switch
    }

    override suspend fun persistLoginContext(
        visitorData: String?,
        dataSyncId: String?,
    ) {
        context = visitorData to dataSyncId
    }

    override suspend fun readAccountSnapshot(): YouTubeAccountSnapshot = snapshot
}

private fun savedAccount(
    id: String = "account-id",
    cookie: String = "SAPISID=x; LOGIN_INFO=y",
    visitorData: String = " visitor ",
    dataSyncId: String = "old-sync",
) = SavedAccount(
    id = id,
    name = "Name",
    email = "name@example.invalid",
    channelHandle = "@name",
    innerTubeCookie = cookie,
    visitorData = visitorData,
    dataSyncId = dataSyncId,
    ytmSync = true,
    selectedYtmPlaylists = "playlist-a",
)

class YouTubeAccountCoordinatorTest {
    @Test
    fun `credential-bearing values do not expose secrets in toString`() {
        val snapshot = YouTubeAccountSnapshot("secret-cookie", "secret-visitor", "secret-sync")
        val persisted = PersistedLoginSession("secret-cookie", "secret-visitor", "secret-sync", "name", "email", "handle")
        val session = YouTubeLoginSession("secret-visitor", "secret-sync", YouTubeAccountInfo("name", "email", "handle"))
        assertFalse(snapshot.toString().contains("secret"))
        assertFalse(persisted.toString().contains("secret"))
        assertFalse(session.toString().contains("secret"))
    }

    private val completeCookie = "SAPISID=x; LOGIN_INFO=y"

    @Test
    fun `completion rejects missing and incomplete login cookies before any backend call`() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()
        val coordinator = YouTubeAccountCoordinator(coreCredentialPolicy, backend, store)

        val missing = coordinator.completeLogin("PREF=only", null, null)
        assertEquals("YouTube login cookie is missing", missing.exceptionOrNull()?.message)

        val incomplete = coordinator.completeLogin("SAPISID=x", null, null)
        assertEquals("YouTube login cookies are incomplete", incomplete.exceptionOrNull()?.message)

        assertTrue(backend.events.isEmpty())
        assertNull(store.loginSession)
    }

    @Test
    fun `completion verifies without a DataSyncId then persists the network-resolved session`() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()
        val coordinator = YouTubeAccountCoordinator(coreCredentialPolicy, backend, store)

        val result =
            coordinator.completeLogin(
                cookie = "  $completeCookie  ",
                visitorData = " visitor ",
                dataSyncId = "candidate-sync",
            )
        val session = result.getOrThrow()

        assertEquals("network-sync", session.dataSyncId)
        assertEquals("visitor", session.visitorData)
        assertEquals("Name", session.accountInfo.name)
        assertEquals("name@example.invalid", session.accountInfo.email)
        assertEquals("@name", session.accountInfo.channelHandle)
        assertEquals(listOf("auth:null", "info", "dataSyncId", "auth:network-sync"), backend.events)

        val persisted = assertNotNull(store.loginSession)
        assertEquals(completeCookie, persisted.cookie)
        assertEquals("visitor", persisted.visitorData)
        assertEquals("network-sync", persisted.dataSyncId)
        assertEquals("Name", persisted.accountName)
    }

    @Test
    fun `completion falls back to the candidate DataSyncId when the network lookup fails`() = runBlocking {
        val backend = FakeBackend { Result.failure(IllegalStateException("offline")) }
        val store = FakeStore()
        val coordinator = YouTubeAccountCoordinator(coreCredentialPolicy, backend, store)

        val result = coordinator.completeLogin(completeCookie, null, "candidate-sync")

        assertEquals("candidate-sync", result.getOrThrow().dataSyncId)
        assertEquals("candidate-sync", assertNotNull(store.loginSession).dataSyncId)
    }

    @Test
    fun `completion fails with the dedicated missing DataSyncId error`() = runBlocking {
        val backend = FakeBackend { Result.failure(IllegalStateException("offline")) }
        val coordinator = YouTubeAccountCoordinator(coreCredentialPolicy, backend, FakeStore())

        val result = coordinator.completeLogin(completeCookie, null, null)

        assertTrue(result.exceptionOrNull() is MissingYouTubeDataSyncIdException)
    }

    @Test
    fun `cancellation from DataSyncId resolution is rethrown rather than captured`() = runBlocking {
        val backend = FakeBackend { Result.failure(CancellationException("cancelled")) }
        val coordinator = YouTubeAccountCoordinator(coreCredentialPolicy, backend, FakeStore())

        assertFailsWith<CancellationException> {
            coordinator.completeLogin(completeCookie, null, "candidate-sync")
        }
    }

    @Test
    fun `switch validates the saved cookie, resolves and persists the account, and reads it back`() = runBlocking {
        val backend = FakeBackend { Result.success("resolved-sync") }
        val snapshot = YouTubeAccountSnapshot(completeCookie, null, "resolved-sync")
        val store = FakeStore(snapshot)
        val coordinator = YouTubeAccountCoordinator(coreCredentialPolicy, backend, store)
        val account = savedAccount()

        val result = coordinator.switchSavedAccount(account)

        assertEquals(snapshot, result.getOrThrow())
        assertEquals(listOf("auth:old-sync", "dataSyncId", "auth:resolved-sync"), backend.events)
        val switch = assertNotNull(store.accountSwitch)
        assertEquals(account, switch.account)
        assertEquals("resolved-sync", switch.resolvedDataSyncId)
    }

    @Test
    fun `switch rejects a saved account with an incomplete cookie`() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()
        val coordinator = YouTubeAccountCoordinator(coreCredentialPolicy, backend, store)

        val result = coordinator.switchSavedAccount(savedAccount(cookie = "SAPISID=x"))

        assertEquals("Saved account login cookies are incomplete", result.exceptionOrNull()?.message)
        assertTrue(backend.events.isEmpty())
        assertNull(store.accountSwitch)
    }

    @Test
    fun `login context normalizes values, skips empty updates and updates the backend`() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()
        val coordinator = YouTubeAccountCoordinator(coreCredentialPolicy, backend, store)

        coordinator.saveLoginContext(" null ", null)
        assertNull(store.context)
        assertTrue(backend.visitors.isEmpty())

        coordinator.saveLoginContext(" visitor ", " null ")
        assertEquals("visitor" to null, store.context)
        assertEquals(listOf("visitor"), backend.visitors)
        assertTrue(backend.dataSyncIds.isEmpty())

        coordinator.saveLoginContext(null, " sync ")
        assertEquals(null to "sync", store.context)
        assertEquals(listOf("sync"), backend.dataSyncIds)
    }

    @Test
    fun `saved account repair only rewrites the switched account and keeps order`() {
        val first = savedAccount(id = "first")
        val second = savedAccount(id = "second")
        val repaired = repairedSavedAccounts(listOf(first, second), "second", "new-sync")

        assertEquals(listOf(first, second.copy(dataSyncId = "new-sync")), repaired)
        assertEquals(listOf(first, second), repairedSavedAccounts(listOf(first, second), "missing", "new-sync"))
    }
}
