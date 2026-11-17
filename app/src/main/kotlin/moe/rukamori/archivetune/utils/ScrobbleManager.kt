/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.social.scrobble.ScrobbleSessionCoordinator
import moe.rukamori.archivetune.social.scrobble.ScrobbleSink
import moe.rukamori.archivetune.social.scrobble.ScrobbleTrack
import timber.log.Timber

class ScrobbleManager internal constructor(
    scope: CoroutineScope,
    sink: ScrobbleSink,
    minSongDuration: Int = 30,
    scrobbleDelayPercent: Float = 0.5f,
    scrobbleDelaySeconds: Int = 180,
    nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(
        scope: CoroutineScope,
        minSongDuration: Int = 30,
        scrobbleDelayPercent: Float = 0.5f,
        scrobbleDelaySeconds: Int = 180,
    ) : this(
        scope = scope,
        sink = LastFmScrobbleSink,
        minSongDuration = minSongDuration,
        scrobbleDelayPercent = scrobbleDelayPercent,
        scrobbleDelaySeconds = scrobbleDelaySeconds,
        nowEpochMillis = System::currentTimeMillis,
    )

    private val coordinator =
        ScrobbleSessionCoordinator(
            scope = scope,
            sink = sink,
            nowEpochMillis = nowEpochMillis,
            onError = { operation, track, throwable ->
                val actionLabel = if (operation == "scrobble") "scrobble" else "update now playing"
                Timber.tag("ScrobbleManager").e(throwable, "Failed to $actionLabel: ${track.title}")
            },
        ).apply {
            this.minSongDurationSeconds = minSongDuration
            this.scrobbleDelayPercent = scrobbleDelayPercent
            this.scrobbleDelaySeconds = scrobbleDelaySeconds
        }

    var minSongDuration: Int
        get() = coordinator.minSongDurationSeconds
        set(value) {
            coordinator.minSongDurationSeconds = value
        }

    var scrobbleDelayPercent: Float
        get() = coordinator.scrobbleDelayPercent
        set(value) {
            coordinator.scrobbleDelayPercent = value
        }

    var scrobbleDelaySeconds: Int
        get() = coordinator.scrobbleDelaySeconds
        set(value) {
            coordinator.scrobbleDelaySeconds = value
        }

    var useNowPlaying: Boolean
        get() = coordinator.useNowPlaying
        set(value) {
            coordinator.useNowPlaying = value
        }

    fun destroy() {
        coordinator.destroy()
    }

    fun onSongStart(
        metadata: MediaMetadata?,
        duration: Long? = null,
    ) {
        coordinator.onSongStart(metadata?.toScrobbleTrack(), duration)
    }

    fun onSongResume(metadata: MediaMetadata) {
        coordinator.onSongResume(metadata.toScrobbleTrack())
    }

    fun onSongPause() {
        coordinator.onSongPause()
    }

    fun onSongStop() {
        coordinator.onSongStop()
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        metadata: MediaMetadata?,
        duration: Long? = null,
    ) {
        coordinator.onPlayerStateChanged(isPlaying, metadata?.toScrobbleTrack(), duration)
    }

    private fun MediaMetadata.toScrobbleTrack(): ScrobbleTrack =
        ScrobbleTrack(
            title = title,
            artists = artists.map { it.name },
            durationSeconds = duration,
            albumTitle = album?.title,
        )

    private object LastFmScrobbleSink : ScrobbleSink {
        override suspend fun scrobble(
            track: ScrobbleTrack,
            startedAtEpochSeconds: Long,
        ): Result<Unit> =
            LastFM
                .scrobble(
                    artist = track.formattedArtistString(),
                    track = track.title,
                    duration = track.durationSeconds,
                    timestamp = startedAtEpochSeconds,
                    album = track.albumTitle,
                ).onSuccess {
                    Timber
                        .tag("ScrobbleManager")
                        .d("Scrobbled: ${track.title} by ${track.formattedArtistString()}")
                }.map { Unit }

        override suspend fun updateNowPlaying(track: ScrobbleTrack): Result<Unit> =
            LastFM
                .updateNowPlaying(
                    artist = track.formattedArtistString(),
                    track = track.title,
                    album = track.albumTitle,
                    duration = track.durationSeconds,
                ).onSuccess {
                    Timber.tag("ScrobbleManager").d("Updated now playing: ${track.title}")
                }.map { Unit }
    }
}
