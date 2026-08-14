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
        private val bindings = linkedMapOf(
            1 to MediaCommand.PLAY_PAUSE,
            2 to MediaCommand.NEXT,
            3 to MediaCommand.PREVIOUS,
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
        }

        private fun runLoop() {
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            bindings.forEach { (id, command) ->
                val virtualKey = when (command) {
                    MediaCommand.PLAY_PAUSE -> 0xB3 // VK_MEDIA_PLAY_PAUSE
                    MediaCommand.NEXT -> 0xB0 // VK_MEDIA_NEXT_TRACK
                    MediaCommand.PREVIOUS -> 0xB1 // VK_MEDIA_PREV_TRACK
                    else -> error("No Windows virtual key")
                }
                User32.INSTANCE.RegisterHotKey(null, id, 0, virtualKey)
            }
            started.countDown()
            try {
                val message = WinUser.MSG()
                while (User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
                    if (message.message == WinUser.WM_HOTKEY) {
                        bindings[message.wParam.toInt()]?.let(onCommand)
                    }
                }
            } finally {
                bindings.keys.forEach { User32.INSTANCE.UnregisterHotKey(null, it) }
                stopped.countDown()
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
