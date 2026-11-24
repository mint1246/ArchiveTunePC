/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.shared.resources.*
import org.jetbrains.compose.resources.painterResource

/** Source-derived Search discovery composition, dimensions and mood card rendering. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidSearchContent(controller: MusicController, onSearchClick: () -> Unit, modifier: Modifier = Modifier) {
    val discovery by controller.discovery.collectAsState()
    val settings = controller.library?.collectAsState()?.value?.settings ?: MusicSettings()
    var tab by rememberSaveable { mutableStateOf(0) }
    var menuTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var menuBrowse by remember { mutableStateOf<Pair<MusicCatalogItem.Browse, List<MusicTrack>>?>(null) }
    val scroll = rememberLazyListState()
    AndroidScrollToTopRequests("Search", scroll)
    LaunchedEffect(controller) { controller.loadDiscovery() }

    menuTrack?.let { AndroidSongMenuSheet(it, controller, onDismiss = { menuTrack = null }) }
    menuBrowse?.let { (b, tracks) -> AndroidBrowseMenuSheet(b, tracks, controller, onDismiss = { menuBrowse = null }) }

    PullToRefreshBox(
        isRefreshing = discovery.loading && discovery.loaded,
        onRefresh = { controller.loadDiscovery(true) },
        modifier = modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(430.dp).background(Brush.verticalGradient(
                0f to MaterialTheme.colorScheme.primaryContainer.copy(alpha = .30f),
                .42f to MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .14f), 1f to Color.Transparent,
            )))
            LazyColumn(state = scroll, contentPadding = PaddingValues(top = LocalAndroidHeaderInset.current, bottom = 110.dp)) {
                item { AndroidSearchEntry(onSearchClick, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                item {
                    PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                        listOf("Explore", "Suggestions").forEachIndexed { index, title ->
                            Tab(selected = tab == index, onClick = { tab = index },
                                icon = { Icon(painterResource(if (index == 0) Res.drawable.explore_outlined else Res.drawable.auto_awesome), null) },
                                text = { Text(title, maxLines = 1) })
                        }
                    }
                }
                if (discovery.loading && !discovery.loaded) item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                discovery.error?.let { message -> item {
                    Column(Modifier.padding(24.dp)) { Text(message); Button(onClick = { controller.loadDiscovery(true) }) { Text("Retry") } }
                } }
                if (tab == 0 && discovery.loaded) {
                    item {
                        Text("Mood & Genres", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    item {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val columns = (maxWidth.value / 180).toInt().coerceAtLeast(1)
                            Column(Modifier.padding(6.dp)) {
                                discovery.data.moods.chunked(columns).forEach { row ->
                                    Row(Modifier.fillMaxWidth()) {
                                        row.forEach { mood ->
                                            AndroidMoodCard(mood, Modifier.weight(1f).padding(6.dp)) { controller.openBrowse(mood.endpoint) }
                                        }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                } else if (tab == 1) {
                    items(discovery.data.shelves.size) { index ->
                        AndroidShelf(
                            shelf = discovery.data.shelves[index],
                            controller = controller,
                            gridSize = settings.gridItemSize,
                            onTrackMenu = { menuTrack = it },
                            onBrowseMenu = { b, tracks -> menuBrowse = b to tracks },
                        )
                    }
                    if (discovery.loaded && discovery.data.shelves.isEmpty()) item {
                        Text("No suggestions available", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidMoodCard(mood: MusicMood, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val base = Color(mood.stripeColor)
    val card = Brush.linearGradient(listOf(lerp(base, colors.primaryContainer, .18f),
        lerp(base, colors.surfaceContainerHighest, .34f)), Offset.Zero, Offset(900f, 650f))
    val cover = Brush.linearGradient(listOf(lerp(base, colors.surface, .28f),
        lerp(base, colors.scrim, .2f)), Offset.Zero, Offset(360f, 360f))
    Box(modifier.height(100.dp).clip(RoundedCornerShape(24.dp)).background(card).clickable(onClick = onClick)) {
        Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp).size(80.dp)
            .clip(RoundedCornerShape(16.dp)).background(cover)) {
            mood.artworkUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(colors.scrim.copy(alpha = .38f),
            colors.scrim.copy(alpha = .18f), Color.Transparent))))
        Text(mood.title, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 92.dp, bottom = 16.dp),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
