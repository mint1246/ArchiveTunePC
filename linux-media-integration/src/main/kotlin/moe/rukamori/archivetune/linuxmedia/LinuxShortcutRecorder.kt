package moe.rukamori.archivetune.linuxmedia

/** A completed chord stays available even when every key event arrives before the next UI frame. */
data class LinuxShortcutRecording(
    val session: Long = 0L,
    val heldKeys: List<Int> = emptyList(),
    val bestChord: List<Int> = emptyList(),
    val completedChord: List<Int>? = null,
    val cancelled: Boolean = false,
)

internal class LinuxShortcutRecorder {
    private val tracker = LinuxChordTracker()
    var state = LinuxShortcutRecording()
        private set
    var active = false
        private set

    fun start(): LinuxShortcutRecording {
        tracker.reset()
        active = true
        state = LinuxShortcutRecording(session = state.session + 1L)
        return state
    }

    fun press(key: Int, modifiers: Set<Int> = emptySet()): LinuxShortcutRecording {
        if (!active) return state
        // The ordinary key's modifier mask also covers modifiers whose separate key-down event
        // was consumed by the window system (or was pressed just before capture started).
        modifiers.forEach(tracker::keyDown)
        tracker.keyDown(key)
        return publish()
    }

    fun release(key: Int): LinuxShortcutRecording {
        if (!active) return state
        tracker.keyUp(key)
        val chord = tracker.bestChord()
        if (tracker.heldKeys().isEmpty()) {
            if (chord.any { !LinuxVirtualKeys.isModifier(it) }) {
                active = false
                state = state.copy(heldKeys = emptyList(), bestChord = chord.sorted(), completedChord = chord.sorted())
                return state
            }
            // Releasing Ctrl or Shift alone must not finish recording a shortcut.
            tracker.reset()
        }
        return publish()
    }

    fun cancel(): LinuxShortcutRecording {
        if (active) {
            active = false
            tracker.reset()
            state = state.copy(heldKeys = emptyList(), bestChord = emptyList(), cancelled = true)
        }
        return state
    }

    private fun publish(): LinuxShortcutRecording {
        state = state.copy(heldKeys = tracker.heldKeys().sorted(), bestChord = tracker.bestChord().sorted())
        return state
    }
}
