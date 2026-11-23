package moe.rukamori.archivetune.shared

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import org.jetbrains.compose.resources.painterResource
import moe.rukamori.archivetune.shared.resources.Res
import moe.rukamori.archivetune.shared.resources.done
import moe.rukamori.archivetune.shared.resources.play
import moe.rukamori.archivetune.shared.resources.more_vert
import moe.rukamori.archivetune.shared.resources.arrow_forward
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Android Home's filter row and data-driven sections. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AndroidHomeContent(controller: MusicController, modifier: Modifier = Modifier) {
    val home by controller.home.collectAsState()
    val scroll = rememberLazyListState()
    AndroidScrollToTopRequests("Home", scroll)
    LaunchedEffect(controller) { controller.loadHome() }
    val shelves = remember(home.shelves) {
        val quick = home.shelves.indexOfFirst { it.title.contains("quick pick", ignoreCase = true) }
        if (quick < 0) home.shelves else listOf(home.shelves[quick]) + home.shelves.filterIndexed { index, _ -> index != quick }
    }
    LazyColumn(modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(top = LocalAndroidHeaderInset.current, bottom = 190.dp)) {
        if (home.chips.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                home.chips.forEach { chip ->
                    val selected = home.selectedChip == chip
                    FilterChip(selected = selected, onClick = { controller.selectHomeChip(chip) },
                        shape = CircleShape,
                        label = { Text(chip.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = if (selected) { { Icon(painterResource(Res.drawable.done), null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .78f), labelColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .94f)), border = null)
                }
            }
        }
        if (home.loading && home.shelves.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        home.error?.let { message -> item { Column(Modifier.padding(24.dp)) { Text(message); TextButton(onClick = { controller.loadHome(true) }) { Text("Retry") } } } }
        if (home.loaded && home.shelves.isEmpty() && !home.loading && home.error == null) item { Text("No recommendations available right now.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(shelves) { index, shelf ->
            if (index > 0) Spacer(Modifier.height(18.dp))
            AndroidShelf(shelf, controller, quickPicks = shelf.title.equals("Quick picks", ignoreCase = true))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AndroidShelf(shelf: MusicShelf, controller: MusicController, quickPicks: Boolean = false) {
    val browse = shelf.browseId?.let { MusicCatalogItem.Browse(it, shelf.title, shelf.subtitle, shelf.artworkUrl, MusicBrowseKind.OTHER, shelf.browseParams) }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (browse != null) Modifier.clickable { controller.openBrowse(browse) } else Modifier).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (shelf.artworkUrl != null) CatalogArtwork(shelf.artworkUrl, Modifier.size(56.dp), false)
        Column(Modifier.weight(1f).heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
            shelf.subtitle?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(shelf.title, style = MaterialTheme.typography.titleLargeEmphasized, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (browse != null) Icon(painterResource(Res.drawable.arrow_forward), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val songs = shelf.items.mapNotNull { (it as? MusicCatalogItem.Track)?.track }
    if (shelf.featuredCards.isNotEmpty()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = (maxWidth - 40.dp).coerceAtLeast(264.dp).coerceAtMost(520.dp)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shelf.featuredCards, key = { it.item.id }) { card ->
                    Surface(Modifier.width(width), shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .58f))) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).clickable { controller.openBrowse(card.item) },
                                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CatalogArtwork(card.item.artworkUrl, Modifier.size(96.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(card.item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    card.item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                            card.tracks.take(3).forEachIndexed { index, track ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { controller.playAt(index, card.tracks) }, verticalAlignment = Alignment.CenterVertically) {
                                    CatalogArtwork(track.artworkUrl, Modifier.size(48.dp))
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                                FilledIconButton(onClick = { controller.playBrowse(card.item) }, modifier = Modifier.size(56.dp)) {
                                    Icon(painterResource(Res.drawable.play), "Play")
                                }
                            }
                        }
                    }
                }
            }
        }
    } else if (quickPicks && songs.isNotEmpty()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val heroWidth = (maxWidth - 48.dp).coerceAtLeast(232.dp).coerceAtMost(440.dp)
            val heroHeight = if (maxWidth >= 840.dp) 380.dp else if (maxWidth >= 600.dp) 356.dp else 332.dp
            HorizontalCenteredHeroCarousel(state = rememberCarouselState { songs.size },
                maxItemWidth = heroWidth, itemSpacing = 10.dp,
                contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.fillMaxWidth().height(heroHeight)) { index ->
                    val track = songs[index]
                    Box(Modifier.fillMaxSize().maskClip(MaterialTheme.shapes.extraLarge)
                        .maskBorder(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)), MaterialTheme.shapes.extraLarge)
                        .clickable { controller.playTrack(track) }) {
                        AsyncImage(track.artworkUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .48f to Color.Black.copy(alpha = .08f), 1f to Color.Black.copy(alpha = .84f))))
                        Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = .78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
            }
        }
    } else {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
            itemsIndexed(shelf.items) { _, item -> AndroidCatalogCard(item) {
                when (item) {
                    is MusicCatalogItem.Track -> controller.playTrack(item.track)
                    is MusicCatalogItem.Browse -> controller.openBrowse(item)
                }
            } }
        }
    }
}

@Composable
internal fun CatalogArtwork(url: String?, modifier: Modifier, circular: Boolean = false) {
    Box(modifier.clip(if (circular) CircleShape else RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun AndroidCatalogCard(item: MusicCatalogItem, onClick: () -> Unit) {
    val title = when (item) { is MusicCatalogItem.Track -> item.track.title; is MusicCatalogItem.Browse -> item.title }
    val subtitle = when (item) { is MusicCatalogItem.Track -> item.track.artist; is MusicCatalogItem.Browse -> item.subtitle }
    val url = when (item) { is MusicCatalogItem.Track -> item.track.artworkUrl; is MusicCatalogItem.Browse -> item.artworkUrl }
    val artist = item is MusicCatalogItem.Browse && item.kind == MusicBrowseKind.ARTIST
    Column(Modifier.width(144.dp).clickable(onClick = onClick).padding(horizontal = 4.dp)) {
        CatalogArtwork(url, Modifier.fillMaxWidth().aspectRatio(1f), artist)
        Text(title, Modifier.fillMaxWidth().padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, textAlign = if (artist) TextAlign.Center else TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!artist && !subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
