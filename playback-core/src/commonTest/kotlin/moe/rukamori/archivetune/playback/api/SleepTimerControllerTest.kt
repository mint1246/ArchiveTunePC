/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerControllerTest {
    @Test
    fun timer_publishes_deadline_and_pauses_once_at_elapsed_duration() = runTest {
        var pauses = 0
        val states = mutableListOf<SleepTimerState>()
        val controller = SleepTimerController(this, { pauses++ }, states::add) { 1_000L }

        controller.start(2)
        assertEquals(121_000L, controller.state.triggerTime)
        assertTrue(controller.state.isActive)
        advanceTimeBy(119_999)
        assertEquals(0, pauses)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, pauses)
        assertEquals(controller.state, states.last())
        controller.clear()
        assertFalse(controller.state.isActive)
    }

    @Test
    fun replacing_and_clearing_timers_cancel_obsolete_callbacks() = runTest {
        var pauses = 0
        val controller = SleepTimerController(this, { pauses++ })
        controller.start(1)
        advanceTimeBy(30_000)
        controller.start(2)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, pauses)
        controller.close()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, pauses)
        assertFalse(controller.state.isActive)
    }

    @Test
    fun song_end_mode_pauses_on_transition_and_ended_but_not_before_being_enabled() = runTest {
        var pauses = 0
        val controller = SleepTimerController(this, { pauses++ })
        controller.onSongTransition()
        controller.onPlaybackEnded()
        assertEquals(0, pauses)
        controller.start(-1)
        assertTrue(controller.state.pauseWhenSongEnd)
        controller.onSongTransition()
        controller.onPlaybackEnded()
        assertEquals(2, pauses)
        controller.clear()
        controller.onPlaybackEnded()
        assertEquals(2, pauses)
    }

    @Test
    fun pause_callback_can_clear_timer_before_subsequent_player_events() = runTest {
        var pauses = 0
        lateinit var controller: SleepTimerController
        controller = SleepTimerController(this, {
            pauses++
            controller.clear()
        })
        controller.start(-1)
        controller.onSongTransition()
        controller.onPlaybackEnded()
        assertEquals(1, pauses)
        assertFalse(controller.state.isActive)
    }

    @Test
    fun switching_modes_preserves_previous_state_as_Android_did() = runTest {
        val controller = SleepTimerController(this, {}, currentTimeMillis = { 10L })
        controller.start(1)
        controller.start(-1)
        assertEquals(60_010L, controller.state.triggerTime)
        assertTrue(controller.state.pauseWhenSongEnd)
        controller.start(2)
        assertEquals(120_010L, controller.state.triggerTime)
        assertTrue(controller.state.pauseWhenSongEnd)
        controller.clear()
    }
}
