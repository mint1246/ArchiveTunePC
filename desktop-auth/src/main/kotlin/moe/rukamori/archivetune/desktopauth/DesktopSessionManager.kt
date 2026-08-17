/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.DesktopSessionProvider
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.models.AccountInfo
import java.awt.Desktop
import java.net.URI
import java.time.Clock

/**
 * Desktop account lifecycle. Secret cookie, visitor and token values never appear in this state.
 */
sealed interface DesktopAccountState {
    data object SignedOut : DesktopAccountState

    data class SignedIn(
        val account: DesktopAccount,
        val restoredFromSecureStore: Boolean,
    ) : DesktopAccountState

    data class Expired(
        val account: DesktopAccount?,
    ) : DesktopAccountState

    data class Unavailable(
        val reason: DesktopAuthIssue,
    ) : DesktopAccountState
}

data class DesktopAccount(
    val name: String,
    val email: String?,
    val channelHandle: String?,
    /** Account-avatar URL; never include this in diagnostics because it can contain signed query data. */
    val avatarUrl: String? = null,
) {
    override fun toString(): String =
        "DesktopAccount(name=${name.take(80)}, hasEmail=${!email.isNullOrBlank()}, hasChannel=${!channelHandle.isNullOrBlank()}, hasAvatar=${!avatarUrl.isNullOrBlank()})"
}

enum class DesktopAuthIssue {
    SECURE_STORAGE_UNAVAILABLE,
    CORRUPT_SESSION,
}

/** A value supplied by the desktop UI after the user completes Google sign-in in their browser. */
data class YouTubeSessionImport(
    val cookieHeader: String,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val poToken: String? = null,
    val poTokenGvs: String? = null,
    val poTokenPlayer: String? = null,
    val webClientPoTokenEnabled: Boolean = false,
    /** Optional known expiry. Unknown is deliberately represented as null, never guessed. */
    val expiresAtEpochMillis: Long? = null,
)

sealed interface SessionImportResult {
    data class Success(val account: DesktopAccount) : SessionImportResult
    data class Rejected(val reason: SessionImportFailure) : SessionImportResult
}

enum class SessionImportFailure {
    MISSING_LOGIN_COOKIE,
    EXPIRED,
    VALIDATION_FAILED,
    SECURE_STORAGE_UNAVAILABLE,
}

/**
 * There is no public Google OAuth client/callback in ArchiveTune. The Android app owns an embedded
 * WebView and receives its cookies directly; a system browser cannot lawfully expose those cookies
 * back to a desktop application. This handoff opens the genuine Google/YouTube Music sign-in page
 * and explicitly tells the UI to collect a user-provided session export for validation/import.
 */
data class BrowserLoginHandoff(
    val url: URI,
    val requiresManualSessionImport: Boolean = true,
)

fun interface SystemBrowserLauncher {
    fun open(uri: URI): Result<Unit>
}

object DesktopSystemBrowserLauncher : SystemBrowserLauncher {
    override fun open(uri: URI): Result<Unit> =
        runCatching {
            check(Desktop.isDesktopSupported()) { "System browser is unavailable" }
            Desktop.getDesktop().browse(uri)
        }
}

/** Network verifier is constructor-injected so session lifecycle tests remain network-free. */
fun interface DesktopSessionVerifier {
    suspend fun verify(candidate: PlaybackAuthState): Result<VerifiedDesktopSession>
}

/**
 * Optional source for obtaining a fresh browser session. Desktop browsers do not expose cookies
 * through the normal sign-in redirect, so the application supplies this only when it has a
 * user-authorized cookie bridge (for example, a browser extension or an explicit re-export).
 */
fun interface DesktopSessionCookieRefresher {
    suspend fun refresh(previous: YouTubeSessionImport): Result<YouTubeSessionImport>
}

data class VerifiedDesktopSession(
    val authState: PlaybackAuthState,
    val account: DesktopAccount,
)

private sealed interface VerificationResult {
    data class Verified(val session: VerifiedDesktopSession) : VerificationResult
    data object Rejected : VerificationResult
}

class ArchiveTuneSessionVerifier(
    private val backend: ArchiveTuneJvmClient,
) : DesktopSessionVerifier {
    override suspend fun verify(candidate: PlaybackAuthState): Result<VerifiedDesktopSession> =
        backend.verifyAccountSession(candidate).map { verified ->
            VerifiedDesktopSession(authState = verified.authState, account = verified.account.toDesktopAccount())
        }
}

internal fun AccountInfo.toDesktopAccount(): DesktopAccount =
    DesktopAccount(
        name = name,
        email = email,
        channelHandle = channelHandle,
        avatarUrl = thumbnailUrl?.takeIf { it.startsWith("https://", ignoreCase = true) },
    )

/**
 * Owns an imported ArchiveTune/YouTube Music session. It never acquires browser cookies, logs
 * credentials, or changes [moe.rukamori.archivetune.innertube.NetworkGatekeeper].
 */
class DesktopSessionManager(
    private val secureStore: SecureSessionStore,
    private val verifier: DesktopSessionVerifier,
    private val browserLauncher: SystemBrowserLauncher = DesktopSystemBrowserLauncher,
    private val clock: Clock = Clock.systemUTC(),
    private val cookieRefresher: DesktopSessionCookieRefresher? = null,
    private val refreshIntervalMillis: Long = DEFAULT_REFRESH_INTERVAL_MILLIS,
    private val refreshLeadMillis: Long = DEFAULT_REFRESH_LEAD_MILLIS,
) : DesktopSessionProvider {
    private val refreshMutex = Mutex()
    private val stateLock = Any()
    @Volatile
    private var active: StoredDesktopSession? = null
    @Volatile
    private var knownExpired = false
    @Volatile
    private var lastValidationAtEpochMillis: Long = Long.MIN_VALUE
    private val _state = MutableStateFlow<DesktopAccountState>(DesktopAccountState.SignedOut)
    val state: StateFlow<DesktopAccountState> = _state.asStateFlow()

    /** A redacted profile suitable for diagnostics. */
    val diagnostic: DesktopSessionDiagnostic
        get() = active?.toDiagnostic(clock.millis()) ?: DesktopSessionDiagnostic.signedOut()

    /** Opens the normal, user-controlled Google sign-in page. No callback is claimed or fabricated. */
    fun beginBrowserLogin(): Result<BrowserLoginHandoff> {
        val handoff = BrowserLoginHandoff(GOOGLE_LOGIN_URL)
        return browserLauncher.open(handoff.url).map { handoff }
    }

    /** Parses a browser cookie export without retaining its raw JSON or exposing cookie values. */
    fun inspectCookieJson(rawJson: String): YouTubeCookieJsonInspection =
        YouTubeCookieJsonImporter.inspect(rawJson, clock.millis())

    /**
     * Converts a supported browser export directly into a validated session. Visitor/DataSync/PO
     * values remain absent: the backend derives account context only after cookie validation.
     */
    suspend fun importCookieJson(rawJson: String): CookieJsonSessionImportResult {
        val parsed =
            when (val result = YouTubeCookieJsonImporter.parse(rawJson, clock.millis())) {
                is YouTubeCookieJsonImportResult.Ready -> result.parsed
                is YouTubeCookieJsonImportResult.Rejected -> return CookieJsonSessionImportResult.Rejected(result.reason)
            }
        return importAndValidate(
            YouTubeSessionImport(
                cookieHeader = parsed.cookieHeader,
                expiresAtEpochMillis = parsed.expiresAtEpochMillis,
            ),
        ).asCookieJsonResult()
    }

    /** Restores a DPAPI-protected session if one exists. It is not treated as network-validated. */
    fun restore(): DesktopAccountState {
        val stored =
            try {
                secureStore.read()?.let(StoredDesktopSessionCodec::decode)
            } catch (_: SecureStoreUnavailableException) {
                _state.value = DesktopAccountState.Unavailable(DesktopAuthIssue.SECURE_STORAGE_UNAVAILABLE)
                return _state.value
            } catch (_: Exception) {
                _state.value = DesktopAccountState.Unavailable(DesktopAuthIssue.CORRUPT_SESSION)
                return _state.value
            }
        synchronized(stateLock) {
            active = stored
            knownExpired = false
            lastValidationAtEpochMillis = Long.MIN_VALUE
            _state.value = stateFor(stored, restored = true)
        }
        return _state.value
    }

    /**
     * Revalidates a restored, non-expired DPAPI session in the background. A failed refresh is
     * deliberately non-destructive: offline users retain their last verified session and can keep
     * using it until the next successful validation or explicit logout.
     */
    suspend fun refreshRestoredSession(): Boolean {
        return refreshIfNeeded(force = true)
    }

    /** Called by [ArchiveTuneJvmClient] before each request. Concurrent requests share one check. */
    override suspend fun refreshIfNeeded() {
        refreshIfNeeded(force = false)
    }

    private suspend fun refreshIfNeeded(force: Boolean): Boolean =
        refreshMutex.withLock {
            val current = active ?: return@withLock false
            if (knownExpired && !force) return@withLock false
            val now = clock.millis()
            val expirySoon = current.expiresAtEpochMillis?.let { it - now <= refreshLeadMillis } == true
            val intervalElapsed = lastValidationAtEpochMillis == Long.MIN_VALUE || now - lastValidationAtEpochMillis >= refreshIntervalMillis
            if (!force && !expirySoon && !intervalElapsed) return@withLock true

            var replacementAttempted = false
            // A known near-term browser-cookie expiry is precisely when a user-authorized cookie
            // bridge should be preferred. A successful verifier call only proves that the old
            // cookie is valid now; it cannot extend its lifetime.
            if (expirySoon && cookieRefresher != null) {
                replacementAttempted = true
                val replacement = requestCookieReplacement(current, now)
                if (replacement != null && validateAndPersist(current, replacement)) {
                    return@withLock true
                }
            }

            // A browser-side expiry hint is not authoritative: Google can accept a cookie long
            // after its browser expiration, exactly as the Android app relies on. Re-verify against
            // the network as a best-effort refresh, but do not discard a persisted session merely
            // because this probe rejects it.
            val verification = verifySafely(current.authState)
            if (verification is VerificationResult.Verified && persistVerified(current, verification.session, current.expiresAtEpochMillis)) {
                return@withLock true
            }

            if (!replacementAttempted && cookieRefresher != null) {
                val replacement = requestCookieReplacement(current, now)
                if (replacement != null && validateAndPersist(current, replacement)) {
                    return@withLock true
                }
            }

            // The verifier is a best-effort account-info probe, not an authoritative cookie
            // revocation check. YouTube can reject that probe while the exact same SAPISID cookie
            // still works for browse/playback requests. Treating it as a logout signal made the
            // desktop client erase its usable auth state every ~15 minutes (and on every startup).
            // Imports are still validated before persistence; once persisted, only explicit logout
            // or a successfully validated replacement may remove/replace the local session.
            synchronized(stateLock) {
                if (active?.authState?.fingerprint == current.authState.fingerprint) {
                    lastValidationAtEpochMillis = now
                }
            }
            false
        }

    suspend fun importAndValidate(input: YouTubeSessionImport): SessionImportResult {
        val candidate =
            PlaybackAuthState(
                cookie = input.cookieHeader,
                visitorData = input.visitorData,
                dataSyncId = input.dataSyncId,
                poToken = input.poToken,
                poTokenGvs = input.poTokenGvs,
                poTokenPlayer = input.poTokenPlayer,
                webClientPoTokenEnabled = input.webClientPoTokenEnabled,
            ).normalized()
        if (!candidate.hasLoginCookie) return SessionImportResult.Rejected(SessionImportFailure.MISSING_LOGIN_COOKIE)
        if (input.expiresAtEpochMillis?.let { it <= clock.millis() } == true) {
            return SessionImportResult.Rejected(SessionImportFailure.EXPIRED)
        }

        val verification = verifySafely(candidate)
        val verified = (verification as? VerificationResult.Verified)?.session
            ?: return SessionImportResult.Rejected(SessionImportFailure.VALIDATION_FAILED)
        val verifiedAuthState = verified.authState.normalized()
        if (!verifiedAuthState.hasLoginCookie) {
            return SessionImportResult.Rejected(SessionImportFailure.VALIDATION_FAILED)
        }
        val stored =
            StoredDesktopSession(
                authState = verifiedAuthState,
                account = verified.account,
                expiresAtEpochMillis = input.expiresAtEpochMillis,
            )
        synchronized(stateLock) {
            try {
                secureStore.write(StoredDesktopSessionCodec.encode(stored))
            } catch (_: SecureStoreUnavailableException) {
                return SessionImportResult.Rejected(SessionImportFailure.SECURE_STORAGE_UNAVAILABLE)
            }
            active = stored
            knownExpired = false
            lastValidationAtEpochMillis = clock.millis()
            _state.value = DesktopAccountState.SignedIn(stored.account, restoredFromSecureStore = false)
        }
        return SessionImportResult.Success(stored.account)
    }

    fun logout(): Result<Unit> =
        try {
            synchronized(stateLock) {
                secureStore.delete()
                active = null
                knownExpired = false
                lastValidationAtEpochMillis = Long.MIN_VALUE
                _state.value = DesktopAccountState.SignedOut
            }
            Result.success(Unit)
        } catch (failure: SecureStoreUnavailableException) {
            Result.failure(failure)
        }

    override fun currentAuthState(): PlaybackAuthState =
        active
            ?.takeUnless { knownExpired }
            ?.authState
            ?: PlaybackAuthState.EMPTY

    private fun stateFor(
        stored: StoredDesktopSession?,
        restored: Boolean,
    ): DesktopAccountState =
        when {
            stored == null -> DesktopAccountState.SignedOut
            else -> DesktopAccountState.SignedIn(stored.account, restoredFromSecureStore = restored)
        }

    private suspend fun validateAndPersist(
        previous: StoredDesktopSession,
        input: YouTubeSessionImport,
    ): Boolean {
        val candidate = input.toAuthState()
        if (!candidate.hasLoginCookie) return false
        val verification = verifySafely(candidate)
        val verified = (verification as? VerificationResult.Verified)?.session ?: return false
        // A replacement with an unknown expiry deliberately clears the old expiry. Retaining the
        // previous timestamp would make a freshly imported cookie appear expired immediately.
        return persistVerified(previous, verified, input.expiresAtEpochMillis)
    }

    private suspend fun verifySafely(candidate: PlaybackAuthState): VerificationResult =
        try {
            verifier.verify(candidate).fold(
                onSuccess = { VerificationResult.Verified(it) },
                onFailure = { VerificationResult.Rejected },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            VerificationResult.Rejected
        }

    private suspend fun requestCookieReplacement(
        current: StoredDesktopSession,
        now: Long,
    ): YouTubeSessionImport? =
        try {
            cookieRefresher
                ?.refresh(current.toImport())
                ?.getOrNull()
                ?.takeUnless { it.expiresAtEpochMillis?.let { expiry -> expiry <= now } == true }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    private suspend fun persistVerified(
        previous: StoredDesktopSession,
        verified: VerifiedDesktopSession,
        expiresAtEpochMillis: Long?,
    ): Boolean {
        val verifiedAuthState = verified.authState.normalized()
        // Treat the verifier as a trust boundary: a malformed success must never replace a
        // usable session with an anonymous/invalid credential set.
        if (!verifiedAuthState.hasLoginCookie) return false
        val refreshed =
            previous.copy(
                authState = verifiedAuthState,
                account = verified.account,
                expiresAtEpochMillis = expiresAtEpochMillis,
            )
        return try {
            synchronized(stateLock) {
                if (active?.authState?.fingerprint != previous.authState.fingerprint) return false
                secureStore.write(StoredDesktopSessionCodec.encode(refreshed))
                active = refreshed
                knownExpired = false
                lastValidationAtEpochMillis = clock.millis()
                _state.value = DesktopAccountState.SignedIn(refreshed.account, restoredFromSecureStore = false)
            }
            true
        } catch (_: SecureStoreUnavailableException) {
            false
        }
    }

    private fun StoredDesktopSession.toImport(): YouTubeSessionImport =
        YouTubeSessionImport(
            cookieHeader = authState.cookie.orEmpty(),
            visitorData = authState.visitorData,
            dataSyncId = authState.dataSyncId,
            poToken = authState.poToken,
            poTokenGvs = authState.poTokenGvs,
            poTokenPlayer = authState.poTokenPlayer,
            webClientPoTokenEnabled = authState.webClientPoTokenEnabled,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )

    private fun YouTubeSessionImport.toAuthState(): PlaybackAuthState =
        PlaybackAuthState(
            cookie = cookieHeader,
            visitorData = visitorData,
            dataSyncId = dataSyncId,
            poToken = poToken,
            poTokenGvs = poTokenGvs,
            poTokenPlayer = poTokenPlayer,
            webClientPoTokenEnabled = webClientPoTokenEnabled,
        ).normalized()

    private companion object {
        val GOOGLE_LOGIN_URL = URI("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
        const val DEFAULT_REFRESH_INTERVAL_MILLIS = 15 * 60 * 1_000L
        const val DEFAULT_REFRESH_LEAD_MILLIS = 5 * 60 * 1_000L
    }
}

sealed interface CookieJsonSessionImportResult {
    data class Success(val account: DesktopAccount) : CookieJsonSessionImportResult
    data class SessionRejected(val reason: SessionImportFailure) : CookieJsonSessionImportResult
    data class Rejected(val reason: YouTubeCookieJsonFailure) : CookieJsonSessionImportResult
}

private fun SessionImportResult.asCookieJsonResult(): CookieJsonSessionImportResult =
    when (this) {
        is SessionImportResult.Success -> CookieJsonSessionImportResult.Success(account)
        is SessionImportResult.Rejected -> CookieJsonSessionImportResult.SessionRejected(reason)
    }

data class DesktopSessionDiagnostic(
    val signedIn: Boolean,
    val sessionFingerprint: String?,
    val hasVisitorData: Boolean,
    val hasDataSyncId: Boolean,
    val isExpired: Boolean,
) {
    companion object {
        fun signedOut() = DesktopSessionDiagnostic(false, null, false, false, false)
    }
}

internal data class StoredDesktopSession(
    val authState: PlaybackAuthState,
    val account: DesktopAccount,
    val expiresAtEpochMillis: Long?,
) {
    fun isExpired(now: Long): Boolean = expiresAtEpochMillis?.let { it <= now } == true

    fun toDiagnostic(now: Long) =
        DesktopSessionDiagnostic(
            signedIn = authState.hasLoginCookie,
            sessionFingerprint = authState.fingerprint,
            hasVisitorData = !authState.visitorData.isNullOrBlank(),
            hasDataSyncId = !authState.dataSyncId.isNullOrBlank(),
            isExpired = isExpired(now),
        )
}
