package moe.rukamori.archivetune.playback.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus

class QueuePersistencePolicyTest {
    @Test
    fun `position ticks over ten seconds do not spam writes`() {
        val policy = QueuePersistencePolicy()
        val base = snapshot(positionMs = 0L)

        assertFalse(policy.shouldPersist(base))
        val writes = (1..200).count { tick ->
            policy.shouldPersist(base.copy(positionMs = tick * 50L))
        }

        assertEquals(0, writes)
    }

    @Test
    fun `long playback still checkpoints position periodically`() {
        val policy = QueuePersistencePolicy()
        val base = snapshot(positionMs = 0L)
        assertFalse(policy.shouldPersist(base))

        val writes = (1..1_200).count { tick ->
            policy.shouldPersist(base.copy(positionMs = tick * 50L))
        }

        assertEquals(2, writes)
    }

    @Test
    fun `pause resume current item and queue changes save promptly`() {
        val policy = QueuePersistencePolicy()
        val first = item("one")
        val second = item("two")
        val playing = snapshot(queue = listOf(first, second), currentIndex = 0, positionMs = 1_000L)
        assertFalse(policy.shouldPersist(playing))

        val paused = playing.copy(playWhenReady = false)
        assertTrue(policy.shouldPersist(paused))
        assertFalse(policy.shouldPersist(paused))

        val resumed = paused.copy(playWhenReady = true)
        assertTrue(policy.shouldPersist(resumed))

        val nextItem = resumed.copy(currentIndex = 1, positionMs = 0L)
        assertTrue(policy.shouldPersist(nextItem))

        val reordered = nextItem.copy(queue = listOf(second, first), currentIndex = 0)
        assertTrue(policy.shouldPersist(reordered))
    }

    @Test
    fun `large seek and stop save promptly`() {
        val policy = QueuePersistencePolicy()
        val playing = snapshot(positionMs = 1_000L)
        assertFalse(policy.shouldPersist(playing))

        val sought = playing.copy(positionMs = 12_000L)
        assertTrue(policy.shouldPersist(sought))
        assertFalse(policy.shouldPersist(sought))

        val stopped = sought.copy(status = PlaybackStatus.IDLE, playWhenReady = false)
        assertTrue(policy.shouldPersist(stopped))
        assertFalse(policy.shouldPersist(stopped))
    }

    @Test
    fun `forced close flush persists even unchanged snapshot`() {
        val policy = QueuePersistencePolicy()
        val state = snapshot(positionMs = 4_000L)
        assertFalse(policy.shouldPersist(state))
        assertFalse(policy.shouldPersist(state))

        assertTrue(policy.shouldPersist(state, force = true))
    }

    private fun snapshot(
        queue: List<PlaybackItem> = listOf(item("one")),
        currentIndex: Int = 0,
        positionMs: Long = 0L,
    ) = PlaybackSnapshot(
        queue = queue,
        queueTitle = "Queue",
        currentIndex = currentIndex,
        status = PlaybackStatus.READY,
        playWhenReady = true,
        positionMs = positionMs,
    )

    private fun item(id: String) = PlaybackItem(PlaybackMetadata(id = id, title = id))
}
