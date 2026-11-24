/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.playback.internal.PlaybackAtomicLong

/**
 * Coordinates non-critical background network work (related tracks, radio queues, artwork,
 * speculative preloading, beat analysis) so it does not compete for bandwidth while a track
 * is actively resolving or buffering its initial audio on low-bandwidth links.
 *
 * Call [beginStartup] / [markReady] directly or feed snapshots via [observeSnapshot]. Background
 * tasks call [awaitStartupClear], which returns immediately when playback is already ready or
 * idle, and unblocks as soon as playback starts or [maxWaitMs] elapses.
 */
class PlaybackStartupContentionGate(
    private val defaultMaxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
) {
    private val generation = PlaybackAtomicLong(0L)
    private val mutableStartingUp = MutableStateFlow(false)

    val isStartingUp: StateFlow<Boolean> = mutableStartingUp.asStateFlow()

    /** Marks the start of a new track startup phase and returns its generation token. */
    fun beginStartup(): Long {
        val token = generation.incrementAndGet()
        mutableStartingUp.value = true
        return token
    }

    /**
     * Releases the startup gate when playback becomes ready, paused, idle, or failed.
     * Passing [token] ignores stale completions from superseded startup generations.
     */
    fun markReady(token: Long? = null) {
        if (token != null && token != generation.get()) return
        mutableStartingUp.value = false
    }

    /** Updates gate state from a [PlaybackSnapshot] stream. */
    fun observeSnapshot(snapshot: PlaybackSnapshot) {
        val starting =
            snapshot.playWhenReady &&
                (
                    snapshot.status == PlaybackStatus.RESOLVING ||
                        snapshot.status == PlaybackStatus.LOADING ||
                        (snapshot.status == PlaybackStatus.BUFFERING && snapshot.positionMs <= INITIAL_BUFFER_POSITION_MS)
                )
        if (mutableStartingUp.value != starting) {
            if (starting) {
                generation.incrementAndGet()
            }
            mutableStartingUp.value = starting
        }
    }

    /** Resets the gate to clear (e.g. on controller close or stop). */
    fun reset() {
        generation.incrementAndGet()
        mutableStartingUp.value = false
    }

    /**
     * Suspends until the current track finishes initial startup or [maxWaitMs] elapses.
     * Returns `true` if startup cleared cleanly, or `false` if the safety timeout elapsed.
     */
    suspend fun awaitStartupClear(maxWaitMs: Long = defaultMaxWaitMs): Boolean {
        if (!mutableStartingUp.value) return true
        val clamped = maxWaitMs.coerceAtLeast(0L)
        if (clamped == 0L) return !mutableStartingUp.value
        val cleared = withTimeoutOrNull(clamped) {
            mutableStartingUp.first { !it }
            true
        }
        return cleared == true
    }

    companion object {
        const val DEFAULT_MAX_WAIT_MS: Long = 4_000L
        private const val INITIAL_BUFFER_POSITION_MS: Long = 500L
    }
}
