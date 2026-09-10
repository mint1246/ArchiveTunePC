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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shell-wide artwork backdrop used by the dynamic theme. Desktop uses a Skia domain-warp
 * shader; Android keeps the same slow colour-field motion and beat response with a heavily
 * blurred, oversized artwork layer so it remains cheap and works below Android 13 too.
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

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
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
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val pulse = beatPulse.coerceIn(0f, 1f)
                                val scale = 1.24f + breathe.value * 0.025f + pulse * 0.025f
                                scaleX = scale
                                scaleY = scale
                                translationX = driftX.value
                                translationY = driftY.value
                            }
                            .blur(64.dp)
                            .alpha(0.62f),
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
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
    }
}
