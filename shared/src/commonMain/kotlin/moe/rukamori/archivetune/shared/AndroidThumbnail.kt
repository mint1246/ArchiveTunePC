/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.abs

private data class AndroidThumbnailPage(val slot: String, val index: Int, val track: MusicTrack)

/** The three-slot grid and 500 px/s snap threshold from Android Thumbnail.kt. */
@Composable
internal fun AndroidThumbnail(controller: MusicController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val playback by controller.playback.collectAsState()
    val options by controller.playbackOptions.collectAsState()
    val current = playback.track ?: return
    val pages = remember(state.queue, state.queueIndex, current, options.shuffle) {
        buildList {
            controller.thumbnailNeighbor(-1)?.let { add(AndroidThumbnailPage("previous", it, state.queue[it])) }
            add(AndroidThumbnailPage("current", state.queueIndex, current))
            controller.thumbnailNeighbor(1)?.let { add(AndroidThumbnailPage("next", it, state.queue[it])) }
        }
    }
    val currentPage = pages.indexOfFirst { it.slot == "current" }
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = currentPage.coerceAtLeast(0))
    val snap = remember(grid) { androidThumbnailSnapProvider(grid) }
    val direction = LocalLayoutDirection.current
    var seekEffect by remember { mutableStateOf<String?>(null) }
    var seekSequence by remember { mutableIntStateOf(0) }
    var userSwiping by remember { mutableStateOf(false) }
    LaunchedEffect(seekSequence) { if (seekEffect != null) { delay(1000); seekEffect = null } }
    // Changes from queue controls reposition the center slot without making a second skip.
    LaunchedEffect(current.id, state.queueIndex, pages) {
        userSwiping = false
        grid.scrollToItem(currentPage.coerceAtLeast(0))
    }
    LaunchedEffect(grid, pages, currentPage) {
        snapshotFlow { Triple(grid.isScrollInProgress, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) }
            .collect { (scrolling, page, offset) ->
                if (!scrolling && userSwiping && offset == 0 && page != currentPage && page in pages.indices &&
                    pages[page].index != controller.state.value.queueIndex) {
                    userSwiping = false
                    controller.playAt(pages[page].index)
                }
            }
    }
    BoxWithConstraints(modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            userSwiping = true
            do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed })
        }
    }, contentAlignment = Alignment.Center) {
        val pageWidth = maxWidth
        val coverSize = (pageWidth - 64.dp).coerceAtLeast(1.dp)
        LazyHorizontalGrid(rows = GridCells.Fixed(1), state = grid,
            flingBehavior = rememberSnapFlingBehavior(snap), modifier = Modifier.fillMaxSize()) {
            items(pages, key = { "${it.slot}:${it.index}:${it.track.id}" }, contentType = { "thumbnailPage" }) { page ->
                Box(Modifier.width(pageWidth).fillMaxHeight().padding(horizontal = 32.dp)
                    .pointerInput(page.track.id, direction) {
                        detectTapGestures(onDoubleTap = { offset ->
                            val backward = if (direction == LayoutDirection.Ltr) offset.x < size.width / 2 else offset.x > size.width / 2
                            val old = controller.playback.value
                            controller.seekTo(if (backward) (old.positionMs - 5000).coerceAtLeast(0)
                                else (old.positionMs + 5000).coerceAtMost(old.durationMs.coerceAtLeast(0)))
                            seekEffect = if (backward) "-5 seconds" else "+5 seconds"
                            seekSequence++
                        })
                    }, contentAlignment = Alignment.Center) {
                    val url = page.track.artworkUrl?.androidHighResArtwork()
                    Box(Modifier.size(coverSize).clip(RoundedCornerShape(16.dp))) {
                        AsyncImage(url, null, Modifier.fillMaxSize().graphicsLayer {
                            renderEffect = BlurEffect(36f, 36f); alpha = .6f
                        }, contentScale = ContentScale.FillBounds)
                        AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
            }
        }
        AnimatedVisibility(seekEffect != null, enter = fadeIn(), exit = fadeOut()) {
            Text(seekEffect.orEmpty(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color.Black.copy(alpha = .7f), RoundedCornerShape(8.dp)).padding(8.dp))
        }
    }
}

private fun androidThumbnailSnapProvider(grid: LazyGridState): SnapLayoutInfoProvider = object : SnapLayoutInfoProvider {
    override fun calculateApproachOffset(velocity: Float, decayOffset: Float) = 0f
    override fun calculateSnapOffset(velocity: Float): Float {
        val layout = grid.layoutInfo
        val width = layout.viewportSize.width - layout.beforeContentPadding - layout.afterContentPadding
        var lower = Float.NEGATIVE_INFINITY
        var upper = Float.POSITIVE_INFINITY
        layout.visibleItemsInfo.forEach { item ->
            val offset = item.offset.x - (width / 2f - item.size.width / 2f)
            if (offset <= 0 && offset > lower) lower = offset
            if (offset >= 0 && offset < upper) upper = offset
        }
        val target = if (abs(velocity) < 500f) { if (abs(lower) < abs(upper)) lower else upper }
            else if (velocity < 0) lower else upper
        return target.takeIf { it.isFinite() } ?: 0f
    }
}
