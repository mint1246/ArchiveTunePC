/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.session

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.playback.api.NextTrackPrefetcher
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.internal.PlaybackAtomicBoolean
import moe.rukamori.archivetune.playback.internal.platformIoDispatcher

data class PersistedPlaybackQueue(
    val title: String?,
    val items: List<PlaybackItem>,
    val currentIndex: Int,
    val positionMs: Long,
)

/**
 * Platform-neutral durable queue store contract.
 *
 * Implementations must strip transient credentials or request headers before writing to disk.
 */
interface PlaybackQueuePersistence {
    fun load(): PersistedPlaybackQueue?
    fun save(snapshot: PlaybackSnapshot)
    fun clear()
}

/**
 * Independently configurable headless playback session coordinating a [PlaybackController] with
 * optional durable queue checkpointing and next-track pre-resolution.
 *
 * Each feature is opt-in: passing null for [queuePersistence] or [prefetchResolver] starts no
 * corresponding observer or background work. [close] cancels owned observers, flushes a final
 * queue checkpoint if configured, and closes [controller] when [ownsController] is true.
 */
class PlaybackSessionHost(
    val controller: PlaybackController,
    private val ownsController: Boolean = true,
    parentContext: CoroutineContext = Dispatchers.Default,
    private val queuePersistence: PlaybackQueuePersistence? = null,
    private val queuePersistencePolicy: QueuePersistencePolicy = QueuePersistencePolicy(),
    private val persistenceCoordinator: PlaybackPersistenceCoordinator = PlaybackPersistenceCoordinator(),
    private val persistenceDispatcher: CoroutineDispatcher = platformIoDispatcher,
    prefetchResolver: (suspend (item: PlaybackItem, cacheAllowed: () -> Boolean) -> Result<ResolvedPlaybackSource>)? = null,
) : PlaybackController by controller {
    private val closed = PlaybackAtomicBoolean(false)
    private val sessionScope = CoroutineScope(parentContext + SupervisorJob(parentContext[Job]))
    private val prefetcher: NextTrackPrefetcher? =
        prefetchResolver?.let { resolver -> NextTrackPrefetcher(sessionScope, resolver) }

    val isClosed: Boolean
        get() = closed.get()

    init {
        if (prefetcher != null) {
            sessionScope.launch {
                controller.state.collect(prefetcher::onSnapshot)
            }
        }
        if (queuePersistence != null) {
            sessionScope.launch {
                controller.state.collect { snapshot ->
                    val token = persistenceCoordinator.saveToken()
                    if (persistenceCoordinator.canSave(token) && queuePersistencePolicy.shouldPersist(snapshot)) {
                        withContext(persistenceDispatcher) {
                            if (persistenceCoordinator.isSaveTokenCurrent(token)) {
                                queuePersistence.save(snapshot)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Restores a previously saved queue into [controller] if present and non-empty.
     * Checkpoint writes are gated while restoration is in progress so an empty startup state
     * cannot overwrite the persisted queue.
     */
    suspend fun restorePersistedQueue(playWhenReady: Boolean = false): Boolean {
        check(!closed.get()) { "PlaybackSessionHost is closed" }
        val store = queuePersistence ?: return false
        persistenceCoordinator.beginRestore()
        return try {
            val persisted = withContext(persistenceDispatcher) { store.load() }
            if (persisted == null || persisted.items.isEmpty()) {
                false
            } else {
                controller.replaceQueue(
                    items = persisted.items,
                    startIndex = persisted.currentIndex.coerceIn(0, persisted.items.lastIndex),
                    startPositionMs = persisted.positionMs.coerceAtLeast(0L),
                    title = persisted.title,
                    playWhenReady = playWhenReady,
                )
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            persistenceCoordinator.endRestore()
        }
    }

    /** Clears any persisted queue on disk and invalidates in-flight checkpoint saves. */
    suspend fun clearPersistedQueue() {
        check(!closed.get()) { "PlaybackSessionHost is closed" }
        val store = queuePersistence ?: return
        persistenceCoordinator.invalidateSaves()
        withContext(persistenceDispatcher) {
            store.clear()
        }
    }

    /** Synchronously writes the current controller state to [queuePersistence] when configured. */
    fun flushQueueCheckpoint() {
        val store = queuePersistence ?: return
        val snapshot = controller.state.value
        val token = persistenceCoordinator.saveToken()
        if (persistenceCoordinator.canSave(token) && queuePersistencePolicy.shouldPersist(snapshot, force = true)) {
            store.save(snapshot)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        sessionScope.cancel("PlaybackSessionHost closed")
        prefetcher?.let {
            runCatching(it::close).onFailure { error -> failure = accumulate(failure, error) }
        }
        runCatching(::flushQueueCheckpoint).onFailure { error ->
            failure = accumulate(failure, error)
        }
        if (ownsController) {
            runCatching(controller::close).onFailure { error ->
                failure = accumulate(failure, error)
            }
        }
        failure?.let { throw it }
    }

    private fun accumulate(existing: Throwable?, next: Throwable): Throwable =
        if (existing == null) {
            next
        } else {
            existing.apply { if (this !== next) addSuppressed(next) }
        }
}
