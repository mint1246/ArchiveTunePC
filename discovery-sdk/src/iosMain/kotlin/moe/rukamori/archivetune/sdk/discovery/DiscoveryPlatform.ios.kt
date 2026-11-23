/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSLock
import platform.Foundation.NSRecursiveLock

internal actual val discoveryIoDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual class DiscoveryLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

internal actual class DiscoveryAtomicInt actual constructor(initial: Int) {
    private val lock = NSLock()
    private var value = initial

    actual fun incrementAndGet(): Int {
        lock.lock()
        return try {
            ++value
        } finally {
            lock.unlock()
        }
    }

    actual fun decrementAndGet(): Int {
        lock.lock()
        return try {
            --value
        } finally {
            lock.unlock()
        }
    }
}
