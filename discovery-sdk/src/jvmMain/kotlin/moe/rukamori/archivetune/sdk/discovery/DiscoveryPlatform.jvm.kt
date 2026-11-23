/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val discoveryIoDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual class DiscoveryLock actual constructor() {
    private val monitor = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(monitor, block)
}

internal actual class DiscoveryAtomicInt actual constructor(initial: Int) {
    private val delegate = AtomicInteger(initial)
    actual fun incrementAndGet(): Int = delegate.incrementAndGet()
    actual fun decrementAndGet(): Int = delegate.decrementAndGet()
}
