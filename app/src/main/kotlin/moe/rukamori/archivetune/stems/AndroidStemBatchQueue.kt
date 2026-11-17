/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.sdk.stems.StemBatchProgress
import moe.rukamori.archivetune.sdk.stems.StemBatchQueue
import moe.rukamori.archivetune.sdk.stems.StemQueueState

typealias AndroidStemQueueState = StemQueueState

internal data class AndroidStemBatchRequest(
    val mediaItem: MediaItem,
    val trackId: String,
    val durationMs: Long,
    val title: String?,
)

/** Single-consumer album/playlist queue; the actual separator remains the one shared service runtime. */
internal class AndroidStemBatchQueue(
    scope: CoroutineScope,
    isReady: (AndroidStemBatchRequest) -> Boolean,
    generate: suspend (AndroidStemBatchRequest, (AndroidStemSeparationProgress) -> Unit) -> Unit,
) : AutoCloseable {
    private val delegate =
        StemBatchQueue(
            scope = scope,
            trackIdOf = AndroidStemBatchRequest::trackId,
            titleOf = AndroidStemBatchRequest::title,
            isReady = isReady,
            generate = { request, emitProgress ->
                generate(request) { progress ->
                    emitProgress(StemBatchProgress(progress.fraction, progress.message))
                }
            },
        )

    val state: StateFlow<AndroidStemQueueState> = delegate.state

    fun enqueue(requests: List<AndroidStemBatchRequest>): Int = delegate.enqueue(requests)

    fun cancel() = delegate.cancel()

    fun runningJob(): Job? = delegate.runningJob()

    override fun close() = delegate.close()
}
