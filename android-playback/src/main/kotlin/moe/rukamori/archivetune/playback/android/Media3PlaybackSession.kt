/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.session.PlaybackSessionHost

/**
 * Turnkey Android Media3 playback session combining [ExoPlayer], [Media3PlaybackController],
 * and [PlaybackSessionHost] (optional durable queue persistence and next-track pre-resolution).
 *
 * [close] deterministically cancels session background work, flushes a final queue checkpoint,
 * detaches the controller listener, and releases [exoPlayer] when [ownsPlayer] is true.
 */
class Media3PlaybackSession(
    val exoPlayer: ExoPlayer,
    val controller: Media3PlaybackController,
    private val host: PlaybackSessionHost,
    private val ownsPlayer: Boolean = true,
) : PlaybackController by host {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get() || host.isClosed

    suspend fun restorePersistedQueue(playWhenReady: Boolean = false): Boolean =
        host.restorePersistedQueue(playWhenReady = playWhenReady)

    suspend fun clearPersistedQueue() {
        host.clearPersistedQueue()
    }

    fun flushQueueCheckpoint() {
        host.flushQueueCheckpoint()
    }

    suspend fun refreshProgress() {
        controller.refreshProgress()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        runCatching(host::close).onFailure { failure = it }
        if (ownsPlayer) {
            runCatching(exoPlayer::release).onFailure { error ->
                val existing = failure
                if (existing == null) {
                    failure = error
                } else if (existing !== error) {
                    existing.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }
}

/**
 * Creates a standalone [Media3PlaybackSession] wired with lazy data-source deferred resolution
 * ([resolvingPlaybackSourceUpstream]), [Media3ShuffleOrderCapability], optional durable JSON
 * queue persistence ([AndroidPlaybackQueueStore]), and optional next-track prefetching.
 */
@OptIn(UnstableApi::class)
fun createMedia3PlaybackSession(
    context: Context,
    sourceResolver: PlaybackSourceResolver,
    queueFile: File? = null,
    httpDataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory(),
    chunkLength: Long = 8L * 1024 * 1024,
    exoPlayer: ExoPlayer? = null,
    ownsPlayer: Boolean = exoPlayer == null,
    playerDispatcher: CoroutineDispatcher? = null,
    progressSamplingScope: CoroutineScope? = null,
    progressSamplingIntervalMs: Long = 250L,
    parentContext: CoroutineContext = Dispatchers.Default,
    prefetchResolver: (suspend (item: PlaybackItem, cacheAllowed: () -> Boolean) -> Result<ResolvedPlaybackSource>)? = null,
): Media3PlaybackSession {
    val controllerRef = AtomicReference<Media3PlaybackController?>()
    val resolvedPlayer = exoPlayer ?: ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                resolvingPlaybackSourceUpstream(
                    context = context,
                    httpFactory = httpDataSourceFactory,
                    chunkLength = chunkLength,
                    itemForKey = { key ->
                        controllerRef.get()?.findDeferredItemByKey(key) ?: PlaybackItem(
                            metadata = PlaybackMetadata(id = key, title = ""),
                            source = PlaybackSource.Deferred(key),
                        )
                    },
                    sourceResolver = sourceResolver,
                ),
            ),
        )
        .build()

    val shuffleCapability = Media3ShuffleOrderCapability { shuffledIndices ->
        resolvedPlayer.setShuffleOrder(
            ShuffleOrder.DefaultShuffleOrder(shuffledIndices, Random.nextLong()),
        )
    }

    val controller = Media3PlaybackController(
        player = resolvedPlayer,
        sourceResolver = sourceResolver,
        shuffleOrderCapability = shuffleCapability,
        playerDispatcher = playerDispatcher,
        progressSamplingScope = progressSamplingScope,
        progressSamplingIntervalMs = progressSamplingIntervalMs,
        useDataSourceDeferredResolution = true,
    ).also(controllerRef::set)

    val host = PlaybackSessionHost(
        controller = controller,
        ownsController = true,
        parentContext = parentContext,
        queuePersistence = queueFile?.let(::AndroidPlaybackQueueStore),
        prefetchResolver = prefetchResolver,
    )

    return Media3PlaybackSession(
        exoPlayer = resolvedPlayer,
        controller = controller,
        host = host,
        ownsPlayer = ownsPlayer,
    )
}
