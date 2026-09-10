/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.home

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.innertube.models.YTItem

sealed interface HomeRecommendationFeedbackNotice {
    data class RemoteFailure(
        val item: YTItem,
    ) : HomeRecommendationFeedbackNotice
}

/**
 * Keeps Home recommendation feedback truthful: remote cards are hidden only after YouTube accepts
 * the feedback, failures remain retryable, and an older request cannot replace a newer notice.
 */
internal class HomeRecommendationFeedback(
    private val sendRemoteFeedback: suspend (YTItem) -> Result<Unit>,
    private val hideRemoteItem: (YTItem) -> Unit,
) {
    private val generation = AtomicLong(0L)
    private val _notice = MutableStateFlow<HomeRecommendationFeedbackNotice?>(null)

    val notice: StateFlow<HomeRecommendationFeedbackNotice?> = _notice.asStateFlow()

    suspend fun dismissRemote(item: YTItem): Result<Unit> {
        if (item.feedbackToken.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("This recommendation has no feedback token"))
        }
        val requestGeneration = generation.incrementAndGet()
        val result = sendRemoteFeedback(item)
        result.onSuccess {
            hideRemoteItem(item)
            if (generation.get() == requestGeneration) _notice.value = null
        }.onFailure {
            if (generation.get() == requestGeneration) {
                _notice.value = HomeRecommendationFeedbackNotice.RemoteFailure(item)
            }
        }
        return result
    }

    suspend fun retryRemoteFailure(): Result<Unit>? {
        val failed = _notice.value as? HomeRecommendationFeedbackNotice.RemoteFailure ?: return null
        _notice.value = null
        return dismissRemote(failed.item)
    }

    fun clearNotice() {
        generation.incrementAndGet()
        _notice.value = null
    }
}

internal fun homeRecommendationKey(item: YTItem): String = "${item::class.simpleName}:${item.id}"
