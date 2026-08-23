/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.io.FileNotFoundException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackFailure
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackQueue
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.api.TransitionReason
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.log.LogEventListener
import uk.co.caprica.vlcj.log.LogLevel
import uk.co.caprica.vlcj.log.NativeLog
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

/**
 * In-process desktop playback backed by one application-scoped VLCJ/libVLC primary player, plus a
 * short-lived second player while crossfading between tracks.
 *
 * ArchiveTune resolves deferred YouTube items before this class is called. Local/general media is
 * handed directly to libVLC; signed Googlevideo media uses the application HTTP callback transport
 * so the exact resolver headers/range semantics are preserved instead of relying on libVLC's HTTP
 * implementation. No process, IPC pipe, JSON command protocol, or extractor lives in this backend.
 */
class VlcjPlaybackController(
    private val sourceResolver: PlaybackSourceResolver,
    private val runtimeConfiguration: VlcRuntimeConfiguration = VlcRuntimeConfiguration(),
    private val onStreamHttpFailure: suspend (PlaybackItem, ResolvedPlaybackSource, Int) -> Boolean = { _, _, _ -> false },
    initialAudioOutputDeviceId: String? = null,
) : PlaybackController, DesktopAudioOutputControl, DesktopCrossfadeControl {
    private data class LoadRequest(
        val generation: Long,
        val item: PlaybackItem,
        val startPositionMs: Long,
    )

    private data class CrossfadeTarget(
        val outgoingOccurrenceId: Long,
        val incomingOccurrenceId: Long,
        val item: PlaybackItem,
        val repeated: Boolean,
    )

    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("ArchiveTuneDesktopVlcPlayback"),
    )
    private val commandMutex = Mutex()
    private val playerMutex = Mutex()
    private val nativeLifecycleLock = Any()
    private val queue = PlaybackQueue()
    private val logger = Logger.getLogger("moe.rukamori.archivetune.desktop.playback")
    private val rangeHttpTransport = VlcjRangeHttpTransport()
    private val _state = MutableStateFlow(PlaybackSnapshot())
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 32)
    private val audioOutputSelection = DesktopAudioOutputSelection(initialAudioOutputDeviceId)
    private val latestPositionMs = AtomicLong(0L)
    private var positionTicker: Job? = null
    private var loadWatchdog: Job? = null
    private var factory: MediaPlayerFactory? = null
    private var nativeLog: NativeLog? = null
    private var nativeLogListener: LogEventListener? = null
    private var player: MediaPlayer? = null
    private var equalizerBackend: EqualizerBackend? = null
    private var outputVolumeBackend: OutputVolumeBackend? = null
    private var listener: MediaPlayerEventAdapter? = null
    private var activeCallbackMedia: VlcjRangeCallbackMedia? = null
    private var secondaryCrossfadePlayer: MediaPlayer? = null
    private var secondaryCrossfadeCallbackMedia: VlcjRangeCallbackMedia? = null
    @Volatile private var crossfadeConfiguration = DesktopCrossfadeConfiguration()
    private var crossfadeJob: Job? = null
    private var crossfadeAttemptedOccurrenceId: Long? = null
    private var crossfadeRunSequence = 0L
    private var activeCrossfadeRunId: Long? = null
    private var crossfadeSuppressFinishedRunId: Long? = null
    @Volatile private var generation = 0L
    private val loadJobOwner = LatestLoadJobOwner(
        scope = scope,
        accept = { request: LoadRequest -> !closed.get() && request.generation == generation },
        execute = ::executeLoad,
    )
    @Volatile private var activeGeneration: Long? = null
    @Volatile private var activeResolvedSource: ResolvedPlaybackSource? = null
    private val httpRecoveryGeneration = AtomicLong(NO_RECOVERY_GENERATION)
    private var recoveryAttemptedMediaId: String? = null
    private var prematureFinishRetriedMediaId: String? = null
    private var pendingSeek: Pair<Long, Long>? = null
    private var suppressStoppedEvents = 0

    override val state = _state.asStateFlow()
    override val events = _events.asSharedFlow()
    private val outputGain = DesktopOutputGain()
    private val vlcjAudioEffects = VlcjAudioEffects(
        ensureBackend = {
            ensurePlayer()
            synchronized(nativeLifecycleLock) { checkNotNull(equalizerBackend) }
        },
        applyLoudnessNormalizationEnabled = ::setLoudnessNormalizationEnabled,
    )
    val audioEffects: DesktopAudioEffects = vlcjAudioEffects
    override val audioOutputState = audioOutputSelection.state

    override suspend fun refreshAudioOutputDevices(): DesktopAudioOutputState = withContext(Dispatchers.IO) {
        ensurePlayer()
        audioOutputSelection.refresh()
    }

    override suspend fun selectAudioOutputDevice(deviceId: String?): DesktopAudioOutputState = withContext(Dispatchers.IO) {
        ensurePlayer()
        audioOutputSelection.select(deviceId).also { selected ->
            secondaryCrossfadePlayer?.let { secondary ->
                runCatching { VlcjAudioOutputBackend(secondary).selectOutputDevice(selected.selectedDeviceId) }
            }
        }
    }

    override suspend fun setCrossfadeConfiguration(configuration: DesktopCrossfadeConfiguration) {
        checkOpen()
        val sanitized = configuration.sanitized()
        crossfadeConfiguration = sanitized
        commandMutex.withLock {
            cancelCrossfadeLocked(resetAttempt = true)
        }
    }

    init {
        // libVLC startup is relatively expensive (especially on OpenJ9 -Xint), but its idle
        // footprint is small. Warm the factory/player as soon as ArchiveTune starts so the first
        // real Play action never has to pay native discovery/plugin/JNA initialization latency.
        // Keep this asynchronous so application/window startup is not blocked. A prewarm failure is
        // deliberately non-fatal: executeLoad() will retry ensurePlayer() and surface the normal
        // engine-unavailable error only if playback actually needs libVLC.
        scope.launch(CoroutineName("ArchiveTuneDesktopVlcPrewarm")) {
            try {
                ensurePlayer()
                logger.info("libVLC prewarm completed")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.log(Level.WARNING, "libVLC prewarm failed; playback will retry initialization on demand", error)
            }
        }

        positionTicker = scope.launch {
            while (true) {
                delay(POSITION_POLL_INTERVAL_MS)
                val position = latestPositionMs.get()
                commandMutex.withLock {
                    if (!closed.get() && activeGeneration == generation) {
                        _state.update { snapshot ->
                            snapshot.copy(
                                positionMs = position.coerceAtMostPositive(snapshot.durationMs),
                                bufferedPositionMs = maxOf(
                                    snapshot.bufferedPositionMs,
                                    position.coerceAtMostPositive(snapshot.durationMs),
                                ),
                            )
                        }
                    }
                }
                maybeStartCrossfade()
            }
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
        val request = commandMutex.withLock {
            val previous = queue.current
            queue.replace(items, startIndex)
            advanceLoadGeneration()
            stopActiveFileLocked()
            publishQueueLocked(title)
            val position = startPositionMs.coerceAtLeast(0L)
            val shouldLoad = playWhenReady && queue.current != null
            _state.update {
                it.copy(
                    playWhenReady = shouldLoad,
                    positionMs = position,
                    bufferedPositionMs = position,
                    durationMs = queue.current?.metadata?.durationHintMs?.coerceAtLeast(0L) ?: 0L,
                    // A restored queue is intentionally paused. Do not spin up libVLC and begin
                    // an asynchronous play/pause cycle merely to represent that paused state.
                    // The first real play command will reload this item from [position]. This is
                    // especially important during application startup: cancelling libVLC while
                    // its first media is still opening can make stop/reset block for many seconds.
                    status = if (shouldLoad) PlaybackStatus.RESOLVING else PlaybackStatus.IDLE,
                    error = null,
                )
            }
            transition(previous, queue.current, TransitionReason.QUEUE_REPLACED)
            queue.current?.takeIf { shouldLoad }?.let { LoadRequest(generation, it, position) }
        }
        request?.let(::enqueueLoad)
    }

    override suspend fun play() {
        checkOpen()
        val reload = commandMutex.withLock {
            _state.update { it.copy(playWhenReady = queue.current != null, error = null) }
            if (queue.current != null && _state.value.status in setOf(
                    PlaybackStatus.IDLE,
                    PlaybackStatus.ENDED,
                    PlaybackStatus.ERROR,
                )
            ) {
                prepareReloadLocked(
                    TransitionReason.RETRY,
                    if (_state.value.status == PlaybackStatus.ENDED) 0L else _state.value.positionMs,
                )
            } else {
                null
            }
        }
        if (reload != null) {
            enqueueLoad(reload)
        } else {
            commandMutex.withLock { player?.controls()?.setPause(false) }
        }
    }

    override suspend fun pause() {
        checkOpen()
        commandMutex.withLock {
            cancelCrossfadeLocked(resetAttempt = true)
            _state.update { it.copy(playWhenReady = false) }
            player?.controls()?.setPause(true)
        }
    }

    override suspend fun stop() {
        checkOpen()
        commandMutex.withLock {
            advanceLoadGeneration()
            activeGeneration = null
            pendingSeek = null
            stopActiveFileLocked()
            _state.update {
                it.copy(
                    status = PlaybackStatus.IDLE,
                    playWhenReady = false,
                    positionMs = 0L,
                    bufferedPositionMs = 0L,
                    error = null,
                )
            }
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        checkOpen()
        commandMutex.withLock {
            cancelCrossfadeLocked(resetAttempt = true)
            val duration = _state.value.durationMs
            val safePosition = positionMs.coerceAtLeast(0L).let { requested ->
                if (duration > 0L) requested.coerceAtMost(duration) else requested
            }
            latestPositionMs.set(safePosition)
            if (activeGeneration != null && _state.value.status in setOf(
                    PlaybackStatus.RESOLVING,
                    PlaybackStatus.LOADING,
                )
            ) {
                pendingSeek = (activeGeneration ?: generation) to safePosition
            }
            _state.update {
                it.copy(
                    positionMs = safePosition,
                    bufferedPositionMs = maxOf(it.bufferedPositionMs, safePosition),
                )
            }
            player?.controls()?.setTime(safePosition)
        }
    }

    override suspend fun seekToItem(index: Int, positionMs: Long) {
        checkOpen()
        if (index == queue.currentIndex) {
            seekTo(positionMs)
            return
        }
        val request = commandMutex.withLock {
            val previous = queue.current
            val selected = queue.select(index)
            advanceLoadGeneration()
            stopActiveFileLocked()
            publishQueueLocked()
            val position = positionMs.coerceAtLeast(0L)
            _state.update {
                it.copy(
                    status = PlaybackStatus.RESOLVING,
                    positionMs = position,
                    bufferedPositionMs = position,
                    durationMs = selected.item.metadata.durationHintMs?.coerceAtLeast(0L) ?: 0L,
                    error = null,
                )
            }
            transition(previous, selected.item, TransitionReason.USER_SELECTED)
            LoadRequest(generation, selected.item, position)
        }
        enqueueLoad(request)
    }

    override suspend fun skipNext() {
        checkOpen()
        val request = commandMutex.withLock {
            val previous = queue.current
            val selected = queue.advance(automatic = false)
            if (selected == null) {
                advanceLoadGeneration()
                activeGeneration = null
                stopActiveFileLocked()
                _state.update { it.copy(status = PlaybackStatus.ENDED, playWhenReady = false) }
                null
            } else {
                prepareSelectionLocked(previous, selected, TransitionReason.USER_NEXT)
            }
        }
        request?.let(::enqueueLoad)
    }

    override suspend fun skipPrevious(restartThresholdMs: Long) {
        checkOpen()
        if (_state.value.positionMs > restartThresholdMs.coerceAtLeast(0L)) {
            seekTo(0L)
            return
        }
        val request = commandMutex.withLock {
            val previous = queue.current
            val selected = queue.retreat()
            selected?.let { prepareSelectionLocked(previous, it, TransitionReason.USER_PREVIOUS) }
        }
        if (request == null) seekTo(0L) else enqueueLoad(request)
    }

    override suspend fun enqueue(items: List<PlaybackItem>) {
        checkOpen()
        val request = commandMutex.withLock {
            val previous = queue.current
            val mutation = queue.append(items)
            publishQueueLocked()
            mutation.current?.takeIf { mutation.currentChanged }?.let {
                advanceLoadGeneration()
                _state.update { state -> state.copy(status = PlaybackStatus.RESOLVING, error = null) }
                transition(previous, it.item, TransitionReason.QUEUE_REPLACED)
                LoadRequest(generation, it.item, 0L)
            }
        }
        request?.let(::enqueueLoad)
    }

    override suspend fun playNext(items: List<PlaybackItem>) {
        checkOpen()
        val request = commandMutex.withLock {
            cancelCrossfadeLocked(resetAttempt = true)
            val previous = queue.current
            val mutation = queue.insertNext(items)
            publishQueueLocked()
            mutation.current?.takeIf { mutation.currentChanged }?.let {
                advanceLoadGeneration()
                _state.update { state -> state.copy(status = PlaybackStatus.RESOLVING, error = null) }
                transition(previous, it.item, TransitionReason.QUEUE_REPLACED)
                LoadRequest(generation, it.item, 0L)
            }
        }
        request?.let(::enqueueLoad)
    }

    override suspend fun removeQueueItem(index: Int) {
        checkOpen()
        val request = commandMutex.withLock {
            cancelCrossfadeLocked(resetAttempt = true)
            val previous = queue.current
            val mutation = queue.removeAt(index)
            publishQueueLocked()
            when {
                queue.current == null -> {
                    advanceLoadGeneration()
                    activeGeneration = null
                    stopActiveFileLocked()
                    _state.update {
                        it.copy(
                            status = PlaybackStatus.IDLE,
                            playWhenReady = false,
                            positionMs = 0L,
                            bufferedPositionMs = 0L,
                            durationMs = 0L,
                        )
                    }
                    transition(previous, null, TransitionReason.CURRENT_ITEM_REMOVED)
                    null
                }
                mutation.currentChanged -> prepareSelectionLocked(
                    previous,
                    requireNotNull(mutation.current),
                    TransitionReason.CURRENT_ITEM_REMOVED,
                )
                else -> null
            }
        }
        request?.let(::enqueueLoad)
    }

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        checkOpen()
        commandMutex.withLock {
            cancelCrossfadeLocked(resetAttempt = true)
            queue.moveVisual(fromIndex, toIndex)
            publishQueueLocked()
        }
    }

    override suspend fun moveQueueItemToNext(index: Int) {
        checkOpen()
        commandMutex.withLock {
            cancelCrossfadeLocked(resetAttempt = true)
            queue.moveToNext(index)
            publishQueueLocked()
        }
    }

    override suspend fun clearQueue() {
        checkOpen()
        commandMutex.withLock {
            cancelCrossfadeLocked(resetAttempt = true)
            val previous = queue.current
            queue.clear()
            publishQueueLocked()
            if (queue.current == null) {
                advanceLoadGeneration()
                activeGeneration = null
                stopActiveFileLocked()
                _state.update {
                    it.copy(
                        status = PlaybackStatus.IDLE,
                        playWhenReady = false,
                        positionMs = 0L,
                        bufferedPositionMs = 0L,
                        durationMs = 0L,
                        queueTitle = null,
                    )
                }
                transition(previous, null, TransitionReason.CURRENT_ITEM_REMOVED)
            }
        }
    }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        checkOpen()
        commandMutex.withLock {
            cancelCrossfadeLocked(resetAttempt = true)
            queue.setRepeatMode(mode)
            publishQueueLocked()
        }
    }

    override suspend fun setShuffleEnabled(enabled: Boolean) {
        checkOpen()
        commandMutex.withLock {
            cancelCrossfadeLocked(resetAttempt = true)
            queue.setShuffleEnabled(enabled)
            publishQueueLocked()
        }
    }

    override suspend fun setVolume(volume: Float) {
        checkOpen()
        val safeVolume = volume.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        commandMutex.withLock {
            _state.update { it.copy(volume = safeVolume) }
            outputGain.setUserVolume(safeVolume)
        }
    }

    private suspend fun setLoudnessNormalizationEnabled(enabled: Boolean) {
        checkOpen()
        commandMutex.withLock { outputGain.setNormalizationEnabled(enabled) }
    }

    override suspend fun setSpeed(speed: Float) {
        checkOpen()
        val safeSpeed = speed.takeIf(Float::isFinite)?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f
        commandMutex.withLock {
            _state.update { it.copy(speed = safeSpeed) }
            player?.controls()?.setRate(safeSpeed)
            secondaryCrossfadePlayer?.controls()?.setRate(safeSpeed)
        }
    }

    override suspend fun retry() {
        checkOpen()
        val request = commandMutex.withLock {
            prematureFinishRetriedMediaId = null
            prepareReloadLocked(TransitionReason.RETRY, _state.value.positionMs)
        }
        request?.let(::enqueueLoad)
    }

    override suspend fun clearError() {
        checkOpen()
        commandMutex.withLock {
            _state.update {
                it.copy(
                    error = null,
                    status = if (queue.current == null) PlaybackStatus.IDLE else PlaybackStatus.IDLE,
                )
            }
        }
    }

    private fun prepareReloadLocked(reason: TransitionReason, positionMs: Long): LoadRequest? {
        val item = queue.current ?: return null
        advanceLoadGeneration()
        stopActiveFileLocked()
        _state.update { it.copy(status = PlaybackStatus.RESOLVING, error = null) }
        transition(item, item, reason)
        return LoadRequest(generation, item, positionMs.coerceAtLeast(0L))
    }

    private fun prepareSelectionLocked(
        previous: PlaybackItem?,
        selection: PlaybackQueue.Selection,
        reason: TransitionReason,
    ): LoadRequest {
        advanceLoadGeneration()
        stopActiveFileLocked()
        publishQueueLocked()
        _state.update {
            it.copy(
                status = PlaybackStatus.RESOLVING,
                positionMs = 0L,
                bufferedPositionMs = 0L,
                durationMs = selection.item.metadata.durationHintMs?.coerceAtLeast(0L) ?: 0L,
                error = null,
            )
        }
        transition(previous, selection.item, if (selection.repeated) TransitionReason.REPEAT else reason)
        return LoadRequest(generation, selection.item, 0L)
    }

    private suspend fun executeLoad(request: LoadRequest) {
        val resolved = try {
            sourceResolver.resolve(request.item).getOrElse { throw it }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.log(Level.WARNING, "Playback source resolution failed for ${request.item.logLabel()}", error)
            failIfCurrent(
                request,
                PlaybackFailure(
                    message = when {
                        error is FileNotFoundException -> "The local audio file is unavailable"
                        error.message?.isNotBlank() == true -> error.message.orEmpty()
                        else -> "Unable to resolve the audio stream"
                    },
                    kind = if (error is FileNotFoundException) {
                        PlaybackFailure.Kind.LOCAL_FILE_MISSING
                    } else {
                        PlaybackFailure.Kind.SOURCE_RESOLUTION
                    },
                ),
            )
            return
        }

        if (resolved.uri.isBlank() ||
            resolved.expiresAtEpochMs?.let { it <= System.currentTimeMillis() + MIN_URL_LIFETIME_MS } == true
        ) {
            logger.warning(
                "Resolved playback source is unusable for ${request.item.logLabel()}: " +
                    "location=${resolved.uri.toLogMediaLocation()} expiresAt=${resolved.expiresAtEpochMs}",
            )
            failIfCurrent(
                request,
                PlaybackFailure(
                    message = "The resolved audio stream is blank or already expired",
                    kind = PlaybackFailure.Kind.SOURCE_RESOLUTION,
                ),
            )
            return
        }
        if (!isCurrent(request)) return

        logger.info(
            "Resolved playback source for ${request.item.logLabel()}: " +
                "location=${resolved.uri.toLogMediaLocation()} " +
                "headerNames=${resolved.headers.keys.sorted()} expiresAt=${resolved.expiresAtEpochMs}",
        )

        val activePlayer = try {
            ensurePlayer()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.log(Level.SEVERE, "Unable to initialize libVLC for ${request.item.logLabel()}", error)
            failIfCurrent(
                request,
                PlaybackFailure(
                    message = error.message?.takeIf(String::isNotBlank)
                        ?: "libVLC is unavailable; install or package the VLC runtime",
                    kind = PlaybackFailure.Kind.ENGINE_UNAVAILABLE,
                ),
            )
            return
        }

        commandMutex.withLock {
            if (!isCurrentLocked(request)) return@withLock
            try {
                _state.update { it.copy(status = PlaybackStatus.LOADING, error = null) }
                stopActiveFileLocked()
                activeGeneration = request.generation
                activeResolvedSource = resolved
                outputGain.setSource(resolved)
                httpRecoveryGeneration.set(NO_RECOVERY_GENERATION)
                pendingSeek = pendingSeek?.takeIf { it.first == request.generation }
                    ?: (request.generation to request.startPositionMs)
                latestPositionMs.set(request.startPositionMs)
                val callbackMedia =
                    if (resolved.shouldUseApplicationHttpTransport()) {
                        rangeHttpTransport.media(
                            url = resolved.uri,
                            headers = resolved.headers,
                            onHttpFailure = { httpStatus ->
                                scheduleStreamHttpRecovery(request.generation, httpStatus, "application HTTP transport")
                            },
                        ).also {
                            activeCallbackMedia = it
                            logger.info("Using application HTTP transport for ${request.item.logLabel()}")
                        }
                    } else {
                        null
                    }
                val started =
                    if (callbackMedia != null) {
                        activePlayer.media().play(
                            callbackMedia,
                            *resolved.toCallbackMediaOptions().toTypedArray(),
                        )
                    } else {
                        activePlayer.media().play(
                            resolved.uri,
                            *resolved.headers.toVlcMediaOptions().toTypedArray(),
                        )
                    }
                if (!started) {
                    logger.severe(
                        "libVLC rejected media.play for ${request.item.logLabel()}: " +
                            "location=${resolved.uri.toLogMediaLocation()}",
                    )
                    stopActiveFileLocked()
                    failLocked(
                        PlaybackFailure(
                            message = "libVLC rejected the resolved audio stream",
                            kind = PlaybackFailure.Kind.ENGINE,
                        ),
                    )
                    return@withLock
                }
                // media.play can reset native output state on some VLC builds, so replay the
                // already-computed user-volume × normalization gain after every track start.
                outputGain.reapply()
                activePlayer.controls().setRate(_state.value.speed)
                activePlayer.controls().setPause(!_state.value.playWhenReady)
                armLoadWatchdog(request)
            } catch (error: Throwable) {
                logger.log(
                    Level.SEVERE,
                    "libVLC threw while starting ${request.item.logLabel()}: " +
                        "location=${resolved.uri.toLogMediaLocation()}",
                    error,
                )
                stopActiveFileLocked()
                failLocked(
                    PlaybackFailure(
                        message = "libVLC could not start the resolved audio stream",
                        kind = PlaybackFailure.Kind.ENGINE,
                    ),
                )
            }
        }
    }

    private suspend fun ensurePlayer(): MediaPlayer =
        playerMutex.withLock {
            checkOpen()
            player?.let { return@withLock it }
            synchronized(nativeLifecycleLock) {
                checkOpen()
                player?.let { return@withLock it }
                val layout = VlcRuntimeLocator.find(runtimeConfiguration)
                    ?: throw IllegalStateException(
                        "VLC runtime not found. Set ARCHIVETUNE_VLC_DIR for development or package native/vlc.",
                    )
                logger.info(
                    "Initializing libVLC: root=${layout.root} plugins=${layout.plugins} " +
                        "vm=${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")} " +
                        "vendor=${System.getProperty("java.vendor")} arch=${System.getProperty("os.arch")}",
                )
                VlcRuntimeLocator.prepareNative(layout)
                val newFactory = MediaPlayerFactory(*layout.mediaPlayerFactoryOptions())
                var newNativeLog: NativeLog? = null
                var newNativeLogListener: LogEventListener? = null
                try {
                    logger.info(
                        "libVLC initialized: version=${newFactory.application().version()} " +
                            "compiler=${newFactory.application().compiler()} " +
                            "nativeLibraryPath=${newFactory.nativeLibraryPath()}",
                    )
                    newNativeLogListener = createNativeLogListener()
                    newNativeLog = newFactory.application().newLog().apply {
                        setLevel(LogLevel.WARNING)
                        addLogListener(newNativeLogListener)
                    }
                    val newPlayer = newFactory.mediaPlayers().newMediaPlayer()
                    val newEqualizerBackend = VlcjEqualizerBackend(newFactory, newPlayer)
                    val newOutputVolumeBackend = OutputVolumeBackend { percent -> newPlayer.audio().setVolume(percent) }
                    val newListener = createEventListener()
                    newPlayer.events().addMediaPlayerEventListener(newListener)
                    factory = newFactory
                    nativeLog = newNativeLog
                    nativeLogListener = newNativeLogListener
                    player = newPlayer
                    equalizerBackend = newEqualizerBackend
                    outputVolumeBackend = newOutputVolumeBackend
                    listener = newListener
                    outputGain.attach(newOutputVolumeBackend)
                    vlcjAudioEffects.attach(newEqualizerBackend)
                    audioOutputSelection.replaceBackend(VlcjAudioOutputBackend(newPlayer))
                    newPlayer
                } catch (error: Throwable) {
                    newNativeLogListener?.let { eventListener ->
                        runCatching { newNativeLog?.removeLogListener(eventListener) }
                    }
                    runCatching { newNativeLog?.release() }
                    runCatching { newFactory.release() }
                    throw error
                }
            }
        }

    private fun createNativeLogListener(): LogEventListener =
        LogEventListener { level, module, file, line, name, header, id, message ->
            val nativeMessage = message.orEmpty()
            if (
                (activeCallbackMedia != null || secondaryCrossfadeCallbackMedia != null) &&
                module == "imem" &&
                nativeMessage.contains("Invalid get/release function pointers", ignoreCase = true)
            ) {
                // libvlc_media_new_callbacks uses the imem access module. VLC also probes the
                // separate imem demux module, which rejects the access callbacks and emits this
                // scary-looking message before the explicitly selected MP4/Matroska demuxer opens.
                // It is module-probe noise, not a playback failure.
                logger.fine("Ignoring expected libVLC imem demux probe while using callback media")
                return@LogEventListener
            }
            if (
                activeCallbackMedia != null &&
                module == "imem_access" &&
                nativeMessage.contains("read error", ignoreCase = true) &&
                httpRecoveryGeneration.get() != NO_RECOVERY_GENERATION
            ) {
                // The application transport already logged the concrete HTTP status and scheduled
                // recovery. The following imem_access read error is only libVLC observing the same
                // IOException through its callback API.
                logger.fine("Ignoring duplicate libVLC callback read error during HTTP recovery")
                return@LogEventListener
            }
            nativeMessage.httpErrorStatusCode()?.takeIf { it in RETRYABLE_STREAM_RESPONSE_CODES }?.let { httpStatus ->
                activeGeneration?.let { failedGeneration ->
                    scheduleStreamHttpRecovery(failedGeneration, httpStatus, "libVLC media request")
                }
            }
            val details = buildString {
                append("libVLC native ")
                append(level.name.lowercase())
                append(": ")
                if (!module.isNullOrBlank()) append('[').append(module).append("] ")
                if (!header.isNullOrBlank()) append(header).append(": ")
                append(nativeMessage.redactSignedUrls())
                if (!file.isNullOrBlank()) {
                    append(" (").append(file.substringAfterLast('/').substringAfterLast('\\'))
                    if (line != null) append(':').append(line)
                    append(')')
                }
                if (!name.isNullOrBlank()) append(" function=").append(name)
                if (id != null) append(" id=").append(id)
            }
            when (level) {
                LogLevel.ERROR -> logger.severe(details)
                LogLevel.WARNING -> logger.warning(details)
                LogLevel.NOTICE -> logger.info(details)
                LogLevel.DEBUG -> logger.fine(details)
            }
        }

    private fun createEventListener(): MediaPlayerEventAdapter =
        object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) = dispatch { withActivePlayerLocked { _state.update { it.copy(status = PlaybackStatus.LOADING) } } }

            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = dispatch {
                withActivePlayerLocked {
                    _state.update { snapshot ->
                        snapshot.copy(
                            // libVLC may continue emitting cache percentages after playback is
                            // already flowing. Do not let those informational callbacks replace a
                            // stable READY/paused state or disable the normal player controls.
                            status = if (
                                snapshot.playWhenReady && snapshot.status in setOf(
                                    PlaybackStatus.LOADING,
                                    PlaybackStatus.BUFFERING,
                                )
                            ) PlaybackStatus.BUFFERING else snapshot.status,
                            bufferedPositionMs = if (snapshot.durationMs > 0L) {
                                (snapshot.durationMs * (newCache / 100f)).roundToLong().coerceAtLeast(snapshot.positionMs)
                            } else snapshot.bufferedPositionMs,
                        )
                    }
                }
            }

            override fun playing(mediaPlayer: MediaPlayer) = dispatch {
                withActivePlayerLocked {
                    cancelLoadWatchdog()
                    pendingSeek?.takeIf { it.first == generation }?.second?.takeIf { it > 0L }?.let {
                        mediaPlayer.controls().setTime(it)
                        latestPositionMs.set(it)
                    }
                    pendingSeek = null
                    queue.current?.metadata?.id?.let { mediaId ->
                        if (recoveryAttemptedMediaId == mediaId) recoveryAttemptedMediaId = null
                    }
                    _state.update { it.copy(status = PlaybackStatus.READY, error = null) }
                }
            }

            override fun paused(mediaPlayer: MediaPlayer) = dispatch {
                withActivePlayerLocked {
                    cancelLoadWatchdog()
                    _state.update { it.copy(status = PlaybackStatus.READY) }
                }
            }

            override fun stopped(mediaPlayer: MediaPlayer) = dispatch {
                commandMutex.withLock {
                    if (suppressStoppedEvents > 0) {
                        suppressStoppedEvents--
                    } else if (isActiveEventLocked() && _state.value.status !in setOf(
                            PlaybackStatus.RESOLVING,
                            PlaybackStatus.LOADING,
                            PlaybackStatus.IDLE,
                        )
                    ) {
                        _state.update { it.copy(status = PlaybackStatus.IDLE, playWhenReady = false) }
                    }
                }
            }

            override fun finished(mediaPlayer: MediaPlayer) = dispatch {
                val request = commandMutex.withLock {
                    if (!isActiveEventLocked()) return@withLock null
                    // Once the incoming player is audible, the crossfade coroutine owns the
                    // transition. Letting the outgoing VLC player auto-advance here would race it.
                    if (crossfadeSuppressFinishedRunId != null) return@withLock null
                    cancelLoadWatchdog()
                    val previous = queue.current
                    val snapshot = _state.value
                    val positionMs = maxOf(snapshot.positionMs, latestPositionMs.get())
                    val expectedDurationMs =
                        previous?.metadata?.durationHintMs?.takeIf { it > 0L }
                            ?: snapshot.durationMs.takeIf { it > 0L }
                    val endedPrematurely =
                        expectedDurationMs != null &&
                            positionMs + PREMATURE_FINISH_TOLERANCE_MS < expectedDurationMs
                    if (endedPrematurely && previous != null) {
                        logger.warning(
                            "Ignoring premature libVLC finished event for ${previous.logLabel()}: " +
                                "positionMs=$positionMs expectedDurationMs=$expectedDurationMs",
                        )
                        if (prematureFinishRetriedMediaId != previous.metadata.id) {
                            prematureFinishRetriedMediaId = previous.metadata.id
                            return@withLock prepareReloadLocked(TransitionReason.RETRY, positionMs)
                        }
                        stopActiveFileLocked()
                        failLocked(
                            PlaybackFailure(
                                message = "The audio stream ended unexpectedly; retry the track",
                                kind = PlaybackFailure.Kind.NETWORK,
                            ),
                        )
                        return@withLock null
                    }
                    val selected = queue.advance(automatic = true)
                    if (selected == null) {
                        _state.update {
                            it.copy(
                                status = PlaybackStatus.ENDED,
                                playWhenReady = false,
                                positionMs = it.durationMs,
                                bufferedPositionMs = it.durationMs,
                            )
                        }
                        activeGeneration = null
                        null
                    } else {
                        prepareSelectionLocked(previous, selected, TransitionReason.AUTO_ADVANCE)
                    }
                }
                request?.let(::enqueueLoad)
            }

            override fun error(mediaPlayer: MediaPlayer) = dispatch {
                commandMutex.withLock {
                    if (isActiveEventLocked()) {
                        if (httpRecoveryGeneration.get() == generation) {
                            logger.info(
                                "Suppressing libVLC engine error while HTTP stream recovery is in progress " +
                                    "for ${queue.current?.logLabel() ?: "unknown item"}",
                            )
                            return@withLock
                        }
                        logger.severe(
                            "libVLC media-player error for ${queue.current?.logLabel() ?: "unknown item"}: " +
                                "status=${_state.value.status} positionMs=${_state.value.positionMs} " +
                                "durationMs=${_state.value.durationMs}",
                        )
                        cancelLoadWatchdog()
                        failLocked(
                            PlaybackFailure(
                                message = "libVLC reported a playback error",
                                kind = PlaybackFailure.Kind.ENGINE,
                            ),
                        )
                    }
                }
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                if (!closed.get()) latestPositionMs.set(newTime.coerceAtLeast(0L))
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = dispatch {
                withActivePlayerLocked {
                    _state.update {
                        it.copy(
                            durationMs = newLength.coerceAtLeast(0L),
                            positionMs = it.positionMs.coerceAtMostPositive(newLength),
                            bufferedPositionMs = it.bufferedPositionMs.coerceAtMostPositive(newLength),
                        )
                    }
                }
            }

            override fun volumeChanged(mediaPlayer: MediaPlayer, volume: Float) {
                // ArchiveTune owns the normalized volume state. libVLC reports backend-specific
                // values here, including transient teardown values, so do not feed them back.
            }
        }

    private suspend fun maybeStartCrossfade() {
        commandMutex.withLock {
            val config = crossfadeConfiguration
            val snapshot = _state.value
            if (
                !config.enabled ||
                config.durationMs < MIN_CROSSFADE_DURATION_MS ||
                !snapshot.isPlaying ||
                snapshot.durationMs <= 0L ||
                activeGeneration != generation ||
                activeCrossfadeRunId != null
            ) {
                return@withLock
            }

            val target = crossfadeTargetLocked(config) ?: return@withLock
            if (crossfadeAttemptedOccurrenceId == target.outgoingOccurrenceId) return@withLock

            val remainingMs =
                (snapshot.durationMs - maxOf(snapshot.positionMs, latestPositionMs.get()))
                    .coerceAtLeast(0L)
            val triggerWindowMs = config.durationMs + CROSSFADE_PREPARE_AHEAD_MS + CROSSFADE_END_GUARD_MS
            if (remainingMs > triggerWindowMs) return@withLock
            if (remainingMs <= MIN_CROSSFADE_DURATION_MS + CROSSFADE_END_GUARD_MS) {
                crossfadeAttemptedOccurrenceId = target.outgoingOccurrenceId
                return@withLock
            }

            crossfadeAttemptedOccurrenceId = target.outgoingOccurrenceId
            val outgoingGeneration = generation
            val runId = ++crossfadeRunSequence
            activeCrossfadeRunId = runId
            crossfadeJob = scope.launch(CoroutineName("ArchiveTuneDesktopCrossfade")) {
                performCrossfade(runId, outgoingGeneration, target, config)
            }
        }
    }

    private fun crossfadeTargetLocked(config: DesktopCrossfadeConfiguration): CrossfadeTarget? {
        val entries = queue.entries()
        val currentVisualIndex = entries.indexOfFirst { it.isCurrent }
        if (currentVisualIndex < 0) return null
        val outgoing = entries[currentVisualIndex]
        val repeated = queue.repeatMode == RepeatMode.ONE
        val incoming = when {
            repeated -> outgoing
            currentVisualIndex < entries.lastIndex -> entries[currentVisualIndex + 1]
            queue.repeatMode == RepeatMode.ALL -> entries.firstOrNull()
            else -> null
        } ?: return null
        if (
            !repeated &&
            config.preserveGaplessAlbumTransitions &&
            sameAlbum(outgoing.item, incoming.item)
        ) {
            return null
        }
        return CrossfadeTarget(
            outgoingOccurrenceId = outgoing.id,
            incomingOccurrenceId = incoming.id,
            item = incoming.item,
            repeated = repeated,
        )
    }

    private suspend fun performCrossfade(
        runId: Long,
        outgoingGeneration: Long,
        target: CrossfadeTarget,
        config: DesktopCrossfadeConfiguration,
    ) {
        try {
            val resolved = try {
                sourceResolver.resolve(target.item).getOrElse { throw it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.log(Level.FINE, "Crossfade source resolution failed for ${target.item.logLabel()}", error)
                return
            }
            if (
                resolved.uri.isBlank() ||
                resolved.expiresAtEpochMs?.let { it <= System.currentTimeMillis() + MIN_URL_LIFETIME_MS } == true
            ) {
                return
            }

            ensurePlayer()
            if (!crossfadeStillValid(runId, outgoingGeneration, target)) return

            val activeFactory = synchronized(nativeLifecycleLock) { factory } ?: return
            val secondary = activeFactory.mediaPlayers().newMediaPlayer()
            val ready = CompletableDeferred<Boolean>()
            val readinessListener = object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
                    ready.complete(true)
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    ready.complete(false)
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    ready.complete(false)
                }
            }
            secondary.events().addMediaPlayerEventListener(readinessListener)

            var callbackMedia: VlcjRangeCallbackMedia? = null
            var adopted = false
            try {
                vlcjAudioEffects.applyCurrentTo(VlcjEqualizerBackend(activeFactory, secondary))
                VlcjAudioOutputBackend(secondary).selectOutputDevice(audioOutputSelection.state.value.selectedDeviceId)
                secondary.controls().setRate(_state.value.speed)
                secondary.audio().setVolume(0)

                callbackMedia = if (resolved.shouldUseApplicationHttpTransport()) {
                    rangeHttpTransport.media(
                        url = resolved.uri,
                        headers = resolved.headers,
                        onHttpFailure = { ready.complete(false) },
                    )
                } else {
                    null
                }

                val started = commandMutex.withLock {
                    if (!crossfadeStillValidLocked(runId, outgoingGeneration, target)) {
                        false
                    } else {
                        secondaryCrossfadePlayer = secondary
                        secondaryCrossfadeCallbackMedia = callbackMedia
                        adopted = true
                        if (callbackMedia != null) {
                            secondary.media().play(
                                callbackMedia,
                                *resolved.toCallbackMediaOptions().toTypedArray(),
                            )
                        } else {
                            secondary.media().play(
                                resolved.uri,
                                *resolved.headers.toVlcMediaOptions().toTypedArray(),
                            )
                        }.also {
                            // libVLC may rewrite volume while opening media.
                            secondary.audio().setVolume(0)
                        }
                    }
                }
                if (!started) return

                val becameReady = withTimeoutOrNull(CROSSFADE_SECONDARY_READY_TIMEOUT_MS) { ready.await() } == true
                runCatching { secondary.events().removeMediaPlayerEventListener(readinessListener) }
                if (!becameReady || !crossfadeStillValid(runId, outgoingGeneration, target)) return

                val fadeDurationMs = commandMutex.withLock {
                    if (!crossfadeStillValidLocked(runId, outgoingGeneration, target)) {
                        null
                    } else {
                        val snapshot = _state.value
                        val remainingMs =
                            (snapshot.durationMs - maxOf(snapshot.positionMs, latestPositionMs.get()))
                                .coerceAtLeast(0L)
                        (remainingMs - CROSSFADE_END_GUARD_MS)
                            .coerceAtMost(config.durationMs)
                            .takeIf { it >= MIN_CROSSFADE_DURATION_MS }
                            ?.also { crossfadeSuppressFinishedRunId = runId }
                    }
                } ?: return

                logger.fine("Starting ${fadeDurationMs}ms crossfade to ${target.item.logLabel()}")
                val fadeStartNanos = System.nanoTime()
                while (true) {
                    val elapsedMs = (System.nanoTime() - fadeStartNanos) / 1_000_000L
                    val progress = (elapsedMs.toFloat() / fadeDurationMs.toFloat()).coerceIn(0f, 1f)
                    val valid = commandMutex.withLock {
                        if (!crossfadeStillValidLocked(runId, outgoingGeneration, target)) {
                            false
                        } else {
                            outputGain.setFadeMultiplier(1f - progress)
                            runCatching {
                                secondary.audio().setVolume(outputGain.volumePercentForSource(resolved, progress))
                            }
                            true
                        }
                    }
                    if (!valid) return
                    if (progress >= 1f) break
                    delay(CROSSFADE_FRAME_MS)
                }

                val handoffPositionMs = runCatching { secondary.status().time() }.getOrDefault(0L).coerceAtLeast(0L)
                val loadRequest = commandMutex.withLock {
                    if (!crossfadeStillValidLocked(runId, outgoingGeneration, target)) return@withLock null
                    val previous = queue.current
                    val selected = queue.advance(automatic = true) ?: return@withLock null
                    val currentOccurrence = queue.entries().firstOrNull { it.isCurrent }
                    if (currentOccurrence?.id != target.incomingOccurrenceId) return@withLock null

                    advanceLoadGeneration(cancelCrossfade = false)
                    outputGain.setFadeMultiplier(0f)
                    stopActiveFileLocked()
                    publishQueueLocked()
                    latestPositionMs.set(handoffPositionMs)
                    _state.update {
                        it.copy(
                            status = PlaybackStatus.RESOLVING,
                            positionMs = handoffPositionMs,
                            bufferedPositionMs = handoffPositionMs,
                            durationMs = selected.item.metadata.durationHintMs?.coerceAtLeast(0L) ?: 0L,
                            error = null,
                        )
                    }
                    transition(
                        previous,
                        selected.item,
                        if (selected.repeated) TransitionReason.REPEAT else TransitionReason.AUTO_ADVANCE,
                    )
                    LoadRequest(generation, selected.item, handoffPositionMs)
                } ?: return
                enqueueLoad(loadRequest)

                val primaryReadyDeadline = System.nanoTime() + CROSSFADE_PRIMARY_READY_TIMEOUT_MS * 1_000_000L
                var primaryReady = false
                while (System.nanoTime() < primaryReadyDeadline) {
                    val readiness = commandMutex.withLock {
                        when {
                            activeCrossfadeRunId != runId || generation != loadRequest.generation -> -1
                            activeGeneration == loadRequest.generation && _state.value.status == PlaybackStatus.READY -> 1
                            _state.value.status == PlaybackStatus.ERROR -> -1
                            else -> 0
                        }
                    }
                    if (readiness > 0) {
                        primaryReady = true
                        break
                    }
                    if (readiness < 0) return
                    delay(CROSSFADE_HANDOFF_POLL_MS)
                }
                if (!primaryReady) return

                // The temporary player kept advancing while the primary source opened. Align the
                // primary before the short handoff so there is no audible repeated fragment.
                val alignedPositionMs = runCatching { secondary.status().time() }.getOrDefault(handoffPositionMs).coerceAtLeast(0L)
                commandMutex.withLock {
                    if (activeCrossfadeRunId != runId || generation != loadRequest.generation) return@withLock
                    runCatching { player?.controls()?.setTime(alignedPositionMs) }
                    latestPositionMs.set(alignedPositionMs)
                    _state.update {
                        it.copy(
                            positionMs = alignedPositionMs.coerceAtMostPositive(it.durationMs),
                            bufferedPositionMs = maxOf(it.bufferedPositionMs, alignedPositionMs.coerceAtMostPositive(it.durationMs)),
                        )
                    }
                }
                delay(CROSSFADE_PRIMARY_SEEK_SETTLE_MS)

                val handoffStartNanos = System.nanoTime()
                while (true) {
                    val elapsedMs = (System.nanoTime() - handoffStartNanos) / 1_000_000L
                    val progress =
                        (elapsedMs.toFloat() / CROSSFADE_HANDOFF_DURATION_MS.toFloat()).coerceIn(0f, 1f)
                    val valid = commandMutex.withLock {
                        if (
                            activeCrossfadeRunId != runId ||
                            generation != loadRequest.generation ||
                            activeGeneration != loadRequest.generation ||
                            !_state.value.playWhenReady
                        ) {
                            false
                        } else {
                            outputGain.setFadeMultiplier(progress)
                            runCatching {
                                secondary.audio().setVolume(outputGain.volumePercentForSource(resolved, 1f - progress))
                            }
                            true
                        }
                    }
                    if (!valid) return
                    if (progress >= 1f) break
                    delay(CROSSFADE_HANDOFF_FRAME_MS)
                }
            } finally {
                runCatching { secondary.events().removeMediaPlayerEventListener(readinessListener) }
                if (!adopted) {
                    runCatching { secondary.controls().stop() }
                    runCatching { secondary.media().reset() }
                    runCatching { callbackMedia?.close() }
                    runCatching { secondary.release() }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.log(Level.WARNING, "Crossfade failed for ${target.item.logLabel()}", error)
        } finally {
            commandMutex.withLock {
                if (activeCrossfadeRunId == runId) {
                    outputGain.setFadeMultiplier(1f)
                    releaseSecondaryCrossfadePlayerLocked()
                    crossfadeSuppressFinishedRunId = null
                    activeCrossfadeRunId = null
                    crossfadeJob = null
                }
            }
        }
    }

    private suspend fun crossfadeStillValid(
        runId: Long,
        outgoingGeneration: Long,
        target: CrossfadeTarget,
    ): Boolean = commandMutex.withLock {
        crossfadeStillValidLocked(runId, outgoingGeneration, target)
    }

    private fun crossfadeStillValidLocked(
        runId: Long,
        outgoingGeneration: Long,
        target: CrossfadeTarget,
    ): Boolean {
        if (
            closed.get() ||
            activeCrossfadeRunId != runId ||
            generation != outgoingGeneration ||
            !_state.value.playWhenReady
        ) {
            return false
        }
        val current = queue.entries().firstOrNull { it.isCurrent } ?: return false
        if (current.id != target.outgoingOccurrenceId) return false
        val expected = crossfadeTargetLocked(crossfadeConfiguration) ?: return false
        return expected.incomingOccurrenceId == target.incomingOccurrenceId && expected.item === target.item
    }

    private fun cancelCrossfadeLocked(resetAttempt: Boolean = false) {
        crossfadeRunSequence++
        activeCrossfadeRunId = null
        crossfadeSuppressFinishedRunId = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        outputGain.setFadeMultiplier(1f)
        releaseSecondaryCrossfadePlayerLocked()
        if (resetAttempt) crossfadeAttemptedOccurrenceId = null
    }

    private fun releaseSecondaryCrossfadePlayerLocked() {
        val secondary = secondaryCrossfadePlayer
        val callbackMedia = secondaryCrossfadeCallbackMedia
        secondaryCrossfadePlayer = null
        secondaryCrossfadeCallbackMedia = null
        runCatching { secondary?.controls()?.stop() }
        runCatching { secondary?.media()?.reset() }
        runCatching { callbackMedia?.close() }
        runCatching { secondary?.release() }
    }

    private fun sameAlbum(first: PlaybackItem, second: PlaybackItem): Boolean {
        val firstAlbum = first.metadata.album ?: return false
        val secondAlbum = second.metadata.album ?: return false
        val firstId = firstAlbum.id?.trim()?.takeIf(String::isNotEmpty)
        val secondId = secondAlbum.id?.trim()?.takeIf(String::isNotEmpty)
        if (firstId != null && secondId != null) return firstId == secondId

        val firstTitle = firstAlbum.title.trim().takeIf(String::isNotEmpty) ?: return false
        val secondTitle = secondAlbum.title.trim().takeIf(String::isNotEmpty) ?: return false
        return firstTitle.equals(secondTitle, ignoreCase = true)
    }

    private suspend fun recoverAfterStreamHttpFailure(
        failedGeneration: Long,
        httpStatus: Int,
    ) {
        data class RecoveryContext(
            val item: PlaybackItem,
            val source: ResolvedPlaybackSource,
            val positionMs: Long,
        )

        val context = commandMutex.withLock {
            if (
                closed.get() ||
                generation != failedGeneration ||
                activeGeneration != failedGeneration ||
                httpRecoveryGeneration.get() != failedGeneration
            ) {
                httpRecoveryGeneration.compareAndSet(failedGeneration, NO_RECOVERY_GENERATION)
                return@withLock null
            }

            val item = queue.current ?: run {
                httpRecoveryGeneration.set(NO_RECOVERY_GENERATION)
                return@withLock null
            }
            val source = activeResolvedSource ?: run {
                httpRecoveryGeneration.set(NO_RECOVERY_GENERATION)
                return@withLock null
            }
            val mediaId = item.metadata.id
            if (recoveryAttemptedMediaId == mediaId) {
                cancelLoadWatchdog()
                // The corresponding VLC error callback can arrive immediately after the native
                // HTTP log. Tear down this failed generation first so it cannot overwrite the
                // useful HTTP-specific failure with a second generic engine error.
                stopActiveFileLocked()
                failLocked(
                    PlaybackFailure(
                        message = "YouTube rejected the audio stream with HTTP $httpStatus after retrying",
                        kind = PlaybackFailure.Kind.NETWORK,
                    ),
                )
                return@withLock null
            }

            recoveryAttemptedMediaId = mediaId
            cancelLoadWatchdog()
            _state.update { it.copy(status = PlaybackStatus.RESOLVING, error = null) }
            RecoveryContext(
                item = item,
                source = source,
                positionMs = _state.value.positionMs,
            )
        } ?: return

        val shouldRetry =
            try {
                onStreamHttpFailure(context.item, context.source, httpStatus)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.log(
                    Level.WARNING,
                    "Stream HTTP recovery callback failed for ${context.item.logLabel()} after HTTP $httpStatus",
                    error,
                )
                false
            }

        val retryRequest = commandMutex.withLock {
            if (
                closed.get() ||
                generation != failedGeneration ||
                activeGeneration != failedGeneration ||
                httpRecoveryGeneration.get() != failedGeneration
            ) {
                httpRecoveryGeneration.compareAndSet(failedGeneration, NO_RECOVERY_GENERATION)
                return@withLock null
            }

            if (!shouldRetry) {
                stopActiveFileLocked()
                failLocked(
                    PlaybackFailure(
                        message = "YouTube rejected the audio stream with HTTP $httpStatus",
                        kind = PlaybackFailure.Kind.NETWORK,
                    ),
                )
                return@withLock null
            }

            logger.info(
                "Retrying ${context.item.logLabel()} from ${context.positionMs}ms after HTTP $httpStatus " +
                    "using a different YouTube stream client",
            )
            val request = prepareReloadLocked(TransitionReason.RETRY, context.positionMs)
            httpRecoveryGeneration.set(NO_RECOVERY_GENERATION)
            request
        }
        retryRequest?.let(::enqueueLoad)
    }

    private fun scheduleStreamHttpRecovery(
        failedGeneration: Long,
        httpStatus: Int,
        source: String,
    ) {
        if (httpStatus !in RETRYABLE_STREAM_RESPONSE_CODES) return
        if (activeGeneration != failedGeneration) return
        if (!httpRecoveryGeneration.compareAndSet(NO_RECOVERY_GENERATION, failedGeneration)) return
        logger.warning(
            "$source returned HTTP $httpStatus; scheduling Android-style stream recovery " +
                "for generation=$failedGeneration",
        )
        dispatch { recoverAfterStreamHttpFailure(failedGeneration, httpStatus) }
    }

    private fun dispatch(block: suspend () -> Unit) {
        if (!closed.get()) scope.launch { runCatching { block() } }
    }

    private suspend fun withActivePlayerLocked(block: () -> Unit) {
        commandMutex.withLock {
            if (isActiveEventLocked()) block()
        }
    }

    private fun isActiveEventLocked(): Boolean =
        !closed.get() && activeGeneration != null && activeGeneration == generation

    private fun armLoadWatchdog(request: LoadRequest) {
        cancelLoadWatchdog()
        loadWatchdog = scope.launch {
            delay(LOAD_WATCHDOG_TIMEOUT_MS)
            commandMutex.withLock {
                if (isCurrentLocked(request) && activeGeneration == request.generation &&
                    _state.value.status == PlaybackStatus.LOADING
                ) {
                    logger.warning(
                        "libVLC load watchdog timed out for ${request.item.logLabel()}: " +
                            "positionMs=${_state.value.positionMs} durationMs=${_state.value.durationMs}",
                    )
                    activeGeneration = null
                    stopActiveFileLocked()
                    failLocked(
                        PlaybackFailure(
                            message = "libVLC took too long to start the track",
                            kind = PlaybackFailure.Kind.NETWORK,
                        ),
                    )
                }
            }
        }
    }

    private fun cancelLoadWatchdog() {
        loadWatchdog?.cancel()
        loadWatchdog = null
    }

    private fun advanceLoadGeneration(cancelCrossfade: Boolean = true) {
        if (cancelCrossfade) cancelCrossfadeLocked(resetAttempt = true)
        generation++
        loadJobOwner.cancelCurrent()
    }

    private fun enqueueLoad(request: LoadRequest) = loadJobOwner.submit(request)

    private suspend fun failIfCurrent(request: LoadRequest, failure: PlaybackFailure) {
        commandMutex.withLock { if (isCurrentLocked(request)) failLocked(failure) }
    }

    private fun failLocked(failure: PlaybackFailure) {
        logger.warning(
            "Playback failure kind=${failure.kind} item=${queue.current?.logLabel() ?: "none"}: ${failure.message}",
        )
        _state.update { it.copy(status = PlaybackStatus.ERROR, playWhenReady = false, error = failure) }
        _events.tryEmit(PlaybackEvent.Failure(failure, queue.current))
    }

    private suspend fun isCurrent(request: LoadRequest): Boolean =
        commandMutex.withLock { isCurrentLocked(request) }

    private fun isCurrentLocked(request: LoadRequest): Boolean =
        request.generation == generation && queue.current === request.item && !closed.get()

    private fun publishQueueLocked(title: String? = _state.value.queueTitle) {
        val items = queue.items
        _state.update {
            it.copy(
                queue = items,
                queueEntries = queue.entries(),
                queueTitle = title,
                currentIndex = queue.currentIndex,
                repeatMode = queue.repeatMode,
                shuffleEnabled = queue.shuffleEnabled,
                hasPreviousItem = queue.canRetreat,
                hasNextItem = queue.canAdvance,
            )
        }
        _events.tryEmit(PlaybackEvent.QueueChanged(items, queue.currentIndex))
    }

    private fun transition(previous: PlaybackItem?, current: PlaybackItem?, reason: TransitionReason) {
        crossfadeAttemptedOccurrenceId = null
        if (previous?.metadata?.id != current?.metadata?.id) {
            recoveryAttemptedMediaId = null
            prematureFinishRetriedMediaId = null
        }
        _events.tryEmit(PlaybackEvent.MediaTransition(previous, current, reason))
    }

    private fun stopActiveFileLocked() {
        val hadActiveMedia = activeGeneration != null
        val callbackMedia = activeCallbackMedia
        activeGeneration = null
        activeResolvedSource = null
        outputGain.setSource(null)
        activeCallbackMedia = null
        httpRecoveryGeneration.set(NO_RECOVERY_GENERATION)
        pendingSeek = null
        cancelLoadWatchdog()
        player?.let { activePlayer ->
            if (hadActiveMedia) suppressStoppedEvents++
            runCatching { activePlayer.controls().stop() }
            runCatching { activePlayer.media().reset() }
        }
        runCatching { callbackMedia?.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        loadJobOwner.close()
        synchronized(nativeLifecycleLock) {
            crossfadeJob?.cancel()
            crossfadeJob = null
            activeCrossfadeRunId = null
            crossfadeSuppressFinishedRunId = null
            releaseSecondaryCrossfadePlayerLocked()
            generation++
            activeGeneration = null
            cancelLoadWatchdog()
            positionTicker?.cancel()
            listener?.let { player?.events()?.removeMediaPlayerEventListener(it) }
            nativeLogListener?.let { eventListener ->
                runCatching { nativeLog?.removeLogListener(eventListener) }
            }
            runCatching { player?.controls()?.stop() }
            runCatching { player?.media()?.reset() }
            runCatching { activeCallbackMedia?.close() }
            runCatching { rangeHttpTransport.close() }
            equalizerBackend?.let(vlcjAudioEffects::detach)
            outputVolumeBackend?.let(outputGain::detach)
            runCatching { player?.release() }
            runCatching { nativeLog?.release() }
            runCatching { factory?.release() }
            listener = null
            nativeLogListener = null
            nativeLog = null
            player = null
            equalizerBackend = null
            audioOutputSelection.replaceBackend(null)
            outputVolumeBackend = null
            activeCallbackMedia = null
            factory = null
            _state.update { it.copy(status = PlaybackStatus.IDLE, playWhenReady = false) }
        }
        scope.cancel()
    }

    private fun checkOpen() = check(!closed.get()) { "Playback controller is closed" }

    private fun Long.coerceAtMostPositive(maximum: Long): Long =
        if (maximum > 0L) coerceIn(0L, maximum) else coerceAtLeast(0L)

    private companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        const val MIN_URL_LIFETIME_MS = 5_000L
        const val POSITION_POLL_INTERVAL_MS = 50L
        const val LOAD_WATCHDOG_TIMEOUT_MS = 30_000L
        const val PREMATURE_FINISH_TOLERANCE_MS = 5_000L
        const val MIN_CROSSFADE_DURATION_MS = 250L
        const val CROSSFADE_PREPARE_AHEAD_MS = 1_000L
        const val CROSSFADE_END_GUARD_MS = 150L
        const val CROSSFADE_FRAME_MS = 32L
        const val CROSSFADE_SECONDARY_READY_TIMEOUT_MS = 3_000L
        const val CROSSFADE_PRIMARY_READY_TIMEOUT_MS = 10_000L
        const val CROSSFADE_HANDOFF_POLL_MS = 25L
        const val CROSSFADE_PRIMARY_SEEK_SETTLE_MS = 80L
        const val CROSSFADE_HANDOFF_DURATION_MS = 220L
        const val CROSSFADE_HANDOFF_FRAME_MS = 16L
        const val NO_RECOVERY_GENERATION = -1L
        val RETRYABLE_STREAM_RESPONSE_CODES = setOf(403, 404, 410, 416)
    }
}

private fun PlaybackItem.logLabel(): String =
    "id=${metadata.id} title=\"${metadata.title.replace('\n', ' ').replace('\r', ' ').take(160)}\""

private fun ResolvedPlaybackSource.shouldUseApplicationHttpTransport(): Boolean =
    runCatching {
        val parsed = URI(uri)
        val host = parsed.host?.lowercase().orEmpty()
        parsed.scheme?.lowercase() in setOf("http", "https") &&
            (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
    }.getOrDefault(false)

private fun ResolvedPlaybackSource.toCallbackMediaOptions(): List<String> =
    when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/mp4" -> listOf(":demux=mp4")
        "audio/webm" -> listOf(":demux=mkv")
        else -> emptyList()
    }

private fun String.toLogMediaLocation(): String {
    if (isBlank()) return "<blank>"
    return runCatching {
        val parsed = URI(this)
        when (parsed.scheme?.lowercase()) {
            "http", "https" -> buildString {
                append(parsed.scheme).append("://")
                append(parsed.host ?: "<unknown-host>")
                append(parsed.rawPath.orEmpty())
                if (!parsed.rawQuery.isNullOrBlank()) append("?<redacted>")
            }
            else -> take(MAX_LOG_LOCATION_CHARS).redactSignedUrls()
        }
    }.getOrElse { take(MAX_LOG_LOCATION_CHARS).redactSignedUrls() }
}

private fun String.redactSignedUrls(): String =
    URL_QUERY_PATTERN.replace(this) { match -> "${match.groupValues[1]}?<redacted>" }

private fun String.httpErrorStatusCode(): Int? =
    HTTP_ERROR_STATUS_PATTERN.find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun Map<String, String>.toVlcMediaOptions(): List<String> = buildList {
    this@toVlcMediaOptions.entries
        .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
        ?.value
        ?.takeIf(String::isNotBlank)
        ?.let { add(":http-user-agent=$it") }
    this@toVlcMediaOptions.entries
        .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
        ?.value
        ?.takeIf(String::isNotBlank)
        ?.let { add(":http-referrer=$it") }
}

private const val MAX_LOG_LOCATION_CHARS = 512
private val URL_QUERY_PATTERN = Regex("(https?://[^\\s?]+)\\?[^\\s]+", RegexOption.IGNORE_CASE)
private val HTTP_ERROR_STATUS_PATTERN = Regex("\\bHTTP\\s+(\\d{3})\\s+error\\b", RegexOption.IGNORE_CASE)
