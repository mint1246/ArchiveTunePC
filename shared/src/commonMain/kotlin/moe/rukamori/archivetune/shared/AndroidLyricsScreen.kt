package moe.rukamori.archivetune.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
    val settings = controller.library?.collectAsState()?.value?.settings ?: MusicSettings()
    var menu by remember { mutableStateOf(false) }
    var scrub by remember(playback.track?.id) { mutableStateOf<Float?>(null) }
    val activePos = scrub?.toLong() ?: playback.positionMs

    Box(modifier.fillMaxSize().background(Color(0xff242329))) {
        if (settings.lyricsBackgroundStyle != MusicLyricsBackgroundStyle.SOLID) {
            playback.track?.artworkUrl?.let { url ->
                AsyncImage(
                    url,
                    null,
                    Modifier.fillMaxSize()
                        .then(if (!settings.disableBlur) Modifier.blur(48.dp) else Modifier)
                        .graphicsLayer { alpha = .45f },
                    contentScale = ContentScale.Crop,
                )
            }
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
                        if (lyrics.lines.isNotEmpty() && controller.canShare) {
                            DropdownMenuItem(text = { Text("Share lyrics") }, onClick = {
                                menu = false
                                val header = playback.track?.let { "${it.title} — ${it.artist}\n\n" }.orEmpty()
                                controller.shareText(header + lyrics.lines.joinToString("\n") { it.text })
                            })
                        }
                        DropdownMenuItem(
                            text = { Text("Timing offset -200 ms (${settings.lyricsOffsetMs} ms)") },
                            onClick = {
                                controller.updateSettings { it.copy(lyricsOffsetMs = (it.lyricsOffsetMs - 200L).coerceIn(-5000L, 5000L)) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Timing offset +200 ms (${settings.lyricsOffsetMs} ms)") },
                            onClick = {
                                controller.updateSettings { it.copy(lyricsOffsetMs = (it.lyricsOffsetMs + 200L).coerceIn(-5000L, 5000L)) }
                            },
                        )
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val horizontal = (maxWidth * .04f).coerceIn(12.dp, 36.dp)
                when {
                    lyrics.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    lyrics.error != null || lyrics.lines.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(horizontal = horizontal), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(lyrics.error?.message ?: "Lyrics not found", color = Color.White.copy(alpha = .72f))
                        TextButton(onClick = onFindVersions) { Text("Find another version", color = Color.White) }
                    }
                    else -> AndroidTimedLyrics(
                        lines = lyrics.lines,
                        positionMs = activePos,
                        isPlaying = playback.isPlaying && scrub == null,
                        onSeek = controller::seekTo,
                        modifier = Modifier.fillMaxSize().padding(horizontal = horizontal),
                        textSizeSp = settings.lyricsTextSize,
                        lineSpacingMultiplier = settings.lyricsLineSpacing,
                        alignment = settings.lyricsAlignment,
                        lineBlurEnabled = settings.lyricsLineBlur && !settings.disableBlur,
                        clickToSeek = settings.lyricsClick,
                        autoScroll = settings.lyricsScroll,
                        offsetMs = settings.lyricsOffsetMs,
                        textColor = Color.White,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val duration = playback.durationMs.coerceAtLeast(1L)
                val position = (scrub ?: playback.positionMs.toFloat()).coerceIn(0f, duration.toFloat())
                AndroidStyledPlaybackSlider(
                    value = position,
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { controller.seekTo(it.toLong()) }; scrub = null },
                    valueRange = 0f..duration.toFloat(),
                    style = settings.sliderStyle,
                    isPlaying = playback.isPlaying,
                    activeColor = Color.White,
                    inactiveColor = Color.White.copy(alpha = .28f),
                    enabled = playback.durationMs > 0,
                    modifier = Modifier.fillMaxWidth(),
                )
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
