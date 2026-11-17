/*
 * ArchiveTune (2026)
 * Â© Rukamori â€” github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata as Media3Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.extensions.getQueueWindows
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackFailure
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackQueueEntry
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.api.TransitionReason

/**
 * Host capability for mutating a shuffled Media3 queue order (for example via
 * `ExoPlayer.setShuffleOrder(DefaultShuffleOrder(shuffledPeriodIndices, seed))`).
 *
 * `androidx.media3:media3-common`'s [Player] interface does not expose `setShuffleOrder`, so
 * operations that reorder or insert into a shuffled queue require this explicit capability.
 */
fun interface Media3ShuffleOrderCapability {
    fun setShuffleOrder(shuffledPeriodIndices: IntArray)
}

/**
 * Optional host customization for building Media3 [MediaItem] instances from [PlaybackItem]s.
 */
fun interface Media3MediaItemFactory {
    fun createMediaItem(item: PlaybackItem, resolvedSource: ResolvedPlaybackSource?): MediaItem
}

/**
 * Adapter implementing [PlaybackController] on top of a caller-supplied Media3 [Player].
 *
 * Ownership & threading:
 * - The caller owns [player] and releases it separately; [close] only detaches this adapter's
 *   listener and stops adapter-owned sampling/resolution work.
 * - If [playerDispatcher] is provided, all [Player] interactions are dispatched onto it. When null,
 *   calls execute on the caller's thread/dispatcher, which must match [Player.getApplicationLooper]
 *   for real Android players.
 * - When [useDataSourceDeferredResolution] is true, [PlaybackSource.Deferred] items are built with
 *   `mediaId = item.metadata.id`, `uri = key`, and `customCacheKey = key` so Media3's
 *   `resolvingPlaybackUpstream` / `resolvingPlaybackSourceUpstream` resolves them lazily on the
 *   loading thread (supporting automatic advancement, gapless pre-buffering, and duplicate entries).
 * - No hidden background `CoroutineScope` or polling loop is created by default. To sample
 *   `positionMs` / `bufferedPositionMs` periodically during active playback, pass an explicit
 *   [progressSamplingScope] (whose lifecycle you own) or call [refreshProgress] on demand.
 */
class Media3PlaybackController(
    val player: Player,
    private val sourceResolver: PlaybackSourceResolver? = null,
    private val shuffleOrderCapability: Media3ShuffleOrderCapability? = null,
    private val mediaItemFactory: Media3MediaItemFactory = DefaultMedia3MediaItemFactory,
    private val mediaItemToPlaybackItem: (MediaItem) -> PlaybackItem = ::defaultMediaItemToPlaybackItem,
    private val playerDispatcher: CoroutineDispatcher? = null,
    private val progressSamplingScope: CoroutineScope? = null,
    private val progressSamplingIntervalMs: Long = DEFAULT_PROGRESS_SAMPLING_INTERVAL_MS,
    private val useDataSourceDeferredResolution: Boolean = false,
) : PlaybackController {
    private val closed = AtomicBoolean(false)
    private val commandMutex = Mutex()
    private val stateLock = Any()

    private val uidTokens = IdentityHashMap<Any, Long>()
    private val mediaItemToPlaybackMap = WeakHashMap<MediaItem, PlaybackItem>()
    private val resolvedMediaItems = Collections.newSetFromMap(WeakHashMap<MediaItem, Boolean>())
    private var nextToken = 1L

    private var queueTitle: String? = null
    private var dismissedPlayerError: PlaybackException? = null
    private var localFailure: PlaybackFailure? = null
    private var resolvingCount = 0
    private var pendingTransitionReason: TransitionReason? = null
    private var lastQueue: List<PlaybackItem> = emptyList()
    private var lastCurrentIndex: Int = -1
    private var lastCurrentItem: PlaybackItem? = null

    private var samplingJob: Job? = null
    private var autoResolveJob: Job? = null
    private var internalAutoResolveScope: CoroutineScope? = null

    private val _state = MutableStateFlow(PlaybackSnapshot())
    override val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_IDLE || player.playerError == null) {
                syncErrorFromPlayer()
            }
            publishSnapshot(emitQueueChange = false)
            updateSamplingState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publishSnapshot(emitQueueChange = false)
            updateSamplingState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishSnapshot(emitQueueChange = false)
            updateSamplingState()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            publishSnapshot(emitQueueChange = false)
        }

        override fun onVolumeChanged(volume: Float) {
            publishSnapshot(emitQueueChange = false)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            publishSnapshot(emitQueueChange = false)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            publishSnapshot(emitQueueChange = false)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            publishSnapshot(emitQueueChange = false)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            publishSnapshot(emitQueueChange = true)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            cancelAutoResolve()
            val mappedReason = synchronized(stateLock) {
                pendingTransitionReason?.also { pendingTransitionReason = null }
                    ?: reason.toTransitionReason()
            }
            val snapshot = publishSnapshot(emitQueueChange = true)
            val current = snapshot.currentItem
            val previous = synchronized(stateLock) {
                lastCurrentItem.also { lastCurrentItem = current }
            }
            if (
                previous != current ||
                mappedReason == TransitionReason.QUEUE_REPLACED ||
                mappedReason == TransitionReason.REPEAT ||
                mappedReason == TransitionReason.RETRY
            ) {
                _events.tryEmit(
                    PlaybackEvent.MediaTransition(
                        previous = previous,
                        current = current,
                        reason = mappedReason,
                    ),
                )
            }
            if (
                !useDataSourceDeferredResolution &&
                sourceResolver != null &&
                snapshot.currentIndex >= 0 &&
                current?.source is PlaybackSource.Deferred &&
                !hasPlayableUri(snapshot.currentIndex)
            ) {
                scheduleTransitionResolution(snapshot.currentIndex, current)
            }
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            val emittedFailure = syncErrorFromPlayer()
            val snapshot = publishSnapshot(emitQueueChange = false)
            if (emittedFailure != null) {
                _events.tryEmit(PlaybackEvent.Failure(emittedFailure, snapshot.currentItem))
            }
            updateSamplingState()
        }
    }

    init {
        player.addListener(listener)
        syncErrorFromPlayer()
        publishSnapshot(emitQueueChange = false)
        updateSamplingState()
    }

    /**
     * Samples current position/buffered position/duration from [player] into [state].
     * Useful when [progressSamplingScope] is null and the host drives its own UI ticks.
     */
    suspend fun refreshProgress() {
        checkOpen()
        onPlayerThread {
            publishSnapshot(emitQueueChange = false)
        }
    }

    override suspend fun replaceQueue(
        items: List<PlaybackItem>,
        startIndex: Int,
        startPositionMs: Long,
        title: String?,
        playWhenReady: Boolean,
    ) {
        checkOpen()
        cancelAutoResolve()
        commandMutex.withLock {
            onPlayerThread {
                val safePosition = startPositionMs.coerceAtLeast(0L)
                synchronized(stateLock) {
                    queueTitle = title
                    localFailure = null
                    dismissedPlayerError = player.playerError
                    pendingTransitionReason = TransitionReason.QUEUE_REPLACED
                }

                if (items.isEmpty()) {
                    player.clearMediaItems()
                    player.playWhenReady = false
                    publishSnapshot(emitQueueChange = true)
                    return@onPlayerThread
                }

                val safeStartIndex = startIndex.coerceIn(items.indices)
                val mediaItems = buildMediaItemsForQueue(items, startIndex = safeStartIndex, resolveTarget = playWhenReady)
                if (mediaItems == null) {
                    val placeholders = items.map { buildDeferredAwareMediaItem(it, resolved = null) }
                    player.setMediaItems(placeholders, safeStartIndex, safePosition)
                    player.playWhenReady = false
                    publishSnapshot(emitQueueChange = true)
                    return@onPlayerThread
                }

                player.setMediaItems(mediaItems, safeStartIndex, safePosition)
                player.playWhenReady = playWhenReady
                if (playWhenReady || player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                publishSnapshot(emitQueueChange = true)
            }
        }
    }

    override suspend fun play() {
        checkOpen()
        cancelAutoResolve()
        commandMutex.withLock {
            onPlayerThread {
                synchronized(stateLock) {
                    localFailure = null
                    dismissedPlayerError = player.playerError
                }
                val currentIndex = currentLogicalIndex()
                val currentItem = if (currentIndex >= 0) readPlaybackItemAt(currentIndex) else null
                if (currentItem != null && !hasPlayableUri(currentIndex)) {
                    val resolved = resolveItemOrFail(currentItem) ?: return@onPlayerThread
                    replaceMediaItemPreservingPosition(currentIndex, currentItem, resolved)
                }
                if (player.playbackState == Player.STATE_IDLE || player.playerError != null) {
                    player.prepare()
                } else if (player.playbackState == Player.STATE_ENDED) {
                    player.seekToDefaultPosition()
                }
                player.playWhenReady = true
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun pause() {
        checkOpen()
        commandMutex.withLock {
            onPlayerThread {
                player.playWhenReady = false
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun stop() {
        checkOpen()
        cancelAutoResolve()
        commandMutex.withLock {
            onPlayerThread {
                synchronized(stateLock) {
                    localFailure = null
                    dismissedPlayerError = player.playerError
                }
                player.stop()
                player.playWhenReady = false
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        checkOpen()
        commandMutex.withLock {
            onPlayerThread {
                val safePosition = positionMs.coerceAtLeast(0L)
                player.seekTo(safePosition)
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun seekToItem(index: Int, positionMs: Long) {
        checkOpen()
        cancelAutoResolve()
        commandMutex.withLock {
            onPlayerThread {
                val count = player.mediaItemCount
                require(index in 0 until count) { "Queue index $index is out of bounds (size=$count)" }
                val safePosition = positionMs.coerceAtLeast(0L)
                val targetItem = readPlaybackItemAt(index)
                synchronized(stateLock) {
                    localFailure = null
                    dismissedPlayerError = player.playerError
                    pendingTransitionReason = TransitionReason.USER_SELECTED
                }
                if (!hasPlayableUri(index)) {
                    val resolved = resolveItemOrFail(targetItem) ?: return@onPlayerThread
                    replaceMediaItemAt(index, targetItem, resolved)
                }
                player.seekTo(index, safePosition)
                player.prepare()
                player.playWhenReady = true
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun skipNext() {
        checkOpen()
        cancelAutoResolve()
        commandMutex.withLock {
            onPlayerThread {
                val timeline = player.currentTimeline
                val current = currentLogicalIndex()
                if (timeline.isEmpty || current < 0) return@onPlayerThread

                val nextIndex = timeline.getNextWindowIndex(
                    current,
                    effectiveNextRepeatMode(player.repeatMode),
                    player.shuffleModeEnabled,
                )
                if (nextIndex == C.INDEX_UNSET) {
                    player.seekToNext()
                    publishSnapshot(emitQueueChange = false)
                    return@onPlayerThread
                }

                synchronized(stateLock) {
                    localFailure = null
                    dismissedPlayerError = player.playerError
                    pendingTransitionReason = TransitionReason.USER_NEXT
                }
                val nextItem = readPlaybackItemAt(nextIndex)
                if (!hasPlayableUri(nextIndex)) {
                    val resolved = resolveItemOrFail(nextItem) ?: return@onPlayerThread
                    replaceMediaItemAt(nextIndex, nextItem, resolved)
                }
                player.seekToNextMediaItem()
                player.prepare()
                player.playWhenReady = true
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun skipPrevious(restartThresholdMs: Long) {
        checkOpen()
        cancelAutoResolve()
        commandMutex.withLock {
            onPlayerThread {
                val threshold = restartThresholdMs.coerceAtLeast(0L)
                if (player.currentPosition > threshold) {
                    player.seekTo(0L)
                    publishSnapshot(emitQueueChange = false)
                    return@onPlayerThread
                }
                val timeline = player.currentTimeline
                val current = currentLogicalIndex()
                if (timeline.isEmpty || current < 0) return@onPlayerThread

                val prevIndex = timeline.getPreviousWindowIndex(
                    current,
                    effectiveNextRepeatMode(player.repeatMode),
                    player.shuffleModeEnabled,
                )
                if (prevIndex == C.INDEX_UNSET) {
                    player.seekTo(0L)
                    publishSnapshot(emitQueueChange = false)
                    return@onPlayerThread
                }

                synchronized(stateLock) {
                    localFailure = null
                    dismissedPlayerError = player.playerError
                    pendingTransitionReason = TransitionReason.USER_PREVIOUS
                }
                val prevItem = readPlaybackItemAt(prevIndex)
                if (!hasPlayableUri(prevIndex)) {
                    val resolved = resolveItemOrFail(prevItem) ?: return@onPlayerThread
                    replaceMediaItemAt(prevIndex, prevItem, resolved)
                }
                player.seekToPreviousMediaItem()
                player.prepare()
                player.playWhenReady = true
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun enqueue(items: List<PlaybackItem>) {
        checkOpen()
        if (items.isEmpty()) return
        commandMutex.withLock {
            onPlayerThread {
                if (player.mediaItemCount == 0) {
                    synchronized(stateLock) {
                        localFailure = null
                        pendingTransitionReason = TransitionReason.QUEUE_REPLACED
                    }
                    val mediaItems = buildMediaItemsForQueue(items, startIndex = 0, resolveTarget = true)
                        ?: return@onPlayerThread
                    player.setMediaItems(mediaItems, 0, 0L)
                    player.playWhenReady = true
                    player.prepare()
                    publishSnapshot(emitQueueChange = true)
                    return@onPlayerThread
                }
                val mediaItems = items.map { buildDeferredAwareMediaItem(it, resolved = null) }
                player.addMediaItems(mediaItems)
                publishSnapshot(emitQueueChange = true)
            }
        }
    }

    override suspend fun playNext(items: List<PlaybackItem>) {
        checkOpen()
        if (items.isEmpty()) return
        commandMutex.withLock {
            onPlayerThread {
                val count = player.mediaItemCount
                if (count == 0) {
                    synchronized(stateLock) {
                        localFailure = null
                        pendingTransitionReason = TransitionReason.QUEUE_REPLACED
                    }
                    val mediaItems = buildMediaItemsForQueue(items, startIndex = 0, resolveTarget = true)
                        ?: return@onPlayerThread
                    player.setMediaItems(mediaItems, 0, 0L)
                    player.playWhenReady = true
                    player.prepare()
                    publishSnapshot(emitQueueChange = true)
                    return@onPlayerThread
                }

                val currentIndex = currentLogicalIndex().coerceAtLeast(0)
                val insertionIndex = (currentIndex + 1).coerceAtMost(count)
                val shuffleOrder = if (player.shuffleModeEnabled) {
                    val capability = shuffleOrderCapability
                        ?: throw UnsupportedOperationException(
                            "playNext while shuffle is enabled requires Media3ShuffleOrderCapability",
                        )
                    val currentOrder = player.getQueueWindows().map { it.firstPeriodIndex }
                    val shifted = currentOrder.map { periodIndex ->
                        if (periodIndex >= insertionIndex) periodIndex + items.size else periodIndex
                    }
                    val currentPos = shifted.indexOf(currentIndex).let { if (it >= 0) it else 0 }
                    val newIndices = (insertionIndex until insertionIndex + items.size).toList()
                    val updated = shifted.toMutableList().apply {
                        addAll((currentPos + 1).coerceAtMost(size), newIndices)
                    }
                    capability to updated.toIntArray()
                } else {
                    null
                }

                val mediaItems = items.map { buildDeferredAwareMediaItem(it, resolved = null) }
                player.addMediaItems(insertionIndex, mediaItems)
                shuffleOrder?.let { (capability, indices) -> capability.setShuffleOrder(indices) }
                publishSnapshot(emitQueueChange = true)
            }
        }
    }

    override suspend fun removeQueueItem(index: Int) {
        checkOpen()
        commandMutex.withLock {
            onPlayerThread {
                val count = player.mediaItemCount
                require(index in 0 until count) { "Queue index $index is out of bounds (size=$count)" }
                if (index == currentLogicalIndex()) {
                    synchronized(stateLock) {
                        pendingTransitionReason = TransitionReason.CURRENT_ITEM_REMOVED
                    }
                }
                player.removeMediaItem(index)
                publishSnapshot(emitQueueChange = true)
            }
        }
    }

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        checkOpen()
        commandMutex.withLock {
            onPlayerThread {
                val windows = player.getQueueWindows()
                require(fromIndex in windows.indices) { "Queue row index $fromIndex is out of bounds (size=${windows.size})" }
                require(toIndex in windows.indices) { "Queue row index $toIndex is out of bounds (size=${windows.size})" }
                if (fromIndex == toIndex) return@onPlayerThread

                if (!player.shuffleModeEnabled) {
                    player.moveMediaItem(fromIndex, toIndex)
                    publishSnapshot(emitQueueChange = true)
                    return@onPlayerThread
                }

                val capability = shuffleOrderCapability
                    ?: throw UnsupportedOperationException(
                        "moveQueueItem while shuffle is enabled requires Media3ShuffleOrderCapability",
                    )
                val reordered = windows
                    .map { it.firstPeriodIndex }
                    .toMutableList()
                    .apply { add(toIndex, removeAt(fromIndex)) }
                    .toIntArray()
                capability.setShuffleOrder(reordered)
                publishSnapshot(emitQueueChange = true)
            }
        }
    }

    override suspend fun moveQueueItemToNext(index: Int) {
        checkOpen()
        commandMutex.withLock {
            onPlayerThread {
                val count = player.mediaItemCount
                require(index in 0 until count) { "Queue index $index is out of bounds (size=$count)" }
                val currentIndex = currentLogicalIndex()
                if (count < 2 || currentIndex < 0 || index == currentIndex) return@onPlayerThread

                if (!player.shuffleModeEnabled) {
                    val destinationIndex = if (index < currentIndex) {
                        currentIndex
                    } else {
                        (currentIndex + 1).coerceAtMost(count - 1)
                    }
                    if (index != destinationIndex) {
                        player.moveMediaItem(index, destinationIndex)
                        publishSnapshot(emitQueueChange = true)
                    }
                    return@onPlayerThread
                }

                val capability = shuffleOrderCapability
                    ?: throw UnsupportedOperationException(
                        "moveQueueItemToNext while shuffle is enabled requires Media3ShuffleOrderCapability",
                    )
                val shuffledIndices = player.getQueueWindows().map { it.firstPeriodIndex }.toMutableList()
                val sourcePos = shuffledIndices.indexOf(index)
                val currentPos = shuffledIndices.indexOf(currentIndex)
                if (sourcePos < 0 || currentPos < 0) return@onPlayerThread

                val destinationPos = if (sourcePos < currentPos) {
                    currentPos
                } else {
                    (currentPos + 1).coerceAtMost(shuffledIndices.lastIndex)
                }
                if (sourcePos == destinationPos) return@onPlayerThread

                shuffledIndices.add(destinationPos, shuffledIndices.removeAt(sourcePos))
                capability.setShuffleOrder(shuffledIndices.toIntArray())
                publishSnapshot(emitQueueChange = true)
            }
        }
    }

    override suspend fun clearQueue() {
        checkOpen()
        commandMutex.withLock {
            onPlayerThread {
                val count = player.mediaItemCount
                val currentIndex = currentLogicalIndex()
                if (count == 0) {
                    synchronized(stateLock) { queueTitle = null }
                    publishSnapshot(emitQueueChange = false)
                    return@onPlayerThread
                }
                if (currentIndex < 0 || currentIndex >= count) {
                    synchronized(stateLock) { queueTitle = null }
                    player.clearMediaItems()
                    publishSnapshot(emitQueueChange = true)
                    return@onPlayerThread
                }
                if (currentIndex + 1 < count) {
                    player.removeMediaItems(currentIndex + 1, count)
                }
                if (currentIndex > 0) {
                    player.removeMediaItems(0, currentIndex)
                }
                publishSnapshot(emitQueueChange = true)
            }
        }
    }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        checkOpen()
        commandMutex.withLock {
            onPlayerThread {
                player.repeatMode = mode.toMedia3RepeatMode()
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun setShuffleEnabled(enabled: Boolean) {
        checkOpen()
        commandMutex.withLock {
            onPlayerThread {
                player.shuffleModeEnabled = enabled
                publishSnapshot(emitQueueChange = true)
            }
        }
    }

    override suspend fun setVolume(volume: Float) {
        checkOpen()
        val safeVolume = volume.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        commandMutex.withLock {
            onPlayerThread {
                player.volume = safeVolume
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun setSpeed(speed: Float) {
        checkOpen()
        val safeSpeed = speed.takeIf(Float::isFinite)?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f
        commandMutex.withLock {
            onPlayerThread {
                val currentPitch = player.playbackParameters.pitch
                player.playbackParameters = PlaybackParameters(safeSpeed, currentPitch)
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun setPitch(pitchSemitones: Float) {
        checkOpen()
        val safeSemitones = pitchSemitones.takeIf(Float::isFinite)?.coerceIn(MIN_PITCH, MAX_PITCH) ?: 0f
        val pitchMultiplier = 2.0.pow(safeSemitones / 12.0).toFloat()
        commandMutex.withLock {
            onPlayerThread {
                val currentSpeed = player.playbackParameters.speed
                player.playbackParameters = PlaybackParameters(currentSpeed, pitchMultiplier)
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun retry() {
        checkOpen()
        cancelAutoResolve()
        commandMutex.withLock {
            onPlayerThread {
                val currentIndex = currentLogicalIndex()
                if (currentIndex < 0) return@onPlayerThread
                val currentItem = readPlaybackItemAt(currentIndex)
                synchronized(stateLock) {
                    localFailure = null
                    dismissedPlayerError = player.playerError
                    pendingTransitionReason = TransitionReason.RETRY
                }
                if (currentItem.source is PlaybackSource.Deferred && sourceResolver != null && !useDataSourceDeferredResolution) {
                    val resolved = resolveItemOrFail(currentItem) ?: return@onPlayerThread
                    replaceMediaItemPreservingPosition(currentIndex, currentItem, resolved)
                } else if (!hasPlayableUri(currentIndex)) {
                    val resolved = resolveItemOrFail(currentItem) ?: return@onPlayerThread
                    replaceMediaItemPreservingPosition(currentIndex, currentItem, resolved)
                }
                player.prepare()
                player.playWhenReady = true
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override suspend fun clearError() {
        checkOpen()
        commandMutex.withLock {
            onPlayerThread {
                synchronized(stateLock) {
                    localFailure = null
                    dismissedPlayerError = player.playerError
                }
                publishSnapshot(emitQueueChange = false)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        samplingJob?.cancel()
        samplingJob = null
        cancelAutoResolve()
        synchronized(stateLock) {
            internalAutoResolveScope?.cancel("Media3PlaybackController closed")
            internalAutoResolveScope = null
        }
        player.removeListener(listener)
        synchronized(stateLock) {
            uidTokens.clear()
            mediaItemToPlaybackMap.clear()
            resolvedMediaItems.clear()
        }
    }

    internal fun findDeferredItemByKey(key: String): PlaybackItem? {
        val fromSnapshot = _state.value.queue.firstOrNull { item ->
            (item.source as? PlaybackSource.Deferred)?.key == key || item.metadata.id == key
        }
        if (fromSnapshot != null) return fromSnapshot
        return synchronized(stateLock) {
            mediaItemToPlaybackMap.values.firstOrNull { item ->
                (item.source as? PlaybackSource.Deferred)?.key == key || item.metadata.id == key
            }
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "Media3PlaybackController is closed" }
    }

    private suspend fun <T> onPlayerThread(block: suspend () -> T): T =
        if (playerDispatcher != null) {
            withContext(playerDispatcher) { block() }
        } else {
            block()
        }

    private fun cancelAutoResolve() {
        synchronized(stateLock) {
            autoResolveJob?.cancel()
            autoResolveJob = null
        }
    }

    private fun resolveScopeForTransition(): CoroutineScope? =
        progressSamplingScope ?: synchronized(stateLock) {
            if (closed.get() || playerDispatcher == null) {
                null
            } else {
                internalAutoResolveScope ?: CoroutineScope(SupervisorJob() + playerDispatcher).also {
                    internalAutoResolveScope = it
                }
            }
        }

    private fun scheduleTransitionResolution(targetIndex: Int, targetItem: PlaybackItem) {
        val scope = resolveScopeForTransition() ?: return
        synchronized(stateLock) {
            autoResolveJob?.cancel()
            autoResolveJob = scope.launch {
                commandMutex.withLock {
                    onPlayerThread {
                        if (closed.get()) return@onPlayerThread
                        val currentIndex = currentLogicalIndex()
                        if (currentIndex != targetIndex || hasPlayableUri(currentIndex)) return@onPlayerThread
                        val currentItem = readPlaybackItemAt(currentIndex)
                        if (currentItem != targetItem) return@onPlayerThread

                        val resolved = resolveItemOrFail(currentItem) ?: return@onPlayerThread
                        if (closed.get()) return@onPlayerThread
                        val verifiedIndex = currentLogicalIndex()
                        if (verifiedIndex != targetIndex || readPlaybackItemAt(verifiedIndex) != targetItem) {
                            return@onPlayerThread
                        }
                        replaceMediaItemPreservingPosition(verifiedIndex, currentItem, resolved)
                        player.prepare()
                        publishSnapshot(emitQueueChange = false)
                    }
                }
            }
        }
    }

    private suspend fun buildMediaItemsForQueue(
        items: List<PlaybackItem>,
        startIndex: Int,
        resolveTarget: Boolean,
    ): List<MediaItem>? {
        val result = ArrayList<MediaItem>(items.size)
        for ((index, item) in items.withIndex()) {
            if (!useDataSourceDeferredResolution && resolveTarget && index == startIndex && item.source is PlaybackSource.Deferred) {
                val resolved = resolveItemOrFail(item) ?: return null
                result += createTrackedMediaItem(item, resolved)
            } else {
                result += buildDeferredAwareMediaItem(item, resolved = null)
            }
        }
        return result
    }

    private fun buildDeferredAwareMediaItem(
        item: PlaybackItem,
        resolved: ResolvedPlaybackSource?,
    ): MediaItem {
        val itemSource = item.source
        return if (resolved == null && itemSource is PlaybackSource.Deferred && mediaItemFactory === DefaultMedia3MediaItemFactory) {
            if (useDataSourceDeferredResolution) {
                buildDataSourceDeferredMediaItem(item, itemSource.key).also { mediaItem ->
                    synchronized(stateLock) {
                        mediaItemToPlaybackMap[mediaItem] = item
                        resolvedMediaItems.add(mediaItem)
                    }
                }
            } else {
                buildPlaceholderMediaItem(item).also { mediaItem ->
                    synchronized(stateLock) {
                        mediaItemToPlaybackMap[mediaItem] = item
                    }
                }
            }
        } else {
            createTrackedMediaItem(item, resolved)
        }
    }

    private fun createTrackedMediaItem(
        item: PlaybackItem,
        resolved: ResolvedPlaybackSource?,
    ): MediaItem =
        mediaItemFactory.createMediaItem(item, resolved).also { mediaItem ->
            synchronized(stateLock) {
                mediaItemToPlaybackMap[mediaItem] = item
                if (resolved != null || item.source !is PlaybackSource.Deferred || useDataSourceDeferredResolution) {
                    resolvedMediaItems.add(mediaItem)
                }
            }
        }

    private suspend fun resolveItemOrFail(item: PlaybackItem): ResolvedPlaybackSource? {
        val resolver = sourceResolver
        if (resolver == null) {
            val failure = PlaybackFailure(
                message = "No PlaybackSourceResolver configured to resolve deferred item '${item.metadata.id}'",
                kind = PlaybackFailure.Kind.SOURCE_RESOLUTION,
                recoverable = false,
            )
            recordLocalFailure(failure, item)
            return null
        }

        synchronized(stateLock) { resolvingCount++ }
        publishSnapshot(emitQueueChange = false)
        return try {
            val result = resolver.resolve(item)
            result.getOrElse { error ->
                val failure = PlaybackFailure(
                    message = error.message ?: "Failed to resolve '${item.metadata.title}'",
                    kind = PlaybackFailure.Kind.SOURCE_RESOLUTION,
                    recoverable = true,
                )
                recordLocalFailure(failure, item)
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            synchronized(stateLock) { resolvingCount = (resolvingCount - 1).coerceAtLeast(0) }
            publishSnapshot(emitQueueChange = false)
        }
    }

    private fun recordLocalFailure(failure: PlaybackFailure, item: PlaybackItem?) {
        synchronized(stateLock) {
            localFailure = failure
        }
        publishSnapshot(emitQueueChange = false)
        _events.tryEmit(PlaybackEvent.Failure(failure, item))
    }

    private fun replaceMediaItemPreservingPosition(
        index: Int,
        item: PlaybackItem,
        resolved: ResolvedPlaybackSource,
    ) {
        val position = player.currentPosition.coerceAtLeast(0L)
        replaceMediaItemAt(index, item, resolved)
        if (position > 0L) {
            player.seekTo(index, position)
        }
    }

    private fun replaceMediaItemAt(
        index: Int,
        item: PlaybackItem,
        resolved: ResolvedPlaybackSource,
    ) {
        val updated = createTrackedMediaItem(item, resolved)
        if (player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
            player.replaceMediaItem(index, updated)
        } else {
            val all = (0 until player.mediaItemCount).map { i ->
                if (i == index) updated else player.getMediaItemAt(i)
            }
            val pos = player.currentPosition.coerceAtLeast(0L)
            player.setMediaItems(all, currentLogicalIndex().coerceAtLeast(0), pos)
        }
    }

    private fun hasPlayableUri(index: Int): Boolean {
        if (index !in 0 until player.mediaItemCount) return false
        val mediaItem = player.getMediaItemAt(index)
        val trackedResolved = synchronized(stateLock) { mediaItem in resolvedMediaItems }
        if (trackedResolved) return true
        val uri = mediaItem.localConfiguration?.uri
        if (uri != null && uri != Uri.EMPTY && uri.toString() != UNRESOLVED_URI_SENTINEL) {
            return true
        }
        return useDataSourceDeferredResolution || readPlaybackItemAt(index).source !is PlaybackSource.Deferred
    }

    private fun syncErrorFromPlayer(): PlaybackFailure? {
        val playerError = player.playerError
        return synchronized(stateLock) {
            when {
                playerError == null -> {
                    dismissedPlayerError = null
                    null
                }
                playerError === dismissedPlayerError -> null
                else -> {
                    dismissedPlayerError = null
                    playerError.toPlaybackFailure()
                }
            }
        }
    }

    private fun publishSnapshot(emitQueueChange: Boolean): PlaybackSnapshot {
        val timeline = player.currentTimeline
        val itemCount = player.mediaItemCount
        val queue = (0 until itemCount).map(::readPlaybackItemAt)
        val currentIndex = if (timeline.isEmpty || itemCount == 0) {
            -1
        } else {
            player.currentMediaItemIndex.takeIf { it in 0 until itemCount } ?: -1
        }

        val windows = player.getQueueWindows()
        val entries = synchronized(stateLock) {
            val activeUids = windows.mapTo(HashSet(windows.size)) { it.uid }
            uidTokens.keys.retainAll(activeUids)
            windows.map { window ->
                val token = uidTokens.getOrPut(window.uid) { nextToken++ }
                val logicalIndex = window.firstPeriodIndex
                val item = queue.getOrElse(logicalIndex) {
                    mediaItemToPlaybackItem(window.mediaItem)
                }
                PlaybackQueueEntry(
                    id = token,
                    item = item,
                    logicalIndex = logicalIndex,
                    isCurrent = logicalIndex == currentIndex && currentIndex >= 0,
                )
            }
        }

        val activeFailure = synchronized(stateLock) {
            localFailure ?: player.playerError?.takeIf { it !== dismissedPlayerError }?.toPlaybackFailure()
        }
        val isResolving = synchronized(stateLock) { resolvingCount > 0 }
        val status = when {
            isResolving -> PlaybackStatus.RESOLVING
            activeFailure != null -> PlaybackStatus.ERROR
            else -> when (player.playbackState) {
                Player.STATE_IDLE -> PlaybackStatus.IDLE
                Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                Player.STATE_READY -> PlaybackStatus.READY
                Player.STATE_ENDED -> PlaybackStatus.ENDED
                else -> PlaybackStatus.IDLE
            }
        }

        val repeatMode = player.repeatMode.toPlaybackRepeatMode()
        val shuffleEnabled = player.shuffleModeEnabled
        val hasPrevious = if (!timeline.isEmpty && currentIndex >= 0) {
            player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) ||
                timeline.getPreviousWindowIndex(
                    currentIndex,
                    effectiveNextRepeatMode(player.repeatMode),
                    shuffleEnabled,
                ) != C.INDEX_UNSET
        } else {
            false
        }
        val hasNext = if (!timeline.isEmpty && currentIndex >= 0) {
            player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) ||
                timeline.getNextWindowIndex(
                    currentIndex,
                    effectiveNextRepeatMode(player.repeatMode),
                    shuffleEnabled,
                ) != C.INDEX_UNSET
        } else {
            false
        }

        val currentItem = queue.getOrNull(currentIndex)
        val durationMs = if (!timeline.isEmpty && currentIndex >= 0) {
            player.duration.takeIf { it != C.TIME_UNSET && it >= 0L }
                ?: currentItem?.metadata?.durationHintMs?.coerceAtLeast(0L)
                ?: 0L
        } else {
            0L
        }
        val positionMs = if (!timeline.isEmpty && currentIndex >= 0) {
            player.currentPosition.coerceAtLeast(0L)
        } else {
            0L
        }
        val bufferedMs = if (!timeline.isEmpty && currentIndex >= 0) {
            player.bufferedPosition.coerceAtLeast(0L)
        } else {
            0L
        }

        val params = player.playbackParameters
        val speed = params.speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val pitchSemitones = pitchMultiplierToSemitones(params.pitch)
        val volume = player.volume.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        val title = synchronized(stateLock) { queueTitle }

        val snapshot = PlaybackSnapshot(
            queue = queue,
            queueEntries = entries,
            queueTitle = title,
            currentIndex = currentIndex,
            status = status,
            playWhenReady = player.playWhenReady,
            positionMs = positionMs,
            bufferedPositionMs = bufferedMs,
            durationMs = durationMs,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            hasPreviousItem = hasPrevious,
            hasNextItem = hasNext,
            volume = volume,
            speed = speed,
            pitchSemitones = pitchSemitones,
            error = activeFailure,
        )

        val shouldEmitQueueChanged = synchronized(stateLock) {
            val changed = emitQueueChange && (queue != lastQueue || currentIndex != lastCurrentIndex)
            lastQueue = queue
            lastCurrentIndex = currentIndex
            changed
        }

        _state.value = snapshot
        if (shouldEmitQueueChanged) {
            _events.tryEmit(PlaybackEvent.QueueChanged(queue, currentIndex))
        }
        return snapshot
    }

    private fun updateSamplingState() {
        val scope = progressSamplingScope ?: return
        if (closed.get()) return
        val shouldSample = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_READY)
        if (shouldSample && samplingJob?.isActive != true) {
            val interval = progressSamplingIntervalMs.coerceAtLeast(MIN_PROGRESS_SAMPLING_INTERVAL_MS)
            samplingJob = scope.launch {
                while (isActive && !closed.get()) {
                    delay(interval)
                    onPlayerThread {
                        if (!closed.get()) {
                            publishSnapshot(emitQueueChange = false)
                        }
                    }
                }
            }
        } else if (!shouldSample) {
            samplingJob?.cancel()
            samplingJob = null
        }
    }

    private fun currentLogicalIndex(): Int =
        if (player.currentTimeline.isEmpty || player.mediaItemCount == 0) {
            -1
        } else {
            player.currentMediaItemIndex.takeIf { it in 0 until player.mediaItemCount } ?: -1
        }

    private fun readPlaybackItemAt(index: Int): PlaybackItem {
        val mediaItem = player.getMediaItemAt(index)
        val tagged = mediaItem.localConfiguration?.tag as? PlaybackItem
        val tracked = synchronized(stateLock) { mediaItemToPlaybackMap[mediaItem] }
        return tagged ?: tracked ?: mediaItemToPlaybackItem(mediaItem)
    }

    private fun effectiveNextRepeatMode(repeatMode: Int): Int =
        if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else repeatMode

    companion object {
        private const val MIN_SPEED = 0.25f
        private const val MAX_SPEED = 4.0f
        private const val MIN_PITCH = -12f
        private const val MAX_PITCH = 12f
        private const val DEFAULT_PROGRESS_SAMPLING_INTERVAL_MS = 250L
        private const val MIN_PROGRESS_SAMPLING_INTERVAL_MS = 50L
        internal const val UNRESOLVED_URI_SENTINEL = "archivetune://unresolved"
    }
}

object DefaultMedia3MediaItemFactory : Media3MediaItemFactory {
    override fun createMediaItem(item: PlaybackItem, resolvedSource: ResolvedPlaybackSource?): MediaItem {
        val uriString = when {
            resolvedSource != null -> resolvedSource.uri
            else -> when (val source = item.source) {
                is PlaybackSource.Remote -> source.url
                is PlaybackSource.LocalFile -> toLocalUriString(source.path)
                is PlaybackSource.Deferred -> throw IllegalStateException(
                    "Deferred item '${item.metadata.id}' has not been resolved",
                )
            }
        }
        val mimeType = resolvedSource?.mimeType ?: (item.source as? PlaybackSource.Remote)?.mimeType
        return buildBaseMediaItem(item, uriString, mimeType)
    }
}

private fun buildPlaceholderMediaItem(item: PlaybackItem): MediaItem =
    buildBaseMediaItem(item, Media3PlaybackController.UNRESOLVED_URI_SENTINEL, mimeType = null)

private fun buildDataSourceDeferredMediaItem(item: PlaybackItem, key: String): MediaItem =
    buildBaseMediaItem(
        item = item,
        uriString = key,
        mimeType = null,
        customCacheKey = key,
    )

private fun buildBaseMediaItem(
    item: PlaybackItem,
    uriString: String,
    mimeType: String?,
    customCacheKey: String? = null,
): MediaItem {
    val meta = item.metadata
    val mediaMetadata = Media3Metadata.Builder()
        .setTitle(meta.title)
        .setArtist(meta.artistText.ifEmpty { null })
        .setSubtitle(meta.artistText.ifEmpty { null })
        .setAlbumTitle(meta.album?.title)
        .setAlbumArtist(meta.albumArtist)
        .setArtworkUri(meta.artworkUri?.let(Uri::parse))
        .setTrackNumber(meta.trackNumber)
        .setDiscNumber(meta.discNumber)
        .setReleaseYear(meta.year)
        .setIsPlayable(true)
        .build()

    return MediaItem.Builder()
        .setMediaId(meta.id)
        .setUri(uriString)
        .apply {
            if (!customCacheKey.isNullOrBlank()) setCustomCacheKey(customCacheKey)
            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
        }
        .setTag(item)
        .setMediaMetadata(mediaMetadata)
        .build()
}

private fun toLocalUriString(path: String): String =
    if (path.startsWith("content://") || path.startsWith("file://")) {
        path
    } else {
        Uri.fromFile(File(path)).toString()
    }

private fun defaultMediaItemToPlaybackItem(mediaItem: MediaItem): PlaybackItem {
    val tagged = mediaItem.localConfiguration?.tag as? PlaybackItem
    if (tagged != null) return tagged

    val meta = mediaItem.mediaMetadata
    val id = mediaItem.mediaId.ifEmpty {
        mediaItem.localConfiguration?.uri?.toString().orEmpty()
    }
    val artistName = meta.artist?.toString() ?: meta.subtitle?.toString()
    val artists = if (!artistName.isNullOrBlank()) {
        listOf(PlaybackArtist(name = artistName))
    } else {
        emptyList()
    }
    val album = meta.albumTitle?.toString()?.takeIf { it.isNotBlank() }?.let { PlaybackAlbum(title = it) }
    val uriString = mediaItem.localConfiguration?.uri?.toString()
    val source = when {
        uriString.isNullOrBlank() || uriString == Media3PlaybackController.UNRESOLVED_URI_SENTINEL ->
            PlaybackSource.Deferred(id)
        !uriString.contains("://") ->
            PlaybackSource.Deferred(uriString)
        uriString.startsWith("file://") || uriString.startsWith("content://") ->
            PlaybackSource.LocalFile(uriString)
        else -> PlaybackSource.Remote(
            url = uriString,
            mimeType = mediaItem.localConfiguration?.mimeType,
        )
    }

    return PlaybackItem(
        metadata = PlaybackMetadata(
            id = id,
            title = meta.title?.toString().orEmpty(),
            artists = artists,
            album = album,
            artworkUri = meta.artworkUri?.toString(),
            albumArtist = meta.albumArtist?.toString(),
            trackNumber = meta.trackNumber,
            discNumber = meta.discNumber,
            year = meta.releaseYear,
        ),
        source = source,
    )
}

fun PlaybackException.toPlaybackFailure(): PlaybackFailure {
    val kind = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> PlaybackFailure.Kind.NETWORK

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackFailure.Kind.LOCAL_FILE_MISSING

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> PlaybackFailure.Kind.UNSUPPORTED_FORMAT

        PlaybackException.ERROR_CODE_REMOTE_ERROR -> PlaybackFailure.Kind.SOURCE_RESOLUTION
        PlaybackException.ERROR_CODE_UNSPECIFIED -> PlaybackFailure.Kind.UNKNOWN
        else -> PlaybackFailure.Kind.ENGINE
    }
    return PlaybackFailure(
        message = message ?: errorCodeName,
        kind = kind,
        recoverable = true,
    )
}

private fun Int.toTransitionReason(): TransitionReason =
    when (this) {
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> TransitionReason.REPEAT
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> TransitionReason.AUTO_ADVANCE
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> TransitionReason.USER_SELECTED
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> TransitionReason.QUEUE_REPLACED
        else -> TransitionReason.USER_SELECTED
    }

private fun pitchMultiplierToSemitones(pitch: Float): Float {
    if (!pitch.isFinite() || pitch <= 0f) return 0f
    val semitones = (12.0 * ln(pitch.toDouble()) / ln(2.0)).toFloat()
    return (semitones * 10f).roundToInt() / 10f
}
