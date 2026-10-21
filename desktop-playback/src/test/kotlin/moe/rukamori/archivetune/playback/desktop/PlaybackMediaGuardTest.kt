package moe.rukamori.archivetune.playback.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the media identity bookkeeping that keeps native libVLC callbacks of a
 * replaced media from being handled as the end of the current media.
 */
class PlaybackMediaGuardTest {
    @Test
    fun `a callback of a replaced media is obsolete`() {
        val guard = PlaybackMediaGuard()

        val identity = assertNotNull(guard.captureIdentity(generation = 7L))

        assertTrue(guard.describesActiveMedia(identity, currentGeneration = 7L, currentActiveGeneration = 7L))
        // The media was replaced by a reload: the callback belongs to the previous media now.
        assertFalse(guard.describesActiveMedia(identity, currentGeneration = 8L, currentActiveGeneration = 8L))
        assertFalse(guard.describesActiveMedia(identity, currentGeneration = 8L, currentActiveGeneration = 7L))
    }

    @Test
    fun `a user seek supersedes an in flight terminal event`() {
        val guard = PlaybackMediaGuard()

        val identity = assertNotNull(guard.captureIdentity(generation = 7L))
        assertFalse(guard.supersededByUserSeek(identity))

        guard.noteUserSeek(5_000L)

        assertTrue(guard.supersededByUserSeek(identity))
        assertEquals(5_000L, guard.lastUserSeekPositionMs())
        assertEquals(1L, guard.seekRevision())
    }

    @Test
    fun `the decoded length is tracked per generation and dropped with the media`() {
        val guard = PlaybackMediaGuard()

        guard.recordDecodedDuration(generation = 7L, durationMs = 109_650L)

        assertEquals(109_650L, guard.decodedDurationMs(generation = 7L))
        assertNull(guard.decodedDurationMs(generation = 8L))

        guard.resetMedia()

        assertNull(guard.decodedDurationMs(generation = 7L))
    }

    @Test
    fun `the queued start position belongs to the generation that requested it`() {
        val guard = PlaybackMediaGuard()

        guard.queuePendingSeek(generation = 3L, positionMs = 7_000L)

        assertEquals(7_000L, guard.pendingSeekFor(generation = 3L))
        assertNull(guard.pendingSeekFor(generation = 4L))

        assertEquals(7_000L, guard.consumePendingSeek(generation = 3L))
        assertNull(guard.consumePendingSeek(generation = 3L))
    }

    @Test
    fun `a forward seek drops the stale position until the engine acknowledges it`() {
        var now = 0L
        val guard = PlaybackMediaGuard(nowMs = { now })

        assertEquals(1_000L, guard.acceptNativePosition(1_000L))
        guard.noteUserSeek(30_000L)

        // The engine still reports the position it had before the seek.
        assertNull(guard.acceptNativePosition(1_000L))
        now += 100L
        assertEquals(30_000L, guard.acceptNativePosition(30_000L))
        // The acknowledgement window is closed, so the native clock is followed again.
        assertEquals(30_500L, guard.acceptNativePosition(30_500L))
    }

    @Test
    fun `a backward seek drops the stale position until the engine acknowledges it`() {
        var now = 0L
        val guard = PlaybackMediaGuard(nowMs = { now })

        assertEquals(60_000L, guard.acceptNativePosition(60_000L))
        guard.noteUserSeek(10_000L)

        // The engine still reports the position it had before the seek.
        assertNull(guard.acceptNativePosition(60_000L))
        now += 100L
        assertEquals(10_000L, guard.acceptNativePosition(10_000L))
        assertEquals(10_500L, guard.acceptNativePosition(10_500L))
    }

    @Test
    fun `a seek that is never acknowledged releases to the native clock`() {
        var now = 0L
        val guard = PlaybackMediaGuard(nowMs = { now })

        assertEquals(1_000L, guard.acceptNativePosition(1_000L))
        guard.noteUserSeek(30_000L)
        assertNull(guard.acceptNativePosition(1_000L))

        // The engine never applied the seek. The window is bounded, so the real native clock is
        // followed again instead of freezing the reported position forever.
        now += 5_000L
        assertEquals(1_000L, guard.acceptNativePosition(1_000L))
    }

    @Test
    fun `the end of stream announcement is scoped to its generation`() {
        val guard = PlaybackMediaGuard()

        guard.noteEndOfStreamArrived(generation = 4L)

        // The end of a replaced media must not silence the stopped event of its successor.
        assertFalse(guard.consumeEndOfStreamArrival(generation = 5L))
        assertTrue(guard.consumeEndOfStreamArrival(generation = 4L))
        assertFalse(guard.consumeEndOfStreamArrival(generation = 4L))
    }

    @Test
    fun `the media start is tracked per generation`() {
        val guard = PlaybackMediaGuard()

        guard.noteMediaStarted(generation = 2L)

        assertTrue(guard.hasStarted(generation = 2L))
        assertFalse(guard.hasStarted(generation = 3L))

        guard.resetMedia()

        assertFalse(guard.hasStarted(generation = 2L))
    }
}
