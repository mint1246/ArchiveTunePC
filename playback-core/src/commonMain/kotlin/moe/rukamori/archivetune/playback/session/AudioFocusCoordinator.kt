/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Focus events normalized away from Android's `AudioManager` constants so the state machine can be
 * owned and tested headlessly. Platform adapters map their native focus codes to these values.
 */
enum class MediaFocusType {
    NONE,
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK,
    GAIN_TRANSIENT,
    GAIN_TRANSIENT_MAY_DUCK,
}

/** What the playback owner must do to honor a focus transition. */
enum class FocusPlaybackAction {
    NONE,
    PAUSE,
    RESUME,
}

data class FocusTransition(
    val playbackAction: FocusPlaybackAction = FocusPlaybackAction.NONE,
)

/**
 * Owns the audio-focus session state machine used by a Media3 playback service.
 *
 * The coordinator keeps focus ownership, the "resume when focus returns" flag and the ducking
 * volume factor. It does not touch a player or an `AudioManager`: the caller supplies focus
 * request/abandon callbacks and applies the returned [FocusTransition] to its own player. Shutdown
 * stays with the platform adapter so no platform resource is owned here.
 *
 * Calls are expected to be serialized by the caller (the Android service drives them from its
 * main thread), matching the serialization contract of the surrounding playback state.
 */
class AudioFocusCoordinator(
    private val requestFocus: () -> Boolean,
    private val abandonFocus: () -> Unit,
) {
    private val _volumeFactor = MutableStateFlow(1f)

    /** Multiplier applied to the player's effective volume for focus-driven ducking. */
    val volumeFactor: StateFlow<Float> = _volumeFactor.asStateFlow()

    /** Whether focus is currently owned. */
    var hasFocus: Boolean = false
        private set

    /** Whether playback was active when focus was lost and should resume on the next gain. */
    var wasPlayingBeforeLoss: Boolean = false
        private set

    /** Last handled focus event, exposed for diagnostics. */
    var lastType: MediaFocusType = MediaFocusType.NONE
        private set

    /**
     * Handle a focus event. [playWhenReady] is read at the moment of the event, matching the
     * platform service's decision to pause or resume.
     */
    fun onFocusChange(
        type: MediaFocusType,
        playWhenReady: Boolean,
    ): FocusTransition =
        when (type) {
            MediaFocusType.GAIN,
            MediaFocusType.GAIN_TRANSIENT,
            -> {
                hasFocus = true
                _volumeFactor.value = 1f
                val action =
                    if (wasPlayingBeforeLoss) {
                        wasPlayingBeforeLoss = false
                        FocusPlaybackAction.RESUME
                    } else {
                        FocusPlaybackAction.NONE
                    }
                lastType = type
                FocusTransition(action)
            }

            MediaFocusType.GAIN_TRANSIENT_MAY_DUCK -> {
                // Another source may duck us; keep playing and restore full volume without a resume.
                hasFocus = true
                _volumeFactor.value = 1f
                lastType = type
                FocusTransition()
            }

            MediaFocusType.LOSS -> {
                hasFocus = false
                notePausedForFocusLoss(resumeWhenFocusReturns = false, playWhenReady = playWhenReady)
                val action = pauseAction(playWhenReady)
                // Mirrors the platform flow: focus is cleared before abandon, so abandon is a no-op.
                abandon()
                lastType = type
                FocusTransition(action)
            }

            MediaFocusType.LOSS_TRANSIENT,
            MediaFocusType.LOSS_TRANSIENT_CAN_DUCK,
            -> {
                hasFocus = false
                notePausedForFocusLoss(resumeWhenFocusReturns = true, playWhenReady = playWhenReady)
                val action = pauseAction(playWhenReady)
                lastType = type
                FocusTransition(action)
            }

            MediaFocusType.NONE -> FocusTransition()
        }

    /**
     * Request focus for active playback. When focus is already owned, only restore a ducked
     * factor instead of re-requesting focus from the platform.
     */
    fun request(): Boolean {
        if (hasFocus) {
            if (_volumeFactor.value != 1f || lastType == MediaFocusType.LOSS_TRANSIENT_CAN_DUCK) {
                restoreVolume()
            }
            return true
        }
        val granted = requestFocus()
        hasFocus = granted
        if (granted) restoreVolume()
        return granted
    }

    /**
     * Ensure focus for active playback. Returns false when focus was denied; in that case the
     * caller should pause and the coordinator remembers to resume if focus later returns.
     */
    fun ensureForPlayback(playWhenReady: Boolean): Boolean {
        if (!playWhenReady) return true
        if (request()) return true
        notePausedForFocusLoss(resumeWhenFocusReturns = true, playWhenReady = playWhenReady)
        return false
    }

    /** Abandon focus only when it is owned. */
    fun abandon() {
        if (hasFocus) {
            abandonFocus()
            hasFocus = false
        }
    }

    /** Remember that playback was active, e.g. when a route change interrupts a resume attempt. */
    fun notePlayingBeforeLoss() {
        wasPlayingBeforeLoss = true
    }

    /** Forget a pending resume, e.g. after a permanent loss was already handled. */
    fun clearPlayingBeforeLoss() {
        wasPlayingBeforeLoss = false
    }

    /**
     * Record the state part of pausing for a focus loss. The caller performs the actual player
     * pause so the coordinator stays free of platform player types.
     */
    fun notePausedForFocusLoss(
        resumeWhenFocusReturns: Boolean,
        playWhenReady: Boolean,
    ) {
        _volumeFactor.value = 1f
        wasPlayingBeforeLoss = resumeWhenFocusReturns && playWhenReady
    }

    private fun pauseAction(playWhenReady: Boolean): FocusPlaybackAction =
        if (playWhenReady) FocusPlaybackAction.PAUSE else FocusPlaybackAction.NONE

    private fun restoreVolume() {
        _volumeFactor.value = 1f
        hasFocus = true
        lastType = MediaFocusType.GAIN
    }
}
