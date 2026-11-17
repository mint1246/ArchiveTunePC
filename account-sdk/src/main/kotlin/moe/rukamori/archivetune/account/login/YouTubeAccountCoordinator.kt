/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.account.login

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.utils.SavedAccount

/**
 * Headless YouTube login/account orchestration. It performs the exact verify -> account lookup ->
 * DataSyncId resolution -> persistence sequence used by the Android app, with injected seams for
 * the existing credential rules, YouTube backend and platform storage. No Android or DataStore
 * types are referenced here; dispatch on the platform executor is the caller's responsibility.
 */
class YouTubeAccountCoordinator(
    private val credentialPolicy: YouTubeAccountCredentialPolicy,
    private val backend: YouTubeAccountBackend,
    private val store: YouTubeAccountStore,
) {
    suspend fun completeLogin(
        cookie: String,
        visitorData: String?,
        dataSyncId: String?,
    ): Result<YouTubeLoginSession> =
        runCatchingPreservingCancellation {
            val normalizedCookie = cookie.trim()
            check(credentialPolicy.hasLoginCookie(normalizedCookie)) { "YouTube login cookie is missing" }
            check(credentialPolicy.hasCompleteLoginCookies(normalizedCookie)) { "YouTube login cookies are incomplete" }

            val normalizedVisitorData = credentialPolicy.normalizeAuthValue(visitorData)
            val candidateDataSyncId = credentialPolicy.normalizeDataSyncId(dataSyncId)

            // Verification runs without a DataSyncId so a stale candidate cannot affect the
            // account lookup; the resolved id is applied before any persistence happens.
            backend.setAuthState(normalizedCookie, normalizedVisitorData, null)
            val accountInfo = backend.accountInfo().getOrThrow()

            val resolvedDataSyncId = resolveRequiredDataSyncId(candidateDataSyncId)
            backend.setAuthState(normalizedCookie, normalizedVisitorData, resolvedDataSyncId)

            store.persistLoginSession(
                PersistedLoginSession(
                    cookie = normalizedCookie,
                    visitorData = normalizedVisitorData,
                    dataSyncId = resolvedDataSyncId,
                    accountName = accountInfo.name,
                    accountEmail = accountInfo.email,
                    accountChannelHandle = accountInfo.channelHandle,
                ),
            )

            YouTubeLoginSession(
                visitorData = normalizedVisitorData,
                dataSyncId = resolvedDataSyncId,
                accountInfo = accountInfo,
            )
        }

    suspend fun switchSavedAccount(account: SavedAccount): Result<YouTubeAccountSnapshot> =
        runCatchingPreservingCancellation {
            check(credentialPolicy.hasLoginCookie(account.innerTubeCookie)) { "Saved account login cookie is missing" }
            check(credentialPolicy.hasCompleteLoginCookies(account.innerTubeCookie)) {
                "Saved account login cookies are incomplete"
            }

            val normalizedVisitorData = credentialPolicy.normalizeAuthValue(account.visitorData)
            val candidateDataSyncId = credentialPolicy.normalizeDataSyncId(account.dataSyncId)

            backend.setAuthState(account.innerTubeCookie, normalizedVisitorData, candidateDataSyncId)
            val resolvedDataSyncId = resolveRequiredDataSyncId(candidateDataSyncId)
            backend.setAuthState(account.innerTubeCookie, normalizedVisitorData, resolvedDataSyncId)

            store.persistAccountSwitch(
                PersistedAccountSwitch(
                    account = account,
                    resolvedDataSyncId = resolvedDataSyncId,
                ),
            )
            store.readAccountSnapshot()
        }

    suspend fun saveLoginContext(
        visitorData: String?,
        dataSyncId: String?,
    ) {
        val normalizedVisitorData = credentialPolicy.normalizeAuthValue(visitorData)
        val normalizedDataSyncId = credentialPolicy.normalizeDataSyncId(dataSyncId)
        if (normalizedVisitorData == null && normalizedDataSyncId == null) return

        store.persistLoginContext(normalizedVisitorData, normalizedDataSyncId)
        normalizedVisitorData?.let(backend::setVisitorData)
        normalizedDataSyncId?.let(backend::setDataSyncId)
    }

    private suspend fun resolveRequiredDataSyncId(candidate: String?): String {
        val networkDataSyncId =
            backend
                .accountDataSyncId()
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?.let(credentialPolicy::normalizeDataSyncId)

        return networkDataSyncId
            ?: candidate
            ?: throw MissingYouTubeDataSyncIdException()
    }
}

private suspend inline fun <T> runCatchingPreservingCancellation(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        Result.failure(throwable)
    }
