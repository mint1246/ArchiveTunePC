/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
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
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

/**
 * In-process desktop playback backed by one application-scoped VLCJ/libVLC player.
 *
 * ArchiveTune resolves deferred YouTube items before this class is called. The normal path hands
 * the resolved URI directly to libVLC; no process, IPC pipe, JSON command protocol, or extractor
 * lives in this backend.
 */
class VlcjPlaybackController(
    private val sourceResolver: PlaybackSourceResolver,
    private val runtimeConfiguration: VlcRuntimeConfiguration = VlcRuntimeConfiguration(),
) : PlaybackController {
    private data class LoadRequest(
        val generation: Long,
        val item: PlaybackItem,
        val startPositionMs: Long,
    )

    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("ArchiveTuneDesktopVlcPlayback"),
    )
    private val commandMutex = Mutex()
    private val playerMutex = Mutex()
    private val queue = PlaybackQueue()
    private val _state = MutableStateFlow(PlaybackSnapshot())
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 32)
    private val latestPositionMs = AtomicLong(0L)
    private var positionTicker: Job? = null
    private var loadWatchdog: Job? = null
    private var factory: MediaPlayerFactory? = null
    private var player: MediaPlayer? = null
    private var listener: MediaPlayerEventAdapter? = null
    private var generation = 0L
    private var activeGeneration: Long? = null
    private var pendingSeek: Pair<Long, Long>? = null
    private var suppressStoppedEvents = 0

    override val state = _state.asStateFlow()
    override val events = _events.asSharedFlow()

    init {
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
            generation++
            stopActiveFileLocked()
            publishQueueLocked(title)
            val position = startPositionMs.coerceAtLeast(0L)
            _state.update {
                it.copy(
                    playWhenReady = playWhenReady && queue.current != null,
                    positionMs = position,
                    bufferedPositionMs = position,
                    durationMs = queue.current?.metadata?.durationHintMs?.coerceAtLeast(0L) ?: 0L,
                    status = if (queue.current == null) PlaybackStatus.IDLE else PlaybackStatus.RESOLVING,
                    error = null,
                )
            }
            transition(previous, queue.current, TransitionReason.QUEUE_REPLACED)
            queue.current?.let { LoadRequest(generation, it, position) }
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
            _state.update { it.copy(playWhenReady = false) }
            player?.controls()?.setPause(true)
        }
    }

    override suspend fun stop() {
        checkOpen()
        commandMutex.withLock {
            generation++
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
            val duration = _state.value.durationMs
            val safePosition = positionMs.coerceAtLeast(0L).let { requested ->
                if (duration > 0L) requested.coerceAtMost(duration) else requested
            }
            latestPositionMs.set(safePosition)
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
            generation++
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
                generation++
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
                generation++
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
            val previous = queue.current
            val mutation = queue.insertNext(items)
            publishQueueLocked()
            mutation.current?.takeIf { mutation.currentChanged }?.let {
                generation++
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
            val previous = queue.current
            val mutation = queue.removeAt(index)
            publishQueueLocked()
            when {
                queue.current == null -> {
                    generation++
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
            queue.move(fromIndex, toIndex)
            publishQueueLocked()
        }
    }

    override suspend fun moveQueueItemToNext(index: Int) {
        checkOpen()
        commandMutex.withLock {
            queue.moveToNext(index)
            publishQueueLocked()
        }
    }

    override suspend fun clearQueue() {
        checkOpen()
        commandMutex.withLock {
            val previous = queue.current
            queue.clear()
            publishQueueLocked()
            if (queue.current == null) {
                generation++
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
            queue.setRepeatMode(mode)
            publishQueueLocked()
        }
    }

    override suspend fun setShuffleEnabled(enabled: Boolean) {
        checkOpen()
        commandMutex.withLock {
            queue.setShuffleEnabled(enabled)
            publishQueueLocked()
        }
    }

    override suspend fun setVolume(volume: Float) {
        checkOpen()
        val safeVolume = volume.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        commandMutex.withLock {
            _state.update { it.copy(volume = safeVolume) }
            player?.audio()?.setVolume((safeVolume * 100f).roundToInt())
        }
    }

    override suspend fun setSpeed(speed: Float) {
        checkOpen()
        val safeSpeed = speed.takeIf(Float::isFinite)?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f
        commandMutex.withLock {
            _state.update { it.copy(speed = safeSpeed) }
            player?.controls()?.setRate(safeSpeed)
        }
    }

    override suspend fun retry() {
        checkOpen()
        val request = commandMutex.withLock {
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
                    status = if (queue.current == null) PlaybackStatus.IDLE else PlaybackStatus.ENDED,
                )
            }
        }
    }

    private fun prepareReloadLocked(reason: TransitionReason, positionMs: Long): LoadRequest? {
        val item = queue.current ?: return null
        generation++
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
        generation++
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

        val activePlayer = try {
            ensurePlayer()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
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
                pendingSeek = request.generation to request.startPositionMs
                latestPositionMs.set(request.startPositionMs)
                val started = activePlayer.media().play(
                    resolved.uri,
                    *resolved.headers.toVlcMediaOptions().toTypedArray(),
                )
                if (!started) {
                    activeGeneration = null
                    failLocked(
                        PlaybackFailure(
                            message = "libVLC rejected the resolved audio stream",
                            kind = PlaybackFailure.Kind.ENGINE,
                        ),
                    )
                    return@withLock
                }
                activePlayer.audio().setVolume((_state.value.volume * 100f).roundToInt())
                activePlayer.controls().setRate(_state.value.speed)
                activePlayer.controls().setPause(!_state.value.playWhenReady)
                armLoadWatchdog(request)
            } catch (error: Throwable) {
                activeGeneration = null
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
            player?.let { return@withLock it }
            val layout = VlcRuntimeLocator.find(runtimeConfiguration)
                ?: throw IllegalStateException(
                    "VLC runtime not found. Set ARCHIVETUNE_VLC_DIR for development or package native/vlc.",
                )
            VlcRuntimeLocator.prepareNative(layout)
            val newFactory = MediaPlayerFactory(*layout.mediaPlayerFactoryOptions())
            val newPlayer = newFactory.mediaPlayers().newMediaPlayer()
            val newListener = createEventListener()
            newPlayer.events().addMediaPlayerEventListener(newListener)
            factory = newFactory
            player = newPlayer
            listener = newListener
            newPlayer
        }

    private fun createEventListener(): MediaPlayerEventAdapter =
        object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) = dispatch { withActivePlayerLocked { _state.update { it.copy(status = PlaybackStatus.LOADING) } } }

            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = dispatch {
                withActivePlayerLocked {
                    _state.update { snapshot ->
                        snapshot.copy(
                            status = if (snapshot.playWhenReady) PlaybackStatus.BUFFERING else snapshot.status,
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
                    cancelLoadWatchdog()
                    val previous = queue.current
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

            override fun volumeChanged(mediaPlayer: MediaPlayer, volume: Float) = dispatch {
                commandMutex.withLock {
                    if (!closed.get()) _state.update { it.copy(volume = (volume / 100f).coerceIn(0f, 1f)) }
                }
            }
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

    private fun enqueueLoad(request: LoadRequest) {
        scope.launch { executeLoad(request) }
    }

    private suspend fun failIfCurrent(request: LoadRequest, failure: PlaybackFailure) {
        commandMutex.withLock { if (isCurrentLocked(request)) failLocked(failure) }
    }

    private fun failLocked(failure: PlaybackFailure) {
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
        _events.tryEmit(PlaybackEvent.MediaTransition(previous, current, reason))
    }

    private fun stopActiveFileLocked() {
        activeGeneration = null
        pendingSeek = null
        cancelLoadWatchdog()
        player?.let { activePlayer ->
            suppressStoppedEvents++
            runCatching { activePlayer.controls().stop() }
            runCatching { activePlayer.media().reset() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation++
        activeGeneration = null
        cancelLoadWatchdog()
        positionTicker?.cancel()
        listener?.let { player?.events()?.removeMediaPlayerEventListener(it) }
        runCatching { player?.controls()?.stop() }
        runCatching { player?.media()?.reset() }
        runCatching { player?.release() }
        runCatching { factory?.release() }
        listener = null
        player = null
        factory = null
        scope.cancel()
        _state.update { it.copy(status = PlaybackStatus.IDLE, playWhenReady = false) }
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
    }
}

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
