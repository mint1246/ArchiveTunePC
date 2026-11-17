/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Checks whether a media item is already fully available in local player/download caches. */
fun interface PreloadCacheProbe {
    fun isFullyCached(mediaId: String): Boolean
}

/** Receives non-cancellation preload failures for platform logging/diagnostics. */
fun interface PreloadDiagnostics {
    fun onPreloadFailed(
        request: AudioStreamRequest,
        throwable: Throwable,
    )
}

/**
 * Coordinates single-target background preloading of the next [AudioStreamRequest].
 *
 * Preserves last-target deduplication, cancellation of superseded or cleared targets,
 * retry-on-failure (clearing the target on non-cancellation errors), resolver cache-hit
 * short-circuiting, and local player/download cache checks via [PreloadCacheProbe].
 */
class NextStreamPreloadCoordinator(
    private val peekResolved: (AudioStreamRequest) -> ResolvedAudioStream?,
    private val preloadResolved: suspend (AudioStreamRequest) -> Unit,
    private val localCacheProbe: PreloadCacheProbe = PreloadCacheProbe { false },
    private val diagnostics: PreloadDiagnostics = PreloadDiagnostics { _, _ -> },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    constructor(
        coordinator: StreamResolutionCoordinator,
        localCacheProbe: PreloadCacheProbe = PreloadCacheProbe { false },
        diagnostics: PreloadDiagnostics = PreloadDiagnostics { _, _ -> },
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(
        peekResolved = coordinator::peek,
        preloadResolved = coordinator::preload,
        localCacheProbe = localCacheProbe,
        diagnostics = diagnostics,
        scope = scope,
    )

    private data class Target(val request: AudioStreamRequest)

    private val lock = Any()
    private var target: Target? = null
    private var job: Job? = null

    fun updateTarget(request: AudioStreamRequest) {
        val nextTarget = Target(request)
        lateinit var preloadFailure: AtomicReference<Throwable?>
        val jobToStart =
            synchronized(lock) {
                if (target == nextTarget) return

                val previousJob = job
                preloadFailure = AtomicReference()
                val nextJob =
                    scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            preload(nextTarget)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (throwable: Throwable) {
                            preloadFailure.set(throwable)
                            diagnostics.onPreloadFailed(request, throwable)
                        }
                    }

                target = nextTarget
                job = nextJob
                previousJob?.cancel()
                nextJob
            }

        jobToStart.invokeOnCompletion { cause ->
            complete(nextTarget, jobToStart, preloadFailure.get() ?: cause)
        }
        jobToStart.start()
    }

    fun cancel() {
        val jobToCancel =
            synchronized(lock) {
                target = null
                job.also { job = null }
            }
        jobToCancel?.cancel()
    }

    private suspend fun preload(target: Target) {
        if (peekResolved(target.request) != null) return

        val cacheKey = target.request.mediaId
        if (localCacheProbe.isFullyCached(cacheKey)) return

        preloadResolved(target.request)
    }

    private fun complete(
        completedTarget: Target,
        completedJob: Job,
        cause: Throwable?,
    ) {
        synchronized(lock) {
            if (target != completedTarget || job !== completedJob) return

            job = null
            if (cause != null && cause !is CancellationException) {
                target = null
            }
        }
    }
}
