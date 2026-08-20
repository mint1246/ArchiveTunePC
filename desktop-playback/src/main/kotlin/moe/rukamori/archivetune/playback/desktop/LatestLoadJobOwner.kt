/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.playback.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the single source-load coroutine that is allowed to make progress for a controller.
 *
 * Submitting a newer accepted request cancels the previous job. Requests that have already become
 * stale are rejected before they can cancel the current job; callers must still keep their normal
 * generation/current-item guard after suspension because cancellation is cooperative.
 */
internal class LatestLoadJobOwner<T>(
    private val scope: CoroutineScope,
    private val accept: (T) -> Boolean,
    private val execute: suspend (T) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var currentJob: Job? = null
    private var closed = false

    fun submit(request: T) {
        val next: Job
        val previous: Job?
        synchronized(lock) {
            if (closed || !accept(request)) return
            next = scope.launch(start = CoroutineStart.LAZY) { execute(request) }
            previous = currentJob
            currentJob = next
            next.invokeOnCompletion {
                synchronized(lock) {
                    if (currentJob === next) currentJob = null
                }
            }
        }
        previous?.cancel()
        next.start()
    }

    fun cancelCurrent() {
        val previous = synchronized(lock) {
            val existing = currentJob
            currentJob = null
            existing
        }
        previous?.cancel()
    }

    override fun close() {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true
            val existing = currentJob
            currentJob = null
            existing
        }
        previous?.cancel()
    }
}
