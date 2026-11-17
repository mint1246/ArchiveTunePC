/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

/**
 * Target slot for a completed queue drag-reorder operation, anchored by unique entry identity
 * ([After.itemUid]) rather than media ID so duplicate tracks remain disambiguated.
 */
sealed interface QueueDragDestination {
    data object Start : QueueDragDestination

    data class After(
        val itemUid: Any,
    ) : QueueDragDestination
}

/**
 * Minimal queue window representation needed to plan a drag-reorder commit:
 * - [uid]: unique entry identity (e.g. Media3 `Timeline.Window.uid`)
 * - [periodIndex]: logical media item index (e.g. Media3 `Timeline.Window.firstPeriodIndex`)
 */
data class QueueReorderWindow(
    val uid: Any,
    val periodIndex: Int,
)

sealed interface QueueReorderAction {
    data object NoOp : QueueReorderAction

    data class MoveLogical(
        val fromIndex: Int,
        val toIndex: Int,
    ) : QueueReorderAction

    data class UpdateShuffleOrder(
        val shuffledPeriodIndices: IntArray,
    ) : QueueReorderAction {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UpdateShuffleOrder) return false
            return shuffledPeriodIndices.contentEquals(other.shuffledPeriodIndices)
        }

        override fun hashCode(): Int = shuffledPeriodIndices.contentHashCode()
    }
}

object QueueReorderPlanner {
    fun resolveDestinationIndex(
        windowUids: List<Any>,
        sourceIndex: Int,
        destination: QueueDragDestination,
    ): Int? =
        when (destination) {
            QueueDragDestination.Start -> if (windowUids.isEmpty()) null else 0
            is QueueDragDestination.After -> {
                val anchorIndex = windowUids.indexOfFirst { it == destination.itemUid }
                when {
                    sourceIndex !in windowUids.indices -> null
                    anchorIndex == -1 -> null
                    sourceIndex < anchorIndex -> anchorIndex
                    else -> (anchorIndex + 1).coerceAtMost(windowUids.lastIndex)
                }
            }
        }

    fun planReorder(
        windows: List<QueueReorderWindow>,
        draggedItemUid: Any,
        destination: QueueDragDestination,
        shuffleEnabled: Boolean,
    ): QueueReorderAction {
        val sourceIndex = windows.indexOfFirst { it.uid == draggedItemUid }
        if (sourceIndex == -1) return QueueReorderAction.NoOp

        val destinationIndex =
            resolveDestinationIndex(
                windowUids = windows.map { it.uid },
                sourceIndex = sourceIndex,
                destination = destination,
            ) ?: return QueueReorderAction.NoOp

        if (sourceIndex == destinationIndex) return QueueReorderAction.NoOp

        return if (!shuffleEnabled) {
            QueueReorderAction.MoveLogical(
                fromIndex = sourceIndex,
                toIndex = destinationIndex,
            )
        } else {
            val reorderedPeriodIndices =
                windows
                    .map { it.periodIndex }
                    .toMutableList()
                    .apply { add(destinationIndex, removeAt(sourceIndex)) }
                    .toIntArray()
            QueueReorderAction.UpdateShuffleOrder(reorderedPeriodIndices)
        }
    }
}
