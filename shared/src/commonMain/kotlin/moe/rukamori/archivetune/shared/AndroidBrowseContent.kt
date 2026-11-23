package moe.rukamori.archivetune.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.resources.*
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun AndroidBrowseContent(controller: MusicController, modifier: Modifier = Modifier) {
    val liveState by controller.browse.collectAsState()
    val state = LocalAndroidBrowseSnapshot.current ?: liveState
    val item = state.item ?: return
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = controller::closeBrowse) { Icon(painterResource(Res.drawable.arrow_back), "Back") }
            Text(state.page?.title?.ifBlank { item.title } ?: item.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.loading && state.page == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.error?.let { message -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f)); TextButton(onClick = controller::retryBrowse) { Text("Retry") }
        } }
        state.page?.let { page ->
            LazyColumn(contentPadding = PaddingValues(bottom = 190.dp)) {
                item {
                    when (item.kind) {
                        MusicBrowseKind.ARTIST -> ArtistBrowseHeader(item, page, controller)
                        MusicBrowseKind.ALBUM -> MediaBrowseHeader(item, page, controller)
                        else -> MediaBrowseHeader(item, page, controller)
                    }
                }
                if (page.tracks.isNotEmpty() && item.kind == MusicBrowseKind.ARTIST) item {
                    Text("Songs", Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                itemsIndexed(page.tracks) { index, track ->
                    Row(Modifier.fillMaxWidth().clickable { controller.playAt(index, page.tracks) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.kind == MusicBrowseKind.ALBUM) {
                            Text("${index + 1}", Modifier.width(36.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else CatalogArtwork(track.artworkUrl, Modifier.size(56.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { controller.toggleFavorite(track) }) { Icon(painterResource(Res.drawable.favorite_border), "Toggle favorite") }
                    }
                }
                items(page.shelves.size) { index -> AndroidShelf(page.shelves[index], controller) }
            }
        }
    }
}

@Composable
private fun MediaBrowseHeader(item: MusicCatalogItem.Browse, page: MusicBrowsePage, controller: MusicController) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CatalogArtwork(page.artworkUrl ?: item.artworkUrl, Modifier.size(220.dp))
                Column(Modifier.weight(1f).padding(start = 24.dp)) { MediaBrowseDetails(item, page, controller, false) }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CatalogArtwork(page.artworkUrl ?: item.artworkUrl, Modifier.size(220.dp))
                Spacer(Modifier.height(20.dp))
                MediaBrowseDetails(item, page, controller, true)
            }
        }
    }
}

@Composable
private fun MediaBrowseDetails(item: MusicCatalogItem.Browse, page: MusicBrowsePage, controller: MusicController, centered: Boolean) {
    val alignment = if (centered) TextAlign.Center else TextAlign.Start
    Text(page.title.ifBlank { item.title }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = alignment, maxLines = 2, overflow = TextOverflow.Ellipsis)
    (page.subtitle ?: item.subtitle)?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, textAlign = alignment) }
    page.description?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, textAlign = alignment, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    if (page.tracks.isNotEmpty() || !page.metadata.isNullOrBlank()) {
        Text(page.metadata ?: "${page.tracks.size} songs", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (page.tracks.isNotEmpty()) BrowseActions(page, controller)
    }
}

@Composable
private fun ArtistBrowseHeader(item: MusicCatalogItem.Browse, page: MusicBrowsePage, controller: MusicController) {
    Box(Modifier.fillMaxWidth().heightIn(min = 560.dp)) {
        CatalogArtwork(page.artworkUrl ?: item.artworkUrl, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .42f), .18f to Color.Transparent, .42f to Color.Transparent, .72f to MaterialTheme.colorScheme.surface.copy(alpha = .78f), 1f to MaterialTheme.colorScheme.surface)))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(page.title.ifBlank { item.title }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            (page.subtitle ?: item.subtitle)?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
            if (page.tracks.isNotEmpty()) BrowseActions(page, controller)
        }
    }
}

@Composable
private fun BrowseActions(page: MusicBrowsePage, controller: MusicController) {
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { controller.playShuffled(page.tracks) }) { Icon(painterResource(Res.drawable.shuffle), "Shuffle") }
        Button(onClick = { controller.playAt(0, page.tracks) }) { Icon(painterResource(Res.drawable.play), null); Spacer(Modifier.width(8.dp)); Text("Play") }
    }
}
