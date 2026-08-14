/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class VerifiedDesktopSession(
    val authState: PlaybackAuthState,
    val account: DesktopAccount,
)

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
) : DesktopSessionProvider {
    private var active: StoredDesktopSession? = null
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
        return importAndValidate(YouTubeSessionImport(cookieHeader = parsed.cookieHeader)).asCookieJsonResult()
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
        active = stored
        _state.value = stateFor(stored, restored = true)
        return _state.value
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

        val verified = verifier.verify(candidate).getOrElse {
            return SessionImportResult.Rejected(SessionImportFailure.VALIDATION_FAILED)
        }
        val stored =
            StoredDesktopSession(
                authState = verified.authState.normalized(),
                account = verified.account,
                expiresAtEpochMillis = input.expiresAtEpochMillis,
            )
        try {
            secureStore.write(StoredDesktopSessionCodec.encode(stored))
        } catch (_: SecureStoreUnavailableException) {
            return SessionImportResult.Rejected(SessionImportFailure.SECURE_STORAGE_UNAVAILABLE)
        }
        active = stored
        _state.value = DesktopAccountState.SignedIn(stored.account, restoredFromSecureStore = false)
        return SessionImportResult.Success(stored.account)
    }

    fun logout(): Result<Unit> =
        try {
            secureStore.delete()
            active = null
            _state.value = DesktopAccountState.SignedOut
            Result.success(Unit)
        } catch (failure: SecureStoreUnavailableException) {
            Result.failure(failure)
        }

    override fun currentAuthState(): PlaybackAuthState =
        active
            ?.takeUnless { it.isExpired(clock.millis()) }
            ?.authState
            ?: PlaybackAuthState.EMPTY

    private fun stateFor(
        stored: StoredDesktopSession?,
        restored: Boolean,
    ): DesktopAccountState =
        when {
            stored == null -> DesktopAccountState.SignedOut
            stored.isExpired(clock.millis()) -> DesktopAccountState.Expired(stored.account)
            else -> DesktopAccountState.SignedIn(stored.account, restoredFromSecureStore = restored)
        }

    private companion object {
        val GOOGLE_LOGIN_URL = URI("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
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
            signedIn = !isExpired(now),
            sessionFingerprint = authState.fingerprint,
            hasVisitorData = !authState.visitorData.isNullOrBlank(),
            hasDataSyncId = !authState.dataSyncId.isNullOrBlank(),
            isExpired = isExpired(now),
        )
}
