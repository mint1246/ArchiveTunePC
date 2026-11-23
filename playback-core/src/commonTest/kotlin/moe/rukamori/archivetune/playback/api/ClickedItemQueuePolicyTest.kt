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

class ClickedItemQueuePolicyTest {
    private data class Track(val id: String, val title: String = id)

    @Test
    fun clicked_item_index_wins_over_server_default_index_when_present_in_returned_queue() {
        val clicked = Track("target-vid", "Target")
        val returned = listOf(Track("server-first"), Track("other"), clicked, Track("target-vid", "Duplicate"))

        val selection = selectClickedItemQueue(
            clickedItem = clicked,
            clickedId = clicked.id,
            returnedItems = returned,
            itemId = Track::id,
            returnedTitle = "Radio mix",
        )

        assertEquals(ClickedItemQueueSelection.Outcome.MATCHED, selection.outcome)
        assertEquals(returned, selection.items)
        assertEquals(2, selection.startIndex)
        assertEquals("Radio mix", selection.title)
    }

    @Test
    fun missing_clicked_item_falls_back_to_playing_clicked_item_alone_instead_of_index_zero() {
        val clicked = Track("clicked-id")
        val returned = listOf(Track("wrong-1"), Track("wrong-2"))

        val selection = selectClickedItemQueue(
            clickedItem = clicked,
            clickedId = clicked.id,
            returnedItems = returned,
            itemId = Track::id,
            returnedTitle = "Custom Mix",
        )

        assertEquals(ClickedItemQueueSelection.Outcome.MISSING_TARGET_FALLBACK, selection.outcome)
        assertEquals(listOf(clicked), selection.items)
        assertEquals(0, selection.startIndex)
        assertEquals("Custom Mix", selection.title)
        assertNull(requestedQueueItemIndex(returned.map(Track::id), clicked.id))
    }

    @Test
    fun empty_queue_and_failure_fallbacks_preserve_clicked_item_and_default_title() {
        val clicked = Track("solo")

        val emptySelection = selectClickedItemQueue(
            clickedItem = clicked,
            clickedId = clicked.id,
            returnedItems = emptyList(),
            itemId = Track::id,
            returnedTitle = "Ignored on empty",
        )
        assertEquals(ClickedItemQueueSelection.Outcome.EMPTY_QUEUE_FALLBACK, emptySelection.outcome)
        assertEquals(listOf(clicked), emptySelection.items)
        assertEquals(0, emptySelection.startIndex)
        assertEquals("Up next", emptySelection.title)

        val failureSelection = fallbackClickedItemQueue(clicked)
        assertEquals(ClickedItemQueueSelection.Outcome.FAILURE_FALLBACK, failureSelection.outcome)
        assertEquals(listOf(clicked), failureSelection.items)
        assertEquals(0, failureSelection.startIndex)
        assertEquals("Up next", failureSelection.title)
    }
}
