/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

/**
 * Authoritative clicked-item queue selection policy.
 *
 * Search and artist song clicks request an "Up next" queue for an explicit video ID, while
 * YouTube's server-reported `selected` / `currentIndex` can point at a different entry depending
 * on account, locale or experiment. The item the user explicitly clicked always wins:
 * - if the returned queue contains [clickedId], start at its first matching index;
 * - if the returned queue is empty or omits [clickedId], play [clickedItem] alone rather than
 *   falling back to index 0 or a server-selected track.
 */
data class ClickedItemQueueSelection<out T>(
    val items: List<T>,
    val startIndex: Int,
    val title: String,
    val outcome: Outcome,
) {
    enum class Outcome {
        MATCHED,
        EMPTY_QUEUE_FALLBACK,
        MISSING_TARGET_FALLBACK,
        FAILURE_FALLBACK,
    }
}

fun requestedQueueItemIndex(
    itemIds: List<String>,
    requestedId: String,
): Int? = itemIds.indexOf(requestedId).takeIf { it >= 0 }

fun <T> selectClickedItemQueue(
    clickedItem: T,
    clickedId: String,
    returnedItems: List<T>,
    itemId: (T) -> String,
    returnedTitle: String? = null,
    defaultTitle: String = "Up next",
): ClickedItemQueueSelection<T> {
    if (returnedItems.isEmpty()) {
        return ClickedItemQueueSelection(
            items = listOf(clickedItem),
            startIndex = 0,
            title = defaultTitle,
            outcome = ClickedItemQueueSelection.Outcome.EMPTY_QUEUE_FALLBACK,
        )
    }

    val matchedIndex = requestedQueueItemIndex(returnedItems.map(itemId), clickedId)
    val resolvedTitle = returnedTitle ?: defaultTitle
    if (matchedIndex == null) {
        return ClickedItemQueueSelection(
            items = listOf(clickedItem),
            startIndex = 0,
            title = resolvedTitle,
            outcome = ClickedItemQueueSelection.Outcome.MISSING_TARGET_FALLBACK,
        )
    }

    return ClickedItemQueueSelection(
        items = returnedItems,
        startIndex = matchedIndex,
        title = resolvedTitle,
        outcome = ClickedItemQueueSelection.Outcome.MATCHED,
    )
}

fun <T> fallbackClickedItemQueue(
    clickedItem: T,
    defaultTitle: String = "Up next",
): ClickedItemQueueSelection<T> =
    ClickedItemQueueSelection(
        items = listOf(clickedItem),
        startIndex = 0,
        title = defaultTitle,
        outcome = ClickedItemQueueSelection.Outcome.FAILURE_FALLBACK,
    )
