package moe.rukamori.archivetune.linuxmedia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxChordTrackerTest {
    @Test
    fun `the held keys are reported while they are pressed`() {
        val tracker = LinuxChordTracker()

        assertEquals(setOf(0x11), tracker.keyDown(0x11))
        assertEquals(setOf(0x11, 0x4B), tracker.keyDown(0x4B))
        assertEquals(setOf(0x11), tracker.keyUp(0x4B))
        assertEquals(emptySet(), tracker.keyUp(0x11))
    }

    @Test
    fun `the widest chord survives the release order`() {
        val tracker = LinuxChordTracker()

        tracker.keyDown(0x11)
        tracker.keyDown(0x4B)
        tracker.keyUp(0x4B)
        tracker.keyUp(0x11)

        assertEquals(setOf(0x11, 0x4B), tracker.complete())
    }

    @Test
    fun `completing the recording clears the held keys`() {
        val tracker = LinuxChordTracker()

        tracker.keyDown(0x11)
        tracker.keyDown(0x4B)
        tracker.keyUp(0x4B)
        tracker.keyUp(0x11)
        tracker.complete()

        assertTrue(tracker.heldKeys().isEmpty())
        assertTrue(tracker.bestChord().isEmpty())
    }

    @Test
    fun `the modifier keys normalize to the generic ids`() {
        val tracker = LinuxChordTracker()

        tracker.keyDown(0xA3)
        tracker.keyDown(0xA1)

        assertEquals(setOf(0x11, 0x10), tracker.heldKeys())
    }

    @Test
    fun `a repeated key press does not widen the chord`() {
        val tracker = LinuxChordTracker()

        tracker.keyDown(0x4B)
        tracker.keyDown(0x4B)

        assertEquals(setOf(0x4B), tracker.heldKeys())
        assertEquals(setOf(0x4B), tracker.complete())
    }

    @Test
    fun `reset forgets the recording`() {
        val tracker = LinuxChordTracker()

        tracker.keyDown(0x11)
        tracker.keyDown(0x4B)
        tracker.reset()

        assertTrue(tracker.heldKeys().isEmpty())
        assertTrue(tracker.bestChord().isEmpty())
    }
}
