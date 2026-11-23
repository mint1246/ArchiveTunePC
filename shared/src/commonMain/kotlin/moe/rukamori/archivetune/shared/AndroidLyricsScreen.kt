package moe.rukamori.archivetune.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.abs
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import moe.rukamori.archivetune.shared.resources.*

/** Full-screen Android-style lyrics surface. The caller owns navigation and version selection. */
@Composable
internal fun AndroidLyricsScreen(
    controller: MusicController,
    onDismiss: () -> Unit,
    onQueueClick: () -> Unit,
    onFindVersions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playback by controller.playback.collectAsState()
    val lyrics by controller.lyrics.collectAsState()
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var manual by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var scrub by remember(playback.track?.id) { mutableStateOf<Float?>(null) }
    val active = lyrics.activeLines((scrub?.toLong() ?: playback.positionMs))
    LaunchedEffect(dragging) {
        if (dragging) manual = true else if (manual) { delay(3000); manual = false }
    }
    LaunchedEffect(lyrics.trackId) { list.scrollToItem(0) }
    LaunchedEffect(lyrics.trackId, active?.first, manual) {
        if (!manual && active != null) {
            val viewport = list.layoutInfo.viewportSize.height
            list.animateScrollToItem(active!!.first + 1, -(viewport * .45f).toInt())
        }
    }
    Box(modifier.fillMaxSize().background(Color(0xff242329))) {
        playback.track?.artworkUrl?.let { url ->
            AsyncImage(url, null, Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = .45f }, contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .48f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onDismiss))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                    LyricsIcon(Res.drawable.music_note, null, Modifier.size(26.dp), Color.White.copy(alpha = .72f))
                    playback.track?.artworkUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(playback.track?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(playback.track?.artist.orEmpty(), color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Find another version", Modifier.clickable(onClick = onFindVersions), color = Color.White.copy(alpha = .68f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                LyricsButton(Res.drawable.close, "Close", onDismiss)
                Box {
                    LyricsButton(Res.drawable.more_horiz, "More options", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Find another version") }, onClick = { menu = false; onFindVersions() })
                        DropdownMenuItem(text = { Text("Queue") }, onClick = { menu = false; onQueueClick() })
                        DropdownMenuItem(text = { Text("Refresh lyrics") }, onClick = { menu = false; controller.retryLyrics() })
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val horizontal = (maxWidth * .06f).coerceIn(20.dp, 48.dp)
                when {
                    lyrics.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    lyrics.error != null || lyrics.lines.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(horizontal = horizontal), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(lyrics.error?.message ?: "Lyrics not found", color = Color.White.copy(alpha = .72f))
                        TextButton(onClick = onFindVersions) { Text("Find another version", color = Color.White) }
                    }
                    else -> LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = horizontal)) {
                        item { Spacer(Modifier.height(120.dp)) }
                        itemsIndexed(lyrics.lines, key = { index, _ -> "${lyrics.trackId}:$index" }) { index, line ->
                            val synced = line.startTimeMs != null && active != null
                            val distance = if (synced) active!!.minOf { abs(index - it) } else 0
                            val alpha by animateFloatAsState(if (synced) (1f / (1f + distance * .8f)).coerceIn(.25f, 1f) else .92f, spring(dampingRatio = .92f, stiffness = 180f))
                            val scale by animateFloatAsState(if (synced) (1.05f - distance * .06f).coerceIn(.97f, 1.05f) else 1f, spring(dampingRatio = .88f, stiffness = 180f))
                            val blur by animateFloatAsState(if (synced && !manual) (1.15f * distance).coerceIn(0f, 3.5f) else 0f, spring(dampingRatio = 1f, stiffness = 220f))
                            Text(line.text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.4.dp).blur(blur.dp).graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, .5f) }.clickable(enabled = line.startTimeMs != null) { line.startTimeMs?.let(controller::seekTo) }, color = Color.White, fontSize = 26.sp, lineHeight = 33.8.sp, fontWeight = FontWeight.Bold)
                        }
                        item { Spacer(Modifier.height(300.dp)) }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val duration = playback.durationMs.coerceAtLeast(1L)
                val position = (scrub ?: playback.positionMs.toFloat()).coerceIn(0f, duration.toFloat())
                Slider(value = position, onValueChange = { scrub = it }, onValueChangeFinished = { scrub?.let { controller.seekTo(it.toLong()) }; scrub = null }, valueRange = 0f..duration.toFloat(), enabled = playback.durationMs > 0, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .28f)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(lyricsTime(position.toLong()), color = Color.White.copy(alpha = .54f), style = MaterialTheme.typography.labelMedium)
                    Text(if (playback.durationMs > 0) "-${lyricsTime(duration - position.toLong())}" else "", color = Color.White.copy(alpha = .54f), style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 26.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    LyricsButton(Res.drawable.skip_previous, "Previous", controller::previous, 68, 44)
                    if (playback.isBuffering) Box(Modifier.size(74.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(42.dp), color = Color.White) }
                    else LyricsButton(if (playback.isPlaying) Res.drawable.pause else Res.drawable.play, if (playback.isPlaying) "Pause" else "Play", controller::togglePlayback, 74, 54)
                    LyricsButton(Res.drawable.skip_next, "Next", controller::next, 68, 44)
                }
            }
        }
    }
}

@Composable
private fun LyricsIcon(icon: DrawableResource, description: String?, modifier: Modifier = Modifier, tint: Color = Color.White) {
    Icon(painterResource(icon), description, modifier, tint)
}

@Composable
private fun LyricsButton(icon: DrawableResource, description: String, onClick: () -> Unit, touch: Int = 40, size: Int = 24) {
    IconButton(onClick = onClick, modifier = Modifier.size(touch.dp)) { LyricsIcon(icon, description, Modifier.size(size.dp)) }
}

private fun lyricsTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
