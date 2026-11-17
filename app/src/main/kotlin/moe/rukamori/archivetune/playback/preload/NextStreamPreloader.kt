/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.preload

import androidx.media3.datasource.cache.Cache
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import moe.rukamori.archivetune.playback.stream.AudioStreamRequest
import moe.rukamori.archivetune.playback.android.isFullyCached
import moe.rukamori.archivetune.playback.stream.NextStreamPreloadCoordinator
import moe.rukamori.archivetune.playback.stream.PreloadCacheProbe
import moe.rukamori.archivetune.playback.stream.PreloadDiagnostics
import moe.rukamori.archivetune.playback.stream.ResolveAudioStreamUseCase
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NextStreamPreloader
    @Inject
    constructor(
        private val resolveAudioStream: ResolveAudioStreamUseCase,
        @PlayerCache private val playerCache: Cache,
        @DownloadCache private val downloadCache: Cache,
    ) {
        private val coordinator = NextStreamPreloadCoordinator(
            peekResolved = resolveAudioStream::peek,
            preloadResolved = resolveAudioStream::preload,
            localCacheProbe = PreloadCacheProbe { cacheKey ->
                playerCache.isFullyCached(cacheKey) || downloadCache.isFullyCached(cacheKey)
            },
            diagnostics = PreloadDiagnostics { request, throwable ->
                Timber.tag(LOG_TAG).w(throwable, "Failed to preload resolved stream for %s", request.mediaId)
            },
        )

        fun updateTarget(request: AudioStreamRequest) = coordinator.updateTarget(request)

        fun cancel() = coordinator.cancel()

        private companion object {
            const val LOG_TAG = "AudioStreamResolver"
        }
    }
