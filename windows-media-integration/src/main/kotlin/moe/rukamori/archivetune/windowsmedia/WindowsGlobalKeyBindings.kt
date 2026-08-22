/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.windowsmedia

import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One global Windows shortcut. Chords are exact: extra held keys do not also trigger a subset. */
data class WindowsGlobalKeyBinding(
    val actionId: String,
    val virtualKeys: Set<Int>,
    val repeatWhileHeld: Boolean = false,
)

/**
 * Owns the Windows key-state poller used for user-configurable shortcuts.
 *
 * RegisterHotKey cannot express arbitrary three-or-more-key chords or key-up/held state, so custom
 * bindings sample GetAsyncKeyState instead. This intentionally avoids a native callback: JNA's
 * WH_KEYBOARD_LL callback path is unreliable in the Graal native executable even though it works on
 * the JVM. Polling also sees SendInput-style macro keys that Compose/AWT's KeyEventDispatcher may not
 * receive.
 */
class WindowsGlobalKeyBindings(
    private val onAction: (String) -> Unit,
    private val onPressedKeysChanged: (Set<Int>) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private var currentBindings: List<WindowsGlobalKeyBinding> = emptyList()
    private var matcher = WindowsChordMatcher(emptyList())
    private var captureActive = false
    private var lastSample = emptySet<Int>()
    private var closed = false
    private val poller: ScheduledExecutorService? = if (Platform.isWindows()) {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "archive-tune-win-keybind-poller").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(::poll, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    } else {
        null
    }

    fun updateBindings(bindings: List<WindowsGlobalKeyBinding>) {
        val sanitized = bindings
            .mapNotNull { binding ->
                val keys = binding.virtualKeys
                    .asSequence()
                    .map(::normalizeWindowsVirtualKey)
                    .filter { it in 1..0xFF }
                    .toSet()
                binding.takeIf { keys.isNotEmpty() }?.copy(virtualKeys = keys)
            }
            .distinctBy { it.actionId }
        synchronized(lock) {
            if (closed || sanitized == currentBindings) return
            currentBindings = sanitized
            matcher = WindowsChordMatcher(sanitized)
            lastSample = emptySet()
        }
    }

    fun setCaptureActive(active: Boolean) {
        synchronized(lock) {
            if (closed || captureActive == active) return
            captureActive = active
            matcher.clear()
            lastSample = emptySet()
        }
        onPressedKeysChanged(emptySet())
    }

    private fun poll() {
        val shouldPoll = synchronized(lock) {
            !closed && (captureActive || currentBindings.isNotEmpty())
        }
        if (!shouldPoll) return

        val pressed = readWindowsPressedKeys()
        val now = System.currentTimeMillis()
        val actions: List<String>
        val capture: Boolean
        val changed: Boolean
        synchronized(lock) {
            if (closed) return
            capture = captureActive
            changed = pressed != lastSample
            lastSample = pressed
            actions = if (capture) {
                matcher.clear()
                emptyList()
            } else {
                matcher.updatePressed(pressed, now) + matcher.dueRepeats(now)
            }
        }

        if (capture && changed) onPressedKeysChanged(pressed)
        if (!capture) actions.forEach { actionId -> runCatching { onAction(actionId) } }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            currentBindings = emptyList()
            matcher.clear()
            captureActive = false
            lastSample = emptySet()
        }
        poller?.shutdownNow()
        onPressedKeysChanged(emptySet())
    }

    private companion object {
        const val POLL_INTERVAL_MS = 8L
    }
}

/** Pure chord state machine, separate from JNA so held/repeat behavior can be unit-tested anywhere. */
internal class WindowsChordMatcher(
    bindings: List<WindowsGlobalKeyBinding>,
    private val initialRepeatDelayMs: Long = 350L,
    private val repeatIntervalMs: Long = 80L,
) {
    private val bindings = bindings.associateBy { it.actionId }
    private val pressed = linkedSetOf<Int>()
    private val active = linkedMapOf<String, Long>()

    init {
        require(initialRepeatDelayMs >= 0L)
        require(repeatIntervalMs > 0L)
    }

    @Synchronized
    fun keyDown(virtualKey: Int, nowMs: Long): List<String> {
        val key = normalizeWindowsVirtualKey(virtualKey)
        if (!pressed.add(key)) return emptyList()
        return recalculate(nowMs)
    }

    /** Atomically replaces the observed chord so an extra simultaneous key cannot fire a subset. */
    @Synchronized
    fun updatePressed(virtualKeys: Set<Int>, nowMs: Long): List<String> {
        pressed.clear()
        virtualKeys.asSequence().map(::normalizeWindowsVirtualKey).forEach(pressed::add)
        return recalculate(nowMs)
    }

    @Synchronized
    fun keyUp(virtualKey: Int, nowMs: Long) {
        pressed.remove(normalizeWindowsVirtualKey(virtualKey))
        recalculate(nowMs)
    }

    @Synchronized
    fun dueRepeats(nowMs: Long): List<String> {
        if (active.isEmpty()) return emptyList()
        val fired = mutableListOf<String>()
        active.entries.toList().forEach { (actionId, nextAt) ->
            val binding = bindings[actionId] ?: return@forEach
            if (!binding.repeatWhileHeld || nowMs < nextAt) return@forEach
            fired += actionId
            val overdueIntervals = ((nowMs - nextAt) / repeatIntervalMs).coerceAtLeast(0L)
            active[actionId] = nextAt + (overdueIntervals + 1L) * repeatIntervalMs
        }
        return fired
    }

    @Synchronized
    fun clear() {
        pressed.clear()
        active.clear()
    }

    private fun recalculate(nowMs: Long): List<String> {
        val matching = bindings.values.filter { it.virtualKeys == pressed }.mapTo(linkedSetOf()) { it.actionId }
        active.keys.retainAll(matching)
        val newlyActive = matching.filterNot(active::containsKey)
        newlyActive.forEach { actionId ->
            val binding = bindings.getValue(actionId)
            active[actionId] = if (binding.repeatWhileHeld) nowMs + initialRepeatDelayMs else Long.MAX_VALUE
        }
        return newlyActive
    }
}

internal fun normalizeWindowsVirtualKey(virtualKey: Int): Int = when (virtualKey) {
    VK_LSHIFT, VK_RSHIFT -> VK_SHIFT
    VK_LCONTROL, VK_RCONTROL -> VK_CONTROL
    VK_LMENU, VK_RMENU -> VK_MENU
    VK_RWIN -> VK_LWIN
    else -> virtualKey
}

private fun readWindowsPressedKeys(): Set<Int> = buildSet {
    // 0x01..0x06 are mouse buttons. Keyboard virtual keys start at VK_BACK (0x08).
    for (virtualKey in 0x08..0xFE) {
        val state = User32.INSTANCE.GetAsyncKeyState(virtualKey).toInt()
        val currentlyDown = state and 0x8000 != 0
        val pressedSinceLastPoll = state and 0x0001 != 0
        if (currentlyDown || pressedSinceLastPoll) add(normalizeWindowsVirtualKey(virtualKey))
    }
}

private const val VK_SHIFT = 0x10
private const val VK_CONTROL = 0x11
private const val VK_MENU = 0x12
private const val VK_LWIN = 0x5B
private const val VK_RWIN = 0x5C
private const val VK_LSHIFT = 0xA0
private const val VK_RSHIFT = 0xA1
private const val VK_LCONTROL = 0xA2
private const val VK_RCONTROL = 0xA3
private const val VK_LMENU = 0xA4
private const val VK_RMENU = 0xA5
