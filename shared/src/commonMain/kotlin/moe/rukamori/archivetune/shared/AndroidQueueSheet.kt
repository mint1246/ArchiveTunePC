/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.painterResource
import moe.rukamori.archivetune.shared.resources.*
import kotlin.math.roundToInt

@Composable
internal fun AndroidPlayerPanels(controller: MusicController, queueExpanded: Boolean, lyricsExpanded: Boolean, onQueueExpandedChange: (Boolean) -> Unit, onLyricsExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        AndroidQueueSheet(controller, queueExpanded, onQueueExpandedChange)
        AndroidLyricsOverlay(controller, lyricsExpanded, onLyricsExpandedChange, {
            onLyricsExpandedChange(false)
            onQueueExpandedChange(true)
        })
    }
}

@Composable
internal fun AndroidQueueSheet(controller: MusicController, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val options by controller.playbackOptions.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(expanded) {
        if (expanded && state.queueIndex in state.queue.indices) listState.scrollToItem(state.queueIndex)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val offset = remember { Animatable(heightPx) }
        val scope = rememberCoroutineScope()
        var draggingSheet by remember { mutableStateOf(false) }
        fun settleSheet(velocity: Float = 0f) {
            val close = velocity > 250f || (velocity >= -250f && offset.value > heightPx / 2f)
            draggingSheet = false
            if (close) onExpandedChange(false)
            scope.launch { offset.animateTo(if (close) heightPx else 0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) }
        }
        fun dragSheet(delta: Float): Float {
            val next = (offset.value + delta).coerceIn(0f, heightPx)
            val consumed = next - offset.value
            draggingSheet = true
            scope.launch { offset.snapTo(next) }
            return consumed
        }
        LaunchedEffect(expanded, heightPx) {
            if (!draggingSheet) offset.animateTo(if (expanded) 0f else heightPx, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
        }
        val nested = remember(heightPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && available.y < 0 && offset.value > 0) Offset(0f, dragSheet(available.y)) else Offset.Zero
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && available.y > 0) Offset(0f, dragSheet(available.y)) else Offset.Zero
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!draggingSheet) return Velocity.Zero
                    settleSheet(available.y)
                    return available
                }
            }
        }
        if (expanded || offset.value < heightPx) Column(Modifier.fillMaxSize().offset { IntOffset(0, offset.value.roundToInt()) }.background(MaterialTheme.colorScheme.surfaceContainer).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().pointerInput(heightPx) {
                detectVerticalDragGestures(onDragEnd = { settleSheet() }, onDragCancel = { settleSheet() }, onVerticalDrag = { change, amount -> change.consume(); dragSheet(amount) })
            }, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onExpandedChange(false) }) { Icon(painterResource(Res.drawable.expand_more), "Close queue") }
                Column(Modifier.weight(1f)) {
                    Text(state.queueTitle?.takeIf { it.isNotBlank() } ?: "Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${state.queue.size} songs", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = controller::toggleShuffle) { Icon(painterResource(Res.drawable.shuffle), "Shuffle", tint = if (options.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = controller::cycleRepeat) { Icon(painterResource(if (options.repeatMode == MusicRepeatMode.ONE) Res.drawable.repeat_one else Res.drawable.repeat), "Repeat", tint = if (options.repeatMode != MusicRepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
            }
            if (state.queue.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(state.error ?: "Queue is empty") }
            else {
                // Keys identify queue occurrences, not media IDs (duplicates are valid).
                val keys = remember { mutableStateListOf<Long>() }
                var nextKey by remember { mutableLongStateOf(0L) }
                var draggedIndex by remember { mutableIntStateOf(-1) }
                var dragOffset by remember { mutableFloatStateOf(0f) }
                val rowHeight = with(LocalDensity.current) { 72.dp.toPx() }
                LaunchedEffect(state.queue.size, draggedIndex) {
                    if (draggedIndex < 0) {
                        while (keys.size < state.queue.size) keys.add(nextKey++)
                        while (keys.size > state.queue.size) keys.removeAt(keys.lastIndex)
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).nestedScroll(nested), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    itemsIndexed(state.queue, key = { index, _ -> keys.getOrNull(index) ?: "pending-$index" }) { index, item ->
                        Row(Modifier.fillMaxWidth().height(72.dp).animateItem().graphicsLayer { if (draggedIndex == index) translationY = dragOffset }.clickable { controller.playAt(index) }, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                Icon(painterResource(Res.drawable.music_note), null)
                                item.artworkUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize()) }
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(item.title, color = if (index == state.queueIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { if (index in keys.indices) keys.removeAt(index); controller.removeQueueItem(index) }) { Icon(painterResource(Res.drawable.close), "Remove from queue", Modifier.size(20.dp)) }
                            val handleColor = MaterialTheme.colorScheme.onSurfaceVariant
                            val currentIndex by rememberUpdatedState(index)
                            Box(Modifier.size(44.dp).pointerInput(keys.getOrNull(index)) {
                                detectVerticalDragGestures(onDragStart = { draggedIndex = currentIndex; dragOffset = 0f }, onDragEnd = { draggedIndex = -1; dragOffset = 0f }, onDragCancel = { draggedIndex = -1; dragOffset = 0f }, onVerticalDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount
                                    val from = draggedIndex
                                    val to = (from + (dragOffset / rowHeight).roundToInt()).coerceIn(0, keys.lastIndex)
                                    if (from in keys.indices && to != from) {
                                        keys.add(to, keys.removeAt(from))
                                        draggedIndex = to
                                        dragOffset -= (to - from) * rowHeight
                                        controller.moveQueueItem(from, to)
                                    }
                                    val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggedIndex }
                                    if (itemInfo != null) {
                                        val center = itemInfo.offset + itemInfo.size / 2f + dragOffset
                                        val layout = listState.layoutInfo
                                        val edge = rowHeight
                                        val scroll = when {
                                            center < layout.viewportStartOffset + edge -> -rowHeight / 5f
                                            center > layout.viewportEndOffset - edge -> rowHeight / 5f
                                            else -> 0f
                                        }
                                        if (scroll != 0f) scope.launch { listState.scrollBy(scroll) }
                                    }
                                })
                            }, contentAlignment = Alignment.Center) {
                                // Android's drag_handle vector: two horizontal 16 x 2 dp bars.
                                Canvas(Modifier.size(24.dp)) {
                                    drawRect(handleColor, topLeft = Offset(size.width / 6f, size.height * 0.375f), size = androidx.compose.ui.geometry.Size(size.width * 2f / 3f, size.height / 12f))
                                    drawRect(handleColor, topLeft = Offset(size.width / 6f, size.height * 0.542f), size = androidx.compose.ui.geometry.Size(size.width * 2f / 3f, size.height / 12f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
