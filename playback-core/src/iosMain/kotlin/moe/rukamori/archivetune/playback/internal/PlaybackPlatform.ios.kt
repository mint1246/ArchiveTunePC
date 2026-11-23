/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.timeIntervalSince1970

internal actual fun platformCurrentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual class PlaybackAtomicBoolean actual constructor(initial: Boolean) {
    private val lock = NSLock()
    private var value = initial

    actual fun get(): Boolean {
        lock.lock()
        return try {
            value
        } finally {
            lock.unlock()
        }
    }

    actual fun set(value: Boolean) {
        lock.lock()
        try {
            this.value = value
        } finally {
            lock.unlock()
        }
    }

    actual fun compareAndSet(expected: Boolean, newValue: Boolean): Boolean {
        lock.lock()
        return try {
            if (value == expected) {
                value = newValue
                true
            } else {
                false
            }
        } finally {
            lock.unlock()
        }
    }
}

internal actual class PlaybackAtomicLong actual constructor(initial: Long) {
    private val lock = NSLock()
    private var value = initial

    actual fun get(): Long {
        lock.lock()
        return try {
            value
        } finally {
            lock.unlock()
        }
    }

    actual fun incrementAndGet(): Long {
        lock.lock()
        return try {
            ++value
        } finally {
            lock.unlock()
        }
    }
}
