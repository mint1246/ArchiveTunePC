/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

enum class MusicSliderStyle { STANDARD, WAVY, SLIM }

/**
 * Exact Android player seek slider supporting Standard (thick 10dp rounded bar + pill thumb + end dot),
 * Wavy (Material Expressive LinearWavyProgressIndicator + morphing thumb), and Slim (4dp track).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AndroidStyledPlaybackSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    style: MusicSliderStyle = MusicSliderStyle.STANDARD,
    isPlaying: Boolean = false,
    enabled: Boolean = true,
    activeColor: Color = MaterialTheme.colorScheme.onBackground,
    inactiveColor: Color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.24f),
    thumbColor: Color = MaterialTheme.colorScheme.onBackground,
    hapticsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    when (style) {
        MusicSliderStyle.WAVY -> AndroidWavyPlaybackSlider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            isPlaying = isPlaying,
            enabled = enabled,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            thumbColor = thumbColor,
            hapticsEnabled = hapticsEnabled,
            modifier = modifier,
        )

        MusicSliderStyle.STANDARD, MusicSliderStyle.SLIM -> {
            val trackHeight = if (style == MusicSliderStyle.STANDARD) 10.dp else 4.dp
            val thumbHeight = if (style == MusicSliderStyle.STANDARD) 28.dp else 18.dp
            val thumbWidth = if (style == MusicSliderStyle.STANDARD) 4.dp else 10.dp
            AndroidTrackPlaybackSlider(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                enabled = enabled,
                trackHeight = trackHeight,
                thumbWidth = thumbWidth,
                thumbHeight = thumbHeight,
                showEndDot = style == MusicSliderStyle.STANDARD,
                activeColor = activeColor,
                inactiveColor = inactiveColor,
                thumbColor = thumbColor,
                hapticsEnabled = hapticsEnabled,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun AndroidTrackPlaybackSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean,
    trackHeight: Dp,
    thumbWidth: Dp,
    thumbHeight: Dp,
    showEndDot: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    thumbColor: Color,
    hapticsEnabled: Boolean,
    modifier: Modifier,
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinish by rememberUpdatedState(onValueChangeFinished)
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val dir = LocalLayoutDirection.current
    var dragging by remember { mutableStateOf(false) }

    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) 1.15f else 1f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "sliderThumbScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clearAndSetSemantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.coerceIn(valueRange.start, valueRange.endInclusive),
                    range = valueRange,
                    steps = 0,
                )
                if (enabled) {
                    setProgress { target ->
                        val clamped = target.coerceIn(valueRange.start, valueRange.endInclusive)
                        latestChange(clamped)
                        latestFinish()
                        true
                    }
                }
            }
            .pointerInput(enabled, valueRange, hapticsEnabled) {
                if (!enabled) return@pointerInput
                fun mapX(x: Float): Float {
                    val pad = 8.dp.toPx()
                    val usable = (size.width - pad * 2f).coerceAtLeast(1f)
                    val norm = ((x - pad) / usable).coerceIn(0f, 1f)
                    val dirNorm = if (dir == LayoutDirection.Rtl) 1f - norm else norm
                    return valueRange.start + dirNorm * (valueRange.endInclusive - valueRange.start)
                }
                val lastStep = intArrayOf(-1)
                awaitEachGesture {
                    try {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        down.consume()
                        var current = mapX(down.position.x)
                        latestChange(current)
                        var pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull { it.pressed }
                                ?: break
                            pointerId = change.id
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.position != change.previousPosition) {
                                change.consume()
                                current = mapX(change.position.x)
                                if (hapticsEnabled) {
                                    val step = (((current - valueRange.start) / span) * 20f).toInt()
                                    if (step != lastStep[0]) {
                                        lastStep[0] = step
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                latestChange(current)
                            }
                        }
                        latestFinish()
                    } finally {
                        dragging = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val isRtl = layoutDirection == LayoutDirection.Rtl
            val pad = 8.dp.toPx()
            val trackH = trackHeight.toPx()
            val startX = if (isRtl) size.width - pad else pad
            val endX = if (isRtl) pad else size.width - pad
            val thumbX = startX + (endX - startX) * fraction
            val gap = 6.dp.toPx()

            // Inactive track (right of thumb)
            val inactiveStart = if (isRtl) (thumbX - gap).coerceAtLeast(endX) else (thumbX + gap).coerceAtMost(endX)
            if ((isRtl && inactiveStart > endX) || (!isRtl && inactiveStart < endX)) {
                drawLine(
                    color = inactiveColor,
                    start = Offset(inactiveStart, center.y),
                    end = Offset(endX, center.y),
                    strokeWidth = trackH,
                    cap = StrokeCap.Round,
                )
            }

            // Active track (left of thumb)
            val activeEnd = if (isRtl) (thumbX + gap).coerceAtMost(startX) else (thumbX - gap).coerceAtLeast(startX)
            if ((isRtl && activeEnd < startX) || (!isRtl && activeEnd > startX)) {
                drawLine(
                    color = activeColor,
                    start = Offset(startX, center.y),
                    end = Offset(activeEnd, center.y),
                    strokeWidth = trackH,
                    cap = StrokeCap.Round,
                )
            }

            // Subtle end stop dot (Material 3 expressive / Android V4 standard)
            if (showEndDot && fraction < 0.96f) {
                drawCircle(
                    color = activeColor.copy(alpha = 0.65f),
                    radius = 2.dp.toPx(),
                    center = Offset(endX, center.y),
                )
            }

            // Vertical pill thumb
            val w = thumbWidth.toPx() * thumbScale
            val h = thumbHeight.toPx() * thumbScale
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(thumbX - w / 2f, center.y - h / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AndroidWavyPlaybackSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isPlaying: Boolean,
    enabled: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    thumbColor: Color,
    hapticsEnabled: Boolean,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val strokeWidth = 5.dp
    val thumbRadius = 7.dp
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
    val thumbLineHeightPx = with(density) { 24.dp.toPx() }
    val stroke = remember(strokeWidthPx) { Stroke(width = strokeWidthPx, cap = StrokeCap.Round) }

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestFinish by rememberUpdatedState(onValueChangeFinished)
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    var interacting by remember { mutableStateOf(false) }

    val thumbInteraction by animateFloatAsState(
        targetValue = if (interacting) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "wavyThumbMorph",
    )
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (enabled && isPlaying && !interacting) 1f else 0f,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "wavyAmplitude",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clearAndSetSemantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.coerceIn(valueRange.start, valueRange.endInclusive),
                    range = valueRange,
                    steps = 0,
                )
                if (enabled) {
                    setProgress { target ->
                        val clamped = target.coerceIn(valueRange.start, valueRange.endInclusive)
                        latestChange(clamped)
                        latestFinish()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        LinearWavyProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = thumbRadius)
                .clearAndSetSemantics { },
            color = activeColor,
            trackColor = inactiveColor,
            stroke = stroke,
            trackStroke = stroke,
            gapSize = 10.dp,
            stopSize = 3.dp,
            amplitude = { p -> if (p > 0f) animatedAmplitude else 0f },
            wavelength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
            waveSpeed = WavyProgressIndicatorDefaults.LinearDeterminateWavelength / 2f,
        )

        Canvas(Modifier.fillMaxSize()) {
            val edge = thumbRadiusPx.coerceIn(0f, size.width / 2f)
            val usable = (size.width - edge * 2f).coerceAtLeast(0f)
            val currentW = thumbRadiusPx * 2f * (1f - thumbInteraction) + (strokeWidthPx * 1.2f) * thumbInteraction
            val currentH = thumbRadiusPx * 2f * (1f - thumbInteraction) + thumbLineHeightPx * thumbInteraction
            val x = (edge + usable * fraction).coerceIn(currentW / 2f, (size.width - currentW / 2f).coerceAtLeast(currentW / 2f))
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(x - currentW / 2f, size.height / 2f - currentH / 2f),
                size = Size(currentW, currentH),
                cornerRadius = CornerRadius(currentW / 2f, currentW / 2f),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled, valueRange, hapticsEnabled) {
                    if (!enabled) return@pointerInput
                    fun mapX(rawX: Float): Float {
                        val edge = thumbRadiusPx.coerceIn(0f, size.width / 2f)
                        val usable = (size.width - edge * 2f).coerceAtLeast(1f)
                        val norm = ((rawX - edge) / usable).coerceIn(0f, 1f)
                        return valueRange.start + norm * (valueRange.endInclusive - valueRange.start)
                    }
                    val lastStep = intArrayOf(-1)
                    awaitEachGesture {
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            interacting = true
                            down.consume()
                            var current = mapX(down.position.x)
                            latestChange(current)
                            var pointerId = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: event.changes.firstOrNull { it.pressed }
                                    ?: break
                                pointerId = change.id
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                if (change.position != change.previousPosition) {
                                    change.consume()
                                    current = mapX(change.position.x)
                                    if (hapticsEnabled) {
                                        val step = (((current - valueRange.start) / span) * 20f).toInt()
                                        if (step != lastStep[0]) {
                                            lastStep[0] = step
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                    latestChange(current)
                                }
                            }
                            latestFinish()
                        } finally {
                            interacting = false
                        }
                    }
                },
        )
    }
}
