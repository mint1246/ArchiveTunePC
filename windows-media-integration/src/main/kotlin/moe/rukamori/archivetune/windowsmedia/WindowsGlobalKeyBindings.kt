/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.windowsmedia

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One global Windows shortcut. Unrelated held keys do not block a configured chord. */
data class WindowsGlobalKeyBinding(
    val actionId: String,
    val virtualKeys: Set<Int>,
    val repeatWhileHeld: Boolean = false,
)

/**
 * Owns the Windows key-state poller used for user-configurable shortcuts.
 *
 * RegisterHotKey is used when Windows can represent the chord (modifiers + one key), which makes
 * ordinary shortcuts reliable even while a game owns the foreground input path. Arbitrary chords and
 * held/repeat state are still sampled with GetAsyncKeyState. This intentionally avoids a native
 * callback: JNA's WH_KEYBOARD_LL callback path is unreliable in the Graal native executable even
 * though it works on the JVM. Polling also sees SendInput-style macro keys that Compose/AWT's
 * KeyEventDispatcher may not receive.
 */
class WindowsGlobalKeyBindings(
    private val onAction: (String) -> Unit,
    private val onPressedKeysChanged: (Set<Int>) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private var currentBindings: List<WindowsGlobalKeyBinding> = emptyList()
    private var matcher = WindowsChordMatcher(emptyList())
    @Volatile private var captureActive = false
    private var lastSample = emptySet<Int>()
    private var closed = false
    private var registeredHotKeys: WindowsRegisteredGlobalHotKeys? = null
    private var registrationGeneration = 0L
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
        val oldRegistration: WindowsRegisteredGlobalHotKeys?
        val generation: Long
        synchronized(lock) {
            if (closed || sanitized == currentBindings) return
            registrationGeneration += 1L
            generation = registrationGeneration
            oldRegistration = registeredHotKeys
            registeredHotKeys = null
            currentBindings = sanitized
            matcher = WindowsChordMatcher(sanitized)
            lastSample = emptySet()
        }
        oldRegistration?.close()

        val newRegistration = WindowsRegisteredGlobalHotKeys.start(sanitized) { actionId ->
            onRegisteredHotKey(generation, actionId)
        }
        val installed = synchronized(lock) {
            if (!closed && registrationGeneration == generation && currentBindings == sanitized) {
                registeredHotKeys = newRegistration
                true
            } else {
                false
            }
        }
        if (!installed) newRegistration?.close()
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

    private fun onRegisteredHotKey(generation: Long, actionId: String) {
        val shouldFire = synchronized(lock) {
            if (closed || captureActive || generation != registrationGeneration) {
                false
            } else {
                matcher.activateExternally(actionId, System.currentTimeMillis())
            }
        }
        if (shouldFire) runCatching { onAction(actionId) }
    }

    override fun close() {
        val registration: WindowsRegisteredGlobalHotKeys?
        synchronized(lock) {
            if (closed) return
            closed = true
            registrationGeneration += 1L
            currentBindings = emptyList()
            matcher.clear()
            captureActive = false
            lastSample = emptySet()
            registration = registeredHotKeys
            registeredHotKeys = null
        }
        registration?.close()
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

    /** Atomically replaces the observed keyboard state before evaluating configured chords. */
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

    /** Marks an OS-delivered hotkey active so the polling path cannot fire the same press twice. */
    @Synchronized
    fun activateExternally(actionId: String, nowMs: Long): Boolean {
        if (active.containsKey(actionId)) return false
        val binding = bindings[actionId] ?: return false
        active[actionId] = if (binding.repeatWhileHeld) nowMs + initialRepeatDelayMs else Long.MAX_VALUE
        return true
    }

    @Synchronized
    fun clear() {
        pressed.clear()
        active.clear()
    }

    private fun recalculate(nowMs: Long): List<String> {
        val candidates = bindings.values.filter { binding -> pressed.containsAll(binding.virtualKeys) }
        val matching = candidates
            .filter { candidate ->
                candidates.none { other ->
                    other.actionId != candidate.actionId &&
                        other.virtualKeys.size > candidate.virtualKeys.size &&
                        other.virtualKeys.containsAll(candidate.virtualKeys)
                }
            }
            .mapTo(linkedSetOf()) { it.actionId }
        active.keys.retainAll(matching)
        val newlyActive = matching.filterNot(active::containsKey)
        newlyActive.forEach { actionId ->
            val binding = bindings.getValue(actionId)
            active[actionId] = if (binding.repeatWhileHeld) nowMs + initialRepeatDelayMs else Long.MAX_VALUE
        }
        return newlyActive
    }
}

internal data class WindowsRegisterableGlobalHotKey(
    val actionId: String,
    val virtualKey: Int,
    val modifiers: Int,
)

internal fun WindowsGlobalKeyBinding.toRegisterableHotKey(): WindowsRegisterableGlobalHotKey? {
    val normalized = virtualKeys.mapTo(linkedSetOf(), ::normalizeWindowsVirtualKey)
    val nonModifiers = normalized.filterNot(::isWindowsModifierKey)
    if (nonModifiers.size != 1) return null

    var modifiers = MOD_NOREPEAT
    if (VK_SHIFT in normalized) modifiers = modifiers or MOD_SHIFT
    if (VK_CONTROL in normalized) modifiers = modifiers or MOD_CONTROL
    if (VK_MENU in normalized) modifiers = modifiers or MOD_ALT
    if (VK_LWIN in normalized) modifiers = modifiers or MOD_WIN
    return WindowsRegisterableGlobalHotKey(actionId, nonModifiers.single(), modifiers)
}

/** Dedicated RegisterHotKey message loop for the subset of user bindings Windows can represent. */
private class WindowsRegisteredGlobalHotKeys private constructor(
    private val bindings: List<WindowsRegisterableGlobalHotKey>,
    private val onAction: (String) -> Unit,
) : AutoCloseable {
    private val started = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    @Volatile private var threadId = 0
    @Volatile private var closed = false
    private val thread = Thread(::runLoop, "archive-tune-win-global-hotkeys").apply {
        isDaemon = true
        start()
    }

    init {
        if (!started.await(2, TimeUnit.SECONDS)) {
            close()
            throw UnsupportedOperationException("Windows global-hotkey loop did not start")
        }
    }

    private fun runLoop() {
        threadId = Kernel32.INSTANCE.GetCurrentThreadId()
        val owned = mutableListOf<Int>()
        try {
            val actionsById = linkedMapOf<Int, String>()
            bindings.forEachIndexed { index, binding ->
                val id = REGISTERED_HOTKEY_ID_BASE + index
                if (User32.INSTANCE.RegisterHotKey(null, id, binding.modifiers, binding.virtualKey)) {
                    owned += id
                    actionsById[id] = binding.actionId
                }
            }
            started.countDown()
            if (owned.isEmpty()) return

            val message = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
                if (message.message == WinUser.WM_HOTKEY) {
                    actionsById[message.wParam.toInt()]?.let(onAction)
                }
            }
        } finally {
            started.countDown()
            owned.forEach { id -> runCatching { User32.INSTANCE.UnregisterHotKey(null, id) } }
            stopped.countDown()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val id = threadId
        if (id != 0) User32.INSTANCE.PostThreadMessage(id, WinUser.WM_QUIT, null, null)
        stopped.await(2, TimeUnit.SECONDS)
    }

    companion object {
        fun start(
            bindings: List<WindowsGlobalKeyBinding>,
            onAction: (String) -> Unit,
        ): WindowsRegisteredGlobalHotKeys? {
            if (!Platform.isWindows()) return null
            val registerable = bindings
                .filter { binding ->
                    bindings.none { other ->
                        other.actionId != binding.actionId &&
                            other.virtualKeys.size > binding.virtualKeys.size &&
                            other.virtualKeys.containsAll(binding.virtualKeys)
                    }
                }
                .mapNotNull(WindowsGlobalKeyBinding::toRegisterableHotKey)
            if (registerable.isEmpty()) return null
            return runCatching { WindowsRegisteredGlobalHotKeys(registerable, onAction) }.getOrNull()
        }

        private const val REGISTERED_HOTKEY_ID_BASE = 0x4100
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
private const val MOD_ALT = 0x0001
private const val MOD_CONTROL = 0x0002
private const val MOD_SHIFT = 0x0004
private const val MOD_WIN = 0x0008
private const val MOD_NOREPEAT = 0x4000

private fun isWindowsModifierKey(virtualKey: Int): Boolean =
    virtualKey == VK_SHIFT || virtualKey == VK_CONTROL || virtualKey == VK_MENU || virtualKey == VK_LWIN
