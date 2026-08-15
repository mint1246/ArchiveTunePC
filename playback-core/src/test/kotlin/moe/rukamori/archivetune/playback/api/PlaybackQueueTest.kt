/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQueueTest {
    @Test
    fun `replace clamps initial index and handles empty queue`() {
        val queue = PlaybackQueue(Random(1))

        assertNull(queue.replace(emptyList(), startIndex = 99))
        assertEquals(-1, queue.currentIndex)

        queue.replace(items("a", "b"), startIndex = 99)
        assertEquals("b", queue.current?.metadata?.id)
        assertEquals(1, queue.currentIndex)
    }

    @Test
    fun `repeat one applies to natural completion but user next advances`() {
        val queue = PlaybackQueue(Random(2))
        queue.replace(items("a", "b"))
        queue.setRepeatMode(RepeatMode.ONE)

        val automatic = queue.advance(automatic = true)
        assertEquals("a", automatic?.item?.metadata?.id)
        assertTrue(automatic?.repeated == true)

        val user = queue.advance(automatic = false)
        assertEquals("b", user?.item?.metadata?.id)
        assertFalse(user?.repeated == true)
    }

    @Test
    fun `repeat all wraps both directions`() {
        val queue = PlaybackQueue(Random(3))
        queue.replace(items("a", "b"), startIndex = 1)
        queue.setRepeatMode(RepeatMode.ALL)

        assertTrue(queue.canAdvance)
        assertTrue(queue.canRetreat)
        assertEquals("a", queue.advance(automatic = true)?.item?.metadata?.id)
        assertEquals("b", queue.retreat()?.item?.metadata?.id)
    }

    @Test
    fun `shuffle visits every entry once before ending`() {
        val queue = PlaybackQueue(Random(42))
        queue.replace(items("a", "b", "c", "d", "e"), startIndex = 2)
        queue.setShuffleEnabled(true)

        val visited = mutableListOf(requireNotNull(queue.current).metadata.id)
        while (true) {
            val next = queue.advance(automatic = true) ?: break
            visited += next.item.metadata.id
        }

        assertEquals("c", visited.first())
        assertEquals(setOf("a", "b", "c", "d", "e"), visited.toSet())
        assertEquals(5, visited.size)
    }

    @Test
    fun `play next wins over remaining shuffle order`() {
        val queue = PlaybackQueue(Random(7))
        queue.replace(items("a", "b", "c"))
        queue.setShuffleEnabled(true)
        queue.insertNext(items("x", "y"))

        assertEquals("x", queue.advance(automatic = false)?.item?.metadata?.id)
        assertEquals("y", queue.advance(automatic = false)?.item?.metadata?.id)
    }

    @Test
    fun `removing current selects its next playback-order neighbor`() {
        val queue = PlaybackQueue(Random(8))
        queue.replace(items("a", "b", "c"), startIndex = 1)

        val result = queue.removeAt(1)

        assertTrue(result.currentChanged)
        assertEquals("c", result.current?.item?.metadata?.id)
        assertEquals(1, result.current?.index)
    }

    @Test
    fun `moving current keeps identity and reports new logical index`() {
        val queue = PlaybackQueue(Random(9))
        queue.replace(items("a", "b", "c"), startIndex = 1)

        queue.move(fromIndex = 1, toIndex = 2)

        assertEquals("b", queue.current?.metadata?.id)
        assertEquals(2, queue.currentIndex)
    }

    @Test
    fun `duplicate media ids remain independently addressable`() {
        val queue = PlaybackQueue(Random(10))
        queue.replace(items("same", "same", "last"), startIndex = 1)

        queue.removeAt(0)

        assertEquals("same", queue.current?.metadata?.id)
        assertEquals(0, queue.currentIndex)
        assertEquals("last", queue.advance(automatic = false)?.item?.metadata?.id)
    }

    @Test
    fun `entries are returned in logical order with the current item flagged`() {
        val queue = PlaybackQueue(Random(11))
        queue.replace(items("a", "b", "c", "d", "e"), startIndex = 2)

        val rows = queue.entries()

        assertEquals(listOf("a", "b", "c", "d", "e"), rows.map { it.item.metadata.id })
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.logicalIndex })
        assertEquals(listOf(false, false, true, false, false), rows.map { it.isCurrent })
    }

    @Test
    fun `append in shuffle order places new items at the end`() {
        val queue = PlaybackQueue(Random(12))
        queue.replace(items("a", "b", "c"))
        queue.setShuffleEnabled(true)
        queue.append(items("x", "y"))

        val visited = mutableListOf(requireNotNull(queue.current).metadata.id)
        while (true) {
            val next = queue.advance(automatic = true) ?: break
            visited += next.item.metadata.id
        }

        assertEquals("a", visited.first())
        assertEquals(setOf("a", "b", "c", "x", "y"), visited.toSet())
        assertEquals(listOf("x", "y"), visited.takeLast(2))
    }

    @Test
    fun `moveInPlayOrder only reorders traversal`() {
        val queue = PlaybackQueue(Random(14))
        queue.replace(items("a", "b", "c", "d", "e"), startIndex = 2)
        queue.setShuffleEnabled(true)
        val logicalBefore = queue.items.map { it.metadata.id }

        queue.moveInPlayOrder(fromVisual = 1, toVisual = 2)

        assertEquals(logicalBefore, queue.items.map { it.metadata.id })
        assertEquals("c", queue.current?.metadata?.id)
    }

    @Test
    fun `moveToNext places item after current in linear mode`() {
        val queue = PlaybackQueue(Random(15))
        queue.replace(items("a", "b", "c", "d", "e"), startIndex = 2)

        queue.moveToNext(0)

        assertEquals(listOf("b", "c", "d", "a", "e"), queue.items.map { it.metadata.id })
        assertEquals("c", queue.current?.metadata?.id)
        assertEquals("d", queue.advance(automatic = false)?.item?.metadata?.id)
        assertEquals("a", queue.advance(automatic = false)?.item?.metadata?.id)
    }

    @Test
    fun `moveToNext reorders shuffle traversal after current`() {
        val queue = PlaybackQueue(Random(16))
        queue.replace(items("a", "b", "c", "d", "e"), startIndex = 2)
        queue.setShuffleEnabled(true)

        queue.moveToNext(0)

        assertEquals("c", queue.current?.metadata?.id)
        assertEquals("a", queue.advance(automatic = false)?.item?.metadata?.id)
    }

    @Test
    fun `clear keeps only the current item`() {
        val queue = PlaybackQueue(Random(17))
        queue.replace(items("a", "b", "c", "d", "e"), startIndex = 2)

        queue.clear()

        assertEquals(listOf("c"), queue.items.map { it.metadata.id })
        assertEquals("c", queue.current?.metadata?.id)
        assertFalse(queue.canAdvance)
        assertFalse(queue.canRetreat)
    }

    @Test
    fun `clear on empty queue is a no-op`() {
        val queue = PlaybackQueue(Random(18))
        queue.replace(emptyList())

        val mutation = queue.clear()

        assertFalse(mutation.currentChanged)
        assertNull(queue.current)
    }

    private fun items(vararg ids: String): List<PlaybackItem> =
        ids.map { id ->
            PlaybackItem(
                metadata = PlaybackMetadata(id = id, title = "Track $id"),
            )
        }
}
