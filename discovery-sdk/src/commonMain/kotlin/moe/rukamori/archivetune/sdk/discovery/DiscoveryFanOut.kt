/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield

/**
 * Bounded parallel batch loading used by Android discovery seed expansion.
 *
 * Items are processed in input order, at most [batchSize] at a time, with a [yield] between
 * batches so a large fan-out does not monopolize the dispatcher. Cancellation from any child is
 * rethrown as a [CancellationException] rather than swallowed, matching the behaviour of the
 * original app implementation that this extracts.
 */
class DiscoveryFanOut(
    private val batchSize: Int = 3,
) {
    init {
        require(batchSize > 0) { "Discovery fan-out batch size must be positive" }
    }

    suspend fun <T, R> mapBatched(
        items: List<T>,
        load: suspend (T) -> R,
    ): List<R> =
        coroutineScope {
            if (items.isEmpty()) return@coroutineScope emptyList()
            items
                .chunked(batchSize)
                .flatMap { batch ->
                    batch
                        .map { item -> async { load(item) } }
                        .awaitAll()
                        .also { yield() }
                }
        }

    suspend fun <T, R> flatMapBatched(
        items: List<T>,
        load: suspend (T) -> List<R>,
    ): List<R> = mapBatched(items, load).flatten()

    /**
     * Runs [load] for its own failure handling but still propagates cancellation, mirroring the
     * "try related, fall back to search" step used for songs and artists.
     */
    suspend fun <T, R> flatMapBatchedBestEffort(
        items: List<T>,
        load: suspend (T) -> List<R>,
    ): List<R> =
        flatMapBatched(items) { item ->
            try {
                load(item)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                emptyList()
            }
        }
}
