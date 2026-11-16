/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.playback.api.equalPowerGains
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
    initialPitchSemitones: Float = 0f,
    mediaHttpClient: okhttp3.OkHttpClient? = null,
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
    private val rangeHttpTransport = VlcjRangeHttpTransport(newRangeHttpClient(mediaHttpClient ?: okhttp3.OkHttpClient()))
    private val initialSafePitchSemitones =
        initialPitchSemitones.takeIf(Float::isFinite)?.coerceIn(MIN_PITCH, MAX_PITCH) ?: 0f
    internal var applicationHttpTransportPredicate: (ResolvedPlaybackSource) -> Boolean =
        { it.shouldUseApplicationHttpTransport() }
    private val _state = MutableStateFlow(PlaybackSnapshot(pitchSemitones = initialSafePitchSemitones))
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 32)
    private val mediaGuard = PlaybackMediaGuard()
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
    private data class RetiredCrossfadePlayer(
        val player: MediaPlayer,
        val callbackMedia: VlcjRangeCallbackMedia?,
    )
    private val retiredCrossfadePlayers = mutableListOf<RetiredCrossfadePlayer>()
    @Volatile private var crossfadeConfiguration = DesktopCrossfadeConfiguration()
    private var crossfadeJob: Job? = null
    private var crossfadeAttemptedOccurrenceId: Long? = null
    private var crossfadeRunSequence = 0L
    private var activeCrossfadeRunId: Long? = null
    private var crossfadeSuppressFinishedRunId: Long? = null
    private var deferredFinishedRunId: Long? = null
    private var deferredFinishedGeneration: Long? = null
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
    private var suppressStoppedEvents = 0
    @Volatile private var externalAudioRendererActive = false
    @Volatile private var pitchSemitones = initialSafePitchSemitones
    @Volatile private var pitchNeedsApply = true

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

    /**
     * Decode [item] through the same libVLC/runtime and signed-source transport used by playback,
     * but into a deterministic 44.1 kHz stereo PCM WAV for on-device stem separation.
     *
     * This uses a separate short-lived media player, so normal playback is never paused or
     * reconfigured while the stem input is being prepared.
     */
    suspend fun decodeStemInput(item: PlaybackItem, output: Path): Path =
        decodeAnalysisInput(item, output, sampleRate = 44_100, channels = 2)

    /**
     * Decode a playback item into deterministic PCM for local analysis models. Keeping this on the
     * playback controller means inference uses the same signed/resolved transport as normal play.
     */
    suspend fun decodeAnalysisInput(
        item: PlaybackItem,
        output: Path,
        sampleRate: Int,
        channels: Int = 2,
    ): Path = withContext(Dispatchers.IO) {
        require(sampleRate in 8_000..192_000) { "Unsupported analysis sample rate: $sampleRate" }
        require(channels in 1..2) { "Unsupported analysis channel count: $channels" }
        checkOpen()
        val resolved = sourceResolver.resolve(item).getOrElse { throw it }
        ensurePlayer()
        output.parent?.let(Files::createDirectories)
        runCatching { Files.deleteIfExists(output) }

        val activeFactory = synchronized(nativeLifecycleLock) { factory }
            ?: throw IllegalStateException("libVLC factory is unavailable")
        val decoder = activeFactory.mediaPlayers().newMediaPlayer()
        val finished = CompletableDeferred<Boolean>()
        val eventListener = object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) {
                finished.complete(true)
            }

            override fun error(mediaPlayer: MediaPlayer) {
                finished.complete(false)
            }
        }
        decoder.events().addMediaPlayerEventListener(eventListener)

        var materializedSource: Path? = null
        try {
            val sourceLocation: String
            val sourceOptions: List<String>
            if (applicationHttpTransportPredicate(resolved)) {
                materializedSource = output.resolveSibling(".${output.fileName}.source")
                rangeHttpTransport.materialize(
                    url = resolved.uri,
                    headers = resolved.headers,
                    destination = materializedSource,
                )
                sourceLocation = materializedSource.toUri().toASCIIString()
                // The temporary file deliberately has no meaningful extension. Preserve the
                // resolver's known container hint so VLC does not need to probe arbitrary bytes.
                sourceOptions = resolved.toCallbackMediaOptions()
            } else {
                sourceLocation = resolved.uri
                sourceOptions = resolved.headers.toVlcMediaOptions()
            }

            val destination = output.toAbsolutePath().normalize().toString().replace('\\', '/')
            val transcodeOption =
                ":sout=#transcode{vcodec=none,acodec=s16l,channels=$channels,samplerate=$sampleRate}:" +
                    "std{access=file,mux=wav,dst='$destination'}"
            val options = buildList {
                add(transcodeOption)
                add(":no-video")
                addAll(sourceOptions)
            }
            val started = decoder.media().play(sourceLocation, *options.toTypedArray())
            if (!started) throw IllegalStateException("libVLC rejected the analysis decode source")

            val expectedMs = item.metadata.durationHintMs?.coerceAtLeast(0L) ?: 0L
            val timeoutMs = (expectedMs * 2 + STEM_DECODE_TIMEOUT_SLACK_MS)
                .coerceAtLeast(MIN_STEM_DECODE_TIMEOUT_MS)
            val completed = withTimeoutOrNull(timeoutMs) { finished.await() } == true
            if (!completed || !Files.isRegularFile(output) || Files.size(output) <= STEM_WAV_HEADER_BYTES) {
                throw IllegalStateException("libVLC could not decode the track for local analysis")
            }
            output
        } finally {
            runCatching { decoder.events().removeMediaPlayerEventListener(eventListener) }
            runCatching { decoder.controls().stop() }
            runCatching { decoder.release() }
            runCatching { materializedSource?.let(Files::deleteIfExists) }
        }
    }

    /**
     * Compress a generated PCM stem into the persistent on-disk cache format.
     *
     * Opus is used instead of MP3 because it keeps good quality at a much lower bitrate. The
     * source WAV is intentionally not deleted here; callers delete it only after this method has
     * produced and verified a non-empty Ogg/Opus file so a failed encode cannot destroy the only
     * generated stem.
     */
    suspend fun compressStemForCache(
        sourceWav: Path,
        outputOpus: Path,
        channels: Int = 2,
        bitrateKbps: Int = STEM_OPUS_BITRATE_KBPS,
    ): Path = withContext(Dispatchers.IO) {
        checkOpen()
        require(Files.isRegularFile(sourceWav)) { "Stem WAV does not exist: $sourceWav" }
        require(channels in 1..8) { "Opus cache channel count must be between 1 and 8" }
        require(bitrateKbps > 0) { "Opus cache bitrate must be positive" }
        ensurePlayer()
        outputOpus.parent?.let(Files::createDirectories)
        runCatching { Files.deleteIfExists(outputOpus) }

        val activeFactory = synchronized(nativeLifecycleLock) { factory }
            ?: throw IllegalStateException("libVLC factory is unavailable")
        val encoder = activeFactory.mediaPlayers().newMediaPlayer()
        val finished = CompletableDeferred<Boolean>()
        val eventListener = object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) {
                finished.complete(true)
            }

            override fun error(mediaPlayer: MediaPlayer) {
                finished.complete(false)
            }
        }
        encoder.events().addMediaPlayerEventListener(eventListener)

        try {
            val destination = outputOpus.toAbsolutePath().normalize().toString().replace('\\', '/')
            val transcodeOption =
                ":sout=#transcode{vcodec=none,acodec=opus,ab=$bitrateKbps,channels=$channels,samplerate=48000}:" +
                    "std{access=file,mux=ogg,dst='$destination'}"
            val started = encoder.media().play(
                sourceWav.toUri().toASCIIString(),
                transcodeOption,
                ":no-video",
            )
            if (!started) throw IllegalStateException("libVLC rejected the generated stem for Opus compression")

            val completed = withTimeoutOrNull(STEM_CACHE_ENCODE_TIMEOUT_MS) { finished.await() } == true
            if (!completed || !isUsableOggOpus(outputOpus)) {
                runCatching { Files.deleteIfExists(outputOpus) }
                throw IllegalStateException("libVLC could not compress the generated stem to Opus")
            }
            outputOpus
        } finally {
            runCatching { encoder.events().removeMediaPlayerEventListener(eventListener) }
            runCatching { encoder.controls().stop() }
            runCatching { encoder.release() }
        }
    }

    /**
     * Convert the worker's temporary stereo WAVs into persistent Opus cache files.
     *
     * Do not combine arbitrary stems into a standard 8-channel Opus stream: surround channel
     * mapping gives those channels speaker semantics (including LFE filtering), which can alter or
     * permute stem audio. The native renderer still decodes these files onto one shared sample
     * clock, so separate storage files do not imply separate playback clocks.
     */
    suspend fun compressGeneratedStemSetForCache(directory: Path): List<Path> = withContext(Dispatchers.IO) {
        val pending = STEM_CACHE_SOURCE_NAMES.map { name ->
            val wav = directory.resolve("$name.wav")
            require(Files.isRegularFile(wav)) { "Generated stem is missing: $wav" }
            StemCachePendingFile(
                wav = wav,
                partial = directory.resolve(".$name.opus.part"),
                target = directory.resolve("$name.opus"),
            )
        }
        try {
            // A single libVLC encoder has startup and drain overhead comparable to the actual
            // Opus work. Keep two independent stereo streams in flight: this materially reduces
            // cache time without the six-player memory/CPU spike that an unbounded fan-out causes.
            val permits = Semaphore(STEM_CACHE_ENCODE_PARALLELISM)
            coroutineScope {
                pending.map { file ->
                    async(Dispatchers.IO) {
                        permits.acquire()
                        try {
                            runCatching { Files.deleteIfExists(file.partial) }
                            compressStemForCache(file.wav, file.partial)
                        } finally {
                            permits.release()
                        }
                    }
                }.awaitAll()
            }

            pending.forEach { file ->
                try {
                    Files.move(
                        file.partial,
                        file.target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(file.partial, file.target, StandardCopyOption.REPLACE_EXISTING)
                }
                check(isUsableOggOpus(file.target)) { "Compressed stem cache is invalid: ${file.target}" }
            }

            pending.forEach { file -> Files.deleteIfExists(file.wav) }
            pending.map(StemCachePendingFile::target)
        } catch (error: Throwable) {
            pending.forEach { file -> runCatching { Files.deleteIfExists(file.partial) } }
            throw error
        }
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
                commandMutex.withLock {
                    if (!closed.get() && activeGeneration == generation) {
                        // Read the position while the lock is held. Reading it before the lock
                        // lets a seek that lands in between publish the pre-seek position again,
                        // which makes the progress jump back to where the track was before.
                        val position = latestPositionMs.get()
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
            mediaGuard.clearPendingSeek()
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
            // The user took control of the position, so every terminal event that is still in
            // flight belongs to an older attempt and must not reload the track.
            mediaGuard.noteUserSeek(safePosition)
            // An explicit seek is the user taking over, so the automatic retry budget of the
            // current media is restored. Without this a recovered track would fail on the next
            // premature end of stream instead of getting its single retry.
            prematureFinishRetriedMediaId = null
            if (!activeMediaAlreadyStartedLocked()) {
                // The media is still resolving or loading, and the load that is already running
                // restores its own start position once it opens. Remember the requested position
                // so that load starts there instead of at the stale start position.
                mediaGuard.queuePendingSeek(generation, safePosition)
            }
            _state.update {
                it.copy(
                    positionMs = safePosition,
                    bufferedPositionMs = maxOf(it.bufferedPositionMs, safePosition),
                )
            }
            player?.controls()?.setTime(safePosition)
            if (kotlin.math.abs(pitchSemitones) >= 0.001f) pitchNeedsApply = true
        }
    }

    /** True when the media of the active generation already reached the playing state. */
    private fun activeMediaAlreadyStartedLocked(): Boolean {
        val active = activeGeneration ?: return false
        return active == generation && mediaGuard.hasStarted(active)
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

    /**
     * Mute only libVLC's renderer while preserving the transport and user volume state. Stem
     * playback uses this to let VLC continue driving queue/seek/SMTC state while the native stem
     * renderer owns the audible output.
     */
    suspend fun setExternalAudioRendererActive(active: Boolean) {
        checkOpen()
        commandMutex.withLock {
            externalAudioRendererActive = active
            if (active) cancelCrossfadeLocked(resetAttempt = true)
            outputGain.setExternalRendererActive(active)
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
            if (kotlin.math.abs(pitchSemitones) >= 0.001f) pitchNeedsApply = true
        }
    }

    override suspend fun setPitch(pitchSemitones: Float) {
        checkOpen()
        val safePitch = pitchSemitones.takeIf(Float::isFinite)?.coerceIn(MIN_PITCH, MAX_PITCH) ?: 0f
        commandMutex.withLock {
            if (kotlin.math.abs(this.pitchSemitones - safePitch) < 0.001f) return@withLock
            this.pitchSemitones = safePitch
            _state.update { it.copy(pitchSemitones = safePitch) }
            val activeApplied = player?.let { VlcPitchBridge.applyToActiveAudioOutput(it, safePitch) } == true
            secondaryCrossfadePlayer?.let { VlcPitchBridge.applyToActiveAudioOutput(it, safePitch) }
            pitchNeedsApply = !activeApplied
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
                // A new media item can recreate libVLC's audio-output object. Re-apply the
                // persisted pitch when its playing callback fires, even when the previous item
                // already applied the same value.
                pitchNeedsApply = true
                outputGain.setSource(resolved)
                httpRecoveryGeneration.set(NO_RECOVERY_GENERATION)
                // A user seek that arrived while this load was still resolving wins over the
                // start position the load was created with.
                val startPositionMs = mediaGuard.pendingSeekFor(request.generation)
                    ?: request.startPositionMs
                mediaGuard.queuePendingSeek(request.generation, startPositionMs)
                latestPositionMs.set(startPositionMs)
                val callbackMedia =
                    if (applicationHttpTransportPredicate(resolved)) {
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
                    pitchNeedsApply = true
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
            override fun opening(mediaPlayer: MediaPlayer) = dispatch { withActivePlayerLocked(mediaPlayer) { _state.update { it.copy(status = PlaybackStatus.LOADING) } } }

            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = dispatch {
                withActivePlayerLocked(mediaPlayer) {
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

            override fun playing(mediaPlayer: MediaPlayer) {
                // Note the start while the callback arrives: libVLC delivers `playing` before
                // `finished` on the same event thread, so the end-of-stream handler must already
                // know that this media started.
                if (!closed.get() && player === mediaPlayer) {
                    activeGeneration?.let { mediaGuard.noteMediaStarted(it) }
                }
                dispatch {
                    withActivePlayerLocked(mediaPlayer) {
                        if (pitchNeedsApply && VlcPitchBridge.applyToActiveAudioOutput(mediaPlayer, pitchSemitones)) {
                            pitchNeedsApply = false
                        }
                        cancelLoadWatchdog()
                        mediaGuard.consumePendingSeek(generation)?.takeIf { it > 0L }?.let {
                            // The engine applies the seek asynchronously, so arm the
                            // acknowledgement window before the native seek is issued.
                            mediaGuard.armSeekAcknowledgement(it)
                            mediaPlayer.controls().setTime(it)
                            latestPositionMs.set(it)
                            if (kotlin.math.abs(pitchSemitones) >= 0.001f) pitchNeedsApply = true
                        }
                        queue.current?.metadata?.id?.let { mediaId ->
                            if (recoveryAttemptedMediaId == mediaId) recoveryAttemptedMediaId = null
                        }
                        _state.update { it.copy(status = PlaybackStatus.READY, error = null) }
                    }
                }
            }

            override fun paused(mediaPlayer: MediaPlayer) = dispatch {
                withActivePlayerLocked(mediaPlayer) {
                    cancelLoadWatchdog()
                    _state.update { it.copy(status = PlaybackStatus.READY) }
                }
            }

            override fun stopped(mediaPlayer: MediaPlayer) = dispatch {
                commandMutex.withLock {
                    if (suppressStoppedEvents > 0) {
                        suppressStoppedEvents--
                    } else if (mediaGuard.consumeEndOfStreamArrival(activeGeneration)) {
                        // libVLC emits stopped immediately after the natural finished event. The
                        // end-of-stream handler owns that transition, so the trailing stopped
                        // event must not clear playWhenReady and pause the next track.
                        logger.fine(
                            "Ignoring the stopped event that follows the natural end of the stream",
                        )
                    } else if (
                        isActiveEventLocked(mediaPlayer) &&
                        crossfadeSuppressFinishedRunId != null
                    ) {
                        // VLC emits stopped immediately after finished at natural EOF. During a
                        // crossfade that finished event is intentionally deferred so the handoff
                        // can win the race. Do not let the trailing stopped event clear
                        // playWhenReady, otherwise the deferred auto-advance loads the next track
                        // and immediately pauses it.
                        logger.fine(
                            "Ignoring stopped event while crossfade owns the active EOF transition for " +
                                (queue.current?.logLabel() ?: "unknown item"),
                        )
                    } else if (isActiveEventLocked(mediaPlayer) && _state.value.status !in setOf(
                            PlaybackStatus.RESOLVING,
                            PlaybackStatus.LOADING,
                            PlaybackStatus.IDLE,
                        )
                    ) {
                        _state.update { it.copy(status = PlaybackStatus.IDLE, playWhenReady = false) }
                    }
                }
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                // Capture the media identity while the callback arrives. libVLC posts this on its
                // own event thread and the controller handles it on a coroutine, so the media
                // that produced the event can be replaced before it is processed.
                val identity = mediaGuard.captureIdentity(activeGeneration)
                identity?.let { mediaGuard.noteEndOfStreamArrived(it.generation) }
                dispatch {
                    val request = commandMutex.withLock {
                        if (identity == null) return@withLock null
                        if (!isActiveEventLocked(mediaPlayer)) return@withLock null
                        if (!mediaGuard.describesActiveMedia(identity, generation, activeGeneration)) {
                            logger.fine("Ignoring an obsolete libVLC finished event of a replaced media item")
                            return@withLock null
                        }
                        if (mediaGuard.supersededByUserSeek(identity)) {
                            // The user seeked after this event was raised. The seek is the
                            // corrective action, so the event must not reload the track.
                            logger.fine("Ignoring a libVLC finished event that a newer user seek superseded")
                            return@withLock null
                        }
                        if (!mediaGuard.hasStarted(identity.generation)) {
                            // A media that never started playing cannot have reached its end, so
                            // this event belongs to the media that was replaced.
                            logger.fine("Ignoring a libVLC finished event of media that never started playing")
                            return@withLock null
                        }
                        // Once the incoming player is audible, the crossfade coroutine owns the
                        // transition. Do not throw this EOF away though: if the crossfade misses its
                        // handoff deadline, this is the only natural-end event VLC will emit.
                        crossfadeSuppressFinishedRunId?.let { runId ->
                            deferredFinishedRunId = runId
                            deferredFinishedGeneration = generation
                            return@withLock null
                        }
                        finishActivePlaybackLocked()
                    }
                    request?.let(::enqueueLoad)
                }
            }

            override fun error(mediaPlayer: MediaPlayer) {
                val identity = mediaGuard.captureIdentity(activeGeneration)
                dispatch {
                    commandMutex.withLock {
                        if (identity == null) return@withLock
                        if (!isActiveEventLocked(mediaPlayer)) return@withLock
                        if (!mediaGuard.describesActiveMedia(identity, generation, activeGeneration)) {
                            logger.fine("Ignoring an obsolete libVLC error of a replaced media item")
                            return@withLock
                        }
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
                if (!closed.get() && player === mediaPlayer) {
                    // A seek is applied asynchronously, so the engine can report the pre-seek
                    // position once more. Ignoring that stale value keeps the reported position
                    // on the position the user asked for instead of jumping back.
                    val position = mediaGuard.acceptNativePosition(newTime.coerceAtLeast(0L)) ?: return
                    latestPositionMs.set(position)
                    // The first `playing` callback can arrive a little before VLC exposes the new
                    // track's aout. Keep the pitch armed and retry on the next time tick instead of
                    // silently leaving every later queue item at its original pitch.
                    if (pitchNeedsApply) {
                        dispatch {
                            withActivePlayerLocked(mediaPlayer) {
                                if (
                                    pitchNeedsApply &&
                                    VlcPitchBridge.applyToActiveAudioOutput(mediaPlayer, pitchSemitones)
                                ) {
                                    pitchNeedsApply = false
                                }
                            }
                        }
                    }
                }
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                // Record the decoded length while the callback arrives so a fast end of stream
                // cannot be mistaken for a truncated stream because the callback was handled late.
                if (!closed.get() && player === mediaPlayer && newLength > 0L) {
                    activeGeneration?.let { mediaGuard.recordDecodedDuration(it, newLength) }
                }
                dispatch {
                    withActivePlayerLocked(mediaPlayer) {
                        _state.update {
                            it.copy(
                                durationMs = newLength.coerceAtLeast(0L),
                                positionMs = it.positionMs.coerceAtMostPositive(newLength),
                                bufferedPositionMs = it.bufferedPositionMs.coerceAtMostPositive(newLength),
                            )
                        }
                    }
                }
            }

            override fun volumeChanged(mediaPlayer: MediaPlayer, volume: Float) {
                // ArchiveTune owns the normalized volume state. libVLC reports backend-specific
                // values here, including transient teardown values, so do not feed them back.
            }
        }

    private fun finishActivePlaybackLocked(): LoadRequest? {
        cancelLoadWatchdog()
        val previous = queue.current
        val snapshot = _state.value
        // Ask the engine for the freshest position. The published snapshot is refreshed by the
        // position ticker and can still lag behind the real end of the media.
        val nativeTimeMs = runCatching { player?.status()?.time() ?: 0L }
            .getOrDefault(0L)
            .coerceAtLeast(0L)
        val positionMs = maxOf(nativeTimeMs, snapshot.positionMs, latestPositionMs.get())
        // libVLC knows the length of the media it actually decoded. The catalog duration hint
        // describes the library entry, and an approximate or simply wrong hint turns a normal end
        // of stream into a bogus "stream ended unexpectedly" failure. Prefer the decoded length
        // and only fall back to the snapshot and then to the hint when the engine never reported
        // a length. A stream that really ends before its decoded length is still reported as a
        // premature end of stream.
        val decodedDurationMs = mediaGuard.decodedDurationMs(generation)
            ?: runCatching { player?.status()?.length() ?: 0L }
                .getOrDefault(0L)
                .coerceAtLeast(0L)
                .takeIf { it > 0L }
        val expectedDurationMs = decodedDurationMs
            ?: snapshot.durationMs.takeIf { it > 0L }
            ?: previous?.metadata?.durationHintMs?.takeIf { it > 0L }
        val durationSource = when {
            decodedDurationMs != null -> "decoded"
            snapshot.durationMs > 0L -> "snapshot"
            previous?.metadata?.durationHintMs?.let { it > 0L } == true -> "catalog-hint"
            else -> "unknown"
        }
        val endedPrematurely =
            expectedDurationMs != null &&
                positionMs + PREMATURE_FINISH_TOLERANCE_MS < expectedDurationMs
        if (endedPrematurely && previous != null) {
            logger.warning(
                "Ignoring premature libVLC finished event for ${previous.logLabel()}: " +
                    "positionMs=$positionMs nativeTimeMs=$nativeTimeMs " +
                    "latestPositionMs=${latestPositionMs.get()} " +
                    "decodedDurationMs=${decodedDurationMs ?: "unknown"} " +
                    "snapshotDurationMs=${snapshot.durationMs} " +
                    "hintDurationMs=${previous.metadata.durationHintMs} " +
                    "expectedDurationMs=$expectedDurationMs durationSource=$durationSource",
            )
            if (prematureFinishRetriedMediaId != previous.metadata.id) {
                prematureFinishRetriedMediaId = previous.metadata.id
                return prepareReloadLocked(TransitionReason.RETRY, positionMs)
            }
            stopActiveFileLocked()
            failLocked(
                PlaybackFailure(
                    message = "The audio stream ended unexpectedly; retry the track",
                    kind = PlaybackFailure.Kind.NETWORK,
                ),
            )
            return null
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
            return null
        }
        // A natural end of stream must not stop the queue. libVLC emits a trailing stopped event
        // that can clear playWhenReady before this handler runs, so restore the intent here.
        _state.update { it.copy(playWhenReady = true) }
        return prepareSelectionLocked(previous, selected, TransitionReason.AUTO_ADVANCE)
    }

    private suspend fun maybeStartCrossfade() {
        commandMutex.withLock {
            val config = crossfadeConfiguration
            val snapshot = _state.value
            if (
                externalAudioRendererActive ||
                !config.enabled ||
                config.durationMs < MIN_CROSSFADE_DURATION_MS ||
                !snapshot.isPlaying ||
                snapshot.durationMs <= 0L ||
                snapshot.currentItem?.metadata?.isPodcast == true ||
                activeGeneration != generation ||
                activeCrossfadeRunId != null
            ) {
                return@withLock
            }

            val target = crossfadeTargetLocked(config) ?: return@withLock
            if (target.item.metadata.isPodcast) return@withLock
            if (crossfadeAttemptedOccurrenceId == target.outgoingOccurrenceId) return@withLock

            val remainingMs =
                (snapshot.durationMs - currentPlaybackPositionLocked())
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

            // Resolve the URL early, but do not open a second libVLC audio pipeline early. On
            // Windows two players opening/pausing several seconds before the fade can starve or
            // glitch the primary DirectSound output. Keep the secondary player startup close to
            // the audible transition instead.
            if (!waitForCrossfadePlayerStart(runId, outgoingGeneration, target, config)) return

            ensurePlayer()
            if (!crossfadeStillValid(runId, outgoingGeneration, target)) return

            // Two MediaPlayers are enough. They may share one libVLC instance because ArchiveTune
            // now fades through each player's software aout gain rather than the Windows audio
            // session volume that caused the players to fight each other.
            val activeFactory = synchronized(nativeLifecycleLock) { factory } ?: return
            val secondary = activeFactory.mediaPlayers().newMediaPlayer()
            val secondaryEqualizer = VlcjEqualizerBackend(activeFactory, secondary)
            val secondaryOutputVolume = OutputVolumeBackend { percent -> secondary.audio().setVolume(percent) }
            val promotedHttpRecoveryGeneration = AtomicLong(NO_RECOVERY_GENERATION)
            val ready = CompletableDeferred<Boolean>()
            val readinessListener = object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
                    VlcPitchBridge.applyToActiveAudioOutput(mediaPlayer, pitchSemitones)
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
            var registered = false
            var adopted = false
            try {
                vlcjAudioEffects.applyCurrentTo(secondaryEqualizer)
                VlcjAudioOutputBackend(secondary).selectOutputDevice(audioOutputSelection.state.value.selectedDeviceId)
                secondary.controls().setRate(_state.value.speed)
                secondary.audio().setVolume(0)

                callbackMedia = if (applicationHttpTransportPredicate(resolved)) {
                    rangeHttpTransport.media(
                        url = resolved.uri,
                        headers = resolved.headers,
                        onHttpFailure = { httpStatus ->
                            val promotedGeneration = promotedHttpRecoveryGeneration.get()
                            if (promotedGeneration != NO_RECOVERY_GENERATION) {
                                scheduleStreamHttpRecovery(
                                    promotedGeneration,
                                    httpStatus,
                                    "promoted crossfade application HTTP transport",
                                )
                            } else {
                                // Before handoff this player is only speculative. A failed range
                                // request should abort the crossfade attempt, not disturb the active
                                // outgoing track.
                                ready.complete(false)
                            }
                        },
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
                        registered = true
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
                        }
                    }
                }
                if (!started) return

                val becameReady = withTimeoutOrNull(CROSSFADE_SECONDARY_READY_TIMEOUT_MS) { ready.await() } == true
                runCatching { secondary.events().removeMediaPlayerEventListener(readinessListener) }
                if (!becameReady || !crossfadeStillValid(runId, outgoingGeneration, target)) return

                // Keep the incoming decoder running at zero software gain. Pausing a prepared VLC
                // input and resuming it at the fade boundary makes VLC 3's clock insert corrective
                // silence on Windows ("playback way too early"). Running muted keeps its clock hot
                // without touching the shared Windows audio-session volume.
                val fadeDurationMs =
                    waitForCrossfadeFadeStart(
                        runId,
                        outgoingGeneration,
                        target,
                        config,
                        secondary,
                    )
                        ?: return
                logger.fine("Starting ${fadeDurationMs}ms crossfade to ${target.item.logLabel()}")
                val fadeStartNanos = System.nanoTime()
                while (true) {
                    val elapsedMs = (System.nanoTime() - fadeStartNanos) / 1_000_000L
                    val progress = (elapsedMs.toFloat() / fadeDurationMs.toFloat()).coerceIn(0f, 1f)
                    val gains = equalPowerGains(progress)
                    val valid = commandMutex.withLock {
                        if (!crossfadeStillValidLocked(runId, outgoingGeneration, target)) {
                            false
                        } else {
                            outputGain.setFadeMultiplier(gains.outgoing)
                            runCatching {
                                secondary.audio().setVolume(
                                    outputGain.volumePercentForSource(resolved, gains.incoming),
                                )
                            }
                            true
                        }
                    }
                    if (!valid) return
                    if (progress >= 1f) break
                    delay(CROSSFADE_FRAME_MS)
                }

                val handoffPositionMs = runCatching { secondary.status().time() }.getOrDefault(0L).coerceAtLeast(0L)
                val promoted = commandMutex.withLock {
                    if (!crossfadeStillValidLocked(runId, outgoingGeneration, target)) return@withLock false
                    val previous = queue.current
                    val selected = queue.advance(automatic = true) ?: return@withLock false
                    val currentOccurrence = queue.entries().firstOrNull { it.isCurrent }
                    if (currentOccurrence?.id != target.incomingOccurrenceId) return@withLock false

                    advanceLoadGeneration(cancelCrossfade = false)
                    publishQueueLocked()
                    latestPositionMs.set(handoffPositionMs)
                    val promotedDurationMs =
                        selected.item.metadata.durationHintMs?.coerceAtLeast(0L)
                            ?.takeIf { it > 0L }
                            ?: runCatching { secondary.status().length() }.getOrDefault(0L).coerceAtLeast(0L)
                    _state.update {
                        it.copy(
                            status = PlaybackStatus.READY,
                            playWhenReady = true,
                            positionMs = handoffPositionMs,
                            bufferedPositionMs = handoffPositionMs,
                            durationMs = promotedDurationMs,
                            error = null,
                        )
                    }
                    transition(
                        previous,
                        selected.item,
                        if (selected.repeated) TransitionReason.REPEAT else TransitionReason.AUTO_ADVANCE,
                    )
                    promoteCrossfadeSecondaryLocked(
                        secondary = secondary,
                        equalizer = secondaryEqualizer,
                        outputVolume = secondaryOutputVolume,
                        callbackMedia = callbackMedia,
                        resolved = resolved,
                    )
                    promotedHttpRecoveryGeneration.set(generation)
                    adopted = true
                    true
                }
                if (!promoted) return
            } finally {
                runCatching { secondary.events().removeMediaPlayerEventListener(readinessListener) }
                if (!adopted) {
                    val ownsSecondary = commandMutex.withLock {
                        when {
                            !registered -> true
                            secondaryCrossfadePlayer === secondary -> {
                                secondaryCrossfadePlayer = null
                                secondaryCrossfadeCallbackMedia = null
                                true
                            }
                            else -> false
                        }
                    }
                    if (ownsSecondary) {
                        runCatching { secondary.controls().stop() }
                        runCatching { secondary.media().reset() }
                        runCatching { callbackMedia?.close() }
                        runCatching { secondary.release() }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.log(Level.WARNING, "Crossfade failed for ${target.item.logLabel()}", error)
        } finally {
            val fallbackRequest = commandMutex.withLock {
                if (activeCrossfadeRunId == runId) {
                    val shouldReplayFinished =
                        deferredFinishedRunId == runId &&
                            deferredFinishedGeneration == outgoingGeneration &&
                            generation == outgoingGeneration &&
                            activeGeneration == outgoingGeneration
                    outputGain.setFadeMultiplier(1f)
                    releaseSecondaryCrossfadePlayerLocked()
                    crossfadeSuppressFinishedRunId = null
                    deferredFinishedRunId = null
                    deferredFinishedGeneration = null
                    activeCrossfadeRunId = null
                    crossfadeJob = null
                    if (shouldReplayFinished) finishActivePlaybackLocked() else null
                } else {
                    null
                }
            }
            fallbackRequest?.let(::enqueueLoad)
        }
    }

    private suspend fun waitForCrossfadeFadeStart(
        runId: Long,
        outgoingGeneration: Long,
        target: CrossfadeTarget,
        config: DesktopCrossfadeConfiguration,
        secondary: MediaPlayer,
    ): Long? {
        while (true) {
            var pollMs = CROSSFADE_FADE_START_POLL_MS
            val fadeDurationMs =
                commandMutex.withLock {
                    if (!crossfadeStillValidLocked(runId, outgoingGeneration, target)) {
                        return@withLock -1L
                    }
                    val snapshot = _state.value
                    val remainingMs =
                        (snapshot.durationMs - currentPlaybackPositionLocked())
                            .coerceAtLeast(0L)
                    val availableFadeMs = remainingMs - CROSSFADE_END_GUARD_MS
                    when {
                        availableFadeMs > config.durationMs -> {
                            pollMs =
                                (availableFadeMs - config.durationMs)
                                    .coerceAtMost(CROSSFADE_FADE_START_POLL_MS)
                                    .coerceAtLeast(1L)
                            0L
                        }

                        availableFadeMs >= MIN_CROSSFADE_DURATION_MS -> {
                            runCatching { secondary.audio().setVolume(0) }
                            crossfadeSuppressFinishedRunId = runId
                            availableFadeMs
                        }

                        else -> -1L
                    }
                }
            if (fadeDurationMs < 0L) return null
            if (fadeDurationMs > 0L) return fadeDurationMs
            delay(pollMs)
        }
    }

    private suspend fun waitForCrossfadePlayerStart(
        runId: Long,
        outgoingGeneration: Long,
        target: CrossfadeTarget,
        config: DesktopCrossfadeConfiguration,
    ): Boolean {
        while (true) {
            var pollMs = CROSSFADE_FADE_START_POLL_MS
            val shouldStart = commandMutex.withLock {
                if (!crossfadeStillValidLocked(runId, outgoingGeneration, target)) return@withLock null
                val snapshot = _state.value
                val remainingMs =
                    (snapshot.durationMs - currentPlaybackPositionLocked())
                        .coerceAtLeast(0L)
                val startWindowMs =
                    config.durationMs + CROSSFADE_END_GUARD_MS
                if (remainingMs <= startWindowMs) true
                else {
                    pollMs = (remainingMs - startWindowMs)
                        .coerceAtMost(CROSSFADE_FADE_START_POLL_MS)
                        .coerceAtLeast(1L)
                    false
                }
            } ?: return false
            if (shouldStart) return true
            delay(pollMs)
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

    private fun currentPlaybackPositionLocked(): Long =
        maxOf(
            _state.value.positionMs,
            latestPositionMs.get(),
            runCatching { player?.status()?.time() ?: 0L }.getOrDefault(0L),
        )

    private fun cancelCrossfadeLocked(resetAttempt: Boolean = false) {
        crossfadeRunSequence++
        activeCrossfadeRunId = null
        crossfadeSuppressFinishedRunId = null
        deferredFinishedRunId = null
        deferredFinishedGeneration = null
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

    private fun deferCrossfadePlayerRetirement(
        player: MediaPlayer,
        callbackMedia: VlcjRangeCallbackMedia?,
    ) {
        val retired = RetiredCrossfadePlayer(player, callbackMedia)
        synchronized(nativeLifecycleLock) { retiredCrossfadePlayers += retired }
        scope.launch(CoroutineName("ArchiveTuneDesktopCrossfadeRetirement")) {
            delay(CROSSFADE_RETIRE_DELAY_MS)
            synchronized(nativeLifecycleLock) {
                if (retiredCrossfadePlayers.remove(retired)) {
                    releaseRetiredCrossfadePlayerLocked(retired)
                }
            }
        }
    }

    private fun releaseRetiredCrossfadePlayerLocked(retired: RetiredCrossfadePlayer) {
        runCatching { retired.player.controls().stop() }
        runCatching { retired.player.media().reset() }
        runCatching { retired.callbackMedia?.close() }
        runCatching { retired.player.release() }
    }

    private fun releaseRetiredCrossfadePlayersLocked() {
        val retired = retiredCrossfadePlayers.toList()
        retiredCrossfadePlayers.clear()
        retired.forEach(::releaseRetiredCrossfadePlayerLocked)
    }

    /** Promote the already-audible secondary player instead of reopening/seeking the same media. */
    private fun promoteCrossfadeSecondaryLocked(
        secondary: MediaPlayer,
        equalizer: EqualizerBackend,
        outputVolume: OutputVolumeBackend,
        callbackMedia: VlcjRangeCallbackMedia?,
        resolved: ResolvedPlaybackSource,
    ) {
        val outgoingPlayer = player
        val outgoingCallbackMedia = activeCallbackMedia
        listener?.let { eventListener ->
            runCatching { outgoingPlayer?.events()?.removeMediaPlayerEventListener(eventListener) }
            runCatching { secondary.events().addMediaPlayerEventListener(eventListener) }
        }

        player = secondary
        secondaryCrossfadePlayer = null
        secondaryCrossfadeCallbackMedia = null
        activeCallbackMedia = callbackMedia
        activeResolvedSource = resolved
        activeGeneration = generation
        // The promoted player is already audible, so its media counts as started even though it
        // never emitted a playing callback for this controller.
        mediaGuard.noteMediaStarted(generation)
        // The secondary player's initial `playing` callback is only a best-effort pitch apply.
        // Re-assert it at promotion, then let the active time-tick retry path finish the job if
        // VLC has not exposed the promoted aout yet.
        pitchNeedsApply = !VlcPitchBridge.applyToActiveAudioOutput(secondary, pitchSemitones)

        // Detach the outgoing backend while the fade multiplier is still zero, then attach the
        // promoted player at full gain. This avoids a mute blip at the exact handoff boundary.
        equalizerBackend?.let(vlcjAudioEffects::detach)
        outputVolumeBackend?.let(outputGain::detach)
        outputGain.setFadeMultiplier(1f)
        outputGain.setSource(resolved)

        equalizerBackend = equalizer
        vlcjAudioEffects.adopt(equalizer)
        outputVolumeBackend = outputVolume
        outputGain.attach(outputVolume)
        audioOutputSelection.adoptBackend(VlcjAudioOutputBackend(secondary))
        crossfadeSuppressFinishedRunId = null

        if (outgoingPlayer != null) {
            // The fade finishes just before the outgoing media's natural EOF. Freeze the now-muted
            // player so libVLC cannot tear down its audio pipeline at EOF while the promoted player
            // is already audible; the deferred retirement performs the native cleanup later.
            runCatching { outgoingPlayer.controls().pause() }
            deferCrossfadePlayerRetirement(
                player = outgoingPlayer,
                callbackMedia = outgoingCallbackMedia,
            )
        }
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
            val seekRevision: Long,
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
                seekRevision = mediaGuard.seekRevision(),
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

            // The recovery callback suspended while the transport was re-resolved. The user can
            // seek during that window, and that newer position must win over the position that
            // was current when the failure was detected.
            val resumePositionMs = if (mediaGuard.seekRevision() != context.seekRevision) {
                mediaGuard.lastUserSeekPositionMs()
            } else {
                currentPlaybackPositionLocked()
            }

            logger.info(
                "Retrying ${context.item.logLabel()} from ${resumePositionMs}ms after HTTP $httpStatus " +
                    "using a different YouTube stream client (failure detected at ${context.positionMs}ms)",
            )
            val request = prepareReloadLocked(TransitionReason.RETRY, resumePositionMs)
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

    /**
     * Raise the same stream failure the native HTTP transport raises for a retryable status.
     *
     * Desktop tests use this to drive the recovery path without a signed stream, so the recovery
     * behaviour can be verified deterministically.
     */
    internal fun simulateStreamHttpFailure(httpStatus: Int) {
        val failedGeneration = activeGeneration ?: return
        scheduleStreamHttpRecovery(failedGeneration, httpStatus, "simulated stream failure")
    }

    private fun dispatch(block: suspend () -> Unit) {
        if (!closed.get()) scope.launch { runCatching { block() } }
    }

    private suspend fun withActivePlayerLocked(mediaPlayer: MediaPlayer, block: () -> Unit) {
        commandMutex.withLock {
            if (isActiveEventLocked(mediaPlayer)) block()
        }
    }

    private fun isActiveEventLocked(mediaPlayer: MediaPlayer): Boolean =
        !closed.get() && player === mediaPlayer && activeGeneration != null && activeGeneration == generation

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
        // A queued start position belongs to the load generation that requested it. The new
        // generation owns its own start position now.
        mediaGuard.clearPendingSeek()
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
        // The decoded length, the started flag, and the pending end-of-stream announcement belong
        // to the media that is torn down here. The queued start position does not: the load that
        // is already running may still have to apply the position the user asked for.
        mediaGuard.resetMedia()
        cancelLoadWatchdog()
        player?.let { activePlayer ->
            if (hadActiveMedia) suppressStoppedEvents++
            runCatching { activePlayer.controls().stop() }
            runCatching { activePlayer.media().reset() }
        }
        runCatching { callbackMedia?.close() }
    }

    private fun releasePlayerForPitchReconfigureLocked() = synchronized(nativeLifecycleLock) {
        crossfadeJob?.cancel()
        crossfadeJob = null
        activeCrossfadeRunId = null
        crossfadeSuppressFinishedRunId = null
        releaseSecondaryCrossfadePlayerLocked()
        releaseRetiredCrossfadePlayersLocked()
        cancelLoadWatchdog()
        positionTicker?.cancel()
        positionTicker = null
        listener?.let { player?.events()?.removeMediaPlayerEventListener(it) }
        nativeLogListener?.let { eventListener -> runCatching { nativeLog?.removeLogListener(eventListener) } }
        runCatching { player?.controls()?.stop() }
        runCatching { player?.media()?.reset() }
        runCatching { activeCallbackMedia?.close() }
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
        outputVolumeBackend = null
        activeCallbackMedia = null
        factory = null
        audioOutputSelection.replaceBackend(null)
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
            releaseRetiredCrossfadePlayersLocked()
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
        const val MIN_PITCH = -12f
        const val MAX_PITCH = 12f
        const val MIN_URL_LIFETIME_MS = 5_000L
        const val POSITION_POLL_INTERVAL_MS = 50L
        const val STEM_DECODE_TIMEOUT_SLACK_MS = 60_000L
        const val MIN_STEM_DECODE_TIMEOUT_MS = 120_000L
        const val STEM_WAV_HEADER_BYTES = 44L
        const val STEM_OPUS_BITRATE_KBPS = 96
        const val STEM_CACHE_ENCODE_TIMEOUT_MS = 120_000L
        const val STEM_CACHE_ENCODE_PARALLELISM = 2
        const val LOAD_WATCHDOG_TIMEOUT_MS = 30_000L
        const val PREMATURE_FINISH_TOLERANCE_MS = 5_000L
        const val MIN_CROSSFADE_DURATION_MS = 250L
        // Source resolution can involve a network round-trip; a one-second head start routinely
        // misses the fade window on YouTube streams. Keep enough lead time to resolve and buffer
        // the secondary player without delaying the audible transition.
        const val CROSSFADE_PREPARE_AHEAD_MS = 5_000L
        const val CROSSFADE_END_GUARD_MS = 150L
        // libVLC volume is integer-percent based anyway. Updating it ~20 times/sec is smooth enough
        // for a fade and avoids hammering two native audio-output control paths for no audible gain.
        const val CROSSFADE_FRAME_MS = 50L
        const val CROSSFADE_SECONDARY_READY_TIMEOUT_MS = 3_000L
        const val CROSSFADE_FADE_START_POLL_MS = 50L
        const val CROSSFADE_RETIRE_DELAY_MS = 750L
        const val NO_RECOVERY_GENERATION = -1L
        val STEM_CACHE_SOURCE_NAMES = listOf("drums", "bass", "other", "vocals", "guitar", "piano")
        val RETRYABLE_STREAM_RESPONSE_CODES = setOf(403, 404, 410, 416)
    }
}

private data class StemCachePendingFile(
    val wav: Path,
    val partial: Path,
    val target: Path,
)

private fun isUsableOggOpus(path: Path): Boolean {
    if (!Files.isRegularFile(path)) return false
    if (runCatching { Files.size(path) }.getOrDefault(0L) <= 64L) return false
    return runCatching {
        Files.newInputStream(path).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == magic.size && magic.contentEquals(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()))
        }
    }.getOrDefault(false)
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
