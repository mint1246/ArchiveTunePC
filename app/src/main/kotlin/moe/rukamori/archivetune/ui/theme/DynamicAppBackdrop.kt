/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shell-wide artwork backdrop used by the dynamic theme. Desktop uses a Skia domain-warp
 * shader. Android keeps the same shell-wide visual intent with a lightly blurred, saturated cover
 * field on every API level; normal Material surfaces are made translucent by ArchiveTuneTheme.
 */
@Composable
fun DynamicAppBackdrop(
    artworkUrl: String?,
    accentColor: Color,
    enabled: Boolean,
    isPlaying: Boolean,
    animationsDisabled: Boolean,
    beatPulse: Float,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return

    val driftX = remember { Animatable(-30f) }
    val driftY = remember { Animatable(24f) }
    val breathe = remember { Animatable(0f) }

    // Match desktop's important power/visual behavior: pause freezes the ambient field at its
    // current state instead of rebuilding/advancing a full-screen animation while audio is idle.
    LaunchedEffect(enabled, isPlaying, animationsDisabled) {
        if (!enabled || !isPlaying || animationsDisabled) return@LaunchedEffect
        coroutineScope {
            launch {
                while (isActive) {
                    driftX.animateTo(30f, tween(durationMillis = 13_000))
                    driftX.animateTo(-30f, tween(durationMillis = 13_000))
                }
            }
            launch {
                while (isActive) {
                    driftY.animateTo(-24f, tween(durationMillis = 17_000))
                    driftY.animateTo(24f, tween(durationMillis = 17_000))
                }
            }
            launch {
                while (isActive) {
                    breathe.animateTo(1f, tween(durationMillis = 9_000))
                    breathe.animateTo(0f, tween(durationMillis = 9_000))
                }
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(Color(0xFF0A0712))) {
        val portrait = maxHeight > maxWidth
        val saturationFilter =
            remember {
                ColorFilter.colorMatrix(
                    ColorMatrix().apply { setToSaturation(1.65f) },
                )
            }
        AnimatedContent(
            targetState = artworkUrl,
            transitionSpec = { fadeIn(tween(650)) togetherWith fadeOut(tween(650)) },
            label = "dynamic-app-backdrop-artwork",
        ) { url ->
            if (!url.isNullOrBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = saturationFilter,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val pulse = beatPulse.coerceIn(0f, 1f)
                                // A square album cover already crops aggressively on a portrait
                                // phone. Desktop's warped colour field doesn't need a 24% zoom, so
                                // keep Android only slightly oversized to hide blur edges.
                                val baseScale = if (portrait) 1.06f else 1.04f
                                val scale = baseScale + breathe.value * 0.012f + pulse * 0.014f
                                scaleX = scale
                                scaleY = scale
                                translationX = driftX.value * if (portrait) 0.42f else 0.62f
                                translationY = driftY.value * if (portrait) 0.62f else 0.42f
                            }
                            .blur(16.dp)
                            .alpha(0.96f),
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    accentColor.copy(alpha = 0.18f + beatPulse.coerceIn(0f, 1f) * 0.05f),
                                    Color.Transparent,
                                ),
                        ),
                    ),
        )
        // Desktop applies a 22% overlay after a bright, saturation-heavy shader. The Android
        // source is already a real cover image, so a lighter veil reaches the same perceived
        // luminance instead of crushing it twice.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.06f)))
    }
}
