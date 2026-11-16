/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import android.os.Looper
import android.os.SystemClock
import androidx.annotation.WorkerThread
import java.io.InterruptedIOException
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import moe.rukamori.archivetune.utils.YTPlayerUtils
import timber.log.Timber

@Singleton
class ResolveAudioStreamUseCase internal constructor(
    resolveStream: suspend (AudioStreamRequest, StreamResolutionPriority) -> ResolvedAudioStream,
) {
    @Inject
    constructor(youtubeiRepository: YoutubeiStreamRepository) : this({ request, priority ->
        val resolvedAuthState =
            if (request.authState.hasLoginCookie) {
                YTPlayerUtils.ensureYoutubeiPoTokensForPlayback(
                    videoId = request.mediaId,
                    authState = request.authState,
                )
            } else {
                request.authState
            }
        youtubeiRepository.resolve(request.copy(authState = resolvedAuthState), priority).also { resolved ->
            Timber.tag(TAG).d(
                "Resolved %s via %s (%s)",
                request.mediaId,
                resolved.source,
                resolved.runtimeVersion ?: "native",
            )
        }
    })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coordinator = StreamResolutionCoordinator(resolveStream)

    suspend operator fun invoke(request: AudioStreamRequest): ResolvedAudioStream = coordinator(request)

    suspend fun preload(request: AudioStreamRequest) = coordinator.preload(request)

    @WorkerThread
    fun resolveBlocking(request: AudioStreamRequest): ResolvedAudioStream {
        check(Looper.myLooper() != Looper.getMainLooper())
        val startedAt = SystemClock.elapsedRealtime()
        Timber.tag(TAG).d("Resolving mediaId=%s purpose=%s", request.mediaId, request.purpose)
        val future = scope.future { invoke(request) }
        return try {
            future.get().also {
                Timber.tag(TAG).d("Resolution delivered elapsedMs=%d", SystemClock.elapsedRealtime() - startedAt)
            }
        } catch (throwable: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Audio stream resolution interrupted").apply { initCause(throwable) }
        } catch (throwable: ExecutionException) {
            future.cancel(true)
            val cause = throwable.cause ?: throwable
            if (cause is CancellationException) {
                throw InterruptedIOException("Audio stream resolution was invalidated").apply { initCause(cause) }
            }
            throw cause
        } catch (throwable: CancellationException) {
            future.cancel(true)
            throw InterruptedIOException("Audio stream resolution was cancelled").apply { initCause(throwable) }
        } catch (throwable: Throwable) {
            future.cancel(true)
            throw throwable
        }
    }

    fun invalidate(mediaId: String, purpose: StreamPurpose? = null) = coordinator.invalidate(mediaId, purpose)

    fun invalidateUrl(url: String) = coordinator.invalidateUrl(url)

    fun peek(request: AudioStreamRequest): ResolvedAudioStream? = coordinator.peek(request)

    fun clear() = coordinator.clear()

    private companion object {
        const val TAG = "AudioStreamResolver"
    }
}
