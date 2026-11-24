/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Shared Media3 [DefaultLoadControl] defaults tuned for fast cold-track startup on slow/high-RTT
 * mobile connections while maintaining a deep steady-state buffer to avoid mid-song stalls.
 */
object PlaybackLoadControlDefaults {
    const val PRIMARY_MIN_BUFFER_MS: Int = 20_000
    const val PRIMARY_MAX_BUFFER_MS: Int = 60_000
    const val PRIMARY_BUFFER_FOR_PLAYBACK_MS: Int = 500
    const val PRIMARY_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS: Int = 2_000

    const val CROSSFADE_MIN_BUFFER_MS: Int = 15_000
    const val CROSSFADE_MAX_BUFFER_MS: Int = 45_000
    const val CROSSFADE_BUFFER_FOR_PLAYBACK_MS: Int = 5_000
}

/**
 * Builds a [DefaultLoadControl] tuned for low-latency audio startup (500 ms initial playback
 * threshold instead of Media3's 2,500 ms default) with time-prioritized buffering.
 */
@UnstableApi
fun createLowLatencyPlaybackLoadControl(
    minBufferMs: Int = PlaybackLoadControlDefaults.PRIMARY_MIN_BUFFER_MS,
    maxBufferMs: Int = PlaybackLoadControlDefaults.PRIMARY_MAX_BUFFER_MS,
    bufferForPlaybackMs: Int = PlaybackLoadControlDefaults.PRIMARY_BUFFER_FOR_PLAYBACK_MS,
    bufferForPlaybackAfterRebufferMs: Int = PlaybackLoadControlDefaults.PRIMARY_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
): DefaultLoadControl =
    DefaultLoadControl
        .Builder()
        .setBufferDurationsMs(
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs,
        ).setPrioritizeTimeOverSizeThresholds(true)
        .build()

/**
 * Builds a [DefaultLoadControl] for secondary crossfade players that must pre-buffer enough
 * audio ahead of the crossfade handoff window.
 */
@UnstableApi
fun createCrossfadePlaybackLoadControl(
    minBufferMs: Int = PlaybackLoadControlDefaults.CROSSFADE_MIN_BUFFER_MS,
    maxBufferMs: Int = PlaybackLoadControlDefaults.CROSSFADE_MAX_BUFFER_MS,
    bufferForPlaybackMs: Int = PlaybackLoadControlDefaults.CROSSFADE_BUFFER_FOR_PLAYBACK_MS,
    bufferForPlaybackAfterRebufferMs: Int = bufferForPlaybackMs,
): DefaultLoadControl =
    DefaultLoadControl
        .Builder()
        .setBufferDurationsMs(
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs,
        ).setPrioritizeTimeOverSizeThresholds(true)
        .build()
