/*
 * Vibrant lyrics background — a faithful port of SpicyLyrics' dynamic backdrop.
 *
 * SpicyLyrics uses Kawarp (WebGL): the album art is Kawase-blurred down to a 128x128
 * colour field, then domain-warped with simplex noise and pushed through
 * `saturate(2.5) brightness(0.65)`. We replicate that exact pipeline with Skia:
 *
 *   1. decode artwork, draw into a 128x128 texture (Kawase blur → colour field)
 *   2. run the SAME domain-warp shader (Ashima-arts simplex noise) via
 *      org.jetbrains.skia.RuntimeEffect
 *   3. apply saturation 3.75 (1.5 shader x 2.5 CSS) + brightness 0.65 + vignette
 *
 * The result is a saturated, multi-coloured, slowly-flowing colour field — not a
 * static blurred box.
 */
package moe.rukamori.archivetune.desktop.lyrics.anim

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.artwork.DesktopArtworkImage
import moe.rukamori.archivetune.desktop.artwork.loadDesktopArtwork
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader

/** Same size Kawarp blurs to. */
private const val BLUR_SIZE = 128

/** Gaussian sigma that (with the multi-stage downscale) matches Kawarp's 8-pass Kawase blur. */
private const val BLUR_SIGMA = 10f

/** Keep the ambient shader at 60 fps even on high-refresh desktop displays. */
private const val BACKGROUND_FRAME_INTERVAL_NANOS = 1_000_000_000L / 60L

/** The domain-warp SkSL source (exposed for the compile smoke test). */
internal fun kawarpShaderSource(): String = """
uniform shader content;
uniform float time;
uniform float2 resolution;
uniform float saturation;

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
    m = m * m; m = m * m;
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

float4 main(float2 coord) {
    float2 uv = coord / resolution;
    float t = time * 0.05;

    float2 center = uv - float2(0.5);
    float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));

    // Large-scale movement (slow, big blobs)
    float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));
    float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0, 50.0));
    // Medium-scale detail (organic movement)
    float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));
    float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));

    float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;
    float2 warpedUV = clamp(uv + warp, float2(0.0), float2(1.0));

    float4 color = content.eval(warpedUV * float2(${BLUR_SIZE}.0));

    // Vignette (matches Kawarp output shader)
    float vignette = 1.0 - dot(center, center) * 0.3;
    color.rgb *= vignette;

    // Saturation then brightness (matches shader saturation x CSS saturate/brightness)
    float gray = dot(color.rgb, float3(0.299, 0.587, 0.114));
    color.rgb = mix(float3(gray), color.rgb, saturation);
    color.rgb *= 0.65;

    return color;
}
""".trimIndent()

/** Decode artwork and produce the blurred 128x128 colour field Skia Image (Kawarp blur equivalent). */
internal fun makeVibrantColourField(artwork: DesktopArtworkImage): Image? {
    return runCatching {
        var image = artwork.toSkiaImage()
        try {
            if (image.width <= 0 || image.height <= 0) return null

            // Repeatedly halve for proper area averaging until close to the target size.
            while (image.width > BLUR_SIZE * 2 || image.height > BLUR_SIZE * 2) {
                val w = maxOf(1, image.width / 2)
                val h = maxOf(1, image.height / 2)
                val next = Bitmap().use { bmp ->
                    bmp.allocPixels(ImageInfo.makeN32Premul(w, h))
                    Canvas(bmp).use { canvas ->
                        canvas.drawImageRect(
                            image,
                            Rect.makeXYWH(0f, 0f, image.width.toFloat(), image.height.toFloat()),
                            Rect.makeXYWH(0f, 0f, w.toFloat(), h.toFloat()),
                            SamplingMode.LINEAR,
                            null,
                            false,
                        )
                    }
                    Image.makeFromBitmap(bmp)
                }
                image.close()
                image = next
            }

            // Final squish to a square colour field with a heavy Gaussian blur so the
            // album art becomes an unrecognisable, smooth colour field.
            Bitmap().use { target ->
                target.allocPixels(ImageInfo.makeN32Premul(BLUR_SIZE, BLUR_SIZE))
                ImageFilter.makeBlur(BLUR_SIGMA, BLUR_SIGMA, FilterTileMode.CLAMP, null, null).use { blur ->
                    Paint().use { paint ->
                        paint.imageFilter = blur
                        Canvas(target).use { canvas ->
                            canvas.drawImageRect(
                                image,
                                Rect.makeXYWH(0f, 0f, image.width.toFloat(), image.height.toFloat()),
                                Rect.makeXYWH(0f, 0f, BLUR_SIZE.toFloat(), BLUR_SIZE.toFloat()),
                                SamplingMode.LINEAR,
                                paint,
                                false,
                            )
                        }
                    }
                }
                Image.makeFromBitmap(target)
            }
        } finally {
            image.close()
        }
    }.getOrNull()
}

private fun DesktopArtworkImage.toSkiaImage(): Image {
    // N32 premultiplied pixels are BGRA on Windows' little-endian JVM. The shared artwork is
    // already downsampled to <=256px, so this transfers a tiny pixel field directly into Skia
    // instead of asking Skia to decode the original full-resolution encoded cover again.
    val bytes = ByteArray(argb.size * 4)
    var offset = 0
    argb.forEach { pixel ->
        val alpha = pixel ushr 24 and 0xFF
        val red = pixel ushr 16 and 0xFF
        val green = pixel ushr 8 and 0xFF
        val blue = pixel and 0xFF
        bytes[offset++] = ((blue * alpha + 127) / 255).toByte()
        bytes[offset++] = ((green * alpha + 127) / 255).toByte()
        bytes[offset++] = ((red * alpha + 127) / 255).toByte()
        bytes[offset++] = alpha.toByte()
    }
    return Bitmap().use { bitmap ->
        check(bitmap.installPixels(ImageInfo.makeN32Premul(width, height), bytes, width * 4))
        Image.makeFromBitmap(bitmap)
    }
}

@Composable
fun VibrantLyricsBackground(
    artworkUri: String?,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
) {
    var effect by remember { mutableStateOf<RuntimeEffect?>(null) }
    var childShader by remember { mutableStateOf<Shader?>(null) }
    var time by remember { mutableFloatStateOf(0f) }
    val playing by rememberUpdatedState(isPlaying)

    // Compile the shader once.
    LaunchedEffect(Unit) {
        effect = withContext(Dispatchers.IO) {
            runCatching { RuntimeEffect.makeForShader(kawarpShaderSource()) }.getOrNull()
        }
    }

    // Load artwork when the URI changes and build the child colour-field shader.
    LaunchedEffect(artworkUri) {
        val artwork = loadDesktopArtwork(artworkUri)
        val next = if (artwork == null) {
            null
        } else {
            withContext(Dispatchers.Default) { makeVibrantColourField(artwork) }?.use { img ->
                img.makeShader(
                    FilterTileMode.CLAMP,
                    FilterTileMode.CLAMP,
                    SamplingMode.LINEAR,
                    null,
                )
            }
        }
        val previous = childShader
        childShader = next
        previous?.close()
    }

    DisposableEffect(Unit) {
        onDispose {
            childShader?.close()
            effect?.close()
        }
    }

    // Advance the warp clock. Paused playback freezes the flow instead of burning
    // a full-screen RuntimeShader rebuild every frame.
    LaunchedEffect(Unit) {
        var lastRendered = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastRendered == 0L) {
                    lastRendered = now
                    return@withFrameNanos
                }

                if (!playing) {
                    lastRendered = now
                } else if (now - lastRendered >= BACKGROUND_FRAME_INTERVAL_NANOS) {
                    time += ((now - lastRendered) / 1e9f).toFloat()
                    lastRendered = now
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF0A0712))
            .drawBehind {
                val e = effect
                val child = childShader
                if (e != null && child != null && size.width > 0 && size.height > 0) {
                    RuntimeShaderBuilder(e).use { builder ->
                        builder.uniform("time", time)
                        builder.uniform("resolution", size.width.toFloat(), size.height.toFloat())
                        builder.uniform("saturation", 3.75f)
                        builder.child("content", child)
                        builder.makeShader(null).use { skiaShader ->
                            drawRect(brush = ShaderBrush(skiaShader.asComposeShader()))
                        }
                    }
                } else {
                    // Fallback gradient when there is no artwork yet.
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF7B1FA2), Color(0xFF2A0845), Color(0xFF0A0712)),
                        ),
                    )
                }
            },
    )
}
