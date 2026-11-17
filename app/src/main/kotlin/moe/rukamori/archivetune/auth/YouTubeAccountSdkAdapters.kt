/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.account.login.PersistedAccountSwitch
import moe.rukamori.archivetune.account.login.PersistedLoginSession
import moe.rukamori.archivetune.account.login.YouTubeAccountBackend
import moe.rukamori.archivetune.account.login.YouTubeAccountCredentialPolicy
import moe.rukamori.archivetune.account.login.YouTubeAccountInfo
import moe.rukamori.archivetune.account.login.YouTubeAccountSnapshot
import moe.rukamori.archivetune.account.login.YouTubeAccountStore
import moe.rukamori.archivetune.account.login.repairedSavedAccounts
import moe.rukamori.archivetune.constants.AccountChannelHandleKey
import moe.rukamori.archivetune.constants.AccountEmailKey
import moe.rukamori.archivetune.constants.AccountNameKey
import moe.rukamori.archivetune.constants.DataSyncIdKey
import moe.rukamori.archivetune.constants.InnerTubeCookieKey
import moe.rukamori.archivetune.constants.PoTokenGvsKey
import moe.rukamori.archivetune.constants.PoTokenKey
import moe.rukamori.archivetune.constants.PoTokenPlayerKey
import moe.rukamori.archivetune.constants.SavedAccountsKey
import moe.rukamori.archivetune.constants.SelectedYtmPlaylistsKey
import moe.rukamori.archivetune.constants.VisitorDataKey
import moe.rukamori.archivetune.constants.WebClientPoTokenEnabledKey
import moe.rukamori.archivetune.constants.YtmSyncKey
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.utils.hasCompleteYouTubeLoginCookies
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.decodeSavedAccounts
import moe.rukamori.archivetune.utils.encodeSavedAccounts
import moe.rukamori.archivetune.utils.toPlaybackAuthState
import javax.inject.Inject
import javax.inject.Singleton

internal fun String?.normalizeAuthValue(): String? {
    val trimmed = this?.trim()
    return trimmed?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}

/** Bridges the SDK to the existing core cookie rules and PlaybackAuthState normalization. */
class CoreYouTubeAccountCredentialPolicy @Inject constructor() : YouTubeAccountCredentialPolicy {
    override fun hasLoginCookie(cookie: String?): Boolean = hasYouTubeLoginCookie(cookie)

    override fun hasCompleteLoginCookies(cookie: String?): Boolean = hasCompleteYouTubeLoginCookies(cookie)

    override fun normalizeAuthValue(value: String?): String? = value.normalizeAuthValue()

    override fun normalizeDataSyncId(value: String?): String? =
        PlaybackAuthState(dataSyncId = value).normalized().dataSyncId
}

/** Bridges the SDK to the existing global YouTube backend. The app owns this global's lifecycle. */
class CoreYouTubeAccountBackend @Inject constructor() : YouTubeAccountBackend {
    override fun setAuthState(
        cookie: String?,
        visitorData: String?,
        dataSyncId: String?,
    ) {
        YouTube.authState =
            PlaybackAuthState(
                cookie = cookie,
                visitorData = visitorData,
                dataSyncId = dataSyncId,
            ).normalized()
    }

    override suspend fun accountInfo(): Result<YouTubeAccountInfo> =
        YouTube.accountInfo().map { info ->
            YouTubeAccountInfo(
                name = info.name,
                email = info.email.orEmpty(),
                channelHandle = info.channelHandle.orEmpty(),
            )
        }

    override suspend fun accountDataSyncId(): Result<String?> = YouTube.accountDataSyncId().map { it }

    override fun setVisitorData(value: String) {
        YouTube.visitorData = value
    }

    override fun setDataSyncId(value: String) {
        YouTube.dataSyncId = value
    }
}

/** The only component that touches DataStore for login/account state. */
@Singleton
class AndroidYouTubeAccountStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : YouTubeAccountStore {
        override suspend fun persistLoginSession(session: PersistedLoginSession): Unit =
            withContext(Dispatchers.IO) {
                context.dataStore.edit { preferences ->
                    preferences[InnerTubeCookieKey] = session.cookie
                    session.visitorData
                        ?.let { preferences[VisitorDataKey] = it }
                        ?: preferences.remove(VisitorDataKey)
                    preferences[DataSyncIdKey] = session.dataSyncId
                    preferences[AccountNameKey] = session.accountName
                    preferences[AccountEmailKey] = session.accountEmail
                    preferences[AccountChannelHandleKey] = session.accountChannelHandle
                    preferences.remove(PoTokenKey)
                    preferences.remove(PoTokenGvsKey)
                    preferences.remove(PoTokenPlayerKey)
                    preferences[WebClientPoTokenEnabledKey] = false
                }
            }

        override suspend fun persistAccountSwitch(switch: PersistedAccountSwitch): Unit =
            withContext(Dispatchers.IO) {
                val account = switch.account
                context.dataStore.edit { preferences ->
                    preferences[InnerTubeCookieKey] = account.innerTubeCookie
                    account.visitorData
                        .normalizeAuthValue()
                        ?.let { preferences[VisitorDataKey] = it }
                        ?: preferences.remove(VisitorDataKey)
                    preferences[DataSyncIdKey] = switch.resolvedDataSyncId
                    preferences[AccountNameKey] = account.name
                    preferences[AccountEmailKey] = account.email
                    preferences[AccountChannelHandleKey] = account.channelHandle
                    preferences.remove(PoTokenKey)
                    preferences.remove(PoTokenGvsKey)
                    preferences.remove(PoTokenPlayerKey)
                    preferences[WebClientPoTokenEnabledKey] = false
                    preferences[YtmSyncKey] = account.ytmSync
                    preferences[SelectedYtmPlaylistsKey] = account.selectedYtmPlaylists

                    val savedAccounts = decodeSavedAccounts(preferences[SavedAccountsKey].orEmpty())
                    val repairedAccounts =
                        repairedSavedAccounts(savedAccounts, account.id, switch.resolvedDataSyncId)
                    if (repairedAccounts != savedAccounts) {
                        preferences[SavedAccountsKey] = encodeSavedAccounts(repairedAccounts)
                    }
                }
            }

        override suspend fun persistLoginContext(
            visitorData: String?,
            dataSyncId: String?,
        ): Unit = withContext(Dispatchers.IO) {
            context.dataStore.edit { preferences ->
                visitorData?.let { preferences[VisitorDataKey] = it }
                dataSyncId?.let { preferences[DataSyncIdKey] = it }
            }
        }

        override suspend fun readAccountSnapshot(): YouTubeAccountSnapshot =
            withContext(Dispatchers.IO) {
                context.dataStore.data
                    .first()
                    .toPlaybackAuthState()
                    .let { authState ->
                        YouTubeAccountSnapshot(
                            cookie = authState.cookie,
                            visitorData = authState.visitorData,
                            dataSyncId = authState.dataSyncId,
                        )
                    }
            }
    }

/** Lets existing callers keep asking a resolved snapshot whether a login cookie is present. */
val YouTubeAccountSnapshot.hasLoginCookie: Boolean
    get() = hasYouTubeLoginCookie(cookie)
