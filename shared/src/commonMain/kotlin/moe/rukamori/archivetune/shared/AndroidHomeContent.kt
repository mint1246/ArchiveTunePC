package moe.rukamori.archivetune.shared

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import org.jetbrains.compose.resources.painterResource
import moe.rukamori.archivetune.shared.resources.*
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

/** Android Home's filter row, Speed Dial, Keep Listening, Quick Picks, and data-driven sections. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AndroidHomeContent(controller: MusicController, modifier: Modifier = Modifier) {
    val home by controller.home.collectAsState()
    val library = controller.library?.collectAsState()?.value
    val settings = library?.settings ?: MusicSettings()
    val scroll = rememberLazyListState()
    AndroidScrollToTopRequests("Home", scroll)
    LaunchedEffect(controller) { controller.loadHome() }

    var menuTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var menuBrowse by remember { mutableStateOf<Pair<MusicCatalogItem.Browse, List<MusicTrack>>?>(null) }

    menuTrack?.let { track ->
        AndroidSongMenuSheet(track = track, controller = controller, onDismiss = { menuTrack = null })
    }
    menuBrowse?.let { (browse, tracks) ->
        AndroidBrowseMenuSheet(item = browse, tracks = tracks, controller = controller, onDismiss = { menuBrowse = null })
    }

    val rawShelves = remember(home.shelves, settings.hideExplicit, settings.quickPicksMode) {
        val filtered = home.shelves.mapNotNull { shelf ->
            val items = if (settings.hideExplicit) {
                shelf.items.filterNot { it is MusicCatalogItem.Track && it.track.explicit }
            } else shelf.items
            if (items.isEmpty() && shelf.featuredCards.isEmpty()) null else shelf.copy(items = items)
        }
        val quickIndex = filtered.indexOfFirst { it.title.contains("quick pick", ignoreCase = true) }
        val ordered = if (quickIndex < 0) filtered else listOf(filtered[quickIndex]) + filtered.filterIndexed { i, _ -> i != quickIndex }
        if (settings.quickPicksMode == MusicQuickPicksMode.DONT_SHOW) {
            ordered.filterNot { it.title.contains("quick pick", ignoreCase = true) }
        } else ordered
    }

    // Infinite scroll continuation trigger near bottom
    val shouldLoadMore by remember(home.continuation, home.loadingMore, home.loading) {
        derivedStateOf {
            val total = scroll.layoutInfo.totalItemsCount
            val lastVisible = scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            home.continuation != null && !home.loadingMore && !home.loading && total > 2 && lastVisible >= total - 2
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) controller.loadMoreHome()
    }

    val speedDial = library?.speedDialPins.orEmpty()
    val keepListening = remember(library?.history, settings.hideExplicit) {
        library?.history.orEmpty()
            .filterNot { settings.hideExplicit && it.explicit }
            .take(15)
    }

    PullToRefreshBox(
        isRefreshing = home.loading && home.shelves.isNotEmpty(),
        onRefresh = { controller.loadHome(true) },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = scroll,
            contentPadding = PaddingValues(top = LocalAndroidHeaderInset.current, bottom = 190.dp),
        ) {
            item(key = "home_top_anchor") { Spacer(Modifier.height(0.dp)) }
            if (settings.showHomeCategoryChips && home.chips.isNotEmpty()) {
                item(key = "home_chips") {
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 68.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        home.chips.forEach { chip ->
                            val selected = home.selectedChip == chip
                            FilterChip(
                                selected = selected,
                                onClick = { controller.selectHomeChip(chip) },
                                shape = CircleShape,
                                label = { Text(chip.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = if (selected) {
                                    { Icon(painterResource(Res.drawable.done), null, Modifier.size(FilterChipDefaults.IconSize)) }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .78f),
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .94f),
                                ),
                                border = null,
                            )
                        }
                    }
                }
            }

            // Speed Dial pinned grid (shown on unfiltered Home when user has pinned items)
            if (home.selectedChip == null && speedDial.isNotEmpty()) {
                item(key = "home_speed_dial") {
                    SpeedDialSection(
                        pins = speedDial,
                        controller = controller,
                        onUnpin = { controller.toggleSpeedDialPin(it) },
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }

            // Keep listening horizontal shelf from local playback history
            if (home.selectedChip == null && keepListening.isNotEmpty() && settings.quickPicksMode != MusicQuickPicksMode.LAST_LISTEN) {
                item(key = "home_keep_listening") {
                    KeepListeningSection(
                        tracks = keepListening,
                        controller = controller,
                        gridSize = settings.gridItemSize,
                        onOpenTrackMenu = { menuTrack = it },
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }

            if (home.loading && home.shelves.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            home.error?.let { message ->
                item {
                    Column(Modifier.padding(24.dp)) {
                        Text(message)
                        TextButton(onClick = { controller.loadHome(true) }) { Text("Retry") }
                    }
                }
            }
            if (home.loaded && rawShelves.isEmpty() && !home.loading && home.error == null) {
                item {
                    Text(
                        "No recommendations available right now.",
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(rawShelves) { index, shelf ->
                if (index > 0) Spacer(Modifier.height(18.dp))
                val isQuickPicks = shelf.title.contains("quick pick", ignoreCase = true)
                val displayShelf = if (isQuickPicks && settings.quickPicksMode == MusicQuickPicksMode.LAST_LISTEN && keepListening.isNotEmpty()) {
                    shelf.copy(title = "Keep listening", items = keepListening.map { MusicCatalogItem.Track(it) })
                } else shelf
                AndroidShelf(
                    shelf = displayShelf,
                    controller = controller,
                    quickPicks = isQuickPicks && settings.quickPicksMode != MusicQuickPicksMode.GRID,
                    compactQuickPicksRows = isQuickPicks && settings.quickPicksMode == MusicQuickPicksMode.GRID,
                    gridSize = settings.gridItemSize,
                    onTrackMenu = { menuTrack = it },
                    onBrowseMenu = { browse, tracks -> menuBrowse = browse to tracks },
                )
            }
            if (home.loadingMore) {
                item(key = "home_loading_more") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedDialSection(
    pins: List<MusicSpeedDialPin>,
    controller: MusicController,
    onUnpin: (MusicSpeedDialPin) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "Speed dial",
            style = MaterialTheme.typography.titleLargeEmphasized,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pins.take(9).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { pin ->
                        Box(
                            Modifier.weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .combinedClickable(
                                    onClick = {
                                        when (pin.type) {
                                            "song" -> controller.playTrack(
                                                MusicTrack(
                                                    id = pin.id,
                                                    title = pin.title,
                                                    artist = pin.subtitle,
                                                    artworkUrl = pin.artworkUrl,
                                                ),
                                            )
                                            else -> controller.openBrowse(
                                                MusicCatalogItem.Browse(
                                                    id = pin.id,
                                                    title = pin.title,
                                                    subtitle = pin.subtitle.takeIf(String::isNotBlank),
                                                    artworkUrl = pin.artworkUrl,
                                                    kind = when (pin.type) {
                                                        "artist" -> MusicBrowseKind.ARTIST
                                                        "album" -> MusicBrowseKind.ALBUM
                                                        "playlist" -> MusicBrowseKind.PLAYLIST
                                                        else -> MusicBrowseKind.OTHER
                                                    },
                                                    params = pin.params,
                                                ),
                                            )
                                        }
                                    },
                                    onLongClick = { onUnpin(pin) },
                                ),
                        ) {
                            pin.artworkUrl?.let {
                                AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.55f to Color.Black.copy(alpha = 0.2f),
                                        1f to Color.Black.copy(alpha = 0.82f),
                                    ),
                                ),
                            )
                            Text(
                                pin.title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun KeepListeningSection(
    tracks: List<MusicTrack>,
    controller: MusicController,
    gridSize: MusicGridItemSize,
    onOpenTrackMenu: (MusicTrack) -> Unit,
) {
    val cardWidth = if (gridSize == MusicGridItemSize.BIG) 168.dp else 144.dp
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Keep listening",
            style = MaterialTheme.typography.titleLargeEmphasized,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
            itemsIndexed(tracks, key = { _, t -> "keep:${t.id}" }) { index, track ->
                Column(
                    Modifier.width(cardWidth)
                        .combinedClickable(
                            onClick = { controller.playAt(index, tracks) },
                            onLongClick = { onOpenTrackMenu(track) },
                        )
                        .padding(horizontal = 4.dp),
                ) {
                    CatalogArtwork(track.artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(
                        track.title,
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (track.artist.isNotBlank()) {
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AndroidShelf(
    shelf: MusicShelf,
    controller: MusicController,
    quickPicks: Boolean = false,
    compactQuickPicksRows: Boolean = false,
    gridSize: MusicGridItemSize = MusicGridItemSize.SMALL,
    onTrackMenu: ((MusicTrack) -> Unit)? = null,
    onBrowseMenu: ((MusicCatalogItem.Browse, List<MusicTrack>) -> Unit)? = null,
) {
    val browse = shelf.browseId?.let {
        MusicCatalogItem.Browse(it, shelf.title, shelf.subtitle, shelf.artworkUrl, MusicBrowseKind.OTHER, shelf.browseParams)
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (browse != null) Modifier.clickable { controller.openBrowse(browse) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (shelf.artworkUrl != null) CatalogArtwork(shelf.artworkUrl, Modifier.size(56.dp), false)
        Column(Modifier.weight(1f).heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
            shelf.subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
                    Surface(
                        Modifier.width(width),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .58f)),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 96.dp).clickable { controller.openBrowse(card.item) },
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CatalogArtwork(card.item.artworkUrl, Modifier.size(96.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(card.item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    card.item.subtitle?.let {
                                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (onBrowseMenu != null) {
                                    IconButton(onClick = { onBrowseMenu(card.item, card.tracks) }) {
                                        Icon(painterResource(Res.drawable.more_vert), "More options")
                                    }
                                }
                            }
                            card.tracks.take(3).forEachIndexed { index, track ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { controller.playAt(index, card.tracks) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CatalogArtwork(track.artworkUrl, Modifier.size(48.dp))
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (onTrackMenu != null) {
                                        IconButton(onClick = { onTrackMenu(track) }) {
                                            Icon(painterResource(Res.drawable.more_vert), "Track options", Modifier.size(20.dp))
                                        }
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
    } else if (compactQuickPicksRows && songs.isNotEmpty()) {
        // Android 4-row horizontal paging track grid for Quick Picks "Compact rows" mode
        val pages = songs.chunked(4)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val colWidth = (maxWidth - 48.dp).coerceIn(280.dp, 380.dp)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pages) { group ->
                    Column(Modifier.width(colWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        group.forEach { track ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { controller.playTrack(track) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CatalogArtwork(track.artworkUrl, Modifier.size(48.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (onTrackMenu != null) {
                                    IconButton(onClick = { onTrackMenu(track) }, modifier = Modifier.size(36.dp)) {
                                        Icon(painterResource(Res.drawable.more_vert), "More", Modifier.size(18.dp))
                                    }
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
            HorizontalCenteredHeroCarousel(
                state = rememberCarouselState { songs.size },
                maxItemWidth = heroWidth,
                itemSpacing = 10.dp,
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth().height(heroHeight),
            ) { index ->
                val track = songs[index]
                Box(
                    Modifier.fillMaxSize()
                        .maskClip(MaterialTheme.shapes.extraLarge)
                        .maskBorder(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)), MaterialTheme.shapes.extraLarge)
                        .combinedClickable(
                            onClick = { controller.playTrack(track) },
                            onLongClick = { onTrackMenu?.invoke(track) },
                        ),
                ) {
                    AsyncImage(track.artworkUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .48f to Color.Black.copy(alpha = .08f), 1f to Color.Black.copy(alpha = .84f))))
                    if (onTrackMenu != null) {
                        IconButton(
                            onClick = { onTrackMenu(track) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        ) {
                            Icon(painterResource(Res.drawable.more_vert), "More options", tint = Color.White)
                        }
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = .78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    } else {
        val cardWidth = if (gridSize == MusicGridItemSize.BIG) 168.dp else 144.dp
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
            itemsIndexed(shelf.items) { _, item ->
                AndroidCatalogCard(
                    item = item,
                    cardWidth = cardWidth,
                    onClick = {
                        when (item) {
                            is MusicCatalogItem.Track -> controller.playTrack(item.track)
                            is MusicCatalogItem.Browse -> controller.openBrowse(item)
                        }
                    },
                    onLongClick = {
                        when (item) {
                            is MusicCatalogItem.Track -> onTrackMenu?.invoke(item.track)
                            is MusicCatalogItem.Browse -> onBrowseMenu?.invoke(item, emptyList())
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun CatalogArtwork(url: String?, modifier: Modifier, circular: Boolean = false) {
    Box(modifier.clip(if (circular) CircleShape else RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AndroidCatalogCard(
    item: MusicCatalogItem,
    cardWidth: androidx.compose.ui.unit.Dp = 144.dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val title = when (item) { is MusicCatalogItem.Track -> item.track.title; is MusicCatalogItem.Browse -> item.title }
    val subtitle = when (item) { is MusicCatalogItem.Track -> item.track.artist; is MusicCatalogItem.Browse -> item.subtitle }
    val url = when (item) { is MusicCatalogItem.Track -> item.track.artworkUrl; is MusicCatalogItem.Browse -> item.artworkUrl }
    val artist = item is MusicCatalogItem.Browse && item.kind == MusicBrowseKind.ARTIST
    Column(
        Modifier.width(cardWidth)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp),
    ) {
        CatalogArtwork(url, Modifier.fillMaxWidth().aspectRatio(1f), artist)
        Text(title, Modifier.fillMaxWidth().padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, textAlign = if (artist) TextAlign.Center else TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!artist && !subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
