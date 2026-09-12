/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.theme

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Keep the ambient field capped to 60 fps even on high-refresh phones. */
private const val BACKGROUND_FRAME_INTERVAL_NANOS = 1_000_000_000L / 60L

/**
 * Pure-colour dynamic backdrop.
 *
 * Album art is used only to extract a palette. No cover pixels ever reach the fullscreen renderer,
 * which avoids the "blurred/swirled album cover" look that is especially obvious on tall phones.
 * Android 13+ instead synthesizes a native-resolution organic field from the three album hues using
 * simplex noise. Each hue is lifted to a high saturation/value floor before rendering, so colourful
 * albums stay bright instead of collapsing into a dark muddy wash.
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

    val primary by animateColorAsState(
        targetValue = palette.primary.vividBackdropTone(saturationFloor = 0.88f, brightnessFloor = 0.94f),
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-field-primary",
    )
    val secondary by animateColorAsState(
        targetValue = palette.secondary.vividBackdropTone(saturationFloor = 0.84f, brightnessFloor = 0.92f),
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-field-secondary",
    )
    val tertiary by animateColorAsState(
        targetValue = palette.tertiary.vividBackdropTone(saturationFloor = 0.86f, brightnessFloor = 0.93f),
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-field-tertiary",
    )

    Box(modifier.fillMaxSize().background(primary)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimePaletteBackdrop(
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                isPlaying = isPlaying,
                animationsDisabled = animationsDisabled,
                beatPulse = beatPulse,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PaletteFallbackBackdrop(
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                isPlaying = isPlaying,
                animationsDisabled = animationsDisabled,
                beatPulse = beatPulse,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Mobile surfaces already provide their own dark glass. A heavy global veil made the whole
        // screen look dim; keep only a tiny readability trim so the colour itself stays luminous.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.06f)))
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun RuntimePaletteBackdrop(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    isPlaying: Boolean,
    animationsDisabled: Boolean,
    beatPulse: Float,
    modifier: Modifier = Modifier,
) {
    val runtimeShader = remember { runCatching { RuntimeShader(PURE_COLOUR_FIELD_AGSL) }.getOrNull() }
    if (runtimeShader == null) {
        PaletteFallbackBackdrop(
            primary = primary,
            secondary = secondary,
            tertiary = tertiary,
            isPlaying = isPlaying,
            animationsDisabled = animationsDisabled,
            beatPulse = beatPulse,
            modifier = modifier,
        )
        return
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG) }
    var time by remember { mutableFloatStateOf(0f) }
    val playing by rememberUpdatedState(isPlaying)
    val animationsOff by rememberUpdatedState(animationsDisabled)

    LaunchedEffect(Unit) {
        var lastRendered = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastRendered == 0L) {
                    lastRendered = now
                    return@withFrameNanos
                }

                if (!playing || animationsOff) {
                    lastRendered = now
                } else if (now - lastRendered >= BACKGROUND_FRAME_INTERVAL_NANOS) {
                    time += (now - lastRendered) / 1_000_000_000f
                    lastRendered = now
                }
            }
        }
    }

    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        runtimeShader.setFloatUniform("time", time)
        runtimeShader.setFloatUniform("resolution", size.width, size.height)
        runtimeShader.setFloatUniform("primary", primary.red, primary.green, primary.blue)
        runtimeShader.setFloatUniform("secondary", secondary.red, secondary.green, secondary.blue)
        runtimeShader.setFloatUniform("tertiary", tertiary.red, tertiary.green, tertiary.blue)
        runtimeShader.setFloatUniform("beatPulse", beatPulse.coerceIn(0f, 1f))
        paint.shader = runtimeShader

        // Compose DrawScope dimensions are physical pixels, so AGSL evaluates at the phone's native
        // render resolution while the generated shapes remain aspect-ratio independent.
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
        }
    }
}

/** API < 33 fallback using the same bright palette but simple drifting fields. */
@Composable
private fun PaletteFallbackBackdrop(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    isPlaying: Boolean,
    animationsDisabled: Boolean,
    beatPulse: Float,
    modifier: Modifier = Modifier,
) {
    val driftX = remember { Animatable(-1f) }
    val driftY = remember { Animatable(1f) }
    val breathe = remember { Animatable(0f) }

    LaunchedEffect(isPlaying, animationsDisabled) {
        if (!isPlaying || animationsDisabled) return@LaunchedEffect
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

    val pulse = beatPulse.coerceIn(0f, 1f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        drawRect(primary.copy(alpha = 0.90f))

        val longest = max(w, h)
        val dx = driftX.value
        val dy = driftY.value
        val breathing = breathe.value
        val pulseScale = 1f + pulse * 0.08f

        fun colourBlob(
            color: Color,
            centerX: Float,
            centerY: Float,
            radiusScale: Float,
            alpha: Float,
        ) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = (alpha + pulse * 0.04f).coerceAtMost(1f)),
                        color.copy(alpha = alpha * 0.62f),
                        Color.Transparent,
                    ),
                    center = Offset(centerX, centerY),
                    radius = longest * radiusScale * pulseScale,
                ),
            )
        }

        colourBlob(primary, w * (0.12f + dx * 0.08f), h * (0.22f + dy * 0.06f), 0.84f + breathing * 0.05f, 1f)
        colourBlob(secondary, w * (0.88f - dx * 0.07f), h * (0.38f - dy * 0.08f), 0.78f - breathing * 0.03f, 0.96f)
        colourBlob(tertiary, w * (0.40f - dx * 0.09f), h * (0.87f + dy * 0.04f), 0.88f + breathing * 0.03f, 0.96f)
    }
}

private fun Color.vividBackdropTone(
    saturationFloor: Float,
    brightnessFloor: Float,
): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(saturationFloor).coerceIn(0f, 1f)
    hsv[2] = hsv[2].coerceAtLeast(brightnessFloor).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Organic palette-only field. Unlike the desktop Kawarp shader this never samples album pixels:
 * the three album hues compete through independent simplex-noise masks, producing soft regions of
 * mostly pure colour instead of a smeared image. Coordinates are normalized by the short screen
 * edge so a phone's tall aspect ratio cannot stretch recognizable structures vertically.
 */
private val PURE_COLOUR_FIELD_AGSL =
    """
    uniform float time;
    uniform float2 resolution;
    uniform float3 primary;
    uniform float3 secondary;
    uniform float3 tertiary;
    uniform float beatPulse;

    float mod289(float x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
    float2 mod289(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
    float3 mod289(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
    float3 permute(float3 x) { return mod289(((x * 34.0) + 1.0) * x); }

    float snoise(float2 v) {
        const float4 C = float4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
        float2 i = floor(v + float2(dot(v, C.yy)));
        float2 x0 = v - i + float2(dot(i, C.xx));
        float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
        float4 x12 = x0.xyxy + C.xxzz;
        x12.xy -= i1;
        i = mod289(i);
        float3 p = permute(permute(float3(i.y) + float3(0.0, i1.y, 1.0)) + float3(i.x) + float3(0.0, i1.x, 1.0));
        float3 m = max(float3(0.5) - float3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), float3(0.0));
        m = m * m;
        m = m * m;
        float3 x = 2.0 * fract(p * C.www) - float3(1.0);
        float3 h = abs(x) - float3(0.5);
        float3 ox = floor(x + float3(0.5));
        float3 a0 = x - ox;
        m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
        float3 g;
        g.x = a0.x * x0.x + h.x * x0.y;
        g.yz = a0.yz * x12.xz + h.yz * x12.yw;
        return 130.0 * dot(m, g);
    }

    half4 main(float2 coord) {
        float shortSide = max(min(resolution.x, resolution.y), 1.0);
        float2 p = (coord - resolution * 0.5) / shortSide;
        float t = time * 0.035;

        // A slow domain drift gives motion without resembling any source artwork.
        float2 drift = float2(
            snoise(p * 0.55 + float2(t, t * 0.73)),
            snoise(p * 0.55 + float2(-t * 0.81, t * 0.57) + float2(41.0, 17.0))
        );
        float2 q = p + drift * 0.34;

        // Independent low-frequency masks. Cubing the weights keeps regions saturated and mostly
        // one hue at a time instead of averaging complementary colours into brown/grey.
        float a = 0.5 + 0.5 * snoise(q * 0.72 + float2(t * 0.65, -t * 0.42));
        float b = 0.5 + 0.5 * snoise(q * 0.68 + float2(-t * 0.48, t * 0.70) + float2(23.0, 71.0));
        float c = 0.5 + 0.5 * snoise(q * 0.76 + float2(t * 0.57, t * 0.39) + float2(83.0, 29.0));
        float a2 = a * a;
        float b2 = b * b;
        float c2 = c * c;
        float3 weights = max(float3(a2 * a2 * a, b2 * b2 * b, c2 * c2 * c), float3(0.006));
        weights /= max(weights.x + weights.y + weights.z, 0.001);

        float3 rgb = primary * weights.x + secondary * weights.y + tertiary * weights.z;

        // Keep mobile deliberately luminous. The palette already has a high HSV value floor; this
        // adds a gentle midtone lift rather than desktop's 0.65 brightness reduction.
        float shimmer = 0.5 + 0.5 * snoise(q * 1.10 + float2(-t * 0.35, t * 0.28) + float2(9.0, 57.0));
        rgb = mix(rgb, float3(1.0), 0.075);
        rgb = min(rgb * (1.04 + shimmer * 0.12) + float3(0.02), float3(1.0));

        float2 uv = coord / resolution;
        float centerWeight = 1.0 - smoothstep(0.0, 0.72, length(uv - float2(0.5)));
        rgb = min(rgb * (1.0 + beatPulse * 0.10 * centerWeight), float3(1.0));

        return half4(half3(rgb), 1.0);
    }
    """.trimIndent()
