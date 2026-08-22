package moe.rukamori.archivetune.windowsmedia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsGlobalKeyBindingsTest {
    @Test
    fun `arbitrary length chord fires once when completed`() {
        val matcher = WindowsChordMatcher(
            listOf(WindowsGlobalKeyBinding("action", setOf(0x11, 0x12, 0x10, 0x41, 0x42, 0x43))),
        )
        val fired = mutableListOf<String>()
        listOf(0x11, 0x12, 0x10, 0x41, 0x42, 0x43).forEachIndexed { index, key ->
            fired += matcher.keyDown(key, index.toLong())
        }

        assertEquals(listOf("action"), fired)
        assertTrue(matcher.keyDown(0x43, 10L).isEmpty(), "Windows autorepeat keydown must not retrigger")
    }

    @Test
    fun `extra keys keep subset chord from firing`() {
        val matcher = WindowsChordMatcher(
            listOf(WindowsGlobalKeyBinding("ctrl-k", setOf(0x11, 0x4B))),
        )

        matcher.keyDown(0x11, 0L)
        matcher.keyDown(0x10, 1L)
        assertTrue(matcher.keyDown(0x4B, 2L).isEmpty())
    }

    @Test
    fun `repeat binding waits then repeats at interval while held`() {
        val matcher = WindowsChordMatcher(
            listOf(WindowsGlobalKeyBinding("volume-up", setOf(0x11, 0x26), repeatWhileHeld = true)),
            initialRepeatDelayMs = 300L,
            repeatIntervalMs = 100L,
        )

        assertTrue(matcher.keyDown(0x11, 0L).isEmpty())
        assertEquals(listOf("volume-up"), matcher.keyDown(0x26, 10L))
        assertTrue(matcher.dueRepeats(309L).isEmpty())
        assertEquals(listOf("volume-up"), matcher.dueRepeats(310L))
        assertTrue(matcher.dueRepeats(409L).isEmpty())
        assertEquals(listOf("volume-up"), matcher.dueRepeats(410L))
        matcher.keyUp(0x26, 411L)
        assertTrue(matcher.dueRepeats(1_000L).isEmpty())
    }

    @Test
    fun `atomic key snapshot does not fire a smaller subset chord`() {
        val matcher = WindowsChordMatcher(
            listOf(WindowsGlobalKeyBinding("ctrl-k", setOf(0x11, 0x4B))),
        )

        assertTrue(matcher.updatePressed(setOf(0x11, 0x4B, 0x4C), 0L).isEmpty())
        assertTrue(matcher.updatePressed(emptySet(), 1L).isEmpty())
        assertEquals(listOf("ctrl-k"), matcher.updatePressed(setOf(0x11, 0x4B), 2L))
    }

    @Test
    fun `atomic key snapshot repeat waits while exact chord remains held`() {
        val matcher = WindowsChordMatcher(
            listOf(WindowsGlobalKeyBinding("volume-up", setOf(0x11, 0x26), repeatWhileHeld = true)),
            initialRepeatDelayMs = 300L,
            repeatIntervalMs = 100L,
        )

        assertEquals(listOf("volume-up"), matcher.updatePressed(setOf(0x11, 0x26), 10L))
        assertTrue(matcher.updatePressed(setOf(0x11, 0x26), 100L).isEmpty())
        assertEquals(listOf("volume-up"), matcher.dueRepeats(310L))
        matcher.updatePressed(emptySet(), 311L)
        assertTrue(matcher.dueRepeats(1_000L).isEmpty())
    }

    @Test
    fun `left and right modifier keys normalize to generic modifier`() {
        val matcher = WindowsChordMatcher(
            listOf(WindowsGlobalKeyBinding("action", setOf(0x11, 0x41))),
        )

        assertTrue(matcher.keyDown(0xA3, 0L).isEmpty()) // VK_RCONTROL
        assertEquals(listOf("action"), matcher.keyDown(0x41, 1L))
    }
}
