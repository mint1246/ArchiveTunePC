/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min

data class DynamicThemePalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val neutral: Color,
) {
    companion object {
        val Fallback = DynamicThemePalette(
            primary = Color(0xFF9FC8A6),
            secondary = Color(0xFFB6CCB8),
            tertiary = Color(0xFFAFCBDD),
            neutral = Color(0xFF353A36),
        )
    }
}

/**
 * Keeps the previous palette visible while the next cover is decoded, so changing songs never
 * flashes back to ArchiveTune's fallback green between two valid dynamic palettes.
 */
@Composable
internal fun rememberDynamicThemePalette(artworkUri: String?): DynamicThemePalette {
    var palette by remember { mutableStateOf(DynamicThemePalette.Fallback) }
    LaunchedEffect(artworkUri) {
        if (artworkUri.isNullOrBlank()) {
            palette = DynamicThemePalette.Fallback
            return@LaunchedEffect
        }
        val next = withContext(Dispatchers.IO) { cachedDynamicThemePalette(artworkUri) }
        palette = next ?: DynamicThemePalette.Fallback
    }
    return palette
}

private data class QuantizedColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val hue: Float,
    val saturation: Float,
    val brightness: Float,
    val population: Int,
) {
    val composeColor: Color
        get() = Color(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f), 1f)
}

private data class ColorBucket(
    var population: Int = 0,
    var red: Long = 0,
    var green: Long = 0,
    var blue: Long = 0,
)

private val paletteCache = object : LinkedHashMap<String, DynamicThemePalette>(PALETTE_CACHE_SIZE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DynamicThemePalette>?): Boolean =
        size > PALETTE_CACHE_SIZE
}

private fun cachedDynamicThemePalette(uri: String): DynamicThemePalette? {
    synchronized(paletteCache) { paletteCache[uri] }?.let { return it }
    // Artwork decoding/processing is best-effort. A malformed or unusual cover must never
    // cancel the Compose LaunchedEffect and take down the desktop app.
    val extracted = runCatching { extractDynamicThemePalette(uri) }.getOrNull() ?: return null
    synchronized(paletteCache) { paletteCache[uri] = extracted }
    return extracted
}

private fun extractDynamicThemePalette(uri: String): DynamicThemePalette? {
    val source = loadArtwork(uri) ?: return null
    val field = makeSpicyColourField(source)
    val colors = quantize(field)
    if (colors.isEmpty()) return null

    val dominant = colors.maxByOrNull { it.population } ?: return null
    val ranked = colors.sortedByDescending { color ->
        color.population * (0.45f + color.saturation * 1.35f) * (0.55f + color.brightness)
    }

    val primary = ranked.firstOrNull { it.saturation >= 0.16f && it.brightness >= 0.16f } ?: dominant
    val secondary = ranked.firstOrNull {
        it !== primary && hueDistance(primary.hue, it.hue) >= 28f && it.saturation >= 0.12f
    } ?: primary.shiftHue(42f)
    val tertiary = ranked.firstOrNull {
        it !== primary && it !== secondary &&
            hueDistance(primary.hue, it.hue) >= 42f &&
            hueDistance(secondary.hue, it.hue) >= 24f &&
            it.saturation >= 0.10f
    } ?: primary.shiftHue(-58f)

    val primaryColor = if (primary.saturation < 0.12f) DynamicThemePalette.Fallback.primary else primary.composeColor
    return DynamicThemePalette(
        primary = primaryColor,
        secondary = secondary.composeColor,
        tertiary = tertiary.composeColor,
        neutral = dominant.composeColor,
    )
}

/** Match the lyrics renderer's visual input: square downsample followed by a heavy blur. */
private fun makeSpicyColourField(source: BufferedImage): BufferedImage {
    var current = source
    while (current.width > FIELD_SIZE * 2 || current.height > FIELD_SIZE * 2) {
        current = scale(current, maxOf(1, current.width / 2), maxOf(1, current.height / 2))
    }
    current = scale(current, FIELD_SIZE, FIELD_SIZE)

    repeat(BLUR_PASSES) {
        current = boxBlur(current, BLUR_KERNEL / 2)
    }
    return current
}

/**
 * Small deterministic ARGB box blur used for dynamic-theme palette extraction.
 *
 * Java2D's native [java.awt.image.ConvolveOp] path can reject otherwise valid decoded artwork
 * with `ImagingOpException: Unable to convolve src image` on Windows. The field is only 128x128,
 * so doing the blur directly is cheap and avoids image-type/native-pipeline restrictions.
 */
private fun boxBlur(source: BufferedImage, radius: Int): BufferedImage {
    if (radius <= 0) return source

    val width = source.width
    val height = source.height
    val input = IntArray(width * height)
    val output = IntArray(input.size)
    source.getRGB(0, 0, width, height, input, 0, width)

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

    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { target ->
        target.setRGB(0, 0, width, height, output, 0, width)
    }
}

private fun scale(source: BufferedImage, width: Int, height: Int): BufferedImage {
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    target.createGraphics().use { graphics ->
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(source, 0, 0, width, height, null)
    }
    return target
}

private fun quantize(image: BufferedImage): List<QuantizedColor> {
    val buckets = HashMap<Int, ColorBucket>()
    for (y in 0 until image.height step SAMPLE_STEP) {
        for (x in 0 until image.width step SAMPLE_STEP) {
            val argb = image.getRGB(x, y)
            if ((argb ushr 24) < 0xC0) continue
            val red = argb ushr 16 and 0xFF
            val green = argb ushr 8 and 0xFF
            val blue = argb and 0xFF
            val hsb = java.awt.Color.RGBtoHSB(red, green, blue, null)
            val hueBin = (hsb[0] * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            val saturationBin = (hsb[1] * SATURATION_BINS).toInt().coerceIn(0, SATURATION_BINS - 1)
            val brightnessBin = (hsb[2] * BRIGHTNESS_BINS).toInt().coerceIn(0, BRIGHTNESS_BINS - 1)
            val key = (hueBin shl 8) or (saturationBin shl 4) or brightnessBin
            buckets.getOrPut(key, ::ColorBucket).apply {
                population++
                this.red += red.toLong()
                this.green += green.toLong()
                this.blue += blue.toLong()
            }
        }
    }

    return buckets.values.mapNotNull { bucket ->
        if (bucket.population <= 0) return@mapNotNull null
        val red = (bucket.red / bucket.population).toInt().coerceIn(0, 255)
        val green = (bucket.green / bucket.population).toInt().coerceIn(0, 255)
        val blue = (bucket.blue / bucket.population).toInt().coerceIn(0, 255)
        val hsb = java.awt.Color.RGBtoHSB(red, green, blue, null)
        QuantizedColor(
            red = red / 255f,
            green = green / 255f,
            blue = blue / 255f,
            hue = hsb[0] * 360f,
            saturation = hsb[1],
            brightness = hsb[2],
            population = bucket.population,
        )
    }
}

private fun loadArtwork(artworkUri: String): BufferedImage? = runCatching {
    when {
        artworkUri.startsWith("spotify:image:") ->
            ImageIO.read(URI("https://i.scdn.co/image/" + artworkUri.removePrefix("spotify:image:")).toURL())
        artworkUri.startsWith("http://") || artworkUri.startsWith("https://") || artworkUri.startsWith("file:") ->
            ImageIO.read(URI(artworkUri).toURL())
        else -> {
            val path = Path.of(artworkUri)
            if (Files.isRegularFile(path)) ImageIO.read(path.toFile()) else null
        }
    }
}.getOrNull()

private fun QuantizedColor.shiftHue(degrees: Float): QuantizedColor {
    val shifted = ((hue + degrees) % 360f + 360f) % 360f
    val rgb = java.awt.Color.HSBtoRGB(shifted / 360f, saturation.coerceAtLeast(0.48f), brightness.coerceAtLeast(0.58f))
    val red = rgb ushr 16 and 0xFF
    val green = rgb ushr 8 and 0xFF
    val blue = rgb and 0xFF
    return copy(
        red = red / 255f,
        green = green / 255f,
        blue = blue / 255f,
        hue = shifted,
        saturation = saturation.coerceAtLeast(0.48f),
        brightness = brightness.coerceAtLeast(0.58f),
    )
}

private fun hueDistance(a: Float, b: Float): Float {
    val raw = abs(a - b)
    return min(raw, 360f - raw)
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        dispose()
    }

private const val FIELD_SIZE = 128
private const val BLUR_KERNEL = 9
private const val BLUR_PASSES = 3
private const val SAMPLE_STEP = 2
private const val HUE_BINS = 24
private const val SATURATION_BINS = 4
private const val BRIGHTNESS_BINS = 4
private const val PALETTE_CACHE_SIZE = 12
