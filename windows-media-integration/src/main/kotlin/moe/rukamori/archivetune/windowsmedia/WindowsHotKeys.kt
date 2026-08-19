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
import java.util.concurrent.TimeUnit

/** Isolated native boundary; tests replace this with a network- and Windows-free fake. */
fun interface WindowsHotKeySource {
    fun start(onCommand: (MediaCommand) -> Unit): AutoCloseable
}

internal data class WindowsHotKeyBinding(
    val id: Int,
    val virtualKey: Int,
    val command: MediaCommand,
)

internal interface WindowsHotKeyRegistrationApi {
    fun register(id: Int, virtualKey: Int): Boolean
    fun unregister(id: Int)
}

/** Owns only registrations which Windows actually granted to this process. */
internal class WindowsHotKeyRegistrations(
    private val api: WindowsHotKeyRegistrationApi,
) : AutoCloseable {
    private val owned = linkedMapOf<Int, MediaCommand>()

    fun register(bindings: List<WindowsHotKeyBinding>): Map<Int, MediaCommand> {
        check(owned.isEmpty()) { "Windows hot keys are already registered" }
        try {
            bindings.forEach { binding ->
                if (api.register(binding.id, binding.virtualKey)) {
                    owned[binding.id] = binding.command
                }
            }
            if (owned.isEmpty()) {
                throw UnsupportedOperationException("No Windows media keys could be registered")
            }
            return owned.toMap()
        } catch (failure: Throwable) {
            runCatching { close() }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    override fun close() {
        val registrations = owned.keys.toList()
        owned.clear()
        var firstFailure: Throwable? = null
        registrations.forEach { id ->
            try {
                api.unregister(id)
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }
}

private object JnaWindowsHotKeyRegistrationApi : WindowsHotKeyRegistrationApi {
    override fun register(id: Int, virtualKey: Int): Boolean =
        User32.INSTANCE.RegisterHotKey(null, id, 0, virtualKey)

    override fun unregister(id: Int) {
        User32.INSTANCE.UnregisterHotKey(null, id)
    }
}

/**
 * Captures the Windows virtual media keys using RegisterHotKey and a dedicated message queue.
 * Windows may reserve a key for another app; unavailable keys are simply not registered.
 */
class JnaWindowsHotKeySource : WindowsHotKeySource {
    override fun start(onCommand: (MediaCommand) -> Unit): AutoCloseable {
        if (!Platform.isWindows()) throw UnsupportedOperationException("Windows media keys require Windows")
        return JnaRegistration(onCommand).also { it.awaitStarted() }
    }

    private class JnaRegistration(private val onCommand: (MediaCommand) -> Unit) : AutoCloseable {
        private val started = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        @Volatile private var threadId = 0
        @Volatile private var closed = false
        @Volatile private var startupFailure: Throwable? = null
        private val bindings = listOf(
            WindowsHotKeyBinding(1, 0xB3, MediaCommand.PLAY_PAUSE), // VK_MEDIA_PLAY_PAUSE
            WindowsHotKeyBinding(2, 0xB0, MediaCommand.NEXT), // VK_MEDIA_NEXT_TRACK
            WindowsHotKeyBinding(3, 0xB1, MediaCommand.PREVIOUS), // VK_MEDIA_PREV_TRACK
        )
        private val thread = Thread(::runLoop, "archive-tune-win-media-keys").apply {
            isDaemon = true
            start()
        }

        fun awaitStarted() {
            if (!started.await(3, TimeUnit.SECONDS)) {
                close()
                throw UnsupportedOperationException("Windows media-key loop did not start")
            }
            startupFailure?.let { failure ->
                close()
                if (failure is UnsupportedOperationException) throw failure
                throw UnsupportedOperationException("Windows media-key registration failed", failure)
            }
        }

        private fun runLoop() {
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            val registrations = WindowsHotKeyRegistrations(JnaWindowsHotKeyRegistrationApi)
            try {
                val activeBindings = registrations.register(bindings)
                started.countDown()
                val message = WinUser.MSG()
                while (User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
                    if (message.message == WinUser.WM_HOTKEY) {
                        activeBindings[message.wParam.toInt()]?.let(onCommand)
                    }
                }
            } catch (failure: Throwable) {
                startupFailure = failure
                started.countDown()
            } finally {
                try {
                    registrations.close()
                } finally {
                    stopped.countDown()
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            val id = threadId
            if (id != 0) User32.INSTANCE.PostThreadMessage(id, WinUser.WM_QUIT, null, null)
            stopped.await(3, TimeUnit.SECONDS)
        }
    }
}
