/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the controller's queue wiring against the platform-neutral
 * [moe.rukamori.archivetune.playback.api.PlaybackQueue]. Playback state is published synchronously
 * on mutation, so these assertions do not require a working mpv binary; a deliberately unavailable
 * executable keeps the tests hermetic.
 */
class MpvQueueBehaviorTest {
    private fun item(id: String) =
        PlaybackItem(PlaybackMetadata(id = id, title = "Track $id"), PlaybackSource.LocalFile("/tmp/$id.wav"))

    private fun controller() =
        MpvPlaybackController(
            sourceResolver = PlaybackSourceResolver { Result.success(ResolvedPlaybackSource("file:///tmp/fake.wav")) },
            configuration = MpvConfiguration(executable = "definitely-not-an-mpv-binary.exe"),
        )

    @Test
    fun `replace queue keeps logical order and flags the current item`() = runBlocking {
        controller().use { c ->
            c.replaceQueue(listOf(item("a"), item("b"), item("c"), item("d")), startIndex = 2, playWhenReady = false)
            val s = c.state.value
            assertEquals(listOf("a", "b", "c", "d"), s.queueEntries.map { it.item.metadata.id })
            assertEquals(2, s.currentIndex)
            assertEquals("c", s.currentItem?.metadata?.id)
            assertTrue(s.queueEntries[2].isCurrent)
        }
    }

    @Test
    fun `next and previous advance the current item`() = runBlocking {
        controller().use { c ->
            c.replaceQueue(listOf(item("a"), item("b"), item("c")), startIndex = 0, playWhenReady = false)
            c.skipNext()
            assertEquals("b", c.state.value.currentItem?.metadata?.id)
            c.skipPrevious()
            assertEquals("a", c.state.value.currentItem?.metadata?.id)
        }
    }

    @Test
    fun `next at the end reaches ended state`() = runBlocking {
        controller().use { c ->
            c.replaceQueue(listOf(item("a"), item("b")), startIndex = 0, playWhenReady = false)
            c.skipNext()
            c.skipNext()
            assertEquals(PlaybackStatus.ENDED, c.state.value.status)
        }
    }

    @Test
    fun `move queue item reorders the logical order`() = runBlocking {
        controller().use { c ->
            c.replaceQueue(listOf(item("a"), item("b"), item("c"), item("d")), startIndex = 2, playWhenReady = false)
            c.moveQueueItem(1, 3)
            assertEquals(listOf("a", "c", "d", "b"), c.state.value.queueEntries.map { it.item.metadata.id })
            assertEquals("c", c.state.value.currentItem?.metadata?.id)
        }
    }

    @Test
    fun `move to next places an existing item after the current item`() = runBlocking {
        controller().use { c ->
            c.replaceQueue(listOf(item("a"), item("b"), item("c"), item("d")), startIndex = 2, playWhenReady = false)
            c.moveQueueItemToNext(0)
            assertEquals(listOf("b", "c", "d", "a"), c.state.value.queue.map { it.metadata.id })
            assertEquals("c", c.state.value.currentItem?.metadata?.id)
        }
    }

    @Test
    fun `play next and enqueue mutate the queue`() = runBlocking {
        controller().use { c ->
            c.replaceQueue(listOf(item("a"), item("b")), startIndex = 0, playWhenReady = false)
            c.playNext(listOf(item("x")))
            assertEquals(listOf("a", "x", "b"), c.state.value.queueEntries.map { it.item.metadata.id })
            c.enqueue(listOf(item("y")))
            assertEquals(listOf("a", "x", "b", "y"), c.state.value.queue.map { it.metadata.id })
        }
    }

    @Test
    fun `remove queue item keeps the current item stable`() = runBlocking {
        controller().use { c ->
            c.replaceQueue(listOf(item("a"), item("b"), item("c")), startIndex = 1, playWhenReady = false)
            c.removeQueueItem(0)
            assertEquals(listOf("b", "c"), c.state.value.queue.map { it.metadata.id })
            assertEquals("b", c.state.value.currentItem?.metadata?.id)
        }
    }

    @Test
    fun `clear queue keeps only the current item`() = runBlocking {
        controller().use { c ->
            c.replaceQueue(listOf(item("a"), item("b"), item("c")), startIndex = 1, playWhenReady = false)
            c.clearQueue()
            assertEquals(listOf("b"), c.state.value.queue.map { it.metadata.id })
            assertEquals("b", c.state.value.currentItem?.metadata?.id)
            assertFalse(c.state.value.hasNextItem)
        }
    }

    @Test
    fun `shuffle and repeat are reflected in state`() = runBlocking {
        controller().use { c ->
            c.replaceQueue(listOf(item("a"), item("b"), item("c"), item("d")), startIndex = 0, playWhenReady = false)
            c.setShuffleEnabled(true)
            assertTrue(c.state.value.shuffleEnabled)
            assertEquals(4, c.state.value.queueEntries.size)
            assertTrue(c.state.value.queueEntries[0].isCurrent)
            c.setRepeatMode(RepeatMode.ALL)
            assertEquals(RepeatMode.ALL, c.state.value.repeatMode)
            c.setRepeatMode(RepeatMode.ONE)
            assertEquals(RepeatMode.ONE, c.state.value.repeatMode)
        }
    }
}
