/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFocusCoordinatorTest {
    private class Fixture {
        var requestCalls = 0
        var abandonCalls = 0
        var requestGranted = true

        fun coordinator() =
            AudioFocusCoordinator(
                requestFocus = {
                    requestCalls++
                    requestGranted
                },
                abandonFocus = { abandonCalls++ },
            )
    }

    @Test
    fun transientLoss_pausesAndSetsResumeFlag_thenGainResumesOnce() {
        val fixture = Fixture()
        val coordinator = fixture.coordinator()

        val loss = coordinator.onFocusChange(MediaFocusType.LOSS_TRANSIENT, playWhenReady = true)
        assertEquals(FocusPlaybackAction.PAUSE, loss.playbackAction)
        assertFalse(coordinator.hasFocus)
        assertTrue(coordinator.wasPlayingBeforeLoss)
        assertEquals(0, fixture.abandonCalls)

        val gain = coordinator.onFocusChange(MediaFocusType.GAIN, playWhenReady = false)
        assertEquals(FocusPlaybackAction.RESUME, gain.playbackAction)
        assertTrue(coordinator.hasFocus)
        assertFalse(coordinator.wasPlayingBeforeLoss)

        val repeatedGain = coordinator.onFocusChange(MediaFocusType.GAIN, playWhenReady = false)
        assertEquals(FocusPlaybackAction.NONE, repeatedGain.playbackAction)
    }

    @Test
    fun permanentLoss_neverSetsResumeFlag_andDoesNotAbandonAlreadyClearedFocus() {
        val fixture = Fixture()
        val coordinator = fixture.coordinator()
        assertTrue(coordinator.request())

        val loss = coordinator.onFocusChange(MediaFocusType.LOSS, playWhenReady = true)
        assertEquals(FocusPlaybackAction.PAUSE, loss.playbackAction)
        assertFalse(coordinator.hasFocus)
        assertFalse(coordinator.wasPlayingBeforeLoss)
        // The service clears focus before abandon, so the abandon callback is never invoked.
        assertEquals(0, fixture.abandonCalls)
    }

    @Test
    fun duckingLoss_pausesWhenPlayingButDoesNotResumeOnMayDuckGain() {
        val fixture = Fixture()
        val coordinator = fixture.coordinator()

        val duckLoss =
            coordinator.onFocusChange(MediaFocusType.LOSS_TRANSIENT_CAN_DUCK, playWhenReady = true)
        assertEquals(FocusPlaybackAction.PAUSE, duckLoss.playbackAction)
        assertTrue(coordinator.wasPlayingBeforeLoss)

        val duckGain =
            coordinator.onFocusChange(MediaFocusType.GAIN_TRANSIENT_MAY_DUCK, playWhenReady = false)
        assertEquals(FocusPlaybackAction.NONE, duckGain.playbackAction)
        assertTrue(coordinator.wasPlayingBeforeLoss)
        assertEquals(1f, coordinator.volumeFactor.value)

        // A full gain still resumes after the may-duck gain left the flag untouched.
        val fullGain = coordinator.onFocusChange(MediaFocusType.GAIN, playWhenReady = false)
        assertEquals(FocusPlaybackAction.RESUME, fullGain.playbackAction)
    }

    @Test
    fun lossWhilePaused_doesNotRequestResumeLater() {
        val fixture = Fixture()
        val coordinator = fixture.coordinator()

        val loss =
            coordinator.onFocusChange(MediaFocusType.LOSS_TRANSIENT, playWhenReady = false)
        assertEquals(FocusPlaybackAction.NONE, loss.playbackAction)
        assertFalse(coordinator.wasPlayingBeforeLoss)

        val gain = coordinator.onFocusChange(MediaFocusType.GAIN, playWhenReady = false)
        assertEquals(FocusPlaybackAction.NONE, gain.playbackAction)
    }

    @Test
    fun request_isSingleFlightOnceFocusIsOwned() {
        val fixture = Fixture()
        val coordinator = fixture.coordinator()

        assertTrue(coordinator.request())
        assertEquals(1, fixture.requestCalls)

        // A transient loss followed by a gain re-establishes ownership; a later request while
        // focus is owned must still avoid a second platform request.
        coordinator.onFocusChange(MediaFocusType.LOSS_TRANSIENT, playWhenReady = false)
        coordinator.onFocusChange(MediaFocusType.GAIN, playWhenReady = false)

        assertTrue(coordinator.request())
        assertEquals(1, fixture.requestCalls, "owned focus must not re-request from the platform")
        assertEquals(MediaFocusType.GAIN, coordinator.lastType)
        assertEquals(1f, coordinator.volumeFactor.value)
    }

    @Test
    fun deniedFocusRequest_doesNotClaimOwnership() {
        val fixture = Fixture().apply { requestGranted = false }
        val coordinator = fixture.coordinator()

        assertFalse(coordinator.request())
        assertFalse(coordinator.hasFocus)
        assertEquals(1, fixture.requestCalls)
        assertEquals(MediaFocusType.NONE, coordinator.lastType)
    }

    @Test
    fun abandon_isIdempotent_andCallsPlatformOnlyWhenOwned() {
        val fixture = Fixture()
        val coordinator = fixture.coordinator()

        coordinator.abandon()
        assertEquals(0, fixture.abandonCalls)

        assertTrue(coordinator.request())
        coordinator.abandon()
        assertEquals(1, fixture.abandonCalls)
        assertFalse(coordinator.hasFocus)

        coordinator.abandon()
        assertEquals(1, fixture.abandonCalls)
    }

    @Test
    fun ensureForPlayback_shortCircuitsWhenPaused_andRecordsPendingResumeOnFailure() {
        val fixture = Fixture().apply { requestGranted = false }
        val coordinator = fixture.coordinator()

        assertTrue(coordinator.ensureForPlayback(playWhenReady = false))
        assertEquals(0, fixture.requestCalls)

        assertFalse(coordinator.ensureForPlayback(playWhenReady = true))
        assertEquals(1, fixture.requestCalls)
        assertTrue(coordinator.wasPlayingBeforeLoss)
        assertEquals(1f, coordinator.volumeFactor.value)
    }

    @Test
    fun explicitResumeFlagHelpers_areHonoredByNextGain() {
        val coordinator = Fixture().coordinator()

        coordinator.notePlayingBeforeLoss()
        assertEquals(
            FocusPlaybackAction.RESUME,
            coordinator.onFocusChange(MediaFocusType.GAIN_TRANSIENT, playWhenReady = false).playbackAction,
        )

        coordinator.notePlayingBeforeLoss()
        coordinator.clearPlayingBeforeLoss()
        assertEquals(
            FocusPlaybackAction.NONE,
            coordinator.onFocusChange(MediaFocusType.GAIN_TRANSIENT, playWhenReady = false).playbackAction,
        )
    }

    @Test
    fun unknownFocusEvent_isIgnoredWithoutChangingState() {
        val coordinator = Fixture().coordinator()
        assertTrue(coordinator.request())

        val ignored = coordinator.onFocusChange(MediaFocusType.NONE, playWhenReady = true)
        assertEquals(FocusPlaybackAction.NONE, ignored.playbackAction)
        assertTrue(coordinator.hasFocus)
        assertFalse(coordinator.wasPlayingBeforeLoss)
    }
}
