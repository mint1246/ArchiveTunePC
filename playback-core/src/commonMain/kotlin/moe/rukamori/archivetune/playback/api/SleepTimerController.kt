/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.playback.internal.platformCurrentTimeMillis
import kotlin.time.Duration.Companion.minutes

data class SleepTimerState(
    val triggerTime: Long = -1L,
    val pauseWhenSongEnd: Boolean = false,
) {
    val isActive: Boolean get() = triggerTime != -1L || pauseWhenSongEnd
}

/** Calls must be serialized on the same dispatcher as the supplied scope. */
class SleepTimerController(
    private val scope: CoroutineScope,
    private val pause: () -> Unit,
    private val onStateChanged: (SleepTimerState) -> Unit = {},
    private val currentTimeMillis: () -> Long = ::platformCurrentTimeMillis,
) : AutoCloseable {
    private var job: Job? = null
    var state = SleepTimerState()
        private set

    fun start(minute: Int) {
        job?.cancel()
        job = null
        if (minute == -1) {
            update(state.copy(pauseWhenSongEnd = true))
        } else {
            update(state.copy(triggerTime = currentTimeMillis() + minute.minutes.inWholeMilliseconds))
            job = scope.launch {
                delay(minute.minutes)
                pause()
            }
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        update(SleepTimerState())
    }

    fun onSongTransition() {
        if (state.pauseWhenSongEnd) pause()
    }

    fun onPlaybackEnded() {
        if (state.pauseWhenSongEnd) pause()
    }

    override fun close() = clear()

    private fun update(newState: SleepTimerState) {
        state = newState
        onStateChanged(newState)
    }
}
