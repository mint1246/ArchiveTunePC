/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.party.PartyMode
import moe.rukamori.archivetune.desktop.party.PlayTogetherManager
import moe.rukamori.archivetune.desktop.platform.DesktopMusicServices
import moe.rukamori.archivetune.playback.api.PlaybackQueueEntry
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.RepeatMode
import kotlin.math.abs

private val QueueRowHeight = 56.dp
private val QueueDragHandleSize = 28.dp

/**
 * The "up next" queue panel. Renders the queue the same way the Android queue sheet does: previous
 * traversal items before the current item and upcoming traversal items after it.
 */
@Composable
fun DesktopQueuePanel(
    services: DesktopMusicServices,
    playTogether: PlayTogetherManager,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by services.playback.state.collectAsState()
    val partyState by playTogether.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val playback = services.playback
    val entries = snapshot.queueEntries
    val guestInParty = partyState.isConnectedGuest

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            QueueHeader(
                snapshot,
                entries,
                onClose,
                playback,
                controlsEnabled = !guestInParty,
                shuffleEnabled = partyState.mode == PartyMode.NONE,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            if (entries.isEmpty()) {
                EmptyQueue()
            } else {
                QueueList(
                    entries = entries,
                    snapshot = snapshot,
                    playback = playback,
                    playTogether = playTogether,
                    guestInParty = guestInParty,
                    guestCanEditQueue = partyState.canEditQueue,
                    guestCanChangeSong = partyState.canChangeSong,
                    scope = scope,
                )
            }
        }
    }
}

@Composable
private fun QueueHeader(
    snapshot: PlaybackSnapshot,
    entries: List<PlaybackQueueEntry>,
    onClose: () -> Unit,
    playback: moe.rukamori.archivetune.playback.api.PlaybackController,
    controlsEnabled: Boolean,
    shuffleEnabled: Boolean,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val totalDuration = remember(entries) { entries.sumOf { it.item.metadata.durationHintMs ?: 0L } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    snapshot.queueTitle ?: "Queue",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (entries.isEmpty()) "Queue is empty" else "${entries.size} songs • ${formatDuration(totalDuration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ShuffleButton(active = snapshot.shuffleEnabled, enabled = controlsEnabled && shuffleEnabled, onClick = { scope.launch { playback.setShuffleEnabled(!snapshot.shuffleEnabled) } })
            RepeatButton(mode = snapshot.repeatMode, enabled = controlsEnabled, onClick = {
                scope.launch {
                    playback.setRepeatMode(
                        when (snapshot.repeatMode) {
                            RepeatMode.OFF -> RepeatMode.ALL
                            RepeatMode.ALL -> RepeatMode.ONE
                            RepeatMode.ONE -> RepeatMode.OFF
                        },
                    )
                }
            })
            IconButton(onClick = { scope.launch { playback.clearQueue() } }, enabled = controlsEnabled && entries.isNotEmpty()) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear queue")
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close queue") }
        }
    }
}

@Composable
private fun ShuffleButton(active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    DesktopTooltip(if (active) "Shuffle on" else "Shuffle off") {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = if (active) "Shuffle on" else "Shuffle off",
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RepeatButton(mode: RepeatMode, enabled: Boolean, onClick: () -> Unit) {
    val active = mode != RepeatMode.OFF
    DesktopTooltip("Repeat ${mode.name.lowercase()}") {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                if (mode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "Repeat mode ${mode.name.lowercase()}",
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyQueue() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Queue is empty", style = MaterialTheme.typography.titleMedium)
            Text("Play an album, playlist or song to start a queue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QueueList(
    entries: List<PlaybackQueueEntry>,
    snapshot: PlaybackSnapshot,
    playback: moe.rukamori.archivetune.playback.api.PlaybackController,
    playTogether: PlayTogetherManager,
    guestInParty: Boolean,
    guestCanEditQueue: Boolean,
    guestCanChangeSong: Boolean,
    scope: CoroutineScope,
) {
    val order = remember { mutableStateListOf<PlaybackQueueEntry>() }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var dragPointerY by remember { mutableFloatStateOf(Float.NaN) }
    val listState = rememberLazyListState()
    val dragHandleSizePx = with(LocalDensity.current) { QueueDragHandleSize.toPx() }
    val latestEntries by rememberUpdatedState(entries)

    LaunchedEffect(entries) {
        if (draggedId != null) return@LaunchedEffect
        order.clear()
        order.addAll(entries)
    }

    LaunchedEffect(snapshot.currentIndex) {
        if (draggedId != null) return@LaunchedEffect
        val currentRow = order.indexOfFirst { it.isCurrent }
        if (currentRow >= 0) listState.scrollToItem(currentRow)
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
        itemsIndexed(order, key = { _, entry -> entry.id }) { _, entry ->
            QueueRow(
                entry = entry,
                snapshot = snapshot,
                isDragging = draggedId == entry.id,
                playNowEnabled = !guestInParty || guestCanChangeSong,
                playNextEnabled = !guestInParty,
                removeEnabled = !guestInParty || (guestCanEditQueue && !entry.isCurrent),
                dragHandleEnabled = !guestInParty && !entry.isCurrent,
                onRowClick = {
                    scope.launch {
                        if (guestInParty) playTogether.selectQueueItem(entry.logicalIndex)
                        else playback.seekToItem(entry.logicalIndex)
                    }
                },
                onPlayNext = { scope.launch { playback.moveQueueItemToNext(entry.logicalIndex) } },
                onRemove = {
                    scope.launch {
                        if (guestInParty) playTogether.removeFromQueue(entry.logicalIndex)
                        else playback.removeQueueItem(entry.logicalIndex)
                    }
                },
                onDragStart = { pointerInHandle ->
                    draggedId = entry.id
                    dragPointerY = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == entry.id }
                        ?.let { item ->
                            item.offset + (item.size - dragHandleSizePx) / 2f + pointerInHandle.y
                        }
                        ?: Float.NaN
                },
                onDrag = { change, amount ->
                    change.consume()
                    val dragged = draggedId
                    val from = dragged?.let { queueEntryIndex(order, it) } ?: -1
                    if (from != -1 && dragged != null) {
                        if (!dragPointerY.isFinite()) {
                            listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == dragged }
                                ?.let { it.offset + it.size / 2f }
                                ?.let { dragPointerY = it }
                        }
                        if (dragPointerY.isFinite()) {
                            dragPointerY += amount.y
                            autoScroll(listState, scope, dragPointerY)
                            val target = queueReorderTarget(
                                draggedEntryId = dragged,
                                currentIndex = from,
                                pointerY = dragPointerY,
                                visibleItems = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                                    val entryId = item.key as? Long ?: return@mapNotNull null
                                    QueueVisibleItemLayout(entryId, item.index, item.offset, item.size)
                                },
                                itemCount = order.size,
                            )
                            if (target != from) {
                                val moved = order.removeAt(from)
                                order.add(target, moved)
                            }
                        }
                    }
                },
                onDragEnd = {
                    val dragged = draggedId
                    if (dragged != null) {
                        val source = queueEntryIndex(latestEntries, dragged)
                        val destination = queueEntryIndex(order, dragged)
                        if (source != -1 && destination != -1 && source != destination) {
                            scope.launch { playback.moveQueueItem(source, destination) }
                        }
                    }
                    draggedId = null
                    dragPointerY = Float.NaN
                },
                onDragCancel = {
                    draggedId = null
                    dragPointerY = Float.NaN
                },
            )
        }
    }
}

private fun autoScroll(
    listState: androidx.compose.foundation.lazy.LazyListState,
    scope: CoroutineScope,
    pointerY: Float,
) {
    val layoutInfo = listState.layoutInfo
    val delta = queueAutoScrollDelta(pointerY, layoutInfo.viewportStartOffset, layoutInfo.viewportEndOffset)
    if (delta == 0f) return
    scope.launch {
        listState.scroll(MutatePriority.Default) { scrollBy(delta) }
    }
}

internal data class QueueVisibleItemLayout(
    val entryId: Long,
    val index: Int,
    val offset: Int,
    val size: Int,
) {
    val centerY: Float
        get() = offset + size / 2f
}

internal fun queueEntryIndex(entries: List<PlaybackQueueEntry>, entryId: Long): Int =
    entries.indexOfFirst { it.id == entryId }

internal fun queueReorderTarget(
    draggedEntryId: Long,
    currentIndex: Int,
    pointerY: Float,
    visibleItems: List<QueueVisibleItemLayout>,
    itemCount: Int,
): Int {
    if (itemCount <= 0) return -1
    val clampedCurrent = currentIndex.coerceIn(0, itemCount - 1)
    if (!pointerY.isFinite()) return clampedCurrent
    val target = visibleItems
        .asSequence()
        .filter { it.index in 0 until itemCount }
        .minByOrNull { abs(pointerY - it.centerY) }
        ?: return clampedCurrent
    return if (target.entryId == draggedEntryId) clampedCurrent else target.index.coerceIn(0, itemCount - 1)
}

internal fun queueAutoScrollDelta(
    pointerY: Float,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
): Float {
    if (!pointerY.isFinite() || viewportEndOffset <= viewportStartOffset) return 0f
    val viewportHeight = (viewportEndOffset - viewportStartOffset).toFloat()
    val topEdge = viewportStartOffset + viewportHeight * 0.12f
    val bottomEdge = viewportStartOffset + viewportHeight * 0.88f
    return when {
        pointerY < topEdge -> (pointerY - topEdge) * 0.15f
        pointerY > bottomEdge -> (pointerY - bottomEdge) * 0.15f
        else -> 0f
    }
}

@Composable
private fun QueueRow(
    entry: PlaybackQueueEntry,
    snapshot: PlaybackSnapshot,
    isDragging: Boolean,
    playNowEnabled: Boolean,
    playNextEnabled: Boolean,
    removeEnabled: Boolean,
    dragHandleEnabled: Boolean,
    onRowClick: () -> Unit,
    onPlayNext: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (PointerInputChange, Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val item = entry.item.metadata
    val isCurrent = entry.isCurrent
    val rowBackground =
        if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else if (hovered) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainerHigh

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            .background(if (isDragging) MaterialTheme.colorScheme.primaryContainer else rowBackground)
            .hoverable(interactionSource)
            .combinedClickable(
                onClick = { if (playNowEnabled) onRowClick() },
                onLongClick = { if (playNowEnabled || playNextEnabled || removeEnabled) menuOpen = true },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            if (item.artworkUri != null) {
                AsyncImage(item.artworkUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { "Unknown title" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                item.artistText.ifBlank { " " },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isCurrent && snapshot.isPlaying) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = "Now playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        } else if (isCurrent) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Paused", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Text(
            item.durationHintMs?.let(::formatDuration) ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (playNowEnabled || playNextEnabled || removeEnabled) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Queue item actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (playNowEnabled) {
                        DropdownMenuItem(
                            text = { Text("Play now") },
                            onClick = { menuOpen = false; onRowClick() },
                        )
                    }
                    if (playNextEnabled && !isCurrent) {
                        DropdownMenuItem(
                            text = { Text("Play next") },
                            onClick = { menuOpen = false; onPlayNext() },
                        )
                    }
                    if (removeEnabled) {
                        DropdownMenuItem(
                            text = { Text("Remove from queue") },
                            onClick = { menuOpen = false; onRemove() },
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.width(36.dp))
        }
        if (dragHandleEnabled) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = "Reorder",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .size(QueueDragHandleSize)
                    .pointerInput(entry.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset -> onDragStart(offset) },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragCancel() },
                            onDrag = { change, amount -> onDrag(change, amount) },
                        )
                    },
            )
        } else {
            Spacer(Modifier.width(QueueDragHandleSize))
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds) else "%d:%02d".format(minutes, seconds)
}
