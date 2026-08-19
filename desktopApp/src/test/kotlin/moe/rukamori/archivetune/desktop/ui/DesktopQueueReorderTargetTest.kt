package moe.rukamori.archivetune.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackQueueEntry

class DesktopQueueReorderTargetTest {
    @Test
    fun `scrolled queue keeps same visible occurrence as a no-op target`() {
        val visible = visibleRows(startIndex = 18, count = 9, firstOffset = -28)
        val draggedIndex = 20

        val target = queueReorderTarget(
            draggedEntryId = entryId(draggedIndex),
            currentIndex = draggedIndex,
            pointerY = visible.first { it.index == draggedIndex }.centerY,
            visibleItems = visible,
            itemCount = 40,
        )

        assertEquals(draggedIndex, target)
    }

    @Test
    fun `scrolled queue drag moves down several visible rows`() {
        val visible = visibleRows(startIndex = 18, count = 10, firstOffset = -28)

        val target = queueReorderTarget(
            draggedEntryId = entryId(20),
            currentIndex = 20,
            pointerY = visible.first { it.index == 25 }.centerY,
            visibleItems = visible,
            itemCount = 40,
        )

        assertEquals(25, target)
    }

    @Test
    fun `scrolled queue drag moves up several visible rows`() {
        val visible = visibleRows(startIndex = 18, count = 10, firstOffset = -28)

        val target = queueReorderTarget(
            draggedEntryId = entryId(24),
            currentIndex = 24,
            pointerY = visible.first { it.index == 19 }.centerY,
            visibleItems = visible,
            itemCount = 40,
        )

        assertEquals(19, target)
    }

    @Test
    fun `autoscroll advances target while pointer stays at viewport boundary`() {
        val beforeScroll = visibleRows(startIndex = 20, count = 8)
        val afterScroll = visibleRows(startIndex = 21, count = 8)
        val pointerY = 430f

        val beforeTarget = queueReorderTarget(
            draggedEntryId = entryId(22),
            currentIndex = 22,
            pointerY = pointerY,
            visibleItems = beforeScroll,
            itemCount = 50,
        )
        val afterTarget = queueReorderTarget(
            draggedEntryId = entryId(22),
            currentIndex = 22,
            pointerY = pointerY,
            visibleItems = afterScroll,
            itemCount = 50,
        )

        assertEquals(27, beforeTarget)
        assertEquals(28, afterTarget)
        assertTrue(queueAutoScrollDelta(pointerY, viewportStartOffset = 0, viewportEndOffset = 448) > 0f)
        assertTrue(queueAutoScrollDelta(10f, viewportStartOffset = 0, viewportEndOffset = 448) < 0f)
        assertEquals(0f, queueAutoScrollDelta(224f, viewportStartOffset = 0, viewportEndOffset = 448))
    }

    @Test
    fun `target clamps to first and last visible queue boundaries`() {
        val visible = visibleRows(startIndex = 0, count = 5)

        assertEquals(
            0,
            queueReorderTarget(entryId(2), 2, -1000f, visible, itemCount = 5),
        )
        assertEquals(
            4,
            queueReorderTarget(entryId(2), 2, 1000f, visible, itemCount = 5),
        )
    }

    @Test
    fun `duplicate media ids are disambiguated by stable queue entry ids`() {
        val entries = listOf(
            queueEntry(entryId = 101, mediaId = "same-video", logicalIndex = 0),
            queueEntry(entryId = 202, mediaId = "same-video", logicalIndex = 1),
        )
        val visible = listOf(
            QueueVisibleItemLayout(entryId = 101, index = 0, offset = 0, size = 56),
            QueueVisibleItemLayout(entryId = 202, index = 1, offset = 56, size = 56),
        )

        assertEquals(0, queueEntryIndex(entries, 101))
        assertEquals(1, queueEntryIndex(entries, 202))
        assertEquals(
            1,
            queueReorderTarget(
                draggedEntryId = 101,
                currentIndex = 0,
                pointerY = visible[1].centerY,
                visibleItems = visible,
                itemCount = entries.size,
            ),
        )
    }

    private fun visibleRows(
        startIndex: Int,
        count: Int,
        firstOffset: Int = 0,
        size: Int = 56,
    ): List<QueueVisibleItemLayout> =
        List(count) { position ->
            val index = startIndex + position
            QueueVisibleItemLayout(
                entryId = entryId(index),
                index = index,
                offset = firstOffset + position * size,
                size = size,
            )
        }

    private fun entryId(index: Int): Long = 10_000L + index

    private fun queueEntry(entryId: Long, mediaId: String, logicalIndex: Int): PlaybackQueueEntry =
        PlaybackQueueEntry(
            id = entryId,
            item = PlaybackItem(PlaybackMetadata(id = mediaId, title = mediaId)),
            logicalIndex = logicalIndex,
            isCurrent = false,
        )
}
