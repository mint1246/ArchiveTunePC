/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Same colour-field size used by the desktop Kawarp/SpicyLyrics pipeline. */
private const val FIELD_SIZE = 128

/** Android CPU blur approximation for desktop's sigma-10 Gaussian. */
private const val BLUR_RADIUS = 4
private const val BLUR_PASSES = 3

/** Match desktop's intentional 60 fps cap even on 120/144 Hz phones. */
private const val BACKGROUND_FRAME_INTERVAL_NANOS = 1_000_000_000L / 60L

/**
 * Shell-wide dynamic-theme colour field.
 *
 * Android 13+ now runs the same visual pipeline as desktop:
 *
 *  1. destroy the cover into a heavily blurred 128x128 colour texture;
 *  2. domain-warp that texture with layered simplex noise at the *screen's native resolution*;
 *  3. push saturation to 3.75x, brightness to 0.65x and apply the same vignette;
 *  4. apply desktop's final 22% black readability veil.
 *
 * No recognisable cover detail survives step 1. Older Android versions keep the palette-only
 * fallback because android.graphics.RuntimeShader/AGSL is API 33+.
 */
@Composable
fun DynamicAppBackdrop(
    artwork: Bitmap?,
    palette: ThemeSeedPalette?,
    enabled: Boolean,
    isPlaying: Boolean,
    animationsDisabled: Boolean,
    beatPulse: Float,
    modifier: Modifier = Modifier,
) {
    if (!enabled || palette == null) return

    var colourField by remember { mutableStateOf<Bitmap?>(null) }
    val currentField by rememberUpdatedState(colourField)

    LaunchedEffect(artwork) {
        if (artwork == null || artwork.isRecycled) return@LaunchedEffect
        val next = withContext(Dispatchers.Default) { makeColourField(artwork) }
        val previous = colourField
        colourField = next

        // Desktop cross-fades for 650 ms. Keep the previous tiny 128x128 bitmap alive long enough
        // for the visual hand-off, then release its native pixels.
        if (previous != null && previous !== next && previous !== artwork && !previous.isRecycled) {
            delay(800)
            previous.recycle()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            currentField?.let { field ->
                if (field !== artwork && !field.isRecycled) field.recycle()
            }
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFF0A0712))) {
        val field = colourField
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && field != null && !field.isRecycled) {
            RuntimeWarpBackdrop(
                colourField = field,
                isPlaying = isPlaying,
                animationsDisabled = animationsDisabled,
                beatPulse = beatPulse,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PaletteFallbackBackdrop(
                palette = palette,
                isPlaying = isPlaying,
                animationsDisabled = animationsDisabled,
                beatPulse = beatPulse,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Exact desktop app-shell veil. The shader itself already does brightness(0.65).
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun RuntimeWarpBackdrop(
    colourField: Bitmap,
    isPlaying: Boolean,
    animationsDisabled: Boolean,
    beatPulse: Float,
    modifier: Modifier = Modifier,
) {
    val runtimeShader = remember { RuntimeShader(DESKTOP_KAWARP_AGSL) }
    val bitmapShader =
        remember(colourField) {
            BitmapShader(colourField, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
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
        runtimeShader.setFloatUniform("saturation", 3.75f)
        runtimeShader.setFloatUniform("beatPulse", beatPulse.coerceIn(0f, 1f))
        runtimeShader.setInputShader("content", bitmapShader)
        paint.shader = runtimeShader

        // DrawScope's size is in physical pixels. AGSL therefore evaluates once per native output
        // pixel while sampling only the intentionally low-detail 128x128 colour texture.
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
        }
    }
}

/**
 * API < 33 fallback. This is deliberately retained rather than trying to emulate a full runtime
 * shader on CPU every frame.
 */
@Composable
private fun PaletteFallbackBackdrop(
    palette: ThemeSeedPalette,
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

    // Use stronger values than the old mobile implementation so the fallback still resembles the
    // desktop high-chroma field rather than collapsing into a muddy purple wash.
    val primary by animateColorAsState(
        palette.primary.backdropTone(saturation = 1f, brightness = 0.90f),
        tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-field-primary",
    )
    val secondary by animateColorAsState(
        palette.secondary.backdropTone(saturation = 0.96f, brightness = 0.84f),
        tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-field-secondary",
    )
    val tertiary by animateColorAsState(
        palette.tertiary.backdropTone(saturation = 0.98f, brightness = 0.86f),
        tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-field-tertiary",
    )
    val neutral by animateColorAsState(
        palette.neutral.backdropTone(saturation = 0.38f, brightness = 0.24f),
        tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-field-neutral",
    )

    val pulse = beatPulse.coerceIn(0f, 1f)
    Canvas(modifier) {
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
                                color.copy(alpha = alpha * 0.50f),
                                Color.Transparent,
                            ),
                        center = Offset(centerX, centerY),
                        radius = longest * radiusScale * pulseScale,
                    ),
            )
        }

        colourBlob(primary, w * (0.17f + dx * 0.10f), h * (0.24f + dy * 0.08f), 0.78f + breathing * 0.06f, 0.98f)
        colourBlob(secondary, w * (0.86f - dx * 0.08f), h * (0.32f - dy * 0.11f), 0.72f - breathing * 0.04f, 0.92f)
        colourBlob(tertiary, w * (0.38f - dx * 0.12f), h * (0.91f + dy * 0.05f), 0.82f + breathing * 0.03f, 0.90f)
        colourBlob(primary, w * (1.03f + dx * 0.04f), h * (0.94f - dy * 0.07f), 0.62f, 0.62f)
    }
}

/**
 * Turn album art into the same kind of unrecognisable low-frequency texture desktop feeds into
 * Kawarp. Work happens once per track off the main thread; the animated full-screen shader then
 * samples this tiny texture.
 */
private fun makeColourField(source: Bitmap): Bitmap {
    var current = source

    while (current.width > FIELD_SIZE * 2 || current.height > FIELD_SIZE * 2) {
        val next =
            Bitmap.createScaledBitmap(
                current,
                maxOf(1, current.width / 2),
                maxOf(1, current.height / 2),
                true,
            )
        if (current !== source && current !== next && !current.isRecycled) current.recycle()
        current = next
    }

    val square = Bitmap.createScaledBitmap(current, FIELD_SIZE, FIELD_SIZE, true)
    if (current !== source && current !== square && !current.isRecycled) current.recycle()
    current = square

    repeat(BLUR_PASSES) {
        val next = boxBlur(current, BLUR_RADIUS)
        if (current !== source && current !== next && !current.isRecycled) current.recycle()
        current = next
    }

    return current
}

private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
    if (radius <= 0) return source.copy(Bitmap.Config.ARGB_8888, false)

    val width = source.width
    val height = source.height
    val input = IntArray(width * height)
    val output = IntArray(input.size)
    source.getPixels(input, 0, width, 0, 0, width, height)

    for (y in 0 until height) {
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            var samples = 0

            for (dy in -radius..radius) {
                val sampleY = (y + dy).coerceIn(0, height - 1)
                val row = sampleY * width
                for (dx in -radius..radius) {
                    val sampleX = (x + dx).coerceIn(0, width - 1)
                    val argb = input[row + sampleX]
                    alpha += argb ushr 24 and 0xFF
                    red += argb ushr 16 and 0xFF
                    green += argb ushr 8 and 0xFF
                    blue += argb and 0xFF
                    samples++
                }
            }

            output[y * width + x] =
                ((alpha / samples) shl 24) or
                    ((red / samples) shl 16) or
                    ((green / samples) shl 8) or
                    (blue / samples)
        }
    }

    return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
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

/** Faithful AGSL translation of desktop's SkSL Kawarp shader. */
private val DESKTOP_KAWARP_AGSL =
    """
    uniform shader content;
    uniform float time;
    uniform float2 resolution;
    uniform float saturation;
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
        float2 uv = coord / resolution;
        float t = time * 0.05;

        float2 center = uv - float2(0.5);
        float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));

        float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));
        float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));
        float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));
        float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));

        float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;
        float2 warpedUV = clamp(uv + warp, float2(0.0), float2(1.0));

        half4 sampled = content.eval(warpedUV * float2(128.0));
        float3 rgb = float3(sampled.rgb);

        float vignette = 1.0 - dot(center, center) * 0.3;
        rgb *= vignette;

        float gray = dot(rgb, float3(0.299, 0.587, 0.114));
        rgb = mix(float3(gray), rgb, saturation);
        rgb *= 0.65 * (1.0 + beatPulse * 0.16 * centerWeight);

        return half4(half3(rgb), sampled.a);
    }
    """.trimIndent()
