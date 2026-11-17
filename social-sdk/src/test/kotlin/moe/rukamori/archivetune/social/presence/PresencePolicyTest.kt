/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social.presence

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PresencePolicyTest {
    @Test
    fun semanticState_isPausedByUser_whenPlayWhenReadyIsFalse() {
        val state =
            derivePlaybackSemanticState(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = "song-1",
                    isPlaying = false,
                    showWhenPaused = true,
                    playWhenReady = false,
                    playbackStage = PresencePlaybackStage.READY,
                ),
            )

        assertEquals(PlaybackSemanticState.PausedByUser, state)
    }

    @Test
    fun semanticState_isPlayingReady_whenReadyAndPlaying() {
        val state =
            derivePlaybackSemanticState(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = "song-1",
                    isPlaying = true,
                    showWhenPaused = true,
                    playWhenReady = true,
                    playbackStage = PresencePlaybackStage.READY,
                ),
            )

        assertEquals(PlaybackSemanticState.PlayingReady, state)
    }

    @Test
    fun semanticState_isBufferingWhilePlayRequested_whenReadyButNotPlaying_orBuffering() {
        val readyNotPlaying =
            derivePlaybackSemanticState(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = "song-1",
                    isPlaying = false,
                    showWhenPaused = true,
                    playWhenReady = true,
                    playbackStage = PresencePlaybackStage.READY,
                ),
            )
        val buffering =
            derivePlaybackSemanticState(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = "song-1",
                    isPlaying = false,
                    showWhenPaused = true,
                    playWhenReady = true,
                    playbackStage = PresencePlaybackStage.BUFFERING,
                ),
            )

        assertEquals(PlaybackSemanticState.BufferingWhilePlayRequested, readyNotPlaying)
        assertEquals(PlaybackSemanticState.BufferingWhilePlayRequested, buffering)
    }

    @Test
    fun semanticState_isInactive_whenSongMissingOrIdleOrEnded() {
        val noSong =
            derivePlaybackSemanticState(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = null,
                    isPlaying = true,
                    showWhenPaused = true,
                    playWhenReady = true,
                    playbackStage = PresencePlaybackStage.READY,
                ),
            )
        val idle =
            derivePlaybackSemanticState(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = "song-1",
                    isPlaying = false,
                    showWhenPaused = true,
                    playWhenReady = true,
                    playbackStage = PresencePlaybackStage.IDLE,
                ),
            )
        val ended =
            derivePlaybackSemanticState(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = "song-1",
                    isPlaying = false,
                    showWhenPaused = true,
                    playWhenReady = true,
                    playbackStage = PresencePlaybackStage.ENDED,
                ),
            )

        assertEquals(PlaybackSemanticState.Inactive, noSong)
        assertEquals(PlaybackSemanticState.Inactive, idle)
        assertEquals(PlaybackSemanticState.Inactive, ended)
    }

    @Test
    fun rawDecision_respectsPrecedenceAndGates() {
        val stopping =
            deriveRawPresenceDecision(
                PresencePolicyInputs(
                    enabled = false,
                    hasToken = false,
                    songId = null,
                    isPlaying = false,
                    showWhenPaused = false,
                    serviceStopping = true,
                ),
                PlaybackSemanticState.Inactive,
            )
        assertEquals(PresenceDecision.Hidden(PresenceHiddenReason.ServiceStopping), stopping)

        val disabled =
            deriveRawPresenceDecision(
                PresencePolicyInputs(
                    enabled = false,
                    hasToken = true,
                    songId = "song-1",
                    isPlaying = true,
                    showWhenPaused = true,
                ),
                PlaybackSemanticState.PlayingReady,
            )
        assertEquals(PresenceDecision.Hidden(PresenceHiddenReason.Disabled), disabled)

        val noToken =
            deriveRawPresenceDecision(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = false,
                    songId = "song-1",
                    isPlaying = true,
                    showWhenPaused = true,
                ),
                PlaybackSemanticState.PlayingReady,
            )
        assertEquals(PresenceDecision.Hidden(PresenceHiddenReason.NoToken), noToken)

        val noSong =
            deriveRawPresenceDecision(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = null,
                    isPlaying = false,
                    showWhenPaused = true,
                ),
                PlaybackSemanticState.Inactive,
            )
        assertEquals(PresenceDecision.Hidden(PresenceHiddenReason.NoSong), noSong)

        val pausedByPref =
            deriveRawPresenceDecision(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = "song-1",
                    isPlaying = false,
                    showWhenPaused = false,
                    playWhenReady = false,
                ),
                PlaybackSemanticState.PausedByUser,
            )
        assertEquals(PresenceDecision.Hidden(PresenceHiddenReason.PausedByPreference), pausedByPref)

        val pausedByDismiss =
            deriveRawPresenceDecision(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = "song-1",
                    isPlaying = false,
                    showWhenPaused = true,
                    pausedPresenceGate = PausedPresenceGate.HiddenByNotificationDismiss,
                    playWhenReady = false,
                ),
                PlaybackSemanticState.PausedByUser,
            )
        assertEquals(
            PresenceDecision.Hidden(PresenceHiddenReason.PausedByNotificationDismiss),
            pausedByDismiss,
        )

        val pausedVisible =
            deriveRawPresenceDecision(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = "song-1",
                    isPlaying = false,
                    showWhenPaused = true,
                    playWhenReady = false,
                ),
                PlaybackSemanticState.PausedByUser,
            )
        assertEquals(PresenceDecision.Visible("song-1", PresenceMode.Paused), pausedVisible)
    }

    @Test
    fun resolvePresenceDecision_returnsNoStablePlaybackYet_whenHoldHasNoPriorVisible() {
        val resolution =
            deriveFinalPresenceDecision(
                input =
                    PresencePolicyInputs(
                        enabled = true,
                        hasToken = true,
                        songId = "song-1",
                        isPlaying = false,
                        showWhenPaused = true,
                        playWhenReady = true,
                        playbackStage = PresencePlaybackStage.BUFFERING,
                    ),
                holdContext =
                    PresenceHoldContext(
                        nowMs = 1_000L,
                        activeHoldState = null,
                        lastAppliedVisiblePresence = null,
                        holdTimeoutMs = 10_000L,
                    ),
            )

        assertEquals(
            PresenceDecision.Hidden(PresenceHiddenReason.NoStablePlaybackYet),
            resolution.decision,
        )
        assertNull(resolution.nextHoldState)
    }

    @Test
    fun resolvePresenceDecision_startsPreservesAndTimesOutHold() {
        val lastVisible = LastAppliedPresence("song-1", PresenceMode.Playing, appliedAtMs = 500L)
        val bufferingInput =
            PresencePolicyInputs(
                enabled = true,
                hasToken = true,
                songId = "song-1",
                isPlaying = false,
                showWhenPaused = true,
                playWhenReady = true,
                playbackStage = PresencePlaybackStage.BUFFERING,
            )

        val initial =
            deriveFinalPresenceDecision(
                input = bufferingInput,
                holdContext =
                    PresenceHoldContext(
                        nowMs = 1_000L,
                        activeHoldState = null,
                        lastAppliedVisiblePresence = lastVisible,
                        holdTimeoutMs = 5_000L,
                    ),
            )

        assertEquals(
            PresenceDecision.Hold(PresenceHoldReason.BufferingWhilePlayRequested, "song-1"),
            initial.decision,
        )
        assertEquals(
            PresenceActiveHoldState(PresenceHoldReason.BufferingWhilePlayRequested, "song-1", 1_000L),
            initial.nextHoldState,
        )

        val withinTimeout =
            deriveFinalPresenceDecision(
                input = bufferingInput,
                holdContext =
                    PresenceHoldContext(
                        nowMs = 5_999L,
                        activeHoldState = initial.nextHoldState,
                        lastAppliedVisiblePresence = lastVisible,
                        holdTimeoutMs = 5_000L,
                    ),
            )
        assertSame(initial.nextHoldState, withinTimeout.nextHoldState)
        assertEquals(initial.decision, withinTimeout.decision)

        val timedOut =
            deriveFinalPresenceDecision(
                input = bufferingInput,
                holdContext =
                    PresenceHoldContext(
                        nowMs = 6_000L,
                        activeHoldState = initial.nextHoldState,
                        lastAppliedVisiblePresence = lastVisible,
                        holdTimeoutMs = 5_000L,
                    ),
            )
        assertEquals(
            PresenceDecision.Hidden(PresenceHiddenReason.PlaybackStalled),
            timedOut.decision,
        )
        assertNull(timedOut.nextHoldState)
    }

    @Test
    fun resolvePresenceDecision_fallsBackToLastAppliedSongIdWhenHoldSongIdNull() {
        val lastVisible = LastAppliedPresence("fallback-song", PresenceMode.Playing, appliedAtMs = 100L)
        val resolution =
            resolvePresenceDecision(
                rawDecision = PresenceDecision.Hold(PresenceHoldReason.BufferingWhilePlayRequested, songId = null),
                holdContext =
                    PresenceHoldContext(
                        nowMs = 2_000L,
                        activeHoldState = null,
                        lastAppliedVisiblePresence = lastVisible,
                        holdTimeoutMs = 5_000L,
                    ),
            )

        assertEquals(
            PresenceActiveHoldState(
                reason = PresenceHoldReason.BufferingWhilePlayRequested,
                songId = "fallback-song",
                startedAtMs = 2_000L,
            ),
            resolution.nextHoldState,
        )
    }

    @Test
    fun derivePresenceDecision_convenienceMatchesDefaultContext() {
        val visible =
            derivePresenceDecision(
                PresencePolicyInputs(
                    enabled = true,
                    hasToken = true,
                    songId = "song-9",
                    isPlaying = true,
                    showWhenPaused = false,
                ),
            )
        assertEquals(PresenceDecision.Visible("song-9", PresenceMode.Playing), visible)
    }
}
