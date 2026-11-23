/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.ios

import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackFailure
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackQueue
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.api.TransitionReason
import moe.rukamori.archivetune.sdk.lifecycle.SdkLifecycle

/**
 * Headless-testable iOS [PlaybackController] built on `playback-core` and `sdk-lifecycle`.
 *
 * Owns queue traversal, deferred source resolution with cancellation/generation guards, speed and
 * volume clamping, ended-track advancement, and remote command routing over an [IosPlaybackEngine].
 */
class IosPlaybackController(
    private val engine: IosPlaybackEngine,
    private val sourceResolver: PlaybackSourceResolver,
    private val ownsEngine: Boolean = true,
    parentContext: CoroutineContext = Dispatchers.Default,
    random: Random = Random.Default,
) : PlaybackController {
    private val lifecycle = SdkLifecycle(parentContext)
    private val controllerScope = lifecycle.scope()
    private val commandMutex = Mutex()
    private val queue = PlaybackQueue(random)

    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    override val state: StateFlow<PlaybackSnapshot> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<PlaybackEvent> = mutableEvents.asSharedFlow()

    private var queueTitle: String? = null
    private var playWhenReady: Boolean = false
    private var volume: Float = 1f
    private var speed: Float = 1f
    private var resolving: Boolean = false
    private var loadedInEngine: Boolean = false
    private var pendingStartPositionMs: Long = 0L
    private var loadGeneration: Long = 0L
    private var handledEndedGeneration: Long = -1L
    private var resolveJob: Job? = null
    private var controllerFailure: PlaybackFailure? = null

    init {
        lifecycle.onClosing("unbind-ios-remote-commands") {
            engine.bindRemoteCommands(null)
        }
        if (ownsEngine) {
            lifecycle.register(engine)
        }

        engine.bindRemoteCommands(
            IosRemoteCommandHandlers(
                onPlay = { controllerScope.launch { play() } },
                onPause = { controllerScope.launch { pause() } },
                onTogglePlayPause = {
                    controllerScope.launch {
                        val snapshot = mutableState.value
                        if (snapshot.isPlaying || snapshot.isLoading) pause() else play()
                    }
                },
                onNext = { controllerScope.launch { skipNext() } },
                onPrevious = { controllerScope.launch { skipPrevious() } },
                onSeekTo = { positionMs -> controllerScope.launch { seekTo(positionMs) } },
            ),
        )

        controllerScope.launch {
            var wasEnded = false
            engine.state.collect { engineState ->
                var shouldHandleEnded = false
                var endedGeneration = -1L
                commandMutex.withLock {
                    if (lifecycle.isClosed) return@withLock
                    if (engineState.error != null && controllerFailure == null && loadedInEngine) {
                        playWhenReady = false
                        controllerFailure = engineState.error
                        mutableEvents.tryEmit(PlaybackEvent.Failure(engineState.error, queue.current))
                    }
                    publishLocked(engineState)
                    val currentId = queue.current?.metadata?.id
                    if (engineState.ended &&
                        !wasEnded &&
                        !resolving &&
                        loadedInEngine &&
                        currentId != null &&
                        engineState.itemId == currentId &&
                        handledEndedGeneration != loadGeneration
                    ) {
                        handledEndedGeneration = loadGeneration
                        endedGeneration = loadGeneration
                        shouldHandleEnded = true
                    }
                    wasEnded = engineState.ended
                }
                if (shouldHandleEnded) {
                    handlePlaybackEnded(endedGeneration)
                }
            }
        }
    }

    val canAdjustVolume: Boolean
        get() = engine.canAdjustVolume

    override suspend fun replaceQueue(
        items: List<PlaybackItem>,
        startIndex: Int,
        startPositionMs: Long,
        title: String?,
        playWhenReady: Boolean,
    ) {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            val previous = queue.current
            cancelResolutionLocked()
            queueTitle = title
            controllerFailure = null
            pendingStartPositionMs = startPositionMs.coerceAtLeast(0L)
            this.playWhenReady = playWhenReady
            loadedInEngine = false
            queue.replace(items, startIndex)
            val current = queue.current
            emitQueueChangedLocked()
            emitTransitionLocked(previous, current, TransitionReason.QUEUE_REPLACED)

            if (current == null) {
                this.playWhenReady = false
                engine.stop()
                publishLocked()
                return
            }

            if (playWhenReady) {
                engine.pause()
                startResolutionLocked(current, pendingStartPositionMs)
            } else {
                engine.stop()
                publishLocked()
            }
        }
    }

    override suspend fun play() {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            val current = queue.current ?: return
            if (resolving) {
                playWhenReady = true
                publishLocked()
                return
            }
            if (controllerFailure != null || engine.state.value.error != null || !loadedInEngine) {
                pendingStartPositionMs = currentPositionLocked(engine.state.value)
                controllerFailure = null
                playWhenReady = true
                startResolutionLocked(current, pendingStartPositionMs)
                return
            }
            playWhenReady = true
            if (engine.state.value.ended) {
                handledEndedGeneration = -1L
                engine.seekTo(0L)
            }
            engine.setSpeed(speed)
            if (engine.canAdjustVolume) engine.setVolume(volume)
            engine.play()
            publishLocked()
        }
    }

    override suspend fun pause() {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            playWhenReady = false
            if (resolving) {
                cancelResolutionLocked()
            }
            engine.pause()
            publishLocked()
        }
    }

    override suspend fun stop() {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            cancelResolutionLocked()
            playWhenReady = false
            loadedInEngine = false
            pendingStartPositionMs = 0L
            controllerFailure = null
            engine.stop()
            publishLocked()
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        commandMutex.withLock {
            if (lifecycle.isClosed || resolving) return
            val target = positionMs.coerceAtLeast(0L)
            pendingStartPositionMs = target
            if (loadedInEngine) {
                if (engine.state.value.ended) handledEndedGeneration = -1L
                engine.seekTo(target)
            }
            publishLocked()
        }
    }

    override suspend fun seekToItem(index: Int, positionMs: Long) {
        commandMutex.withLock {
            if (lifecycle.isClosed || index !in queue.items.indices) return
            val previous = queue.current
            queue.select(index)
            transitionToCurrentLocked(
                previous = previous,
                reason = TransitionReason.USER_SELECTED,
                startPositionMs = positionMs.coerceAtLeast(0L),
                shouldPlay = true,
            )
        }
    }

    override suspend fun skipNext() {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            val previous = queue.current
            val selected = queue.advance(automatic = false) ?: return
            val reason = if (selected.repeated) TransitionReason.REPEAT else TransitionReason.USER_NEXT
            val sameTrackRepeat = selected.repeated && queue.items.size == 1
            if (sameTrackRepeat && loadedInEngine && !resolving && controllerFailure == null) {
                handledEndedGeneration = -1L
                pendingStartPositionMs = 0L
                playWhenReady = true
                engine.seekTo(0L)
                engine.play()
                emitTransitionLocked(previous, queue.current, reason)
                publishLocked()
                return
            }
            transitionToCurrentLocked(
                previous = previous,
                reason = reason,
                startPositionMs = 0L,
                shouldPlay = true,
            )
        }
    }

    override suspend fun skipPrevious(restartThresholdMs: Long) {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            val current = queue.current ?: return
            val threshold = restartThresholdMs.coerceAtLeast(0L)
            val currentPos = currentPositionLocked(engine.state.value)
            if (loadedInEngine && !resolving && currentPos > threshold) {
                pendingStartPositionMs = 0L
                engine.seekTo(0L)
                publishLocked()
                return
            }
            val selected = queue.retreat()
            if (selected == null) {
                pendingStartPositionMs = 0L
                if (loadedInEngine && !resolving) engine.seekTo(0L)
                publishLocked()
                return
            }
            val reason = if (selected.repeated) TransitionReason.REPEAT else TransitionReason.USER_PREVIOUS
            transitionToCurrentLocked(
                previous = current,
                reason = reason,
                startPositionMs = 0L,
                shouldPlay = true,
            )
        }
    }

    override suspend fun enqueue(items: List<PlaybackItem>) {
        commandMutex.withLock {
            if (lifecycle.isClosed || items.isEmpty()) return
            val wasEmpty = queue.items.isEmpty()
            queue.append(items)
            emitQueueChangedLocked()
            if (wasEmpty) {
                emitTransitionLocked(null, queue.current, TransitionReason.QUEUE_REPLACED)
            }
            publishLocked()
        }
    }

    override suspend fun playNext(items: List<PlaybackItem>) {
        commandMutex.withLock {
            if (lifecycle.isClosed || items.isEmpty()) return
            val wasEmpty = queue.items.isEmpty()
            queue.insertNext(items)
            emitQueueChangedLocked()
            if (wasEmpty) {
                emitTransitionLocked(null, queue.current, TransitionReason.QUEUE_REPLACED)
            }
            publishLocked()
        }
    }

    override suspend fun removeQueueItem(index: Int) {
        commandMutex.withLock {
            if (lifecycle.isClosed || index !in queue.items.indices) return
            val previous = queue.current
            val outcome = queue.removeAt(index)
            emitQueueChangedLocked()
            if (!outcome.currentChanged) {
                publishLocked()
                return
            }
            val nextItem = queue.current
            if (nextItem == null) {
                cancelResolutionLocked()
                playWhenReady = false
                loadedInEngine = false
                pendingStartPositionMs = 0L
                controllerFailure = null
                engine.stop()
                emitTransitionLocked(previous, null, TransitionReason.CURRENT_ITEM_REMOVED)
                publishLocked()
            } else {
                transitionToCurrentLocked(
                    previous = previous,
                    reason = TransitionReason.CURRENT_ITEM_REMOVED,
                    startPositionMs = 0L,
                    shouldPlay = playWhenReady || engine.state.value.isPlaying,
                )
            }
        }
    }

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            val size = queue.items.size
            if (fromIndex == toIndex || fromIndex !in 0 until size || toIndex !in 0 until size) return
            queue.moveVisual(fromIndex, toIndex)
            emitQueueChangedLocked()
            publishLocked()
        }
    }

    override suspend fun moveQueueItemToNext(index: Int) {
        commandMutex.withLock {
            if (lifecycle.isClosed || index !in queue.items.indices) return
            queue.moveToNext(index)
            emitQueueChangedLocked()
            publishLocked()
        }
    }

    override suspend fun clearQueue() {
        commandMutex.withLock {
            if (lifecycle.isClosed || queue.items.size <= 1) return
            queue.clear()
            emitQueueChangedLocked()
            publishLocked()
        }
    }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            queue.setRepeatMode(mode)
            publishLocked()
        }
    }

    override suspend fun setShuffleEnabled(enabled: Boolean) {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            queue.setShuffleEnabled(enabled)
            publishLocked()
        }
    }

    override suspend fun setVolume(volume: Float) {
        commandMutex.withLock {
            if (lifecycle.isClosed || !engine.canAdjustVolume) return
            val sanitized = if (volume.isFinite()) volume.coerceIn(0f, 1f) else 1f
            this.volume = sanitized
            engine.setVolume(sanitized)
            publishLocked()
        }
    }

    override suspend fun setSpeed(speed: Float) {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            val sanitized = if (speed.isFinite()) speed.coerceIn(MIN_IOS_SPEED, MAX_IOS_SPEED) else 1f
            this.speed = sanitized
            engine.setSpeed(sanitized)
            publishLocked()
        }
    }

    override suspend fun retry() {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            val current = queue.current ?: return
            pendingStartPositionMs = currentPositionLocked(engine.state.value)
            controllerFailure = null
            playWhenReady = true
            emitTransitionLocked(current, current, TransitionReason.RETRY)
            startResolutionLocked(current, pendingStartPositionMs)
        }
    }

    override suspend fun clearError() {
        commandMutex.withLock {
            if (lifecycle.isClosed) return
            controllerFailure = null
            if (engine.state.value.error != null) {
                pendingStartPositionMs = currentPositionLocked(engine.state.value)
                loadedInEngine = false
                engine.stop()
            }
            publishLocked()
        }
    }

    override fun close() {
        resolveJob?.cancel()
        resolveJob = null
        lifecycle.close()
    }

    private suspend fun handlePlaybackEnded(endedGeneration: Long) {
        commandMutex.withLock {
            if (lifecycle.isClosed || endedGeneration != loadGeneration || resolving) return
            val previous = queue.current ?: return
            val selected = queue.advance(automatic = true)
            if (selected == null) {
                playWhenReady = false
                publishLocked()
                return
            }
            val reason = if (selected.repeated) TransitionReason.REPEAT else TransitionReason.AUTO_ADVANCE
            val sameTrackRepeat = selected.repeated && (queue.repeatMode == RepeatMode.ONE || queue.items.size == 1)
            if (sameTrackRepeat && loadedInEngine && controllerFailure == null) {
                handledEndedGeneration = -1L
                pendingStartPositionMs = 0L
                playWhenReady = true
                engine.seekTo(0L)
                engine.setSpeed(speed)
                if (engine.canAdjustVolume) engine.setVolume(volume)
                engine.play()
                emitTransitionLocked(previous, queue.current, reason)
                publishLocked()
                return
            }
            transitionToCurrentLocked(
                previous = previous,
                reason = reason,
                startPositionMs = 0L,
                shouldPlay = true,
            )
        }
    }

    private fun transitionToCurrentLocked(
        previous: PlaybackItem?,
        reason: TransitionReason,
        startPositionMs: Long,
        shouldPlay: Boolean,
    ) {
        cancelResolutionLocked()
        controllerFailure = null
        loadedInEngine = false
        pendingStartPositionMs = startPositionMs
        playWhenReady = shouldPlay
        val current = queue.current
        emitTransitionLocked(previous, current, reason)
        if (current == null) {
            playWhenReady = false
            engine.stop()
            publishLocked()
            return
        }
        if (shouldPlay) {
            engine.pause()
            startResolutionLocked(current, startPositionMs)
        } else {
            engine.stop()
            publishLocked()
        }
    }

    private fun cancelResolutionLocked() {
        ++loadGeneration
        handledEndedGeneration = -1L
        resolveJob?.cancel()
        resolveJob = null
        resolving = false
    }

    private fun startResolutionLocked(item: PlaybackItem, startPositionMs: Long) {
        cancelResolutionLocked()
        val expectedGeneration = loadGeneration
        resolving = true
        loadedInEngine = false
        controllerFailure = null
        publishLocked()

        resolveJob = controllerScope.launch {
            val resolvedResult = try {
                resolvePlaybackSource(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }

            commandMutex.withLock {
                if (lifecycle.isClosed || expectedGeneration != loadGeneration) return@withLock
                resolving = false

                val resolved = resolvedResult.getOrElse { error ->
                    val failure = toPlaybackFailure(error)
                    playWhenReady = false
                    loadedInEngine = false
                    controllerFailure = failure
                    mutableEvents.tryEmit(PlaybackEvent.Failure(failure, item))
                    publishLocked()
                    return@withLock
                }

                val validationFailure = validateIosPlaybackSource(resolved)
                if (validationFailure != null) {
                    playWhenReady = false
                    loadedInEngine = false
                    controllerFailure = validationFailure
                    mutableEvents.tryEmit(PlaybackEvent.Failure(validationFailure, item))
                    publishLocked()
                    return@withLock
                }

                try {
                    engine.load(item, resolved, startPositionMs)
                    loadedInEngine = true
                    engine.setSpeed(speed)
                    if (engine.canAdjustVolume) engine.setVolume(volume)
                    if (playWhenReady) {
                        engine.play()
                    }
                    publishLocked()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val failure = toPlaybackFailure(error, defaultKind = PlaybackFailure.Kind.ENGINE)
                    playWhenReady = false
                    loadedInEngine = false
                    controllerFailure = failure
                    mutableEvents.tryEmit(PlaybackEvent.Failure(failure, item))
                    publishLocked()
                }
            }
        }
    }

    private suspend fun resolvePlaybackSource(item: PlaybackItem): Result<ResolvedPlaybackSource> =
        when (val source = item.source) {
            is PlaybackSource.Remote -> Result.success(
                ResolvedPlaybackSource(
                    uri = source.url,
                    mimeType = source.mimeType,
                    headers = source.headers,
                    expiresAtEpochMs = source.expiresAtEpochMs,
                ),
            )
            is PlaybackSource.LocalFile -> Result.failure(
                IosPlaybackException(
                    PlaybackFailure(
                        message = "Local file playback is not supported on this iOS session.",
                        kind = PlaybackFailure.Kind.UNSUPPORTED_FORMAT,
                        recoverable = false,
                    ),
                ),
            )
            is PlaybackSource.Deferred -> sourceResolver.resolve(item)
        }

    private fun toPlaybackFailure(
        error: Throwable,
        defaultKind: PlaybackFailure.Kind = PlaybackFailure.Kind.SOURCE_RESOLUTION,
    ): PlaybackFailure {
        if (error is IosPlaybackException) return error.failure
        val kind = when (error) {
            is IllegalArgumentException -> PlaybackFailure.Kind.UNSUPPORTED_FORMAT
            else -> defaultKind
        }
        return PlaybackFailure(
            message = sanitizePlaybackErrorMessage(error.message),
            kind = kind,
            recoverable = kind != PlaybackFailure.Kind.UNSUPPORTED_FORMAT,
        )
    }

    private fun emitQueueChangedLocked() {
        mutableEvents.tryEmit(PlaybackEvent.QueueChanged(queue.items, queue.currentIndex))
    }

    private fun emitTransitionLocked(previous: PlaybackItem?, current: PlaybackItem?, reason: TransitionReason) {
        mutableEvents.tryEmit(PlaybackEvent.MediaTransition(previous, current, reason))
    }

    private fun currentPositionLocked(engineState: IosEngineState): Long =
        if (loadedInEngine && !resolving && engineState.itemId == queue.current?.metadata?.id) {
            engineState.positionMs.coerceAtLeast(0L)
        } else {
            pendingStartPositionMs.coerceAtLeast(0L)
        }

    private fun publishLocked(engineState: IosEngineState = engine.state.value) {
        val current = queue.current
        val matchesEngine = loadedInEngine && current != null && engineState.itemId == current.metadata.id
        val failure = controllerFailure ?: engineState.error.takeIf { matchesEngine }
        val status = when {
            current == null -> PlaybackStatus.IDLE
            resolving -> PlaybackStatus.RESOLVING
            failure != null -> PlaybackStatus.ERROR
            !matchesEngine -> PlaybackStatus.IDLE
            engineState.ended -> PlaybackStatus.ENDED
            engineState.isBuffering -> PlaybackStatus.BUFFERING
            engineState.isPlaying -> PlaybackStatus.READY
            playWhenReady -> PlaybackStatus.LOADING
            else -> PlaybackStatus.READY
        }
        val durationMs = when {
            matchesEngine && engineState.durationMs > 0L -> engineState.durationMs
            current?.metadata?.durationHintMs != null -> current.metadata.durationHintMs!!.coerceAtLeast(0L)
            else -> 0L
        }
        val positionMs = currentPositionLocked(engineState)
        val bufferedPositionMs = if (matchesEngine) engineState.bufferedPositionMs.coerceAtLeast(positionMs) else positionMs
        val hasPrevious = queue.canRetreat
        val hasNext = queue.canAdvance
        engine.updateNavigationAvailability(hasPrevious = hasPrevious, hasNext = hasNext)

        mutableState.value = PlaybackSnapshot(
            queue = queue.items,
            queueEntries = queue.entries(),
            queueTitle = queueTitle,
            currentIndex = queue.currentIndex,
            status = status,
            playWhenReady = playWhenReady,
            positionMs = positionMs,
            bufferedPositionMs = bufferedPositionMs,
            durationMs = durationMs,
            repeatMode = queue.repeatMode,
            shuffleEnabled = queue.shuffleEnabled,
            hasPreviousItem = hasPrevious,
            hasNextItem = hasNext,
            volume = volume,
            speed = speed,
            pitchSemitones = 0f,
            error = failure,
        )
    }

    companion object {
        const val MIN_IOS_SPEED: Float = 0.25f
        const val MAX_IOS_SPEED: Float = 2.0f
    }
}
