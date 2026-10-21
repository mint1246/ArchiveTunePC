/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.linuxmedia

/**
 * Tracks the keys that are held while a shortcut is recorded.
 *
 * The tracker is deliberately free of AWT so the recording rules can be unit tested.
 */
class LinuxChordTracker {
    private val pressed = linkedSetOf<Int>()
    private var best: Set<Int> = emptySet()

    /** Records a key press and returns the keys that are held afterwards. */
    fun keyDown(virtualKey: Int): Set<Int> {
        val key = LinuxVirtualKeys.normalize(virtualKey)
        if (key <= 0) return heldKeys()
        pressed.add(key)
        if (pressed.size > best.size) best = pressed.toSet()
        return heldKeys()
    }

    /** Records a key release and returns the keys that are still held. */
    fun keyUp(virtualKey: Int): Set<Int> {
        pressed.remove(LinuxVirtualKeys.normalize(virtualKey))
        return heldKeys()
    }

    /** The keys that are currently held. */
    fun heldKeys(): Set<Int> = pressed.toSet()

    /** The widest combination of keys that was held together during the recording. */
    fun bestChord(): Set<Int> = best.toSet()

    /**
     * Ends the recording and returns the recorded chord.
     *
     * The chord is the widest combination of keys that was held together, so releasing the keys in
     * any order still records the shortcut the user meant.
     */
    fun complete(): Set<Int> {
        val chord = best
        pressed.clear()
        best = emptySet()
        return chord
    }

    /** Forgets everything that was recorded. */
    fun reset() {
        pressed.clear()
        best = emptySet()
    }
}
