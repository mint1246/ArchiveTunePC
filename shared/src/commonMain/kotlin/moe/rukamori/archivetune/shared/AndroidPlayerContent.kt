/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.exp
import kotlin.time.TimeSource
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import moe.rukamori.archivetune.shared.resources.*

@Composable
private fun PlayerIcon(resource: DrawableResource, description: String?, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Icon(painterResource(resource), description, modifier, tint)
}

@Composable
private fun PlayerArtwork(track: MusicTrack, modifier: Modifier, circular: Boolean = false) {
    Box(modifier.clip(if (circular) CircleShape else RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        PlayerIcon(Res.drawable.music_note, null, Modifier.size(28.dp), MaterialTheme.colorScheme.primary)
        track.artworkUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AndroidMiniPlayer(controller: MusicController, onOpen: () -> Unit, modifier: Modifier = Modifier, navigationAttached: Boolean = true, navigationProximity: Float = if (navigationAttached) 1f else 0f) {
    val playback by controller.playback.collectAsState()
    val state by controller.state.collectAsState()
    val track = playback.track ?: return
    val progress = if (playback.durationMs > 0) (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f) else 0f
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val direction = LocalLayoutDirection.current
    val swipeSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
    val proximity = navigationProximity.coerceIn(0f, 1f)
    val miniShape = RoundedCornerShape(topStart = (32f - 4f * proximity).dp,
        topEnd = (32f - 4f * proximity).dp,
        bottomStart = (32f - 20f * proximity).dp,
        bottomEnd = (32f - 20f * proximity).dp)
    Row(modifier.fillMaxWidth().height(70.dp).pointerInput(state.queueIndex, state.queue.size, direction) {
        var started = TimeSource.Monotonic.markNow()
        var distance = 0f
        var dragOffset = 0f
        val sensitivity = .73f
        val threshold = (600 / (1f + exp(-(-11.44748f * sensitivity + 9.04945f)))).roundToInt()
        detectHorizontalDragGestures(
            onDragStart = { started = TimeSource.Monotonic.markNow(); distance = 0f; dragOffset = 0f },
            onDragCancel = { scope.launch { offset.animateTo(0f, swipeSpec) } },
            onDragEnd = {
                val elapsed = started.elapsedNow().inWholeMilliseconds
                val velocity = if (elapsed > 0) distance / elapsed else 0f
                if ((abs(dragOffset) > 50f && velocity > sensitivity * -8.25f + 8.5f) || abs(dragOffset) > threshold) {
                    if (dragOffset > 0 && state.queueIndex > 0) controller.previous()
                    else if (dragOffset < 0 && state.queueIndex < state.queue.lastIndex) controller.next()
                }
                scope.launch { offset.animateTo(0f, swipeSpec) }
            },
            onHorizontalDrag = { change, amount ->
                val adjusted = if (direction == LayoutDirection.Rtl) -amount else amount
                if ((adjusted > 0 && state.queueIndex > 0) || (adjusted < 0 && state.queueIndex < state.queue.lastIndex)) {
                    change.consume()
                    distance += abs(adjusted)
                    dragOffset += adjusted
                    scope.launch { offset.snapTo(dragOffset) }
                }
            },
        )
    }.offset { IntOffset((if (direction == LayoutDirection.Rtl) -offset.value else offset.value).roundToInt(), 0) }.clip(miniShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onOpen).padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            if (playback.isBuffering || state.resolvingTrackId != null) CircularWavyProgressIndicator(
                modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = .18f))
            else CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outline.copy(alpha = .18f))
            PlayerArtwork(track, Modifier.size(42.dp).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .2f), CircleShape), circular = true)
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            AnimatedContent(track.title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") { title ->
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.basicMarquee())
            }
            AnimatedContent(track.artist, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "artist") { artist ->
                Text(artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.basicMarquee())
            }
        }
        IconButton(onClick = controller::previous, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) { PlayerIcon(Res.drawable.skip_previous, "Previous", Modifier.size(20.dp)) }
        Spacer(Modifier.width(4.dp))
        FilledIconButton(onClick = controller::togglePlayback, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
            PlayerIcon(when { playback.ended -> Res.drawable.replay; playback.isPlaying -> Res.drawable.pause; else -> Res.drawable.play },
                if (playback.isPlaying) "Pause" else "Play", Modifier.size(24.dp), MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = controller::next, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) { PlayerIcon(Res.drawable.skip_next, "Next", Modifier.size(20.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AndroidFullPlayer(controller: MusicController, onClose: () -> Unit, onOverlayVisibilityChange: (Boolean) -> Unit = {}) {
    val playback by controller.playback.collectAsState()
    val state by controller.state.collectAsState()
    val options by controller.playbackOptions.collectAsState()
    val library = controller.library?.collectAsState()?.value
    val track = playback.track ?: return
    val playPauseCorner by animateDpAsState(if (playback.isPlaying) 28.dp else 44.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "cinematicPlayPauseCorner")
    // The shell's surface role is transparent over artwork; it cannot be an icon's pigment.
    val playIconColor = if (MaterialTheme.colorScheme.onBackground.luminance() > .5f) Color.Black else Color.White
    var panel by rememberSaveable { mutableStateOf("player") }
    SideEffect { onOverlayVisibilityChange(panel != "player") }
    DisposableEffect(Unit) { onDispose { onOverlayVisibilityChange(false) } }
    var menuExpanded by remember { mutableStateOf(false) }
    var timerExpanded by remember { mutableStateOf(false) }
    if (timerExpanded) {
        var minutes by remember { mutableFloatStateOf(30f) }
        AndroidActionPromptDialog(
            onDismiss = { timerExpanded = false },
            titleBar = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Sleep timer", style = MaterialTheme.typography.headlineSmall) } },
            onConfirm = { controller.setSleepTimer(minutes.roundToInt()); timerExpanded = false },
            onCancel = { timerExpanded = false }, onReset = { minutes = 30f },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${minutes.roundToInt()} minutes", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Slider(value = minutes, onValueChange = { minutes = it }, valueRange = 5f..120f, steps = 22, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { controller.setSleepAfterCurrent(); timerExpanded = false }) { Text("End of song") }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Only the active page is interactive or exposed to accessibility. The lyrics and
        // queue keep the shared artwork backdrop, rather than layering over player controls.
        if (panel == "player") Column(Modifier.fillMaxWidth().fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
            AnimatedContent(
                targetState = "player",
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (slideInVertically(spring(stiffness = Spring.StiffnessLow)) { it } + fadeIn()) togetherWith
                        (slideOutVertically(spring(stiffness = Spring.StiffnessLow)) { it } + fadeOut())
                }, label = "player panel",
            ) { currentPanel ->
              Column(Modifier.fillMaxSize()) {
              when (currentPanel) {
                "lyrics" -> NowPlayingLyricsPane(controller, Modifier.weight(1f).fillMaxWidth())
                "queue" -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    item { Text("Up next", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp)) }
                    itemsIndexed(state.queue) { index, item ->
                        Row(Modifier.fillMaxWidth().clickable { controller.playAt(index) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PlayerArtwork(item, Modifier.size(48.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (index == state.queueIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(item.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton(onClick = { controller.removeQueueItem(index) }) { PlayerIcon(Res.drawable.close, "Remove from queue", Modifier.size(20.dp)) }
                        }
                    }
                }
                else -> {
                    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.padding(horizontal = 32.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Now Playing", style = MaterialTheme.typography.titleMedium)
                            state.queueTitle?.takeIf { it.isNotBlank() }?.let { title ->
                                Spacer(Modifier.height(4.dp))
                                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.basicMarquee())
                            }
                        }
                        AndroidThumbnail(controller, Modifier.weight(1f).fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            AnimatedContent(track.title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "player title") { title ->
                                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.basicMarquee())
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(track.artist, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(12.dp))
                        if (controller.canShare) {
                            PlayerSquare(Res.drawable.share, "Share", 44, 22, action = controller::shareCurrentTrack)
                            Spacer(Modifier.width(10.dp))
                        }
                        if (library != null) PlayerSquare(if (library.favorites.any { it.id == track.id }) Res.drawable.favorite else Res.drawable.favorite_border, "Favorite", 44, 22, action = { controller.toggleFavorite(track) })
                        Spacer(Modifier.width(10.dp))
                        Box {
                            PlayerSquare(Res.drawable.more_horiz, "More", 44, 22, action = { menuExpanded = true })
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                    DropdownMenuItem(text = { Text("${speed}x" + if (options.speed == speed) " ✓" else "") }, onClick = { controller.setSpeed(speed); menuExpanded = false })
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    var seeking by remember(track.id) { mutableStateOf<Float?>(null) }
                    val duration = playback.durationMs.coerceAtLeast(1).toFloat()
                    Slider(value = seeking ?: playback.positionMs.toFloat().coerceIn(0f, duration), onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { controller.seekTo(it.toLong()) }; seeking = null }, valueRange = 0f..duration, enabled = playback.durationMs > 0 && state.resolvingTrackId == null, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onBackground, activeTrackColor = MaterialTheme.colorScheme.onBackground, inactiveTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = .24f)), modifier = Modifier.padding(horizontal = 32.dp))
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime((seeking ?: playback.positionMs.toFloat()).toLong()), style = MaterialTheme.typography.labelMedium)
                        Text(formatTime(playback.durationMs), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                    val scale = (((maxWidth - 128.dp) / 2f - 12.dp) / 102.dp).coerceIn(.6f, 1f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        PlayerSquare(Res.drawable.shuffle, "Shuffle", (46 * scale).toInt(), (22 * scale).toInt(), controller::toggleShuffle, active = options.shuffle)
                        Spacer(Modifier.width(12.dp * scale))
                        PlayerSquare(Res.drawable.skip_previous, "Previous", (56 * scale).toInt(), (28 * scale).toInt(), controller::previous)
                        Spacer(Modifier.width(20.dp))
                        Surface(onClick = controller::togglePlayback, modifier = Modifier.size(88.dp), shape = RoundedCornerShape(playPauseCorner), color = MaterialTheme.colorScheme.onBackground) {
                            Box(contentAlignment = Alignment.Center) {
                                if (playback.isBuffering || state.resolvingTrackId != null) CircularWavyProgressIndicator(Modifier.size(40.dp), color = playIconColor)
                                else PlayerIcon(when { playback.ended -> Res.drawable.replay; playback.isPlaying -> Res.drawable.pause; else -> Res.drawable.play }, if (playback.isPlaying) "Pause" else "Play", Modifier.size(44.dp), playIconColor)
                            }
                        }
                        Spacer(Modifier.width(20.dp))
                        PlayerSquare(Res.drawable.skip_next, "Next", (56 * scale).toInt(), (28 * scale).toInt(), controller::next)
                        Spacer(Modifier.width(12.dp * scale))
                        PlayerSquare(if (options.repeatMode == MusicRepeatMode.ONE) Res.drawable.repeat_one else Res.drawable.repeat, "Repeat", (46 * scale).toInt(), (22 * scale).toInt(), controller::cycleRepeat, active = options.repeatMode != MusicRepeatMode.OFF)
                    }
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }
              }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerPill(Res.drawable.queue_music, "Queue", Modifier.weight(1f)) { panel = if (panel == "queue") "player" else "queue" }
                Spacer(Modifier.width(10.dp))
                Box {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = if ((options.sleepTimerMinutes != null || options.sleepAfterCurrent)) .2f else .1f)).clickable { if ((options.sleepTimerMinutes != null || options.sleepAfterCurrent)) controller.setSleepTimer(null) else timerExpanded = true }, contentAlignment = Alignment.Center) {
                        PlayerIcon(Res.drawable.bedtime, "Sleep timer", Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                PlayerPill(Res.drawable.lyrics, "Lyrics", Modifier.weight(1f)) { panel = if (panel == "lyrics") "player" else "lyrics" }
            }
        }
        AndroidPlayerPanels(controller, queueExpanded = panel == "queue", lyricsExpanded = panel == "lyrics",
            onQueueExpandedChange = { panel = if (it) "queue" else "player" },
            onLyricsExpandedChange = { panel = if (it) "lyrics" else "player" })
        }
}

@Composable
private fun PlayerSquare(resource: DrawableResource, description: String, size: Int, iconSize: Int, action: () -> Unit, active: Boolean = false) {
    Surface(onClick = action, modifier = Modifier.size(size.dp), shape = RoundedCornerShape(if (size == 44) 14.dp else 18.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) .2f else if (size == 46) .08f else .12f)) {
        Box(contentAlignment = Alignment.Center) { PlayerIcon(resource, description, Modifier.size(iconSize.dp), MaterialTheme.colorScheme.onSurface.copy(alpha = if (size == 46 && !active) .6f else 1f)) }
    }
}

@Composable
private fun PlayerPill(resource: DrawableResource, label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    Row(modifier.height(48.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .1f)).clickable(onClick = action), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        PlayerIcon(resource, null, Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
