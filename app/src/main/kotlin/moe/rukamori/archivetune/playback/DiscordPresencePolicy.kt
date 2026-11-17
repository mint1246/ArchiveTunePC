/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.media3.common.Player
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.social.presence.PresenceDecision
import moe.rukamori.archivetune.social.presence.PresencePlaybackStage
import moe.rukamori.archivetune.social.presence.PresencePolicyInputs
import moe.rukamori.archivetune.social.presence.deriveFinalPresenceDecision
import moe.rukamori.archivetune.social.presence.derivePlaybackSemanticState as deriveSocialPlaybackSemanticState
import moe.rukamori.archivetune.social.presence.derivePresenceDecision
import moe.rukamori.archivetune.social.presence.deriveRawPresenceDecision
import moe.rukamori.archivetune.social.presence.resolvePresenceDecision

typealias PausedPresenceGate = moe.rukamori.archivetune.social.presence.PausedPresenceGate
typealias PresenceMode = moe.rukamori.archivetune.social.presence.PresenceMode
typealias PlaybackSemanticState = moe.rukamori.archivetune.social.presence.PlaybackSemanticState
typealias HiddenReason = moe.rukamori.archivetune.social.presence.PresenceHiddenReason
typealias HoldReason = moe.rukamori.archivetune.social.presence.PresenceHoldReason
typealias ActiveHoldState = moe.rukamori.archivetune.social.presence.PresenceActiveHoldState
typealias LastAppliedVisiblePresence = moe.rukamori.archivetune.social.presence.LastAppliedPresence
typealias DiscordHoldContext = moe.rukamori.archivetune.social.presence.PresenceHoldContext

sealed interface DiscordPresenceDecision {
    data class Visible(
        val songId: String,
        val mode: PresenceMode,
    ) : DiscordPresenceDecision {
        val isPaused: Boolean
            get() = mode == PresenceMode.Paused
    }

    data class Hidden(
        val reason: HiddenReason,
    ) : DiscordPresenceDecision

    data class Hold(
        val reason: HoldReason,
        val songId: String?,
    ) : DiscordPresenceDecision
}

data class DiscordPresenceResolution(
    val decision: DiscordPresenceDecision,
    val nextHoldState: ActiveHoldState?,
)

data class DiscordPresenceSnapshot(
    val song: Song,
    val positionMs: Long,
    val isPaused: Boolean,
)

data class DiscordPresenceInputs(
    val enabled: Boolean,
    val hasToken: Boolean,
    val song: Song?,
    val isPlaying: Boolean,
    val showWhenPaused: Boolean,
    val pausedPresenceGate: PausedPresenceGate = PausedPresenceGate.FollowPreference,
    val serviceStopping: Boolean = false,
    val playWhenReady: Boolean = isPlaying,
    val playbackState: Int = if (isPlaying) Player.STATE_READY else Player.STATE_IDLE,
)

private fun DiscordPresenceInputs.toPresencePolicyInputs(): PresencePolicyInputs =
    PresencePolicyInputs(
        enabled = enabled,
        hasToken = hasToken,
        songId = song?.song?.id,
        isPlaying = isPlaying,
        showWhenPaused = showWhenPaused,
        pausedPresenceGate = pausedPresenceGate,
        serviceStopping = serviceStopping,
        playWhenReady = playWhenReady,
        playbackStage =
            when (playbackState) {
                Player.STATE_READY -> PresencePlaybackStage.READY
                Player.STATE_BUFFERING -> PresencePlaybackStage.BUFFERING
                Player.STATE_ENDED -> PresencePlaybackStage.ENDED
                else -> PresencePlaybackStage.IDLE
            },
    )

private fun PresenceDecision.toDiscordDecision(): DiscordPresenceDecision =
    when (this) {
        is PresenceDecision.Visible -> DiscordPresenceDecision.Visible(songId = songId, mode = mode)
        is PresenceDecision.Hidden -> DiscordPresenceDecision.Hidden(reason = reason)
        is PresenceDecision.Hold -> DiscordPresenceDecision.Hold(reason = reason, songId = songId)
    }

private fun DiscordPresenceDecision.toSocialDecision(): PresenceDecision =
    when (this) {
        is DiscordPresenceDecision.Visible -> PresenceDecision.Visible(songId = songId, mode = mode)
        is DiscordPresenceDecision.Hidden -> PresenceDecision.Hidden(reason = reason)
        is DiscordPresenceDecision.Hold -> PresenceDecision.Hold(reason = reason, songId = songId)
    }

fun derivePlaybackSemanticState(input: DiscordPresenceInputs): PlaybackSemanticState =
    deriveSocialPlaybackSemanticState(input.toPresencePolicyInputs())

fun deriveRawDiscordPresenceDecision(
    input: DiscordPresenceInputs,
    semanticState: PlaybackSemanticState,
): DiscordPresenceDecision =
    deriveRawPresenceDecision(input.toPresencePolicyInputs(), semanticState).toDiscordDecision()

fun resolveDiscordPresenceDecision(
    rawDecision: DiscordPresenceDecision,
    holdContext: DiscordHoldContext,
): DiscordPresenceResolution =
    resolvePresenceDecision(rawDecision.toSocialDecision(), holdContext).let {
        DiscordPresenceResolution(decision = it.decision.toDiscordDecision(), nextHoldState = it.nextHoldState)
    }

fun deriveDiscordPresenceDecision(input: DiscordPresenceInputs): DiscordPresenceDecision =
    derivePresenceDecision(input.toPresencePolicyInputs()).toDiscordDecision()

fun deriveFinalDiscordPresenceDecision(
    input: DiscordPresenceInputs,
    holdContext: DiscordHoldContext,
): DiscordPresenceResolution =
    deriveFinalPresenceDecision(input.toPresencePolicyInputs(), holdContext).let {
        DiscordPresenceResolution(decision = it.decision.toDiscordDecision(), nextHoldState = it.nextHoldState)
    }
