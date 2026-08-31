package moe.rukamori.archivetune.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import moe.rukamori.archivetune.desktop.analysis.BeatKeyAnalysis
import moe.rukamori.archivetune.desktop.platform.DesktopAudioAnalysisRepository
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.desktop.VlcjPlaybackController
import kotlin.math.max

data class BeatSyncSample(
    val beatIndex: Int,
    val phase: Float,
    val pulse: Float,
    val beatDurationMs: Float,
    val localBpm: Float,
)

/**
 * One shared, playback-position-driven beat signal for the desktop UI.
 *
 * The controller never integrates phase from previous frames. Each frame derives the song
 * position from the latest playback snapshot anchor, then binary-searches the actual beat grid.
 */
class DesktopBeatSyncController(
    enabled: Boolean,
    intensity: Float,
) {
    var currentBeatIndex by mutableIntStateOf(-1)
        private set
    var beatPhase by mutableFloatStateOf(0f)
        private set
    var beatPulse by mutableFloatStateOf(0f)
        private set
    var beatDurationMs by mutableFloatStateOf(0f)
        private set
    var localBpm by mutableFloatStateOf(0f)
        private set
    var hasBeatData by mutableStateOf(false)
        private set
    var playbackRunning by mutableStateOf(false)
        private set
    var visualsEnabled by mutableStateOf(enabled)
        private set
    var visualIntensity by mutableFloatStateOf(intensity.coerceIn(0f, 1f))
        private set

    private var currentTrackIdentity: String? = null
    private var beats: FloatArray? = null
    private var anchorPositionMs = 0.0
    private var anchorNanos = 0L
    private var anchorSpeed = 1f
    private var anchorPlaying = false
    private val frameSample = MutableBeatSyncSample()

    val visualPulse: Float
        get() = beatPulse * visualIntensity

    fun setVisualPreferences(enabled: Boolean, intensity: Float) {
        visualsEnabled = enabled
        visualIntensity = intensity.coerceIn(0f, 1f)
        if (!enabled) resetVisualState()
    }

    fun onPlaybackSnapshot(snapshot: PlaybackSnapshot, nowNanos: Long) {
        val identity = snapshot.currentItem?.let(DesktopAudioAnalysisRepository::analysisIdentity)
        if (identity != currentTrackIdentity) {
            currentTrackIdentity = identity
            beats = null
            hasBeatData = false
            resetVisualState()
        }
        anchorPositionMs = snapshot.positionMs.toDouble()
        anchorNanos = nowNanos
        anchorSpeed = snapshot.speed
        anchorPlaying = snapshot.isPlaying
        playbackRunning = snapshot.isPlaying
        renderFrame(nowNanos)
    }

    fun installAnalysis(item: PlaybackItem, analysis: BeatKeyAnalysis?) {
        if (DesktopAudioAnalysisRepository.analysisIdentity(item) != currentTrackIdentity) return
        val next = analysis?.beatsSeconds?.takeIf { it.size >= 2 }
        beats = next
        hasBeatData = next != null
        if (next == null) resetVisualState() else renderFrame(System.nanoTime())
    }

    fun clearAnalysis() {
        beats = null
        hasBeatData = false
        resetVisualState()
    }

    fun renderFrame(nowNanos: Long) {
        if (!visualsEnabled) {
            resetVisualState()
            return
        }
        val timeline = beats ?: run {
            resetVisualState()
            return
        }
        val elapsedMs = if (anchorPlaying && anchorNanos != 0L) {
            max(0L, nowNanos - anchorNanos) / 1_000_000.0 * anchorSpeed
        } else {
            0.0
        }
        sampleBeatTimelineInto(timeline, anchorPositionMs + elapsedMs, frameSample)
        applySample(frameSample)
    }

    private fun applySample(sample: MutableBeatSyncSample) {
        currentBeatIndex = sample.beatIndex
        beatPhase = sample.phase
        beatPulse = sample.pulse
        beatDurationMs = sample.beatDurationMs
        localBpm = sample.localBpm
    }

    private fun resetVisualState() {
        currentBeatIndex = -1
        beatPhase = 0f
        beatPulse = 0f
        beatDurationMs = 0f
        localBpm = 0f
    }
}

/**
 * Hosts all visual-only frame work. The frame loop exists only while the window/player is visible,
 * playback is running, visuals are enabled, and an analyzed beat grid is installed.
 */
@Composable
fun DesktopBeatSyncHost(
    playback: VlcjPlaybackController,
    repository: DesktopAudioAnalysisRepository,
    controller: DesktopBeatSyncController,
    active: Boolean,
) {
    LaunchedEffect(playback, controller) {
        playback.state.collect { snapshot ->
            controller.onPlaybackSnapshot(snapshot, System.nanoTime())
        }
    }

    LaunchedEffect(playback, repository, controller, controller.visualsEnabled) {
        if (!controller.visualsEnabled) {
            controller.clearAnalysis()
            return@LaunchedEffect
        }
        playback.state
            .map { it.currentItem }
            .distinctUntilChanged { previous, next ->
                val previousId = previous?.let(DesktopAudioAnalysisRepository::analysisIdentity)
                val nextId = next?.let(DesktopAudioAnalysisRepository::analysisIdentity)
                previousId == nextId
            }
            .collectLatest { item ->
                if (item == null) {
                    controller.clearAnalysis()
                } else {
                    controller.installAnalysis(item, repository.beatKeyAnalysis(item))
                }
            }
    }

    val shouldRender = active && controller.visualsEnabled && controller.hasBeatData && controller.playbackRunning
    LaunchedEffect(controller, shouldRender) {
        if (!shouldRender) return@LaunchedEffect
        var lastRenderedNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastRenderedNanos == 0L || frameNanos - lastRenderedNanos >= BEAT_VISUAL_FRAME_INTERVAL_NANOS) {
                    controller.renderFrame(System.nanoTime())
                    lastRenderedNanos = frameNanos
                }
            }
        }
    }
}

internal fun sampleBeatTimeline(
    beats: FloatArray,
    positionMs: Double,
): BeatSyncSample {
    val sample = MutableBeatSyncSample()
    sampleBeatTimelineInto(beats, positionMs, sample)
    return BeatSyncSample(sample.beatIndex, sample.phase, sample.pulse, sample.beatDurationMs, sample.localBpm)
}

private fun sampleBeatTimelineInto(
    beats: FloatArray,
    positionMs: Double,
    output: MutableBeatSyncSample,
) {
    if (beats.size < 2 || positionMs < 0.0) {
        output.set(-1, 0f, 0f, 0f, 0f)
        return
    }
    val positionSeconds = positionMs / 1_000.0
    val first = beats[0].toDouble()
    val lastIndex = beats.lastIndex

    if (positionSeconds < first) {
        val interval = (beats[1] - beats[0]).coerceAtLeast(MIN_BEAT_INTERVAL_SECONDS)
        val distance = (first - positionSeconds).toFloat()
        val attackWindow = interval * ATTACK_FRACTION
        val pulse = if (distance < attackWindow) smoothStep(1f - distance / attackWindow) else 0f
        output.set(-1, 0f, pulse, interval * 1_000f, 60f / interval)
        return
    }

    var low = 0
    var high = lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (beats[mid].toDouble() <= positionSeconds) low = mid + 1 else high = mid - 1
    }
    val index = high.coerceIn(0, lastIndex)

    if (index == lastIndex) {
        val interval = (beats[lastIndex] - beats[lastIndex - 1]).coerceAtLeast(MIN_BEAT_INTERVAL_SECONDS)
        val elapsed = (positionSeconds - beats[lastIndex]).toFloat()
        val decayWindow = interval * DECAY_FRACTION
        val pulse = if (elapsed < decayWindow) 1f - smoothStep(elapsed / decayWindow) else 0f
        output.set(lastIndex, 1f, pulse, interval * 1_000f, 60f / interval)
        return
    }

    val start = beats[index]
    val end = beats[index + 1]
    val interval = (end - start).coerceAtLeast(MIN_BEAT_INTERVAL_SECONDS)
    val phase = (((positionSeconds - start) / interval).toFloat()).coerceIn(0f, 1f)
    val decay = if (phase < DECAY_FRACTION) {
        1f - smoothStep(phase / DECAY_FRACTION)
    } else {
        0f
    }
    val attackStart = 1f - ATTACK_FRACTION
    val attack = if (phase > attackStart) {
        smoothStep((phase - attackStart) / ATTACK_FRACTION)
    } else {
        0f
    }
    output.set(index, phase, max(decay, attack), interval * 1_000f, 60f / interval)
}

private class MutableBeatSyncSample {
    var beatIndex: Int = -1
    var phase: Float = 0f
    var pulse: Float = 0f
    var beatDurationMs: Float = 0f
    var localBpm: Float = 0f

    fun set(beatIndex: Int, phase: Float, pulse: Float, beatDurationMs: Float, localBpm: Float) {
        this.beatIndex = beatIndex
        this.phase = phase
        this.pulse = pulse
        this.beatDurationMs = beatDurationMs
        this.localBpm = localBpm
    }
}

private fun smoothStep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private const val ATTACK_FRACTION = 0.13f
private const val DECAY_FRACTION = 0.52f
private const val MIN_BEAT_INTERVAL_SECONDS = 0.05f
private const val BEAT_VISUAL_FRAME_INTERVAL_NANOS = 1_000_000_000L / 60L
