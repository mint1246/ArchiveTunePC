/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Shell-wide dynamic-theme colour field.
 *
 * The artwork is intentionally NOT drawn here. Desktop first destroys the cover into a blurred
 * colour field before warping it; drawing a merely blurred fullscreen cover on Android kept faces,
 * text and composition visible and made dynamic theme look like a zoomed album-art wallpaper.
 * Android instead renders only the extracted artwork colours as large, overlapping, animated
 * fields. The result keeps the album's palette while containing zero recognisable cover detail.
 */
@Composable
fun DynamicAppBackdrop(
    palette: ThemeSeedPalette?,
    enabled: Boolean,
    isPlaying: Boolean,
    animationsDisabled: Boolean,
    beatPulse: Float,
    modifier: Modifier = Modifier,
) {
    if (!enabled || palette == null) return

    val driftX = remember { Animatable(-1f) }
    val driftY = remember { Animatable(1f) }
    val breathe = remember { Animatable(0f) }

    LaunchedEffect(enabled, isPlaying, animationsDisabled) {
        if (!enabled || !isPlaying || animationsDisabled) return@LaunchedEffect
        coroutineScope {
            launch {
                while (isActive) {
                    driftX.animateTo(1f, tween(13_000, easing = FastOutSlowInEasing))
                    driftX.animateTo(-1f, tween(13_000, easing = FastOutSlowInEasing))
                }
            }
            launch {
                while (isActive) {
                    driftY.animateTo(-1f, tween(17_000, easing = FastOutSlowInEasing))
                    driftY.animateTo(1f, tween(17_000, easing = FastOutSlowInEasing))
                }
            }
            launch {
                while (isActive) {
                    breathe.animateTo(1f, tween(9_000, easing = FastOutSlowInEasing))
                    breathe.animateTo(0f, tween(9_000, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    val primary by animateColorAsState(
        palette.primary.backdropTone(saturation = 0.92f, brightness = 0.70f),
        tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-field-primary",
    )
    val secondary by animateColorAsState(
        palette.secondary.backdropTone(saturation = 0.88f, brightness = 0.64f),
        tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-field-secondary",
    )
    val tertiary by animateColorAsState(
        palette.tertiary.backdropTone(saturation = 0.90f, brightness = 0.66f),
        tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-field-tertiary",
    )
    val neutral by animateColorAsState(
        palette.neutral.backdropTone(saturation = 0.34f, brightness = 0.20f),
        tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-field-neutral",
    )

    val pulse = beatPulse.coerceIn(0f, 1f)
    Box(modifier.fillMaxSize().background(Color(0xFF0A0712))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            drawRect(neutral)

            val longest = max(w, h)
            val dx = driftX.value
            val dy = driftY.value
            val breathing = breathe.value
            val pulseScale = 1f + pulse * 0.10f

            fun colourBlob(
                color: Color,
                centerX: Float,
                centerY: Float,
                radiusScale: Float,
                alpha: Float,
            ) {
                drawRect(
                    brush =
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    color.copy(alpha = (alpha + pulse * 0.05f).coerceAtMost(1f)),
                                    color.copy(alpha = alpha * 0.46f),
                                    Color.Transparent,
                                ),
                            center = Offset(centerX, centerY),
                            radius = longest * radiusScale * pulseScale,
                        ),
                )
            }

            // Huge overlapping fields instead of album-art pixels. Their centers drift at
            // different rates so the result reads as a flowing colour field rather than circles.
            colourBlob(
                color = primary,
                centerX = w * (0.17f + dx * 0.10f),
                centerY = h * (0.24f + dy * 0.08f),
                radiusScale = 0.78f + breathing * 0.06f,
                alpha = 0.92f,
            )
            colourBlob(
                color = secondary,
                centerX = w * (0.86f - dx * 0.08f),
                centerY = h * (0.32f - dy * 0.11f),
                radiusScale = 0.72f - breathing * 0.04f,
                alpha = 0.86f,
            )
            colourBlob(
                color = tertiary,
                centerX = w * (0.38f - dx * 0.12f),
                centerY = h * (0.91f + dy * 0.05f),
                radiusScale = 0.82f + breathing * 0.03f,
                alpha = 0.82f,
            )
            colourBlob(
                color = primary,
                centerX = w * (1.03f + dx * 0.04f),
                centerY = h * (0.94f - dy * 0.07f),
                radiusScale = 0.62f,
                alpha = 0.52f,
            )
        }

        // Desktop dims the saturated shader by 22%; keep the same final contrast/readability.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
    }
}

private fun Color.backdropTone(
    saturation: Float,
    brightness: Float,
): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = saturation.coerceIn(0f, 1f)
    hsv[2] = brightness.coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
