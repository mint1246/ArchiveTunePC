/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import kotlinx.coroutines.CoroutineDispatcher

internal expect val discoveryIoDispatcher: CoroutineDispatcher

internal expect class DiscoveryLock() {
    fun <T> withLock(block: () -> T): T
}

internal expect class DiscoveryAtomicInt(initial: Int) {
    fun incrementAndGet(): Int
    fun decrementAndGet(): Int
}
