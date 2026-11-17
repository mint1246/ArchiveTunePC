/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social.scrobble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

data class ScrobbleTrack(
    val title: String,
    val artists: List<String>,
    val durationSeconds: Int,
    val albumTitle: String? = null,
) {
    fun formattedArtistString(): String = artists.joinToString(", ")
}

interface ScrobbleSink {
    suspend fun scrobble(
        track: ScrobbleTrack,
        startedAtEpochSeconds: Long,
    ): Result<Unit>

    suspend fun updateNowPlaying(track: ScrobbleTrack): Result<Unit>
}

fun computeScrobbleDelayMillis(
    durationSeconds: Int,
    minSongDurationSeconds: Int,
    delayPercent: Float,
    maxDelaySeconds: Int,
): Long? {
    if (durationSeconds <= minSongDurationSeconds) return null
    val threshold = durationSeconds * 1000L * delayPercent
    return min(threshold.toLong(), maxDelaySeconds * 1000L)
}

class ScrobbleSessionCoordinator(
    private val scope: CoroutineScope,
    private val sink: ScrobbleSink,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val onError: (operation: String, ScrobbleTrack, Throwable) -> Unit = { _, _, _ -> },
) {
    var minSongDurationSeconds: Int = 30
    var scrobbleDelayPercent: Float = 0.5f
    var scrobbleDelaySeconds: Int = 180
    var useNowPlaying: Boolean = true

    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L
    private var songStartedAt: Long = 0L
    private var songStarted: Boolean = false

    fun destroy() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        songStartedAt = 0L
        songStarted = false
    }

    fun close() {
        destroy()
    }

    fun onSongStart(
        track: ScrobbleTrack?,
        durationOverrideMillis: Long? = null,
    ) {
        if (track == null) return
        songStartedAt = nowEpochMillis() / 1000L
        songStarted = true
        startScrobbleTimer(track, durationOverrideMillis)
        if (useNowPlaying) {
            updateNowPlaying(track)
        }
    }

    fun onSongResume(track: ScrobbleTrack) {
        resumeScrobbleTimer(track)
    }

    fun onSongPause() {
        pauseScrobbleTimer()
    }

    fun onSongStop() {
        stopScrobbleTimer()
        songStarted = false
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        track: ScrobbleTrack?,
        durationOverrideMillis: Long? = null,
    ) {
        if (track == null) return
        if (isPlaying) {
            if (!songStarted) {
                onSongStart(track, durationOverrideMillis)
            } else {
                onSongResume(track)
            }
        } else {
            onSongPause()
        }
    }

    private fun startScrobbleTimer(
        track: ScrobbleTrack,
        durationOverrideMillis: Long? = null,
    ) {
        scrobbleJob?.cancel()
        val resolvedDurationSeconds = durationOverrideMillis?.div(1000L)?.toInt() ?: track.durationSeconds
        val delayMillis =
            computeScrobbleDelayMillis(
                durationSeconds = resolvedDurationSeconds,
                minSongDurationSeconds = minSongDurationSeconds,
                delayPercent = scrobbleDelayPercent,
                maxDelaySeconds = scrobbleDelaySeconds,
            ) ?: return

        scrobbleRemainingMillis = delayMillis
        if (scrobbleRemainingMillis <= 0L) {
            scrobbleSong(track)
            return
        }

        scrobbleTimerStartedAt = nowEpochMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(track)
                scrobbleJob = null
            }
    }

    private fun pauseScrobbleTimer() {
        scrobbleJob?.cancel()
        if (scrobbleTimerStartedAt != 0L) {
            val elapsed = nowEpochMillis() - scrobbleTimerStartedAt
            scrobbleRemainingMillis -= elapsed
            if (scrobbleRemainingMillis < 0L) scrobbleRemainingMillis = 0L
            scrobbleTimerStartedAt = 0L
        }
    }

    private fun resumeScrobbleTimer(track: ScrobbleTrack) {
        if (scrobbleRemainingMillis <= 0L) return
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = nowEpochMillis()
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(track)
                scrobbleJob = null
            }
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0L
    }

    private fun scrobbleSong(track: ScrobbleTrack) {
        scope.launch {
            try {
                sink.scrobble(track, songStartedAt).onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    onError("scrobble", track, throwable)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError("scrobble", track, t)
            }
        }
    }

    private fun updateNowPlaying(track: ScrobbleTrack) {
        scope.launch {
            try {
                sink.updateNowPlaying(track).onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    onError("updateNowPlaying", track, throwable)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError("updateNowPlaying", track, t)
            }
        }
    }
}
