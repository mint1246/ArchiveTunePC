/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audio

import android.media.audiofx.Visualizer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.constants.BeatSyncedVisualsEnabledKey
import moe.rukamori.archivetune.constants.BeatSyncedVisualsIntensityKey
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.sdk.audio.BeatEnergyDetector
import moe.rukamori.archivetune.sdk.audio.sampleBeatTimeline
import moe.rukamori.archivetune.utils.rememberPreference

internal class AndroidBeatSyncController private constructor(
    private val visualizer: Visualizer,
    private val detector: BeatEnergyDetector,
) : AutoCloseable {
    private val beatEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val beats = beatEvents.asSharedFlow()

    fun start() {
        val rate = Visualizer.getMaxCaptureRate().coerceAtMost(20_000)
        visualizer.captureSize = Visualizer.getCaptureSizeRange().last()
        visualizer.setDataCaptureListener(
            object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer?,
                    waveform: ByteArray?,
                    samplingRate: Int,
                ) = Unit

                override fun onFftDataCapture(
                    visualizer: Visualizer?,
                    fft: ByteArray?,
                    samplingRate: Int,
                ) {
                    if (fft != null && detector.acceptFft(fft, samplingRate, android.os.SystemClock.elapsedRealtime())) {
                        beatEvents.tryEmit(Unit)
                    }
                }
            },
            rate,
            false,
            true,
        )
        visualizer.enabled = true
    }

    override fun close() {
        runCatching { visualizer.enabled = false }
        runCatching { visualizer.release() }
    }

    companion object {
        fun create(audioSessionId: Int): AndroidBeatSyncController? {
            if (audioSessionId <= 0) return null
            return runCatching {
                AndroidBeatSyncController(
                    visualizer = Visualizer(audioSessionId),
                    detector = BeatEnergyDetector(),
                )
            }.getOrNull()
        }
    }
}

val LocalAndroidBeatPulse = compositionLocalOf { 0f }

@Composable
fun rememberAndroidBeatPulse(
    audioSessionId: Int,
    enabled: Boolean,
    intensity: Float,
): Float {
    val pulse = remember { Animatable(0f) }
    val controller =
        remember(audioSessionId, enabled) {
            if (enabled) AndroidBeatSyncController.create(audioSessionId) else null
        }

    DisposableEffect(controller) {
        if (controller != null) runCatching { controller.start() }
        onDispose { controller?.close() }
    }

    LaunchedEffect(controller, intensity) {
        if (controller == null) {
            pulse.snapTo(0f)
            return@LaunchedEffect
        }
        controller.beats.collectLatest {
            pulse.snapTo(intensity.coerceIn(0f, 1f))
            pulse.animateTo(0f, animationSpec = tween(durationMillis = 390))
        }
    }
    return pulse.value
}

@Composable
fun rememberPlayerBeatPulse(
    isPlaying: Boolean,
    enabledOverride: Boolean = true,
    playerConnectionOverride: PlayerConnection? = null,
): Float {
    val playerConnection = playerConnectionOverride ?: LocalPlayerConnection.current ?: return 0f
    val enabled by rememberPreference(BeatSyncedVisualsEnabledKey, true)
    val intensity by rememberPreference(BeatSyncedVisualsIntensityKey, 0.75f)
    val effectiveEnabled = enabled && enabledOverride
    val beatGrid by playerConnection.service.currentBeatAnalysis.collectAsState()
    val analyzedPulse by
        produceState(
            initialValue = 0f,
            beatGrid,
            isPlaying,
            effectiveEnabled,
            intensity,
        ) {
            if (!effectiveEnabled || !isPlaying || beatGrid == null) {
                value = 0f
                return@produceState
            }
            while (true) {
                val analysis = beatGrid?.analysis
                value =
                    if (analysis == null) {
                        0f
                    } else {
                        sampleBeatTimeline(
                            beatsSeconds = analysis.beatsSeconds,
                            positionMs = playerConnection.player.currentPosition.toDouble(),
                        ).pulse * intensity.coerceIn(0f, 1f)
                    }
                delay(16L)
            }
        }
    val liveFallback = rememberAndroidBeatPulse(
        audioSessionId = playerConnection.player.audioSessionId,
        enabled = effectiveEnabled && isPlaying && beatGrid == null,
        intensity = intensity,
    )
    return if (beatGrid != null) analyzedPulse else liveFallback
}
