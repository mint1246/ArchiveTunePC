/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher

private val lyricsMonotonicOrigin = TimeSource.Monotonic.markNow()

internal fun lyricsMonotonicMillis(): Long = lyricsMonotonicOrigin.elapsedNow().inWholeMilliseconds

internal expect val lyricsIoDispatcher: CoroutineDispatcher

internal expect class LyricsLock() {
    fun <T> withLock(block: () -> T): T
}
