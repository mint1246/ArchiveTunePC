/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.internal

import kotlinx.coroutines.CoroutineDispatcher

internal expect fun platformCurrentTimeMillis(): Long

internal expect val platformIoDispatcher: CoroutineDispatcher

internal expect class PlaybackAtomicBoolean(initial: Boolean) {
    fun get(): Boolean
    fun set(value: Boolean)
    fun compareAndSet(expected: Boolean, newValue: Boolean): Boolean
}

internal expect class PlaybackAtomicLong(initial: Long) {
    fun get(): Long
    fun incrementAndGet(): Long
}

internal expect class PlaybackLock() {
    fun <T> withLock(block: () -> T): T
}
