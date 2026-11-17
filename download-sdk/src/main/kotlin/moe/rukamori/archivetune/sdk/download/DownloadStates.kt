/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.download

enum class DownloadState {
    QUEUED, DOWNLOADING, RESTARTING, COMPLETED, FAILED, REMOVING, STOPPED, UNKNOWN,
}

object DownloadStates {
    fun isProgressState(state: DownloadState): Boolean =
        state == DownloadState.QUEUED ||
            state == DownloadState.DOWNLOADING ||
            state == DownloadState.RESTARTING ||
            state == DownloadState.STOPPED ||
            state == DownloadState.FAILED

    fun isPausedState(state: DownloadState): Boolean = state == DownloadState.STOPPED || state == DownloadState.FAILED

    fun isTrackedState(state: DownloadState): Boolean = state == DownloadState.COMPLETED || isProgressState(state)

    fun isVisibleInProgress(state: DownloadState): Boolean = isProgressState(state)
}
