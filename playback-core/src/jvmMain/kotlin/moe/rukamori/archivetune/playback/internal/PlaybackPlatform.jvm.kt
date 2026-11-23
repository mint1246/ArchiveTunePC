/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.internal

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

internal actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual class PlaybackAtomicBoolean actual constructor(initial: Boolean) {
    private val delegate = AtomicBoolean(initial)

    actual fun get(): Boolean = delegate.get()

    actual fun set(value: Boolean) = delegate.set(value)

    actual fun compareAndSet(expected: Boolean, newValue: Boolean): Boolean =
        delegate.compareAndSet(expected, newValue)
}

internal actual class PlaybackAtomicLong actual constructor(initial: Long) {
    private val delegate = AtomicLong(initial)

    actual fun get(): Long = delegate.get()

    actual fun incrementAndGet(): Long = delegate.incrementAndGet()
}
