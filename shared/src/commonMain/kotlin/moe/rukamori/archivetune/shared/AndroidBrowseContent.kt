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
    val library = controller.library?.collectAsState()?.value
    val settings = library?.settings ?: MusicSettings()
    val state = LocalAndroidBrowseSnapshot.current ?: liveState
    val item = state.item ?: return

    var menuTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var showBrowseMenu by remember { mutableStateOf(false) }
    var shelfBrowseMenu by remember { mutableStateOf<Pair<MusicCatalogItem.Browse, List<MusicTrack>>?>(null) }

    val favoriteIds = remember(library?.favorites) { library?.favorites?.map { it.id }?.toSet().orEmpty() }
    val downloadedIds = remember(library?.downloads) { library?.downloads?.map { it.track.id }?.toSet().orEmpty() }
    val isSaved = remember(item, library) {
        when (item.kind) {
            MusicBrowseKind.ALBUM -> library?.savedAlbums?.any { it.id == item.id } == true
            MusicBrowseKind.ARTIST -> library?.savedArtists?.any { it.id == item.id } == true
            MusicBrowseKind.PLAYLIST -> library?.savedPlaylists?.any { it.id == item.id } == true
            MusicBrowseKind.OTHER -> library?.savedPodcasts?.any { it.id == item.id } == true
        }
    }

    val effectiveBrowseItem = remember(item, state.page) {
        val page = state.page
        if (page == null) item else item.copy(
            title = page.title.ifBlank { item.title },
            subtitle = page.subtitle ?: item.subtitle,
            artworkUrl = page.artworkUrl ?: item.artworkUrl,
        )
    }

    menuTrack?.let { AndroidSongMenuSheet(it, controller, onDismiss = { menuTrack = null }) }
    if (showBrowseMenu) {
        AndroidBrowseMenuSheet(
            item = effectiveBrowseItem,
            tracks = state.page?.tracks.orEmpty(),
            controller = controller,
            onDismiss = { showBrowseMenu = false },
        )
    }
    shelfBrowseMenu?.let { (b, tracks) ->
        AndroidBrowseMenuSheet(b, tracks, controller, onDismiss = { shelfBrowseMenu = null })
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = controller::closeBrowse) { Icon(painterResource(Res.drawable.arrow_back), "Back") }
            Text(
                state.page?.title?.ifBlank { item.title } ?: item.title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { showBrowseMenu = true }) {
                Icon(painterResource(Res.drawable.more_vert), "More options")
            }
        }
        if (state.loading && state.page == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.error?.let { message ->
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f))
                TextButton(onClick = controller::retryBrowse) { Text("Retry") }
            }
        }
        state.page?.let { page ->
            val tracks = remember(page.tracks, settings.hideExplicit) {
                if (settings.hideExplicit) page.tracks.filterNot { it.explicit } else page.tracks
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 190.dp)) {
                item {
                    when (item.kind) {
                        MusicBrowseKind.ARTIST -> ArtistBrowseHeader(effectiveBrowseItem, page, tracks, isSaved, controller)
                        else -> MediaBrowseHeader(effectiveBrowseItem, page, tracks, isSaved, controller)
                    }
                }
                if (tracks.isNotEmpty() && item.kind == MusicBrowseKind.ARTIST) item {
                    Text(
                        "Songs",
                        Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                itemsIndexed(tracks) { index, track ->
                    val isFav = track.id in favoriteIds
                    val isDl = track.id in downloadedIds
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { controller.playAt(index, tracks) }
                            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.kind == MusicBrowseKind.ALBUM) {
                            Text("${index + 1}", Modifier.width(36.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            CatalogArtwork(track.artworkUrl, Modifier.size(56.dp))
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (track.explicit) {
                                    Icon(painterResource(Res.drawable.explicit), "Explicit", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (isDl) {
                                    Icon(painterResource(Res.drawable.offline), "Downloaded", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                Text(
                                    track.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = { controller.toggleFavorite(track) }) {
                            Icon(
                                painterResource(if (isFav) Res.drawable.favorite else Res.drawable.favorite_border),
                                "Toggle favorite",
                                tint = if (isFav) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { menuTrack = track }) {
                            Icon(painterResource(Res.drawable.more_vert), "More options")
                        }
                    }
                }
                items(page.shelves.size) { index ->
                    AndroidShelf(
                        shelf = page.shelves[index],
                        controller = controller,
                        gridSize = settings.gridItemSize,
                        onTrackMenu = { menuTrack = it },
                        onBrowseMenu = { b, t -> shelfBrowseMenu = b to t },
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaBrowseHeader(
    item: MusicCatalogItem.Browse,
    page: MusicBrowsePage,
    tracks: List<MusicTrack>,
    isSaved: Boolean,
    controller: MusicController,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CatalogArtwork(page.artworkUrl ?: item.artworkUrl, Modifier.size(220.dp))
                Column(Modifier.weight(1f).padding(start = 24.dp)) {
                    MediaBrowseDetails(item, page, tracks, isSaved, controller, false)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CatalogArtwork(page.artworkUrl ?: item.artworkUrl, Modifier.size(220.dp))
                Spacer(Modifier.height(20.dp))
                MediaBrowseDetails(item, page, tracks, isSaved, controller, true)
            }
        }
    }
}

@Composable
private fun MediaBrowseDetails(
    item: MusicCatalogItem.Browse,
    page: MusicBrowsePage,
    tracks: List<MusicTrack>,
    isSaved: Boolean,
    controller: MusicController,
    centered: Boolean,
) {
    val alignment = if (centered) TextAlign.Center else TextAlign.Start
    Text(page.title.ifBlank { item.title }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = alignment, maxLines = 2, overflow = TextOverflow.Ellipsis)
    (page.subtitle ?: item.subtitle)?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, textAlign = alignment) }
    page.description?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, textAlign = alignment, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    if (tracks.isNotEmpty() || !page.metadata.isNullOrBlank()) {
        Text(page.metadata ?: "${tracks.size} songs", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    BrowseActions(item, tracks, isSaved, controller)
}

@Composable
private fun ArtistBrowseHeader(
    item: MusicCatalogItem.Browse,
    page: MusicBrowsePage,
    tracks: List<MusicTrack>,
    isSaved: Boolean,
    controller: MusicController,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 480.dp)) {
        CatalogArtwork(page.artworkUrl ?: item.artworkUrl, Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = .42f),
                    .18f to Color.Transparent,
                    .42f to Color.Transparent,
                    .72f to MaterialTheme.colorScheme.surface.copy(alpha = .78f),
                    1f to MaterialTheme.colorScheme.surface,
                ),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(page.title.ifBlank { item.title }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            (page.subtitle ?: item.subtitle)?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
            BrowseActions(item, tracks, isSaved, controller)
        }
    }
}

@Composable
private fun BrowseActions(
    item: MusicCatalogItem.Browse,
    tracks: List<MusicTrack>,
    isSaved: Boolean,
    controller: MusicController,
) {
    Row(
        Modifier.padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.kind == MusicBrowseKind.ARTIST) {
            FilledTonalButton(onClick = { controller.toggleSavedBrowse(item) }) {
                Icon(
                    painterResource(if (isSaved) Res.drawable.subscribed else Res.drawable.subscribe),
                    null,
                    Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isSaved) "Subscribed" else "Subscribe")
            }
        } else {
            FilledTonalIconButton(onClick = { controller.toggleSavedBrowse(item) }) {
                Icon(
                    painterResource(if (isSaved) Res.drawable.library_add_check else Res.drawable.library_add),
                    if (isSaved) "Saved" else "Save to library",
                )
            }
        }
        if (tracks.isNotEmpty()) {
            FilledTonalIconButton(onClick = { controller.playShuffled(tracks) }) {
                Icon(painterResource(Res.drawable.shuffle), "Shuffle")
            }
            Button(onClick = { controller.playAt(0, tracks) }) {
                Icon(painterResource(Res.drawable.play), null)
                Spacer(Modifier.width(8.dp))
                Text("Play")
            }
            if (controller.platformBridge.canDownload && item.kind != MusicBrowseKind.ARTIST) {
                FilledTonalIconButton(onClick = { controller.downloadTracks(tracks) }) {
                    Icon(painterResource(Res.drawable.download), "Download all")
                }
            }
        }
    }
}
