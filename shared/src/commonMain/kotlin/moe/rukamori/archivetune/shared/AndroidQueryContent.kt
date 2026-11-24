package moe.rukamori.archivetune.shared

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.resources.*
import org.jetbrains.compose.resources.painterResource

private val resultFilters = listOf(
    "All" to null,
    "Library" to "__local__",
    "Songs" to "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D",
    "Videos" to "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D",
    "Albums" to "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D",
    "Artists" to "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D",
    "Podcasts" to "EgWKAQJQAWoKEAkQChAFEAMQBA%3D%3D",
    "Community playlists" to "EgeKAQQoAEABagoQAxAEEAoQCRAF",
    "Featured playlists" to "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D",
)

/** Android SearchBar expansion, segmented suggestions, local Library search, and categorized remote results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidQueryContent(controller: MusicController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val library = controller.library?.collectAsState()?.value
    val settings = library?.settings ?: MusicSettings()
    var query by rememberSaveable { mutableStateOf(state.query) }
    var editing by rememberSaveable { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var menuTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var menuBrowse by remember { mutableStateOf<MusicCatalogItem.Browse?>(null) }
    val resultsScroll = rememberLazyListState()

    menuTrack?.let { AndroidSongMenuSheet(it, controller, onDismiss = { menuTrack = null }) }
    menuBrowse?.let { AndroidBrowseMenuSheet(it, emptyList(), controller, onDismiss = { menuBrowse = null }) }

    LaunchedEffect(Unit) { withFrameNanos { }; expanded = editing }
    LaunchedEffect(query, editing) { if (editing) controller.suggest(query) }

    fun submit(value: String, targetFilter: Int = filter) {
        if (value.isBlank()) return
        query = value
        filter = targetFilter
        editing = false
        expanded = false
        val params = resultFilters[targetFilter].second
        if (params != "__local__") {
            controller.search(value, params)
        }
    }

    val favoriteIds = remember(library?.favorites) { library?.favorites?.map { it.id }?.toSet().orEmpty() }
    val downloadedIds = remember(library?.downloads) { library?.downloads?.map { it.track.id }?.toSet().orEmpty() }

    val localTracks = remember(query, library, filter) {
        if (filter != 1 || query.isBlank() || library == null) emptyList()
        else {
            val q = query.trim()
            val pool = (library.favorites + library.downloads.map { it.track } + library.localTracks + library.history)
                .distinctBy { it.id }
            pool.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true) ||
                    (it.albumName?.contains(q, ignoreCase = true) == true)
            }
        }
    }
    val localPlaylists = remember(query, library, filter) {
        if (filter != 1 || query.isBlank() || library == null) emptyList()
        else library.playlists.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Column(modifier.fillMaxSize()) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = { query = it; editing = true },
                    onSearch = { submit(it) },
                    expanded = expanded,
                    onExpandedChange = { editing = it; expanded = it },
                    placeholder = { Text("Search YouTube Music or library") },
                    leadingIcon = {
                        IconButton(onClick = {
                            if (expanded && (state.searchItems.isNotEmpty() || filter == 1)) {
                                editing = false; expanded = false
                            } else onBack()
                        }) {
                            Icon(painterResource(Res.drawable.arrow_back), "Back")
                        }
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; editing = true; expanded = true }) {
                                Icon(painterResource(Res.drawable.close), "Clear")
                            }
                        }
                    },
                )
            },
            expanded = expanded,
            onExpandedChange = { editing = it; expanded = it },
            windowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.fillMaxWidth(),
            colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            val history = library?.searchHistory.orEmpty().filter { it.contains(query, ignoreCase = true) }
                .let { if (query.isEmpty()) it else it.take(3) }
            val suggestions = state.suggestions.filterNot { it in history }
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (history.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Search history", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            TextButton(onClick = controller::clearSearchHistory) { Text("Clear all") }
                        }
                    }
                }
                itemsIndexed(history, key = { _, value -> "history:$value" }) { index, value ->
                    SearchSuggestion(
                        query = value,
                        online = false,
                        index = index,
                        count = history.size,
                        onClick = { submit(value) },
                        onDelete = { controller.removeSearchHistory(value) },
                        onFill = { query = value },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (suggestions.isNotEmpty()) item { SearchSectionTitle("Suggestions") }
                itemsIndexed(suggestions, key = { _, value -> "suggestion:$value" }) { index, value ->
                    SearchSuggestion(
                        query = value,
                        online = true,
                        index = index,
                        count = suggestions.size,
                        onClick = { submit(value) },
                        onDelete = {},
                        onFill = { query = value },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        if (!expanded) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                resultFilters.forEachIndexed { index, (label, params) ->
                    FilterChip(
                        selected = filter == index,
                        onClick = {
                            filter = index
                            if (params != "__local__" && query.isNotBlank()) controller.search(query, params)
                        },
                        label = { Text(label) },
                        leadingIcon = if (filter == index) {
                            { Icon(painterResource(Res.drawable.done), null, Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null,
                    )
                }
            }
            if (state.searching && filter != 1) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (filter != 1) {
                state.error?.let { message ->
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f))
                        TextButton(onClick = { submit(query) }) { Text("Retry") }
                    }
                }
            }
            AnimatedContent(
                targetState = filter,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                label = "searchFilter",
            ) { activeFilter ->
                if (activeFilter == 1) {
                    // Local Library Search results
                    LazyColumn(state = resultsScroll, contentPadding = PaddingValues(top = 8.dp, bottom = 110.dp)) {
                        if (localTracks.isEmpty() && localPlaylists.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("No matching items in your library", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (localPlaylists.isNotEmpty()) {
                            item { SearchSectionTitle("Playlists") }
                            items(localPlaylists, key = { "lp:${it.id}" }) { playlist ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { if (playlist.tracks.isNotEmpty()) controller.playAt(0, playlist.tracks) }
                                        .heightIn(min = 68.dp)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CatalogArtwork(playlist.tracks.firstOrNull()?.artworkUrl, Modifier.size(56.dp))
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                        Text("Playlist • ${playlist.tracks.size} tracks", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if (localTracks.isNotEmpty()) {
                            item { SearchSectionTitle("Songs") }
                            itemsIndexed(localTracks, key = { _, t -> "lt:${t.id}" }) { index, track ->
                                SearchCatalogRow(
                                    item = MusicCatalogItem.Track(track),
                                    isFavorite = track.id in favoriteIds,
                                    isDownloaded = track.id in downloadedIds,
                                    controller = controller,
                                    onTrackPlay = { controller.playAt(index, localTracks) },
                                    onTrackMenu = { menuTrack = it },
                                    onBrowseMenu = { menuBrowse = it },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                } else if (activeFilter == 0 && state.searchSections.isNotEmpty()) {
                    // Categorized "All" search sections (Top result, Songs, Videos, Albums, Artists, Playlists)
                    LazyColumn(state = resultsScroll, contentPadding = PaddingValues(top = 4.dp, bottom = 110.dp)) {
                        state.searchSections.forEachIndexed { sIdx, section ->
                            val sectionItems = if (settings.hideExplicit) {
                                section.items.filterNot { it is MusicCatalogItem.Track && it.track.explicit }
                            } else section.items
                            if (sectionItems.isNotEmpty()) {
                                item(key = "sec_hdr:$sIdx:${section.title}") {
                                    val targetFilterIdx = matchFilterIndexForSection(section.title)
                                    Row(
                                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            section.title,
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        if (targetFilterIdx != null) {
                                            TextButton(onClick = { submit(query, targetFilterIdx) }) {
                                                Text("See all")
                                            }
                                        }
                                    }
                                }
                                itemsIndexed(sectionItems, key = { i, item -> "sec:$sIdx:$i:$item" }) { _, item ->
                                    val tid = (item as? MusicCatalogItem.Track)?.track?.id
                                    SearchCatalogRow(
                                        item = item,
                                        isFavorite = tid != null && tid in favoriteIds,
                                        isDownloaded = tid != null && tid in downloadedIds,
                                        controller = controller,
                                        onTrackMenu = { menuTrack = it },
                                        onBrowseMenu = { menuBrowse = it },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val filteredItems = remember(state.searchItems, settings.hideExplicit) {
                        if (settings.hideExplicit) {
                            state.searchItems.filterNot { it is MusicCatalogItem.Track && it.track.explicit }
                        } else state.searchItems
                    }
                    LazyColumn(state = resultsScroll, contentPadding = PaddingValues(top = 8.dp, bottom = 110.dp)) {
                        itemsIndexed(filteredItems, key = { index, item -> "$index:$item" }) { _, item ->
                            val tid = (item as? MusicCatalogItem.Track)?.track?.id
                            SearchCatalogRow(
                                item = item,
                                isFavorite = tid != null && tid in favoriteIds,
                                isDownloaded = tid != null && tid in downloadedIds,
                                controller = controller,
                                onTrackMenu = { menuTrack = it },
                                onBrowseMenu = { menuBrowse = it },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun matchFilterIndexForSection(title: String): Int? = when {
    title.equals("Songs", ignoreCase = true) -> 2
    title.equals("Videos", ignoreCase = true) -> 3
    title.equals("Albums", ignoreCase = true) -> 4
    title.equals("Artists", ignoreCase = true) -> 5
    title.contains("Podcast", ignoreCase = true) -> 6
    title.contains("Community", ignoreCase = true) -> 7
    title.contains("Featured", ignoreCase = true) || title.contains("Playlist", ignoreCase = true) -> 8
    else -> null
}

@Composable
private fun SearchSectionTitle(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SearchSuggestion(
    query: String,
    online: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onFill: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(
        topStart = if (index == 0) 24.dp else 6.dp,
        topEnd = if (index == 0) 24.dp else 6.dp,
        bottomStart = if (index == count - 1) 24.dp else 6.dp,
        bottomEnd = if (index == count - 1) 24.dp else 6.dp,
    )
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(Modifier.size(40.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (online) Res.drawable.search else Res.drawable.history), null, Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(query, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (!online) IconButton(onClick = onDelete) { Icon(painterResource(Res.drawable.close), "Remove from history", Modifier.size(18.dp)) }
            IconButton(onClick = onFill) { Icon(painterResource(Res.drawable.arrow_top_left), "Fill query", Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun SearchCatalogRow(
    item: MusicCatalogItem,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    controller: MusicController,
    onTrackPlay: (() -> Unit)? = null,
    onTrackMenu: (MusicTrack) -> Unit,
    onBrowseMenu: (MusicCatalogItem.Browse) -> Unit,
    modifier: Modifier,
) {
    val track = (item as? MusicCatalogItem.Track)?.track
    val browse = item as? MusicCatalogItem.Browse
    Row(
        modifier.fillMaxWidth()
            .clickable {
                if (track != null) {
                    if (onTrackPlay != null) onTrackPlay() else controller.playTrack(track)
                } else {
                    browse?.let(controller::openBrowse)
                }
            }
            .heightIn(min = 72.dp)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogArtwork(track?.artworkUrl ?: browse?.artworkUrl, Modifier.size(56.dp), browse?.kind == MusicBrowseKind.ARTIST)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (track?.explicit == true) {
                    Icon(painterResource(Res.drawable.explicit), "Explicit", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(track?.title ?: browse?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isDownloaded) {
                    Icon(painterResource(Res.drawable.offline), "Downloaded", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    track?.artist ?: browse?.subtitle.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (track != null) {
            IconButton(onClick = { controller.toggleFavorite(track) }) {
                Icon(
                    painterResource(if (isFavorite) Res.drawable.favorite else Res.drawable.favorite_border),
                    "Toggle favorite",
                    tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onTrackMenu(track) }) {
                Icon(painterResource(Res.drawable.more_vert), "More options")
            }
        } else if (browse != null) {
            IconButton(onClick = { onBrowseMenu(browse) }) {
                Icon(painterResource(Res.drawable.more_vert), "More options")
            }
        }
    }
}
