/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.account.login

import moe.rukamori.archivetune.utils.SavedAccount

/** Safe-to-display account metadata. Cookie and token material is deliberately absent. */
data class YouTubeAccountInfo(
    val name: String,
    val email: String,
    val channelHandle: String,
)

/**
 * Result of a completed login. Values are already normalized; the caller owns converting them
 * into its platform auth model and persisting them through [YouTubeAccountStore].
 */
data class YouTubeLoginSession(
    val visitorData: String?,
    val dataSyncId: String,
    val accountInfo: YouTubeAccountInfo,
) {
    override fun toString(): String = "YouTubeLoginSession(redacted)"
}

/** Normalized auth material read back from caller-owned persistence after an account switch. */
data class YouTubeAccountSnapshot(
    val cookie: String?,
    val visitorData: String?,
    val dataSyncId: String?,
) {
    override fun toString(): String = "YouTubeAccountSnapshot(redacted)"
}

class MissingYouTubeDataSyncIdException : IllegalStateException("YouTube DataSyncId is missing")

/** Persisted login material. The cookie is sensitive; callers must never log it. */
data class PersistedLoginSession(
    val cookie: String,
    val visitorData: String?,
    val dataSyncId: String,
    val accountName: String,
    val accountEmail: String,
    val accountChannelHandle: String,
) {
    override fun toString(): String = "PersistedLoginSession(redacted)"
}

/** The account to make current plus the DataSyncId that was resolved for it. */
data class PersistedAccountSwitch(
    val account: SavedAccount,
    val resolvedDataSyncId: String,
) {
    override fun toString(): String = "PersistedAccountSwitch(redacted)"
}

/**
 * Credential validation and normalization are owned by the existing YouTube implementation and
 * injected here so the SDK never carries a second copy of the cookie rules.
 */
interface YouTubeAccountCredentialPolicy {
    fun hasLoginCookie(cookie: String?): Boolean

    fun hasCompleteLoginCookies(cookie: String?): Boolean

    fun normalizeAuthValue(value: String?): String?

    fun normalizeDataSyncId(value: String?): String?
}

/** The authenticated YouTube operations the orchestration needs, supplied by the app. */
interface YouTubeAccountBackend {
    fun setAuthState(
        cookie: String?,
        visitorData: String?,
        dataSyncId: String?,
    )

    suspend fun accountInfo(): Result<YouTubeAccountInfo>

    suspend fun accountDataSyncId(): Result<String?>

    fun setVisitorData(value: String)

    fun setDataSyncId(value: String)
}

/** Platform persistence seam; DataStore and Android Context stay in the application. */
interface YouTubeAccountStore {
    suspend fun persistLoginSession(session: PersistedLoginSession)

    suspend fun persistAccountSwitch(switch: PersistedAccountSwitch)

    suspend fun persistLoginContext(
        visitorData: String?,
        dataSyncId: String?,
    )

    suspend fun readAccountSnapshot(): YouTubeAccountSnapshot
}

/**
 * Keeps the saved-account list consistent with the DataSyncId resolved during a switch. The app
 * applies this inside its single DataStore edit so the read/repair/write stays atomic.
 */
fun repairedSavedAccounts(
    accounts: List<SavedAccount>,
    accountId: String,
    resolvedDataSyncId: String,
): List<SavedAccount> =
    accounts.map { account ->
        if (account.id == accountId && account.dataSyncId != resolvedDataSyncId) {
            account.copy(dataSyncId = resolvedDataSyncId)
        } else {
            account
        }
    }
