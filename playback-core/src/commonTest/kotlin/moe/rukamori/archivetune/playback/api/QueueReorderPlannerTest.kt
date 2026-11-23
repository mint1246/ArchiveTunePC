/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueReorderPlannerTest {
    @Test
    fun resolveDestinationIndex_handles_Start_on_empty_and_non_empty_lists() {
        assertNull(
            QueueReorderPlanner.resolveDestinationIndex(
                windowUids = emptyList(),
                sourceIndex = 0,
                destination = QueueDragDestination.Start,
            ),
        )
        assertEquals(
            0,
            QueueReorderPlanner.resolveDestinationIndex(
                windowUids = listOf("u0", "u1", "u2"),
                sourceIndex = 2,
                destination = QueueDragDestination.Start,
            ),
        )
    }

    @Test
    fun resolveDestinationIndex_adjusts_After_index_for_forward_and_backward_drags() {
        val uids = listOf("u0", "u1", "u2", "u3")

        // Dragging u0 forward after u2 -> lands at index 2 (after u1, u2 shift left)
        assertEquals(
            2,
            QueueReorderPlanner.resolveDestinationIndex(
                windowUids = uids,
                sourceIndex = 0,
                destination = QueueDragDestination.After("u2"),
            ),
        )

        // Dragging u3 backward after u0 -> lands at index 1
        assertEquals(
            1,
            QueueReorderPlanner.resolveDestinationIndex(
                windowUids = uids,
                sourceIndex = 3,
                destination = QueueDragDestination.After("u0"),
            ),
        )
    }

    @Test
    fun planReorder_returns_MoveLogical_when_shuffle_is_disabled() {
        val windows =
            listOf(
                QueueReorderWindow(uid = "a", periodIndex = 0),
                QueueReorderWindow(uid = "b", periodIndex = 1),
                QueueReorderWindow(uid = "c", periodIndex = 2),
                QueueReorderWindow(uid = "d", periodIndex = 3),
            )

        val action =
            QueueReorderPlanner.planReorder(
                windows = windows,
                draggedItemUid = "d",
                destination = QueueDragDestination.Start,
                shuffleEnabled = false,
            )

        assertEquals(QueueReorderAction.MoveLogical(fromIndex = 3, toIndex = 0), action)
    }

    @Test
    fun planReorder_returns_UpdateShuffleOrder_preserving_traversal_period_indices_when_shuffle_is_enabled() {
        val windows =
            listOf(
                QueueReorderWindow(uid = "w2", periodIndex = 2),
                QueueReorderWindow(uid = "w0", periodIndex = 0),
                QueueReorderWindow(uid = "w3", periodIndex = 3),
                QueueReorderWindow(uid = "w1", periodIndex = 1),
            )

        val action =
            QueueReorderPlanner.planReorder(
                windows = windows,
                draggedItemUid = "w2",
                destination = QueueDragDestination.After("w3"),
                shuffleEnabled = true,
            )

        assertEquals(
            QueueReorderAction.UpdateShuffleOrder(intArrayOf(0, 3, 2, 1)),
            action,
        )
    }

    @Test
    fun planReorder_disambiguates_duplicate_items_by_unique_window_uid() {
        val firstDupUid = Any()
        val secondDupUid = Any()
        val otherUid = Any()
        val windows =
            listOf(
                QueueReorderWindow(uid = firstDupUid, periodIndex = 0),
                QueueReorderWindow(uid = otherUid, periodIndex = 1),
                QueueReorderWindow(uid = secondDupUid, periodIndex = 2),
            )

        val action =
            QueueReorderPlanner.planReorder(
                windows = windows,
                draggedItemUid = secondDupUid,
                destination = QueueDragDestination.After(firstDupUid),
                shuffleEnabled = false,
            )

        assertEquals(QueueReorderAction.MoveLogical(fromIndex = 2, toIndex = 1), action)
    }

    @Test
    fun planReorder_returns_NoOp_when_source_or_anchor_is_missing_or_position_is_unchanged() {
        val windows =
            listOf(
                QueueReorderWindow(uid = "u0", periodIndex = 0),
                QueueReorderWindow(uid = "u1", periodIndex = 1),
                QueueReorderWindow(uid = "u2", periodIndex = 2),
            )

        assertEquals(
            QueueReorderAction.NoOp,
            QueueReorderPlanner.planReorder(
                windows = windows,
                draggedItemUid = "missing",
                destination = QueueDragDestination.Start,
                shuffleEnabled = false,
            ),
        )
        assertEquals(
            QueueReorderAction.NoOp,
            QueueReorderPlanner.planReorder(
                windows = windows,
                draggedItemUid = "u1",
                destination = QueueDragDestination.After("missing"),
                shuffleEnabled = true,
            ),
        )
        assertEquals(
            QueueReorderAction.NoOp,
            QueueReorderPlanner.planReorder(
                windows = windows,
                draggedItemUid = "u1",
                destination = QueueDragDestination.After("u0"),
                shuffleEnabled = false,
            ),
        )
    }
}
