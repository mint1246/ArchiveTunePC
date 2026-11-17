/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlin.math.abs

/** Whether the two crossfade players have drifted far enough to require a corrective seek. */
fun needsCorrectiveCrossfadeSeek(
    primaryPositionMs: Long,
    secondaryPositionMs: Long,
    maximumDriftMs: Long,
): Boolean {
    require(maximumDriftMs >= 0L)
    return abs(primaryPositionMs - secondaryPositionMs) > maximumDriftMs
}

/** Whether playback actually moved forward after a seek was issued. */
fun hasPlaybackPositionAdvanced(
    positionAfterSeekMs: Long,
    currentPositionMs: Long,
): Boolean = currentPositionMs > positionAfterSeekMs

/** The primary audio session id to reuse for continuous effects, or null when unusable. */
fun sharedCrossfadeAudioSessionId(primaryAudioSessionId: Int): Int? =
    primaryAudioSessionId.takeIf { it > 0 }
