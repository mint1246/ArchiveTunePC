/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social.presence

enum class PresencePlaybackStage {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
}

enum class PausedPresenceGate {
    FollowPreference,
    HiddenByNotificationDismiss,
}

enum class PresenceMode {
    Playing,
    Paused,
}

enum class PlaybackSemanticState {
    PlayingReady,
    PausedByUser,
    BufferingWhilePlayRequested,
    Inactive,
}

enum class PresenceHiddenReason {
    Disabled,
    NoToken,
    NoSong,
    PausedByPreference,
    PausedByNotificationDismiss,
    ServiceStopping,
    NoStablePlaybackYet,
    PlaybackStalled,
}

enum class PresenceHoldReason {
    BufferingWhilePlayRequested,
}

sealed interface PresenceDecision {
    data class Visible(
        val songId: String,
        val mode: PresenceMode,
    ) : PresenceDecision {
        val isPaused: Boolean
            get() = mode == PresenceMode.Paused
    }

    data class Hidden(
        val reason: PresenceHiddenReason,
    ) : PresenceDecision

    data class Hold(
        val reason: PresenceHoldReason,
        val songId: String?,
    ) : PresenceDecision
}

data class PresenceActiveHoldState(
    val reason: PresenceHoldReason,
    val songId: String?,
    val startedAtMs: Long,
)

data class LastAppliedPresence(
    val songId: String,
    val mode: PresenceMode,
    val appliedAtMs: Long,
)

data class PresenceHoldContext(
    val nowMs: Long,
    val activeHoldState: PresenceActiveHoldState?,
    val lastAppliedVisiblePresence: LastAppliedPresence?,
    val holdTimeoutMs: Long,
)

data class PresenceResolution(
    val decision: PresenceDecision,
    val nextHoldState: PresenceActiveHoldState?,
)

data class PresencePolicyInputs(
    val enabled: Boolean,
    val hasToken: Boolean,
    val songId: String?,
    val isPlaying: Boolean,
    val showWhenPaused: Boolean,
    val pausedPresenceGate: PausedPresenceGate = PausedPresenceGate.FollowPreference,
    val serviceStopping: Boolean = false,
    val playWhenReady: Boolean = isPlaying,
    val playbackStage: PresencePlaybackStage =
        if (isPlaying) PresencePlaybackStage.READY else PresencePlaybackStage.IDLE,
)

fun derivePlaybackSemanticState(input: PresencePolicyInputs): PlaybackSemanticState {
    if (input.songId == null) {
        return PlaybackSemanticState.Inactive
    }
    if (!input.playWhenReady) {
        return PlaybackSemanticState.PausedByUser
    }

    return when (input.playbackStage) {
        PresencePlaybackStage.READY -> {
            if (input.isPlaying) {
                PlaybackSemanticState.PlayingReady
            } else {
                PlaybackSemanticState.BufferingWhilePlayRequested
            }
        }

        PresencePlaybackStage.BUFFERING -> {
            PlaybackSemanticState.BufferingWhilePlayRequested
        }

        PresencePlaybackStage.IDLE,
        PresencePlaybackStage.ENDED,
        -> {
            PlaybackSemanticState.Inactive
        }
    }
}

fun deriveRawPresenceDecision(
    input: PresencePolicyInputs,
    semanticState: PlaybackSemanticState,
): PresenceDecision {
    if (input.serviceStopping) {
        return PresenceDecision.Hidden(PresenceHiddenReason.ServiceStopping)
    }
    if (!input.enabled) {
        return PresenceDecision.Hidden(PresenceHiddenReason.Disabled)
    }
    if (!input.hasToken) {
        return PresenceDecision.Hidden(PresenceHiddenReason.NoToken)
    }

    val songId = input.songId ?: return PresenceDecision.Hidden(PresenceHiddenReason.NoSong)

    return when (semanticState) {
        PlaybackSemanticState.PlayingReady -> {
            PresenceDecision.Visible(
                songId = songId,
                mode = PresenceMode.Playing,
            )
        }

        PlaybackSemanticState.PausedByUser -> {
            when {
                !input.showWhenPaused -> {
                    PresenceDecision.Hidden(PresenceHiddenReason.PausedByPreference)
                }

                input.pausedPresenceGate == PausedPresenceGate.HiddenByNotificationDismiss -> {
                    PresenceDecision.Hidden(PresenceHiddenReason.PausedByNotificationDismiss)
                }

                else -> {
                    PresenceDecision.Visible(
                        songId = songId,
                        mode = PresenceMode.Paused,
                    )
                }
            }
        }

        PlaybackSemanticState.BufferingWhilePlayRequested -> {
            PresenceDecision.Hold(
                reason = PresenceHoldReason.BufferingWhilePlayRequested,
                songId = songId,
            )
        }

        PlaybackSemanticState.Inactive -> {
            PresenceDecision.Hidden(PresenceHiddenReason.PlaybackStalled)
        }
    }
}

fun resolvePresenceDecision(
    rawDecision: PresenceDecision,
    holdContext: PresenceHoldContext,
): PresenceResolution =
    when (rawDecision) {
        is PresenceDecision.Visible -> {
            PresenceResolution(
                decision = rawDecision,
                nextHoldState = null,
            )
        }

        is PresenceDecision.Hidden -> {
            PresenceResolution(
                decision = rawDecision,
                nextHoldState = null,
            )
        }

        is PresenceDecision.Hold -> {
            val lastAppliedVisible =
                holdContext.lastAppliedVisiblePresence
                    ?: return PresenceResolution(
                        decision = PresenceDecision.Hidden(PresenceHiddenReason.NoStablePlaybackYet),
                        nextHoldState = null,
                    )

            val activeHold = holdContext.activeHoldState
            val isSameHold =
                activeHold != null &&
                    activeHold.reason == rawDecision.reason &&
                    activeHold.songId == rawDecision.songId

            if (isSameHold) {
                val elapsedMs = holdContext.nowMs - activeHold.startedAtMs
                if (elapsedMs >= holdContext.holdTimeoutMs) {
                    return PresenceResolution(
                        decision = PresenceDecision.Hidden(PresenceHiddenReason.PlaybackStalled),
                        nextHoldState = null,
                    )
                }

                return PresenceResolution(
                    decision = rawDecision,
                    nextHoldState = activeHold,
                )
            }

            PresenceResolution(
                decision = rawDecision,
                nextHoldState =
                    PresenceActiveHoldState(
                        reason = rawDecision.reason,
                        songId = rawDecision.songId ?: lastAppliedVisible.songId,
                        startedAtMs = holdContext.nowMs,
                    ),
            )
        }
    }

fun derivePresenceDecision(input: PresencePolicyInputs): PresenceDecision =
    deriveFinalPresenceDecision(
        input = input,
        holdContext =
            PresenceHoldContext(
                nowMs = 0L,
                activeHoldState = null,
                lastAppliedVisiblePresence = null,
                holdTimeoutMs = 0L,
            ),
    ).decision

fun deriveFinalPresenceDecision(
    input: PresencePolicyInputs,
    holdContext: PresenceHoldContext,
): PresenceResolution {
    val semanticState = derivePlaybackSemanticState(input)
    val rawDecision = deriveRawPresenceDecision(input, semanticState)
    return resolvePresenceDecision(rawDecision, holdContext)
}
