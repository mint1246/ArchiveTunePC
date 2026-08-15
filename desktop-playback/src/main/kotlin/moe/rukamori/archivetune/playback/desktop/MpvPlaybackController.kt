/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/**
 * Real desktop audio backend using a supervised mpv process and its JSON IPC protocol.
 *
 * mpv receives only sources returned by [sourceResolver]. The class does not invoke yt-dlp,
 * browser automation, or contain a demo URL. ArchiveTune's existing extractor can therefore adapt
 * its PlaybackData directly, including expiring URL, MIME type, and client-specific HTTP headers.
 */
class MpvPlaybackController(
    private val sourceResolver: PlaybackSourceResolver,
    private val configuration: MpvConfiguration = MpvConfiguration(),
) : PlaybackController {
    private data class LoadRequest(
        val generation: Long,
        val item: PlaybackItem,
        val startPositionMs: Long,
    )

    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ArchiveTuneDesktopPlayback"))
    private val commandMutex = Mutex()
    private val hostMutex = Mutex()
    private val queue = PlaybackQueue()
    private val _state = MutableStateFlow(PlaybackSnapshot())
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 32)
    private var host: MpvProcessHost? = null
    private var generation = 0L
    private var activeMpvGeneration: Long? = null
    private var pendingSeek: Pair<Long, Long>? = null

    override val state = _state.asStateFlow()
    override val events = _events.asSharedFlow()

    override suspend fun replaceQueue(
        items: List<PlaybackItem>,
        startIndex: Int,
        startPositionMs: Long,
        title: String?,
        playWhenReady: Boolean,
    ) {
        checkOpen()
        val request =
            commandMutex.withLock {
                val previous = queue.current
                queue.replace(items, startIndex)
                generation++
                stopActiveFileLocked()
                publishQueueLocked(title = title)
                _state.update {
                    it.copy(
                        playWhenReady = playWhenReady && queue.current != null,
                        positionMs = startPositionMs.coerceAtLeast(0L),
                        bufferedPositionMs = startPositionMs.coerceAtLeast(0L),
                        durationMs = queue.current?.metadata?.durationHintMs?.coerceAtLeast(0L) ?: 0L,
                        status = if (queue.current == null) PlaybackStatus.IDLE else PlaybackStatus.RESOLVING,
                        error = null,
                    )
                }
                transition(previous, queue.current, TransitionReason.QUEUE_REPLACED)
                queue.current?.let { LoadRequest(generation, it, startPositionMs.coerceAtLeast(0L)) }
            }

        if (request == null) {
            host?.let { runCatching { it.command("stop") } }
        } else {
            enqueueLoad(request)
        }
    }

    override suspend fun play() {
        checkOpen()
        val reload =
            commandMutex.withLock {
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
            commandMutex.withLock {
                sendHostCommandLocked("set_property", "pause", false)
            }
        }
    }

    override suspend fun pause() {
        checkOpen()
        commandMutex.withLock {
            _state.update { it.copy(playWhenReady = false) }
            sendHostCommandLocked("set_property", "pause", true)
        }
    }

    override suspend fun stop() {
        checkOpen()
        commandMutex.withLock {
            generation++
            activeMpvGeneration = null
            pendingSeek = null
            host?.takeIf(MpvProcessHost::isRunning)?.command("stop")
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
            val safePosition =
                positionMs.coerceAtLeast(0L).let { requested ->
                    if (duration > 0L) requested.coerceAtMost(duration) else requested
                }
            _state.update { it.copy(positionMs = safePosition, bufferedPositionMs = maxOf(it.bufferedPositionMs, safePosition)) }
            sendHostCommandLocked("seek", safePosition / 1_000.0, "absolute", "exact")
        }
    }

    override suspend fun seekToItem(index: Int, positionMs: Long) {
        checkOpen()
        val request =
            commandMutex.withLock {
                val previous = queue.current
                val selected = queue.select(index)
                generation++
                stopActiveFileLocked()
                publishQueueLocked()
                _state.update {
                    it.copy(
                        status = PlaybackStatus.RESOLVING,
                        positionMs = positionMs.coerceAtLeast(0L),
                        bufferedPositionMs = positionMs.coerceAtLeast(0L),
                        durationMs = selected.item.metadata.durationHintMs?.coerceAtLeast(0L) ?: 0L,
                        error = null,
                    )
                }
                transition(previous, selected.item, TransitionReason.USER_SELECTED)
                LoadRequest(generation, selected.item, positionMs.coerceAtLeast(0L))
            }
        enqueueLoad(request)
    }

    override suspend fun skipNext() {
        checkOpen()
        val request =
            commandMutex.withLock {
                val previous = queue.current
                val selected = queue.advance(automatic = false)
                if (selected == null) {
                    generation++
                    activeMpvGeneration = null
                    host?.takeIf(MpvProcessHost::isRunning)?.command("stop")
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

        val request =
            commandMutex.withLock {
                val previous = queue.current
                val selected = queue.retreat()
                if (selected == null) {
                    null
                } else {
                    prepareSelectionLocked(previous, selected, TransitionReason.USER_PREVIOUS)
                }
            }
        if (request == null) seekTo(0L) else enqueueLoad(request)
    }

    override suspend fun enqueue(items: List<PlaybackItem>) {
        checkOpen()
        val request =
            commandMutex.withLock {
                val previous = queue.current
                val mutation = queue.append(items)
                val current = mutation.current
                publishQueueLocked()
                if (mutation.currentChanged && current != null) {
                    generation++
                    _state.update { it.copy(status = PlaybackStatus.RESOLVING, error = null) }
                    transition(previous, current.item, TransitionReason.QUEUE_REPLACED)
                    LoadRequest(generation, current.item, 0L)
                } else {
                    null
                }
            }
        request?.let(::enqueueLoad)
    }

    override suspend fun playNext(items: List<PlaybackItem>) {
        checkOpen()
        val request =
            commandMutex.withLock {
                val previous = queue.current
                val mutation = queue.insertNext(items)
                val current = mutation.current
                publishQueueLocked()
                if (mutation.currentChanged && current != null) {
                    generation++
                    _state.update { it.copy(status = PlaybackStatus.RESOLVING, error = null) }
                    transition(previous, current.item, TransitionReason.QUEUE_REPLACED)
                    LoadRequest(generation, current.item, 0L)
                } else {
                    null
                }
            }
        request?.let(::enqueueLoad)
    }

    override suspend fun removeQueueItem(index: Int) {
        checkOpen()
        val request =
            commandMutex.withLock {
                val previous = queue.current
                val mutation = queue.removeAt(index)
                publishQueueLocked()
                when {
                    queue.current == null -> {
                        generation++
                        activeMpvGeneration = null
                        host?.takeIf(MpvProcessHost::isRunning)?.command("stop")
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

                    mutation.currentChanged -> {
                        val selected = requireNotNull(mutation.current)
                        prepareSelectionLocked(previous, selected, TransitionReason.CURRENT_ITEM_REMOVED)
                    }

                    else -> null
                }
            }
        request?.let(::enqueueLoad)
    }

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        checkOpen()
        commandMutex.withLock {
            queue.moveVisual(fromIndex, toIndex)
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
        val request =
            commandMutex.withLock {
                val previous = queue.current
                val mutation = queue.clear()
                publishQueueLocked()
                when {
                    mutation.current == null -> {
                        generation++
                        activeMpvGeneration = null
                        host?.takeIf(MpvProcessHost::isRunning)?.command("stop")
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
                        null
                    }

                    else -> null
                }
            }
        request?.let(::enqueueLoad)
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
            sendHostCommandLocked("set_property", "volume", safeVolume * 100.0)
        }
    }

    override suspend fun setSpeed(speed: Float) {
        checkOpen()
        val safeSpeed = speed.takeIf(Float::isFinite)?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f
        commandMutex.withLock {
            _state.update { it.copy(speed = safeSpeed) }
            sendHostCommandLocked("set_property", "speed", safeSpeed.toDouble())
        }
    }

    override suspend fun retry() {
        checkOpen()
        val request = commandMutex.withLock { prepareReloadLocked(TransitionReason.RETRY, _state.value.positionMs) }
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
        val resolved =
            try {
                sourceResolver.resolve(request.item).getOrElse { throw it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failIfCurrent(
                    request,
                    PlaybackFailure(
                        message = if (error is FileNotFoundException) {
                            "The local audio file is unavailable"
                        } else if (error.message?.startsWith("Sign in is required") == true ||
                            error.message?.startsWith("YouTube changed") == true ||
                            error.message?.startsWith("YouTube did not respond") == true ||
                            error.message?.startsWith("Could not reach YouTube") == true ||
                            error.message?.startsWith("This YouTube track") == true
                        ) {
                            error.message.orEmpty()
                        } else {
                            "Unable to resolve the audio stream"
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

        if (resolved.uri.isBlank() || resolved.expiresAtEpochMs?.let { it <= System.currentTimeMillis() + MIN_URL_LIFETIME_MS } == true) {
            failIfCurrent(
                request,
                PlaybackFailure(
                    message = "The resolved audio stream is blank or already expired",
                    kind = PlaybackFailure.Kind.SOURCE_RESOLUTION,
                ),
            )
            return
        }

        val safeHeaders =
            try {
                resolved.headers.toMpvHttpHeaders()
            } catch (error: IllegalArgumentException) {
                failIfCurrent(
                    request,
                    PlaybackFailure(
                        message = "The resolved stream supplied invalid HTTP headers",
                        kind = PlaybackFailure.Kind.SOURCE_RESOLUTION,
                        recoverable = false,
                    ),
                )
                return
            }

        if (!isCurrent(request)) return
        val activeHost =
            try {
                ensureHost()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failIfCurrent(
                    request,
                    PlaybackFailure(
                        message = error.message?.takeIf(String::isNotBlank) ?: "mpv is unavailable",
                        kind = PlaybackFailure.Kind.ENGINE_UNAVAILABLE,
                        recoverable = true,
                    ),
                )
                return
            }

        commandMutex.withLock {
            if (!isCurrentLocked(request)) return@withLock
            try {
                _state.update { it.copy(status = PlaybackStatus.LOADING, error = null) }
                activeHost.command("set_property", "http-header-fields", safeHeaders)
                activeHost.command("set_property", "volume", _state.value.volume * 100.0)
                activeHost.command("set_property", "speed", _state.value.speed.toDouble())
                activeMpvGeneration = request.generation
                pendingSeek = request.generation to request.startPositionMs
                activeHost.command("loadfile", resolved.uri, "replace")
                activeHost.command("set_property", "pause", !_state.value.playWhenReady)
            } catch (error: Throwable) {
                failLocked(
                    PlaybackFailure(
                        message = "The mpv control channel closed while loading the track",
                        kind = PlaybackFailure.Kind.ENGINE,
                    ),
                )
            }
        }
    }

    private fun enqueueLoad(request: LoadRequest) {
        scope.launch { executeLoad(request) }
    }

    private suspend fun ensureHost(): MpvProcessHost =
        hostMutex.withLock {
            host?.takeIf(MpvProcessHost::isRunning)?.let { return@withLock it }
            host?.close()
            val candidate =
                MpvProcessHost(
                    configuration = configuration,
                    onMessage = { message -> scope.launch { handleMpvMessage(message) } },
                    onExit = { exitCode, _ ->
                        scope.launch {
                            commandMutex.withLock {
                                if (
                                    !closed.get() &&
                                    activeMpvGeneration == generation &&
                                    _state.value.currentItem != null &&
                                    _state.value.status != PlaybackStatus.IDLE
                                ) {
                                    failLocked(
                                        PlaybackFailure(
                                            message = buildString {
                                                append("mpv exited unexpectedly")
                                                if (exitCode >= 0) append(" (exit code $exitCode)")
                                            },
                                            kind = PlaybackFailure.Kind.ENGINE,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                )
            host = candidate
            try {
                candidate.start()
                OBSERVED_PROPERTIES.forEachIndexed { index, property ->
                    candidate.eventCommand("observe_property", index + 1, property)
                }
                candidate.startEventLoop()
                candidate
            } catch (error: Throwable) {
                candidate.close()
                if (host === candidate) host = null
                throw error
            }
        }

    private suspend fun handleMpvMessage(message: JSONObject) {
        if (activeMpvGeneration != generation) return
        when (message.optString("event")) {
            "start-file" -> commandMutex.withLock {
                if (_state.value.currentItem != null) _state.update { it.copy(status = PlaybackStatus.LOADING) }
            }

            "file-loaded" -> commandMutex.withLock {
                pendingSeek?.takeIf { it.first == generation }?.second?.takeIf { it > 0L }?.let { position ->
                    if (!sendHostCommandLocked("seek", position / 1_000.0, "absolute", "exact")) {
                        return@withLock
                    }
                }
                pendingSeek = null
                if (!sendHostCommandLocked("set_property", "pause", !_state.value.playWhenReady)) {
                    return@withLock
                }
                _state.update { it.copy(status = PlaybackStatus.READY) }
            }

            "playback-restart" -> commandMutex.withLock {
                if (_state.value.currentItem != null) _state.update { it.copy(status = PlaybackStatus.READY, error = null) }
            }

            "seeking" -> commandMutex.withLock {
                if (_state.value.playWhenReady) _state.update { it.copy(status = PlaybackStatus.BUFFERING) }
            }

            "end-file" -> handleEndFile(message)
            "property-change" -> handlePropertyChange(message)
        }
    }

    private suspend fun handleEndFile(message: JSONObject) {
        if (activeMpvGeneration != generation) return
        when (message.optString("reason")) {
            "eof" -> {
                val request =
                    commandMutex.withLock {
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
                            null
                        } else {
                            prepareSelectionLocked(previous, selected, TransitionReason.AUTO_ADVANCE)
                        }
                    }
                request?.let(::enqueueLoad)
            }

            "error", "unknown" -> commandMutex.withLock {
                failLocked(
                    PlaybackFailure(
                        message = "mpv could not play the resolved audio stream",
                        kind = PlaybackFailure.Kind.ENGINE,
                    ),
                )
            }

            // stop/quit/redirect are expected during replacement or shutdown.
        }
    }

    private suspend fun handlePropertyChange(message: JSONObject) {
        if (activeMpvGeneration != generation) return
        val name = message.optString("name")
        val data = message.opt("data").takeUnless { it == null || it === JSONObject.NULL }
        commandMutex.withLock {
            when (name) {
                "time-pos" -> (data as? Number)?.toDouble()?.secondsToMillis()?.let { position ->
                    _state.update { snapshot -> snapshot.copy(positionMs = position.coerceAtMostPositive(snapshot.durationMs)) }
                }

                "duration" -> (data as? Number)?.toDouble()?.secondsToMillis()?.let { duration ->
                    _state.update { it.copy(durationMs = duration, positionMs = it.positionMs.coerceAtMostPositive(duration)) }
                }

                "demuxer-cache-duration" -> (data as? Number)?.toDouble()?.secondsToMillis()?.let { cached ->
                    _state.update { snapshot ->
                        snapshot.copy(
                            bufferedPositionMs =
                                (snapshot.positionMs + cached).coerceAtMostPositive(snapshot.durationMs),
                        )
                    }
                }

                "paused-for-cache" -> (data as? Boolean)?.let { buffering ->
                    _state.update { snapshot ->
                        when {
                            buffering -> snapshot.copy(status = PlaybackStatus.BUFFERING)
                            snapshot.status == PlaybackStatus.BUFFERING -> snapshot.copy(status = PlaybackStatus.READY)
                            else -> snapshot
                        }
                    }
                }

                "volume" -> (data as? Number)?.toFloat()?.let { value ->
                    _state.update { it.copy(volume = (value / 100f).coerceIn(0f, 1f)) }
                }

                "speed" -> (data as? Number)?.toFloat()?.let { value ->
                    _state.update { it.copy(speed = value.coerceIn(MIN_SPEED, MAX_SPEED)) }
                }
            }
        }
    }

    private suspend fun failIfCurrent(request: LoadRequest, failure: PlaybackFailure) {
        commandMutex.withLock {
            if (isCurrentLocked(request)) failLocked(failure)
        }
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
                queueEntries = queue.visualEntries(),
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

    private fun checkOpen() = check(!closed.get()) { "Playback controller is closed" }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation++
        activeMpvGeneration = null
        pendingSeek = null
        host?.close()
        host = null
        scope.cancel()
        _state.update { it.copy(status = PlaybackStatus.IDLE, playWhenReady = false) }
    }

    private fun stopActiveFileLocked() {
        activeMpvGeneration = null
        pendingSeek = null
        host?.takeIf(MpvProcessHost::isRunning)?.let { activeHost ->
            runCatching { activeHost.command("stop") }
        }
    }

    /** Returns true when no running engine exists yet or the command was delivered successfully. */
    private fun sendHostCommandLocked(vararg arguments: Any?): Boolean {
        val activeHost = host?.takeIf(MpvProcessHost::isRunning) ?: return true
        return try {
            activeHost.command(*arguments)
            true
        } catch (error: Throwable) {
            failLocked(
                PlaybackFailure(
                    message = "The mpv control channel closed while applying a playback command",
                    kind = PlaybackFailure.Kind.ENGINE,
                ),
            )
            false
        }
    }

    private fun Double.secondsToMillis(): Long =
        if (isFinite() && this >= 0.0) (this * 1_000.0).roundToLong() else 0L

    private fun Long.coerceAtMostPositive(maximum: Long): Long =
        if (maximum > 0L) coerceIn(0L, maximum) else coerceAtLeast(0L)

    private companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        const val MIN_URL_LIFETIME_MS = 5_000L
        val OBSERVED_PROPERTIES =
            listOf(
                "time-pos",
                "duration",
                "demuxer-cache-duration",
                "paused-for-cache",
                "volume",
                "speed",
            )
    }
}
