/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.account.login.YouTubeAccountCoordinator
import moe.rukamori.archivetune.account.login.YouTubeAccountSnapshot
import moe.rukamori.archivetune.account.login.YouTubeLoginSession
import moe.rukamori.archivetune.utils.SavedAccount
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-facing facade over the headless SDK coordinator. It only selects the IO dispatcher and keeps
 * the existing use-case signatures; all verification, resolution, persistence and account-switch
 * orchestration lives in [YouTubeAccountCoordinator].
 */
@Singleton
class YouTubeLoginRepository
    @Inject
    constructor(
        private val coordinator: YouTubeAccountCoordinator,
    ) {
        suspend fun completeLogin(
            cookie: String,
            visitorData: String?,
            dataSyncId: String?,
        ): Result<YouTubeLoginSession> =
            withContext(Dispatchers.IO) {
                coordinator.completeLogin(
                    cookie = cookie,
                    visitorData = visitorData,
                    dataSyncId = dataSyncId,
                )
            }

        suspend fun switchSavedAccount(account: SavedAccount): Result<YouTubeAccountSnapshot> =
            withContext(Dispatchers.IO) {
                coordinator.switchSavedAccount(account)
            }

        suspend fun saveLoginContext(
            visitorData: String? = null,
            dataSyncId: String? = null,
        ) {
            withContext(Dispatchers.IO) {
                coordinator.saveLoginContext(
                    visitorData = visitorData,
                    dataSyncId = dataSyncId,
                )
            }
        }
    }

class CompleteYouTubeLoginUseCase
    @Inject
    constructor(
        private val repository: YouTubeLoginRepository,
    ) {
        suspend operator fun invoke(
            cookie: String,
            visitorData: String?,
            dataSyncId: String?,
        ): Result<YouTubeLoginSession> =
            repository.completeLogin(
                cookie = cookie,
                visitorData = visitorData,
                dataSyncId = dataSyncId,
            )
    }

class SwitchSavedYouTubeAccountUseCase
    @Inject
    constructor(
        private val repository: YouTubeLoginRepository,
    ) {
        suspend operator fun invoke(account: SavedAccount): Result<YouTubeAccountSnapshot> =
            repository.switchSavedAccount(account)
    }

class UpdateYouTubeLoginContextUseCase
    @Inject
    constructor(
        private val repository: YouTubeLoginRepository,
    ) {
        suspend operator fun invoke(
            visitorData: String? = null,
            dataSyncId: String? = null,
        ) {
            repository.saveLoginContext(
                visitorData = visitorData,
                dataSyncId = dataSyncId,
            )
        }
    }
