package moe.rukamori.archivetune.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.*
import org.jetbrains.skia.impl.use
import kotlin.math.*

private val lyricsFallback = listOf(Color(0xff202020), Color(0xff141414), Color(0xff050505))
private val lyricsPaletteMutex = Mutex()
private val lyricsPaletteCache = linkedMapOf<String, List<Color>>()

@Composable
fun AndroidLyricsBackdrop(artworkUrl: String?, modifier: Modifier = Modifier) {
    val colors by produceState(lyricsFallback, artworkUrl) {
        value = lyricsFallback
        if (artworkUrl != null) try {
            value = withContext(Dispatchers.Default) {
                lyricsPaletteMutex.withLock { lyricsPaletteCache.remove(artworkUrl)?.also { lyricsPaletteCache[artworkUrl] = it } }
                    ?: decodeLyricsPalette(loadArtworkBytes(artworkUrl)).also { result ->
                        lyricsPaletteMutex.withLock {
                            lyricsPaletteCache[artworkUrl] = result
                            while (lyricsPaletteCache.size > 20) lyricsPaletteCache.remove(lyricsPaletteCache.keys.first())
                        }
                    }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { value = lyricsFallback }
    }
    val brush = remember(colors) { Brush.verticalGradient(listOf(
        colors.getOrElse(0) { lyricsFallback[0] }.copy(alpha = .88f),
        colors.getOrElse(1) { lyricsFallback[1] }.copy(alpha = .76f),
        colors.getOrElse(2) { lyricsFallback[2] }.copy(alpha = .96f))) }
    val scrim = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .28f))) }
    Box(modifier.fillMaxSize().background(lyricsFallback.last())) {
        AnimatedContent(artworkUrl, transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) }, label = "lyrics-apple-background") { url ->
            if (url != null) AsyncImage(url, null, Modifier.fillMaxSize().blur(46.dp).alpha(.62f), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(brush))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f)))
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

private fun decodeLyricsPalette(bytes: ByteArray): List<Color> = Image.makeFromEncoded(bytes).use { image ->
    require(image.width > 0 && image.height > 0)
    // Coil's 128×128 request preserves aspect ratio; Palette then limits area to 8000.
    val requestScale = min(128.0 / image.width, 128.0 / image.height).coerceAtMost(1.0)
    val requestWidth = (image.width * requestScale).roundToInt().coerceAtLeast(1)
    val requestHeight = (image.height * requestScale).roundToInt().coerceAtLeast(1)
    val area = requestWidth.toDouble() * requestHeight
    val scale = if (area > 8000) sqrt(8000.0 / area) else 1.0
    val width = ceil(requestWidth * scale).toInt().coerceAtLeast(1)
    val height = ceil(requestHeight * scale).toInt().coerceAtLeast(1)
    Bitmap().use { requested ->
        requested.allocPixels(ImageInfo.makeN32Premul(requestWidth, requestHeight))
        Canvas(requested).use { canvas -> canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(requestWidth.toFloat(), requestHeight.toFloat()), SamplingMode.DEFAULT, null, false) }
        Image.makeFromBitmap(requested).use { resized ->
    Bitmap().use { bitmap ->
        bitmap.allocPixels(ImageInfo.makeN32Premul(width, height))
        Canvas(bitmap).use { canvas -> canvas.drawImageRect(resized, Rect.makeWH(requestWidth.toFloat(), requestHeight.toFloat()), Rect.makeWH(width.toFloat(), height.toFloat()), SamplingMode.DEFAULT, null, false) }
        lyricsGradient(androidPaletteSwatches(IntArray(width * height) { bitmap.getColor(it % width, it / width) }, 32)).map { Color(it) }
    }
        }
    }
}
