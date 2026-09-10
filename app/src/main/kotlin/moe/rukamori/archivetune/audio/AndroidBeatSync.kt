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
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.constants.BeatSyncedVisualsEnabledKey
import moe.rukamori.archivetune.constants.BeatSyncedVisualsIntensityKey
import moe.rukamori.archivetune.utils.rememberPreference
import kotlin.math.sqrt

/**
 * Small adaptive onset detector used by the Android player visuals.
 *
 * It intentionally looks only at bass/kick energy.  This gives the UI a real audio-derived beat
 * signal without requiring the Windows-only Essentia runtime used by desktop.
 */
internal class BeatEnergyDetector(
    private val triggerRatio: Float = 1.72f,
    private val baselineAlpha: Float = 0.055f,
    private val minimumEnergy: Float = 3.5f,
    private val refractoryMs: Long = 210L,
) {
    private var baseline = 0f
    private var initialized = false
    private var lastBeatMs = Long.MIN_VALUE / 2

    fun acceptEnergy(energy: Float, nowMs: Long): Boolean {
        if (!energy.isFinite() || energy < 0f) return false
        if (!initialized) {
            baseline = energy.coerceAtLeast(0.001f)
            initialized = true
            return false
        }

        val reference = baseline.coerceAtLeast(0.001f)
        val isBeat =
            energy >= minimumEnergy &&
                energy >= reference * triggerRatio &&
                nowMs - lastBeatMs >= refractoryMs

        // Follow normal program level quickly enough to avoid permanently over-triggering, but do
        // not let a single transient drag the threshold all the way up to itself.
        val cappedForBaseline = energy.coerceAtMost(reference * 2.2f)
        baseline += (cappedForBaseline - baseline) * baselineAlpha

        if (isBeat) lastBeatMs = nowMs
        return isBeat
    }

    fun acceptFft(
        fft: ByteArray,
        samplingRateMilliHz: Int,
        nowMs: Long,
    ): Boolean {
        if (fft.size < 8 || samplingRateMilliHz <= 0) return false
        val sampleRateHz = samplingRateMilliHz / 1_000f
        val fftSize = fft.size
        val firstBin = ((45f * fftSize) / sampleRateHz).toInt().coerceAtLeast(1)
        val lastBin = ((180f * fftSize) / sampleRateHz).toInt().coerceAtMost((fft.size / 2) - 1)
        if (firstBin > lastBin) return false

        var sum = 0.0
        var count = 0
        for (bin in firstBin..lastBin) {
            val offset = bin * 2
            if (offset + 1 >= fft.size) break
            val real = fft[offset].toInt().toDouble()
            val imaginary = fft[offset + 1].toInt().toDouble()
            sum += real * real + imaginary * imaginary
            count++
        }
        if (count == 0) return false
        return acceptEnergy(sqrt(sum / count).toFloat(), nowMs)
    }
}

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
): Float {
    val playerConnection = LocalPlayerConnection.current ?: return 0f
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
                        sampleAndroidBeatTimeline(
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
