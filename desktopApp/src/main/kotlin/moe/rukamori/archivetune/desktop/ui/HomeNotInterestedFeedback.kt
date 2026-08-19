/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.ui

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.innertube.models.YTItem

internal sealed interface HomeFeedbackNotice {
    val message: String

    data class RemoteFailure(
        val item: YTItem,
        override val message: String,
    ) : HomeFeedbackNotice

    data class LocalDismissed(
        val item: YTItem,
        override val message: String = "Hidden from local recommendations.",
    ) : HomeFeedbackNotice
}

/** Small UI state holder that makes Home recommendation feedback truthful and reversible. */
internal class HomeNotInterestedFeedback(
    private val sendRemoteFeedback: suspend (YTItem) -> Result<Unit>,
    private val hideRemoteItem: (YTItem) -> Unit,
    private val dismissLocalItem: (YTItem) -> Unit,
    private val restoreLocalItem: (YTItem) -> Unit,
) {
    private val generation = AtomicLong(0L)
    private val _notice = MutableStateFlow<HomeFeedbackNotice?>(null)

    val notice: StateFlow<HomeFeedbackNotice?> = _notice.asStateFlow()

    suspend fun dismissRemote(item: YTItem): Result<Unit> {
        if (item.feedbackToken.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("This recommendation cannot be dismissed remotely."))
        }
        val requestGeneration = generation.incrementAndGet()
        val result = sendRemoteFeedback(item)
        result.onSuccess {
            // Remote items are only hidden after YouTube confirms the feedback.
            hideRemoteItem(item)
            if (generation.get() == requestGeneration) _notice.value = null
        }.onFailure { failure ->
            if (generation.get() == requestGeneration) {
                _notice.value = HomeFeedbackNotice.RemoteFailure(
                    item = item,
                    message = failure.message?.takeIf(String::isNotBlank)
                        ?.let { "Could not mark this recommendation as not interested: $it" }
                        ?: "Could not mark this recommendation as not interested.",
                )
            }
        }
        return result
    }

    suspend fun retryRemoteFailure(): Result<Unit>? {
        val failure = _notice.value as? HomeFeedbackNotice.RemoteFailure ?: return null
        return dismissRemote(failure.item)
    }

    fun dismissLocal(item: YTItem) {
        // Any newer user action owns the notice; late failures from older remote requests stay stale.
        generation.incrementAndGet()
        dismissLocalItem(item)
        _notice.value = HomeFeedbackNotice.LocalDismissed(
            item = item,
            message = "Hidden “${item.title}” from local recommendations.",
        )
    }

    fun undoLocalDismissal(): Boolean {
        val dismissal = _notice.value as? HomeFeedbackNotice.LocalDismissed ?: return false
        generation.incrementAndGet()
        restoreLocalItem(dismissal.item)
        _notice.value = null
        return true
    }

    fun clearNotice() {
        generation.incrementAndGet()
        _notice.value = null
    }
}
