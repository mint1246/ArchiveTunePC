/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.*
import org.jetbrains.skia.impl.use
import kotlin.math.*

/** Public artwork only. Palette and backdrop share a bounded, coalesced byte request. */
private object ArtworkBytes {
    val mutex = Mutex()
    val cache = linkedMapOf<String, ByteArray>()
    val pending = mutableMapOf<String, CompletableDeferred<ByteArray>>()
}

internal suspend fun loadArtworkBytes(url: String): ByteArray {
    var leader = false
    val request = ArtworkBytes.mutex.withLock {
        ArtworkBytes.cache.remove(url)?.let { ArtworkBytes.cache[url] = it; return it }
        ArtworkBytes.pending[url] ?: CompletableDeferred<ByteArray>().also {
            ArtworkBytes.pending[url] = it; leader = true
        }
    }
    if (!leader) return request.await()
    try {
        val bytes = withTimeout(15_000) { HttpClient().use { it.get(url).body<ByteArray>() } }
        check(bytes.size <= 4 * 1024 * 1024) { "Artwork exceeds the image limit" }
        ArtworkBytes.mutex.withLock {
            ArtworkBytes.cache[url] = bytes
            while (ArtworkBytes.cache.size > 8 || ArtworkBytes.cache.values.sumOf { it.size } > 8 * 1024 * 1024) {
                ArtworkBytes.cache.remove(ArtworkBytes.cache.keys.first())
            }
        }
        request.complete(bytes)
        return bytes
    } catch (failure: Throwable) {
        request.completeExceptionally(failure)
        throw failure
    } finally {
        withContext(NonCancellable) { ArtworkBytes.mutex.withLock { ArtworkBytes.pending.remove(url) } }
    }
}

private val paletteMutex = Mutex()
private val paletteCache = linkedMapOf<String, ThemeSeedPalette>()

@Composable
internal fun rememberArtworkThemePalette(url: String?): State<ThemeSeedPalette?> = produceState<ThemeSeedPalette?>(null, url) {
    if (url == null) { value = null; return@produceState }
    try {
        value = withContext(Dispatchers.Default) {
            paletteMutex.withLock { paletteCache.remove(url)?.also { paletteCache[url] = it } }
                ?: decodeArtworkPalette(loadArtworkBytes(url)).also { palette ->
                    paletteMutex.withLock {
                        paletteCache[url] = palette
                        while (paletteCache.size > 8) paletteCache.remove(paletteCache.keys.first())
                    }
                }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { value = null }
}

/** Palette.Builder's default 112×112 area limit and unfiltered nearest-neighbor scaling. */
private fun decodeArtworkPalette(bytes: ByteArray): ThemeSeedPalette = Image.makeFromEncoded(bytes).use { image ->
    require(image.width > 0 && image.height > 0)
    val area = image.width.toDouble() * image.height
    val scale = if (area > 112 * 112) sqrt(112 * 112 / area) else 1.0
    val width = ceil(image.width * scale).toInt().coerceAtLeast(1)
    val height = ceil(image.height * scale).toInt().coerceAtLeast(1)
    Bitmap().use { bitmap ->
        bitmap.allocPixels(ImageInfo.makeN32Premul(width, height))
        Canvas(bitmap).use { canvas ->
            canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(width.toFloat(), height.toFloat()), SamplingMode.DEFAULT, null, false)
        }
        val pixels = IntArray(width * height) { bitmap.getColor(it % width, it / width) }
        rankAndroidThemeSeeds(androidPaletteSwatches(pixels, 48))
    }
}

/** The ranking and fallback rules from Android Theme.kt.extractThemeSeedPalette. */
internal fun rankAndroidThemeSeeds(swatches: List<AndroidPaletteSwatch>): ThemeSeedPalette {
    val fallback = Color(0xFFED5564)
    if (swatches.isEmpty()) return ThemeSeedPalette(fallback, shiftThemeHue(fallback, 42f), shiftThemeHue(fallback, -58f), Color(0xFF353A36))
    data class Ranked(val swatch: AndroidPaletteSwatch, val hsv: FloatArray)
    val colors = swatches.filter { it.population > 0 }.map { Ranked(it, androidHsv(it.rgb)) }
    if (colors.isEmpty()) return rankAndroidThemeSeeds(emptyList())
    val dominant = colors.maxBy { it.swatch.population }
    val ranked = colors.sortedByDescending { it.swatch.population * (.45f + it.hsv[1] * 1.35f) * (.55f + it.hsv[2]) }
    fun distance(a: Float, b: Float) = abs(a - b).let { min(it, 360f - it) }
    val primary = ranked.firstOrNull { it.hsv[1] >= .16f && it.hsv[2] >= .16f } ?: dominant
    val secondary = ranked.firstOrNull { it !== primary && distance(primary.hsv[0], it.hsv[0]) >= 28f && it.hsv[1] >= .12f }
    val tertiary = ranked.firstOrNull { it !== primary && it !== secondary && distance(primary.hsv[0], it.hsv[0]) >= 42f &&
        (secondary == null || distance(secondary.hsv[0], it.hsv[0]) >= 24f) && it.hsv[1] >= .10f }
    val primaryColor = if (primary.hsv[1] < .12f) fallback else Color(primary.swatch.rgb)
    return ThemeSeedPalette(primaryColor, secondary?.let { Color(it.swatch.rgb) } ?: shiftThemeHue(primaryColor, 42f),
        tertiary?.let { Color(it.swatch.rgb) } ?: shiftThemeHue(primaryColor, -58f), Color(dominant.swatch.rgb))
}


private fun shiftThemeHue(color: Color, degrees: Float): Color {
    val hsv = androidHsv(color.toArgb())
    return Color(androidHsvToArgb(((hsv[0] + degrees) % 360f + 360f) % 360f, hsv[1].coerceAtLeast(.48f), hsv[2].coerceAtLeast(.58f)))
}
