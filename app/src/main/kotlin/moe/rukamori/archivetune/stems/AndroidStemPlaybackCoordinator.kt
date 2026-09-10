/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.modelruntime.AndroidOnnxDiagnostics
import moe.rukamori.archivetune.modelruntime.AndroidOnnxNoAcceleratorException

data class AndroidStemPlaybackState(
    val trackId: String? = null,
    val controls: AndroidStemControls = AndroidStemControls(),
    val cacheReady: Boolean = false,
    val rendererActive: Boolean = false,
    val separationActive: Boolean = false,
    val separationProgress: AndroidStemSeparationProgress? = null,
    val providerDiagnostics: AndroidOnnxDiagnostics? = null,
    val message: String? = null,
)

/** Service-lifetime owner for persistent controls and a six-source local stem renderer. */
class AndroidStemPlaybackCoordinator internal constructor(
    context: Context,
    private val primaryPlayer: ExoPlayer,
    private val cache: AndroidStemCache,
    private val mixStore: AndroidStemMixStore,
    private val scope: CoroutineScope,
    audioAttributes: AudioAttributes,
    private val masterVolumeProvider: () -> Float,
    private val onRendererActiveChanged: (Boolean) -> Unit,
    private val castConnectedProvider: () -> Boolean,
    private val sourceResolver: suspend (MediaItem) -> AndroidStemInput? = { item ->
        item.localConfiguration?.uri?.takeIf { uri -> uri.scheme != null }?.let(::AndroidStemInput)
    },
    private val separationCenter: AndroidStemSeparationCenter =
        AndroidStemSeparationCenter(context.applicationContext, cache),
    private val rendererFactory: () -> AndroidStemRenderer = {
        Media3AndroidStemRenderer(context.applicationContext, audioAttributes)
    },
) : Player.Listener, AutoCloseable {
    private val _state = MutableStateFlow(AndroidStemPlaybackState())
    val state = _state.asStateFlow()

    private var renderer: AndroidStemRenderer? = null
    private var activationJob: Job? = null
    private var syncJob: Job? = null
    private var separationJob: Job? = null
    private var separationTrackId: String? = null
    private var separationGeneration = 0L
    private var generation = 0L
    private var castConnected = castConnectedProvider()
    private var closed = false

    val rendererActive: Boolean
        get() = _state.value.rendererActive

    init {
        primaryPlayer.addListener(this)
        reconcileCurrentTrack(autoActivate = true)
    }

    fun controlsForCurrent(): AndroidStemControls = _state.value.controls

    fun setCurrentControls(controls: AndroidStemControls) {
        val identity = currentIdentity() ?: return
        val safe = controls.sanitized()
        mixStore.save(identity.trackId, safe)
        _state.value = _state.value.copy(trackId = identity.trackId, controls = safe)
        if (safe.isEffectiveNeutral()) {
            deactivateRenderer(message = null)
        } else {
            val active = renderer
            if (rendererActive && active != null) {
                active.setEffectiveGains(safe.effectiveGains())
            } else {
                activateCurrentIfPossible()
            }
        }
    }

    fun refreshCurrent() {
        reconcileCurrentTrack(autoActivate = true)
    }

    /** Starts real decode -> accelerated HTDemucs -> six-file cache generation for this track. */
    fun separateCurrent(): Job? {
        if (closed || castConnected || castConnectedProvider()) return null
        val identity = currentIdentity() ?: return null
        val mediaItem = primaryPlayer.currentMediaItem ?: return null
        cache.find(identity.trackId, identity.durationMs)?.let {
            reconcileCurrentTrack(autoActivate = true)
            return null
        }
        separationJob?.cancel()
        val separationEpoch = ++separationGeneration
        separationTrackId = identity.trackId
        val job =
            scope.launch {
                _state.value =
                    _state.value.copy(
                        separationActive = true,
                        separationProgress = AndroidStemSeparationProgress(
                            AndroidStemSeparationStage.MODEL,
                            0f,
                            "Preparing stem separation",
                        ),
                        providerDiagnostics = null,
                        message = "Preparing 6-stem separation",
                    )
                try {
                    val input = sourceResolver(mediaItem) ?: error("Current media source cannot be resolved for stem separation")
                    val result =
                        separationCenter.separate(
                            trackId = identity.trackId,
                            durationMs = identity.durationMs,
                            title = identity.title,
                            input = input,
                        ) { progress ->
                            if (
                                separationGeneration == separationEpoch &&
                                separationTrackId == identity.trackId &&
                                currentIdentity()?.trackId == identity.trackId
                            ) {
                                _state.value =
                                    _state.value.copy(
                                        separationActive = true,
                                        separationProgress = progress,
                                        message = progress.message,
                                    )
                            }
                        }
                    if (separationGeneration == separationEpoch && currentIdentity()?.trackId == identity.trackId) {
                        _state.value =
                            _state.value.copy(
                                cacheReady = true,
                                separationActive = false,
                                separationProgress = null,
                                providerDiagnostics = result.diagnostics,
                                message = "6-stem cache ready via ${result.diagnostics.selectedProvider}",
                            )
                        activateCurrentIfPossible(result.entry)
                    }
                } catch (cancelled: CancellationException) {
                    if (separationGeneration == separationEpoch && separationTrackId == identity.trackId) {
                        _state.value = _state.value.copy(separationActive = false, separationProgress = null)
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    if (separationGeneration == separationEpoch && currentIdentity()?.trackId == identity.trackId) {
                        _state.value =
                            _state.value.copy(
                                separationActive = false,
                                separationProgress = null,
                                providerDiagnostics = (error as? AndroidOnnxNoAcceleratorException)?.diagnostics,
                                message = error.message?.takeIf(String::isNotBlank) ?: "Stem separation failed",
                            )
                    }
                } finally {
                    if (separationGeneration == separationEpoch && separationTrackId == identity.trackId) {
                        separationTrackId = null
                    }
                }
            }
        separationJob = job
        return job
    }

    /** Cancels current decode/inference work without touching an already-installed stem cache. */
    fun cancelSeparation() {
        separationGeneration += 1L
        separationJob?.cancel()
        separationJob = null
        separationTrackId = null
        _state.value =
            _state.value.copy(
                separationActive = false,
                separationProgress = null,
                message = null,
            )
    }

    suspend fun installGeneratedSetForCurrent(generatedDirectory: File): AndroidStemCacheEntry? {
        val identity = currentIdentity() ?: return null
        val entry =
            withContext(Dispatchers.IO) {
                cache.installGeneratedSet(
                    trackId = identity.trackId,
                    durationMs = identity.durationMs,
                    title = identity.title,
                    generatedDirectory = generatedDirectory,
                )
            }
        reconcileCurrentTrack(autoActivate = true)
        return entry
    }

    fun updateMasterVolume(volume: Float) {
        renderer?.setMasterVolume(volume)
    }

    fun onCastConnectionChanged(connected: Boolean) {
        if (castConnected == connected) return
        castConnected = connected
        if (connected) {
            deactivateRenderer(message = "Stem playback is local-only while casting.")
        } else {
            reconcileCurrentTrack(autoActivate = true)
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        reconcileCurrentTrack(autoActivate = true)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        renderer?.let { if (isPlaying) it.play() else it.pause() }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (rendererActive) renderer?.seekTo(primaryPlayer.currentPosition)
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        renderer?.setPlaybackParameters(playbackParameters)
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        renderer?.setAudioSessionId(audioSessionId)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            deactivateRenderer(message = null)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        deactivateRenderer(message = "Stem playback stopped: ${error.message}")
    }

    override fun close() {
        if (closed) return
        closed = true
        primaryPlayer.removeListener(this)
        separationGeneration += 1L
        val activeSeparation = separationJob
        activeSeparation?.cancel()
        separationJob = null
        deactivateRenderer(message = null)
        if (activeSeparation?.isActive == true) {
            activeSeparation.invokeOnCompletion { separationCenter.close() }
        } else {
            separationCenter.close()
        }
    }

    private fun reconcileCurrentTrack(autoActivate: Boolean) {
        if (closed) return
        val identity = currentIdentity()
        if (identity == null) {
            separationGeneration += 1L
            separationJob?.cancel()
            separationJob = null
            separationTrackId = null
            deactivateRenderer(message = null)
            _state.value = AndroidStemPlaybackState()
            return
        }
        if (separationTrackId != null && separationTrackId != identity.trackId) {
            separationGeneration += 1L
            separationJob?.cancel()
            separationJob = null
            separationTrackId = null
        }
        if (_state.value.trackId != identity.trackId) {
            deactivateRenderer(message = null)
        }
        val controls = mixStore.load(identity.trackId)
        val entry = cache.find(identity.trackId, identity.durationMs)
        _state.value =
            AndroidStemPlaybackState(
                trackId = identity.trackId,
                controls = controls,
                cacheReady = entry != null,
                rendererActive = rendererActive && renderer != null,
                separationActive = separationTrackId == identity.trackId && separationJob?.isActive == true,
                separationProgress = if (separationTrackId == identity.trackId) _state.value.separationProgress else null,
                providerDiagnostics = if (_state.value.trackId == identity.trackId) _state.value.providerDiagnostics else null,
                message = if (entry == null && !controls.isEffectiveNeutral()) "Stem cache is not available for this track." else null,
            )
        if (autoActivate && entry != null && !controls.isEffectiveNeutral() && !castConnected) {
            activateCurrentIfPossible(entry)
        }
    }

    private fun activateCurrentIfPossible(preResolvedEntry: AndroidStemCacheEntry? = null) {
        if (closed || castConnected || castConnectedProvider()) return
        val identity = currentIdentity() ?: return
        val controls = mixStore.load(identity.trackId)
        if (controls.isEffectiveNeutral()) return
        val entry = preResolvedEntry ?: cache.find(identity.trackId, identity.durationMs) ?: run {
            _state.value = _state.value.copy(cacheReady = false, message = "Stem cache is not available for this track.")
            return
        }
        if (rendererActive && _state.value.trackId == identity.trackId) {
            renderer?.setEffectiveGains(controls.effectiveGains())
            return
        }

        val epoch = ++generation
        activationJob?.cancel()
        activationJob =
            scope.launch {
                val candidate = rendererFactory()
                try {
                    candidate.prepare(
                        entry = entry,
                        positionMs = primaryPlayer.currentPosition,
                        playbackParameters = primaryPlayer.playbackParameters,
                        audioSessionId = primaryPlayer.audioSessionId,
                    )
                    if (closed || epoch != generation || currentIdentity()?.trackId != identity.trackId || castConnected || castConnectedProvider()) {
                        candidate.close()
                        return@launch
                    }
                    val alignedPosition = primaryPlayer.currentPosition
                    candidate.setPlaybackParameters(primaryPlayer.playbackParameters)
                    candidate.setAudioSessionId(primaryPlayer.audioSessionId)
                    candidate.setEffectiveGains(controls.effectiveGains())
                    candidate.setMasterVolume(masterVolumeProvider())
                    candidate.seekTo(alignedPosition)

                    renderer?.close()
                    renderer = candidate
                    _state.value =
                        _state.value.copy(
                            trackId = identity.trackId,
                            controls = controls,
                            cacheReady = true,
                            rendererActive = true,
                            message = "Live 6-stem mix",
                        )
                    onRendererActiveChanged(true)
                    if (primaryPlayer.isPlaying) candidate.play() else candidate.pause()
                    startSyncLoop(epoch, identity.trackId)
                } catch (cancelled: CancellationException) {
                    candidate.close()
                    throw cancelled
                } catch (error: Throwable) {
                    candidate.close()
                    if (epoch == generation && !closed) {
                        _state.value =
                            _state.value.copy(
                                rendererActive = false,
                                message = error.message?.takeIf(String::isNotBlank) ?: "Stem playback failed",
                            )
                        onRendererActiveChanged(false)
                    }
                }
            }
    }

    private fun startSyncLoop(
        epoch: Long,
        trackId: String,
    ) {
        syncJob?.cancel()
        syncJob =
            scope.launch {
                while (isActive && !closed && epoch == generation && rendererActive) {
                    val active = renderer ?: return@launch
                    val failure = active.failure()
                    if (failure != null) {
                        deactivateRenderer("Stem decoder failed: ${failure.message}")
                        return@launch
                    }
                    if (currentIdentity()?.trackId != trackId || castConnected || castConnectedProvider()) {
                        deactivateRenderer(null)
                        return@launch
                    }
                    val primaryPosition = primaryPlayer.currentPosition
                    val stemPositions = active.positionsMs()
                    if (primaryPlayer.isPlaying && stemRendererNeedsResync(primaryPosition, stemPositions)) {
                        active.seekTo(primaryPosition)
                    }
                    delay(SYNC_INTERVAL_MS)
                }
            }
    }

    private fun deactivateRenderer(message: String?) {
        generation += 1
        activationJob?.cancel()
        activationJob = null
        syncJob?.cancel()
        syncJob = null
        val wasActive = rendererActive
        renderer?.let { active ->
            runCatching { active.pause() }
            runCatching { active.close() }
        }
        renderer = null
        _state.value = _state.value.copy(rendererActive = false, message = message)
        if (wasActive) onRendererActiveChanged(false)
    }

    private fun currentIdentity(): TrackIdentity? {
        val mediaItem = primaryPlayer.currentMediaItem ?: return null
        val metadata = mediaItem.metadata
        val trackId = metadata?.id?.takeIf(String::isNotBlank) ?: mediaItem.mediaId.takeIf(String::isNotBlank) ?: return null
        val durationMs = metadata?.duration?.takeIf { it > 0 }?.toLong()?.times(1_000L) ?: -1L
        return TrackIdentity(trackId, durationMs, metadata?.title)
    }

    private data class TrackIdentity(
        val trackId: String,
        val durationMs: Long,
        val title: String?,
    )

    private companion object {
        const val SYNC_INTERVAL_MS = 250L
    }
}
