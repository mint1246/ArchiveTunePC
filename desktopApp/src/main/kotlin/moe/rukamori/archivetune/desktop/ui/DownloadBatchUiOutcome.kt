package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.desktop.platform.DownloadBatchResult

internal data class DownloadBatchUiOutcome(
    val navigateToDownloads: Boolean,
    val message: String?,
)

internal fun DownloadBatchResult.toUiOutcome(): DownloadBatchUiOutcome {
    if (requested <= 0) return DownloadBatchUiOutcome(navigateToDownloads = false, message = null)
    if (failures.isEmpty()) {
        return DownloadBatchUiOutcome(navigateToDownloads = succeeded > 0, message = null)
    }

    val failed = failures.size
    val summary = if (succeeded > 0) {
        "Queued $succeeded/$requested songs. $failed failed."
    } else {
        "Could not queue any of $requested songs. $failed failed."
    }
    val detail = failures.firstOrNull()?.let { " $it" }.orEmpty()
    return DownloadBatchUiOutcome(
        navigateToDownloads = succeeded > 0,
        message = summary + detail,
    )
}
