package moe.rukamori.archivetune.linuxmedia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxShortcutRecorderTest {
    private val modifiers = setOf(0x11, 0x10, 0x12)
    private val chord = modifiers + 0x59

    @Test
    fun `fast mouse macro retains every key when the UI only reads the final state`() {
        val recorder = LinuxShortcutRecorder()
        recorder.start()
        listOf(0x11, 0x10, 0x12, 0x59).forEach { recorder.press(it) }
        listOf(0x59, 0x12, 0x10, 0x11).forEach { recorder.release(it) }
        // No consumer read any intermediate state: a conflated UI flow cannot lose this chord.
        assertEquals(chord.sorted(), recorder.state.completedChord)
        assertFalse(recorder.active)
        assertEquals("CTRL+ALT+SHIFT+y", LinuxShortcutTriggers.triggerFor(recorder.state.completedChord!!.toSet()))
    }

    @Test
    fun `modifier only attempts do not finish capture or contaminate the next chord`() {
        val recorder = LinuxShortcutRecorder()
        recorder.start()
        recorder.press(0x11)
        recorder.release(0x11)
        assertNull(recorder.state.completedChord)
        assertTrue(recorder.active)
        recorder.press(0x10)
        recorder.press(0x59)
        recorder.release(0x10)
        recorder.release(0x59)
        assertEquals(listOf(0x10, 0x59), recorder.state.completedChord)
    }

    @Test
    fun `ordinary key modifier mask recovers missing separate modifier presses`() {
        val recorder = LinuxShortcutRecorder()
        recorder.start()
        recorder.press(0x59, modifiers)
        recorder.release(0x59)
        modifiers.forEach { recorder.release(it) }
        assertEquals(chord.sorted(), recorder.state.completedChord)
    }

    @Test
    fun `release order and auto repeat do not change the completed chord`() {
        val recorder = LinuxShortcutRecorder()
        recorder.start()
        chord.forEach { recorder.press(it) }
        repeat(4) { recorder.press(0x59, modifiers) }
        modifiers.forEach { recorder.release(it) }
        recorder.release(0x59)
        assertEquals(chord.sorted(), recorder.state.completedChord)
        recorder.press(0x41)
        assertEquals(chord.sorted(), recorder.state.completedChord)
    }

    @Test
    fun `focus cancellation cannot publish a partial chord`() {
        val recorder = LinuxShortcutRecorder()
        recorder.start()
        recorder.press(0x11)
        recorder.cancel()
        recorder.release(0x11)
        assertTrue(recorder.state.cancelled)
        assertNull(recorder.state.completedChord)
    }

    @Test
    fun `new recording clears old completion and has a new session identity`() {
        val recorder = LinuxShortcutRecorder()
        val first = recorder.start().session
        chord.forEach { recorder.press(it) }
        chord.forEach { recorder.release(it) }
        recorder.cancel() // UI cleanup after completion preserves its result.
        assertEquals(chord.sorted(), recorder.state.completedChord)
        assertTrue(recorder.start().session > first)
        assertNull(recorder.state.completedChord)
        assertTrue(recorder.state.bestChord.isEmpty())
    }
}
