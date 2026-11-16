/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.impl.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/** Android DynamicAppBackdrop uses a 128 px field and three radius-four box passes. */
private const val BLUR_SIZE = 128

/** Keep the ambient shader at 60 fps even on high-refresh desktop displays. */
private const val BACKGROUND_FRAME_INTERVAL_NANOS = 1_000_000_000L / 60L

/** The domain-warp SkSL source (exposed for the compile smoke test). */
internal fun kawarpShaderSource(): String = """
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
    float vignette = 1.0 - dot(center, center) * 0.16;
    color.rgb *= vignette;

    // Saturation then brightness (matches shader saturation x CSS saturate/brightness)
    float gray = dot(color.rgb, float3(0.299, 0.587, 0.114));
    color.rgb = mix(float3(gray), color.rgb, saturation);
    // Keep the beat response concentrated around the center so it reads as a soft glow rather
    // than a full-screen flash. The supplied pulse is already scaled by the user's intensity.
    color.rgb *= 0.88 * (1.0 + beatPulse * 0.16 * centerWeight);

    return color;
}
""".trimIndent()

/** Convert a cover URI into a loadable http(s) URL, or null if it can't be fetched. */
private fun resolveCoverUrl(artworkUri: String?): String? {
    if (artworkUri.isNullOrBlank()) return null
    return when {
        artworkUri.startsWith("http://") || artworkUri.startsWith("https://") -> artworkUri
        artworkUri.startsWith("spotify:image:") -> "https://i.scdn.co/image/" + artworkUri.removePrefix("spotify:image:")
        else -> null
    }
}

/** Decode artwork and produce the blurred 128x128 colour field Skia Image (Kawarp blur equivalent). */
private fun makeColourField(bytes: ByteArray): Image? {
    return runCatching {
        var image = Image.makeFromEncoded(bytes)
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

            Bitmap().use { target ->
                target.allocPixels(ImageInfo.makeN32Premul(BLUR_SIZE, BLUR_SIZE))
                Canvas(target).use { canvas ->
                    canvas.drawImageRect(
                        image,
                        Rect.makeXYWH(0f, 0f, image.width.toFloat(), image.height.toFloat()),
                        Rect.makeXYWH(0f, 0f, BLUR_SIZE.toFloat(), BLUR_SIZE.toFloat()),
                        SamplingMode.LINEAR, null, false,
                    )
                }
                var pixels = IntArray(BLUR_SIZE * BLUR_SIZE) { index ->
                    target.getColor(index % BLUR_SIZE, index / BLUR_SIZE)
                }
                repeat(3) { pixels = androidBackdropBoxBlur(pixels, BLUR_SIZE, BLUR_SIZE, 4) }
                // Explicit RGBA avoids native byte-order differences between simulator targets.
                val rgba = ByteArray(pixels.size * 4)
                pixels.forEachIndexed { index, argb ->
                    rgba[index * 4] = (argb ushr 16).toByte()
                    rgba[index * 4 + 1] = (argb ushr 8).toByte()
                    rgba[index * 4 + 2] = argb.toByte()
                    rgba[index * 4 + 3] = (argb ushr 24).toByte()
                }
                Bitmap().use { output ->
                    check(output.installPixels(ImageInfo(BLUR_SIZE, BLUR_SIZE, ColorType.RGBA_8888,
                        ColorAlphaType.UNPREMUL), rgba, BLUR_SIZE * 4))
                    Image.makeFromBitmap(output)
                }
            }
        } finally {
            image.close()
        }
    }.getOrNull()
}

@Composable
internal fun AndroidDynamicBackdrop(
    artworkUri: String?,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    beatPulse: () -> Float = { 0f },
) {
    var effect by remember { mutableStateOf<RuntimeEffect?>(null) }
    var childShader by remember { mutableStateOf<Shader?>(null) }
    var time by remember { mutableFloatStateOf(0f) }
    val playing by rememberUpdatedState(isPlaying)

    // Compile the shader once.
    LaunchedEffect(Unit) {
        effect = withContext(Dispatchers.Default) {
            runCatching { RuntimeEffect.makeForShader(kawarpShaderSource()) }.getOrNull()
        }
    }

    // Load artwork when the URI changes and build the child colour-field shader.
    LaunchedEffect(artworkUri) {
        val url = resolveCoverUrl(artworkUri)
        val next = if (url == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                try { makeColourField(loadArtworkBytes(url)) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
            }?.use { img ->
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

            .drawBehind {
                val e = effect
                val child = childShader
                if (e != null && child != null && size.width > 0 && size.height > 0) {
                    RuntimeShaderBuilder(e).use { builder ->
                        builder.uniform("time", time)
                        builder.uniform("resolution", size.width.toFloat(), size.height.toFloat())
                        builder.uniform("saturation", 4.25f)
                        builder.uniform("beatPulse", beatPulse().coerceIn(0f, 1f))
                        builder.child("content", child)
                        builder.makeShader(null).use { skiaShader ->
                            drawRect(brush = ShaderBrush(skiaShader))
                            drawRect(Color.Black.copy(alpha = 0.06f))
                        }
                    }
                }
            },
    )
}
