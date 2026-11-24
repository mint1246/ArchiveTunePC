package moe.rukamori.archivetune.shared

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.sdk.playlist.PlaylistCsvExporter
import moe.rukamori.archivetune.sdk.playlist.PlaylistCsvSong
import moe.rukamori.archivetune.sdk.playlist.PlaylistSongSort
import moe.rukamori.archivetune.sdk.playlist.sortBySongSort
import moe.rukamori.archivetune.shared.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Local collections use the same filter and shortcut language as Android's Library. */
@Composable
internal fun AndroidLibraryContent(controller: MusicController, modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    val libraryFlow = controller.library
    if (libraryFlow == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Library unavailable") }
        return
    }
    val library by libraryFlow.collectAsState()
    val settings = library.settings
    val filters = listOf("Library", "Playlists", "Podcasts", "Songs", "Artists", "Albums")
    val pagerState = rememberPagerState(pageCount = { filters.size })
    val scope = rememberCoroutineScope()
    val pageStateHolder = rememberSaveableStateHolder()
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var nameDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var manageTagsDialog by remember { mutableStateOf(false) }
    var selectedTagId by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf("Recently added") }
    var sortMenu by remember { mutableStateOf(false) }
    var songFilter by rememberSaveable { mutableStateOf("All") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Pair<MusicTrack, Pair<String, () -> Unit>?>?>(null) }

    val selectedPlaylist = library.playlists.firstOrNull { it.id == detail }
    val topOverview = remember(library.statistics, settings.topLength) {
        MusicStatisticsQuery.overview(library.statistics, MusicStatisticsRange.ALL_TIME, currentEpochMillis(), settings.topLength)
    }
    val topTracks = remember(topOverview) { topOverview.topSongs.mapNotNull { it.track } }
    val offlineTracks = remember(library.downloads) { library.downloads.map { it.track } }
    val offlineBytes = remember(library.downloads) { library.downloads.sumOf { it.sizeBytes } }

    val detailSongs = when (detail) {
        "liked" -> library.favorites
        "offline" -> offlineTracks
        "cached" -> library.cachedTracks
        "local" -> library.localTracks
        "top" -> topTracks
        else -> selectedPlaylist?.tracks.orEmpty()
    }

    menuTrack?.let { (track, extra) ->
        AndroidSongMenuSheet(
            track = track,
            controller = controller,
            onDismiss = { menuTrack = null },
            extraActionLabel = extra?.first,
            onExtraAction = extra?.second,
        )
    }

    if (nameDialog != null) {
        val editing = library.playlists.firstOrNull { it.id == nameDialog }
        var value by remember(nameDialog) { mutableStateOf(editing?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            icon = { Icon(painterResource(Res.drawable.playlist_add), null) },
            title = { Text(if (editing == null) "Create playlist" else "Rename playlist") },
            text = { OutlinedTextField(value, { value = it }, placeholder = { Text("Playlist name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    if (editing == null) controller.createPlaylist(value) else controller.renamePlaylist(editing.id, value)
                    nameDialog = null
                }) { Text(if (editing == null) "Create" else "Save") }
            },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Cancel") } },
        )
    }
    deleteDialog?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteDialog = null },
            title = { Text("Delete playlist?") },
            text = { Text("This removes the playlist from your library.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.deletePlaylist(id)
                    if (detail == id) detail = null
                    deleteDialog = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = null }) { Text("Cancel") } },
        )
    }
    if (manageTagsDialog) {
        PlaylistTagsDialog(
            tags = library.playlistTags,
            playlists = library.playlists,
            controller = controller,
            onDismiss = { manageTagsDialog = false },
        )
    }

    // Derived library collections for Songs, Artists, and Albums tabs
    val allLibrarySongs = remember(library.favorites, library.downloads, library.localTracks, songFilter) {
        when (songFilter) {
            "Liked" -> library.favorites
            "Downloaded" -> library.downloads.map { it.track }
            "Local" -> library.localTracks
            else -> (library.favorites + library.downloads.map { it.track } + library.localTracks).distinctBy { it.id }
        }
    }
    val libraryArtists = remember(library.savedArtists, library.favorites, library.downloads, library.playlists, library.history) {
        val byKey = linkedMapOf<String, Pair<MusicCatalogItem.Browse, Int>>()
        library.savedArtists.forEach { byKey[it.id] = it to 0 }
        val pool = (library.favorites + library.downloads.map { it.track } + library.playlists.flatMap { it.tracks } + library.history).distinctBy { it.id }
        pool.forEach { track ->
            val key = track.artistId ?: track.artist.lowercase().takeIf(String::isNotBlank) ?: return@forEach
            val existing = byKey[key]
            if (existing != null) {
                byKey[key] = existing.first to (existing.second + 1)
            } else if (track.artist.isNotBlank()) {
                val item = MusicCatalogItem.Browse(
                    id = track.artistId ?: "",
                    title = track.artist,
                    artworkUrl = track.artworkUrl,
                    kind = MusicBrowseKind.ARTIST,
                )
                byKey[key] = item to 1
            }
        }
        byKey.values.toList()
    }
    val libraryAlbums = remember(library.savedAlbums, library.favorites, library.downloads, library.playlists, library.history) {
        val byKey = linkedMapOf<String, Pair<MusicCatalogItem.Browse, Int>>()
        library.savedAlbums.forEach { byKey[it.id] = it to 0 }
        val pool = (library.favorites + library.downloads.map { it.track } + library.playlists.flatMap { it.tracks } + library.history).distinctBy { it.id }
        pool.forEach { track ->
            val albId = track.albumId ?: return@forEach
            val existing = byKey[albId]
            if (existing != null) {
                byKey[albId] = existing.first to (existing.second + 1)
            } else {
                val item = MusicCatalogItem.Browse(
                    id = albId,
                    title = track.albumName ?: track.title,
                    subtitle = track.artist,
                    artworkUrl = track.artworkUrl,
                    kind = MusicBrowseKind.ALBUM,
                )
                byKey[albId] = item to 1
            }
        }
        byKey.values.toList()
    }

    Column(modifier.fillMaxSize()) {
        if (detail != null) {
            // Detail view for Liked / Offline / Cached / Local / My Top / Playlist
            val title = when (detail) {
                "liked" -> "Liked songs"
                "offline" -> "Offline"
                "cached" -> "Cached"
                "local" -> "Local files"
                "top" -> "My top ${settings.topLength}"
                else -> selectedPlaylist?.name.orEmpty()
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { detail = null }) { Icon(painterResource(Res.drawable.arrow_back), "Back") }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selectedPlaylist != null) {
                    if (controller.canShare && selectedPlaylist.tracks.isNotEmpty()) {
                        IconButton(onClick = {
                            val records = PlaylistCsvExporter.records(
                                songs = selectedPlaylist.tracks.map { t ->
                                    PlaylistCsvSong(t.id, t.title, listOfNotNull(t.artist.takeIf(String::isNotBlank)), t.albumName)
                                },
                            )
                            val csv = PlaylistCsvExporter.encode(records).decodeToString()
                            controller.shareText(csv)
                        }) {
                            Icon(painterResource(Res.drawable.share), "Export CSV")
                        }
                    }
                    TextButton(onClick = { nameDialog = selectedPlaylist.id }) { Text("Edit") }
                    TextButton(onClick = { deleteDialog = selectedPlaylist.id }) { Text("Delete") }
                } else if (detail == "offline" && library.downloads.isNotEmpty()) {
                    TextButton(onClick = controller::clearAllDownloads) { Text("Clear all") }
                } else if (detail == "cached" && library.cachedTracks.isNotEmpty()) {
                    TextButton(onClick = controller::clearAudioCache) { Text("Clear") }
                } else if (detail == "local" && controller.platformBridge.canImportLocalFiles) {
                    FilledTonalButton(onClick = controller::importLocalAudioFiles) {
                        Icon(painterResource(Res.drawable.add), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import")
                    }
                }
            }
            val subtitleText = when (detail) {
                "offline" -> "${detailSongs.size} songs • ${formatLibraryBytes(offlineBytes)}"
                "top" -> "${detailSongs.size} songs • All time audible listening"
                else -> "${detailSongs.size} tracks"
            }
            Text(subtitleText, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (detailSongs.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { controller.playAt(0, detailSongs) }) {
                        Icon(painterResource(Res.drawable.play), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play")
                    }
                    OutlinedButton(onClick = { controller.playShuffled(detailSongs) }) {
                        Icon(painterResource(Res.drawable.shuffle), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle")
                    }
                }
            }
            if (detailSongs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            when (detail) {
                                "offline" -> "No downloaded songs yet. Open any song's menu to download for offline playback."
                                "cached" -> "No cached songs yet. Play songs to populate instant-playback history."
                                "local" -> "No local audio files imported yet."
                                "top" -> "Keep listening to build your personal Top ${settings.topLength} chart."
                                else -> "No songs yet"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (detail == "local" && controller.platformBridge.canImportLocalFiles) {
                            Button(onClick = controller::importLocalAudioFiles) { Text("Import audio files") }
                        }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 190.dp)) {
                    itemsIndexed(detailSongs) { index, track ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { controller.playAt(index, detailSongs) }
                                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (detail == "top") {
                                Text(
                                    "${index + 1}",
                                    Modifier.width(28.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            CatalogArtwork(track.artworkUrl, Modifier.size(52.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                val sub = if (detail == "top") {
                                    val plays = topOverview.topSongs.getOrNull(index)?.playCount ?: 0
                                    "${track.artist} • $plays plays"
                                } else track.artist
                                Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (selectedPlaylist != null) {
                                if (index > 0) {
                                    IconButton(onClick = { controller.reorderPlaylistTrack(selectedPlaylist.id, index, index - 1) }, modifier = Modifier.size(34.dp)) {
                                        Icon(painterResource(Res.drawable.arrow_upward), "Move up", Modifier.size(18.dp))
                                    }
                                }
                                if (index < detailSongs.lastIndex) {
                                    IconButton(onClick = { controller.reorderPlaylistTrack(selectedPlaylist.id, index, index + 1) }, modifier = Modifier.size(34.dp)) {
                                        Icon(painterResource(Res.drawable.arrow_downward), "Move down", Modifier.size(18.dp))
                                    }
                                }
                            }
                            IconButton(onClick = {
                                val extra: Pair<String, () -> Unit>? = when {
                                    selectedPlaylist != null -> "Remove from playlist" to { controller.removeFromPlaylist(selectedPlaylist.id, index) }
                                    detail == "offline" -> "Remove download" to { controller.removeDownload(track.id) }
                                    detail == "local" -> "Delete local file" to { controller.removeLocalTrack(track.id) }
                                    else -> null
                                }
                                menuTrack = track to extra
                            }) {
                                Icon(painterResource(Res.drawable.more_vert), "More options")
                            }
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
                    val filter = filters[page]
                    pageStateHolder.SaveableStateProvider(filter) {
                        val scroll = rememberLazyListState()
                        AndroidScrollToTopRequests("Library", scroll, pagerState.currentPage == page)
                        LazyColumn(
                            state = scroll,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 190.dp),
                            verticalArrangement = Arrangement.spacedBy(if (filter == "Library") 20.dp else 12.dp),
                        ) {
                            if (filter == "Library") {
                                item(key = "library_shortcuts") {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            LibraryShortcut("Liked songs", "${library.favorites.size} tracks", Res.drawable.favorite, MaterialTheme.colorScheme.error, Modifier.weight(1f)) { detail = "liked" }
                                            LibraryShortcut("Offline", if (library.downloads.isEmpty()) "Downloaded" else "${library.downloads.size} songs", Res.drawable.offline, MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { detail = "offline" }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            LibraryShortcut("Cached", if (library.cachedTracks.isEmpty()) "Instant playback" else "${library.cachedTracks.size} songs", Res.drawable.cached, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f)) { detail = "cached" }
                                            LibraryShortcut("Local Files", if (library.localTracks.isEmpty()) "On device" else "${library.localTracks.size} files", Res.drawable.snippet_folder, MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { detail = "local" }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            LibraryShortcut("My top ${settings.topLength}", if (topTracks.isEmpty()) "All time" else "${topTracks.size} songs", Res.drawable.trending_up, MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { detail = "top" }
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                            if (filter == "Songs") {
                                item {
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("All", "Liked", "Downloaded", "Local").forEach { chip ->
                                            FilterChip(
                                                selected = songFilter == chip,
                                                onClick = { songFilter = chip },
                                                label = { Text(chip) },
                                            )
                                        }
                                    }
                                }
                                if (allLibrarySongs.isEmpty()) {
                                    item { LibraryCollectionEmpty(onSearch) }
                                } else {
                                    itemsIndexed(allLibrarySongs, key = { _, t -> "song:${t.id}" }) { index, track ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable { controller.playAt(index, allLibrarySongs) }.padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CatalogArtwork(track.artworkUrl, Modifier.size(52.dp))
                                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            IconButton(onClick = { menuTrack = track to null }) {
                                                Icon(painterResource(Res.drawable.more_vert), "More options")
                                            }
                                        }
                                    }
                                }
                            } else if (filter == "Playlists" || (filter == "Library" && (library.playlists.isNotEmpty() || library.savedPlaylists.isNotEmpty()))) {
                                item {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Playlists", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                        if (filter == "Playlists") {
                                            TextButton(onClick = { manageTagsDialog = true }) { Text("Tags") }
                                        }
                                        IconButton(
                                            onClick = { nameDialog = "new" },
                                            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                                            modifier = Modifier.size(40.dp),
                                        ) { Icon(painterResource(Res.drawable.add), "Create playlist", Modifier.size(18.dp)) }
                                    }
                                }
                                if (filter == "Playlists" && library.playlistTags.isNotEmpty()) {
                                    item {
                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(selected = selectedTagId == null, onClick = { selectedTagId = null }, label = { Text("All") })
                                            library.playlistTags.forEach { tag ->
                                                FilterChip(
                                                    selected = selectedTagId == tag.id,
                                                    onClick = { selectedTagId = if (selectedTagId == tag.id) null else tag.id },
                                                    label = { Text(tag.name) },
                                                )
                                            }
                                        }
                                    }
                                }
                                item {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box {
                                            TextButton(onClick = { sortMenu = true }) {
                                                Text(sort)
                                                Icon(painterResource(Res.drawable.expand_more), null, Modifier.size(16.dp))
                                            }
                                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                                listOf("Recently added", "Name").forEach { choice ->
                                                    DropdownMenuItem(text = { Text(choice) }, onClick = { sort = choice; sortMenu = false })
                                                }
                                            }
                                        }
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = { grid = !grid }) { Text(if (grid) "List" else "Grid") }
                                    }
                                }
                                val activeTag = library.playlistTags.firstOrNull { it.id == selectedTagId }
                                val taggedPlaylists = if (activeTag != null) {
                                    library.playlists.filter { it.id in activeTag.playlistIds }
                                } else library.playlists
                                val playlists = if (sort == "Name") {
                                    sortBySongSort(
                                        items = taggedPlaylists,
                                        sort = PlaylistSongSort.TITLE,
                                        title = { it.name },
                                        firstArtist = { "" },
                                        album = { "" },
                                        duration = { null },
                                    )
                                } else {
                                    taggedPlaylists.asReversed()
                                }
                                if (playlists.isEmpty() && library.savedPlaylists.isEmpty()) {
                                    item {
                                        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                                                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(Res.drawable.playlist_add), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                                                }
                                                Text("No playlists yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                                Button(onClick = { nameDialog = "new" }) { Text("Create playlist") }
                                            }
                                        }
                                    }
                                }
                                if (grid) {
                                    items(playlists.chunked(2)) { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            row.forEach { playlist -> PlaylistTile(playlist, Modifier.weight(1f), true) { detail = playlist.id } }
                                            if (row.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                } else {
                                    items(playlists, key = { it.id }) { playlist ->
                                        PlaylistTile(playlist, Modifier.fillMaxWidth().animateItem(), false) { detail = playlist.id }
                                    }
                                }
                                if (library.savedPlaylists.isNotEmpty() && activeTag == null) {
                                    item {
                                        Text(
                                            "Saved online playlists",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                    }
                                    items(library.savedPlaylists, key = { "sp:${it.id}" }) { saved ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable { controller.openBrowse(saved) }.padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CatalogArtwork(saved.artworkUrl, Modifier.size(56.dp))
                                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                                Text(saved.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(saved.subtitle ?: "Online playlist", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            } else if (filter == "Podcasts") {
                                if (library.savedPodcasts.isEmpty()) {
                                    item {
                                        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                                                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(Res.drawable.mic), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                                                }
                                                Text("No saved podcasts yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                                Text("Search for podcasts and tap Save to keep them here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Button(onClick = onSearch) { Text("Explore podcasts") }
                                            }
                                        }
                                    }
                                } else {
                                    items(library.savedPodcasts, key = { "pod:${it.id}" }) { pod ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable { controller.openBrowse(pod) }.padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CatalogArtwork(pod.artworkUrl, Modifier.size(56.dp))
                                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                                Text(pod.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(pod.subtitle ?: "Podcast", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            } else if (filter == "Artists") {
                                if (libraryArtists.isEmpty()) {
                                    item { LibraryCollectionEmpty(onSearch) }
                                } else {
                                    items(libraryArtists, key = { (a, _) -> "art:${a.id}:${a.title}" }) { (artist, count) ->
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clickable {
                                                    if (artist.id.isNotBlank()) controller.openBrowse(artist)
                                                    else { controller.search(artist.title); onSearch() }
                                                }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CatalogArtwork(artist.artworkUrl, Modifier.size(56.dp), circular = true)
                                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                                Text(artist.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(if (count > 0) "$count songs in library" else "Subscribed artist", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            } else if (filter == "Albums") {
                                if (libraryAlbums.isEmpty()) {
                                    item { LibraryCollectionEmpty(onSearch) }
                                } else {
                                    items(libraryAlbums, key = { (a, _) -> "alb:${a.id}" }) { (album, count) ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable { controller.openBrowse(album) }.padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CatalogArtwork(album.artworkUrl, Modifier.size(56.dp))
                                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                                Text(album.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(
                                                    listOfNotNull(album.subtitle, if (count > 0) "$count songs" else null).joinToString(" • ").ifBlank { "Album" },
                                                    style = MaterialTheme.typography.bodySmall,
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
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    filters.forEachIndexed { index, label ->
                        val chosen = pagerState.currentPage == index
                        val chipColor by animateColorAsState(
                            if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f),
                            animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        )
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val scale by animateFloatAsState(
                            if (pressed) .92f else if (chosen) 1.05f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                        )
                        Row(
                            Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                                .clip(CircleShape)
                                .background(chipColor)
                                .clickable(interactionSource = interaction, indication = null) {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(
                                    when (label) {
                                        "Playlists" -> Res.drawable.queue_music
                                        "Songs" -> Res.drawable.music_note
                                        "Podcasts" -> Res.drawable.mic
                                        "Artists" -> Res.drawable.person
                                        "Albums" -> Res.drawable.album
                                        else -> Res.drawable.graphic_eq
                                    },
                                ),
                                null,
                                Modifier.size(20.dp),
                                tint = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (label == "Podcasts") "Podcast" else label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTagsDialog(
    tags: List<MusicPlaylistTag>,
    playlists: List<MusicPlaylist>,
    controller: MusicController,
    onDismiss: () -> Unit,
) {
    var newTagName by remember { mutableStateOf("") }
    var editingTagId by remember { mutableStateOf<String?>(null) }
    val editingTag = tags.firstOrNull { it.id == editingTagId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingTag != null) "Tag: ${editingTag.name}" else "Playlist tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (editingTag != null) {
                    if (playlists.isEmpty()) {
                        Text("Create a playlist first to assign tags.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        playlists.forEach { pl ->
                            val assigned = pl.id in editingTag.playlistIds
                            Row(
                                Modifier.fillMaxWidth().clickable { controller.togglePlaylistTag(editingTag.id, pl.id) }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = assigned, onCheckedChange = { controller.togglePlaylistTag(editingTag.id, pl.id) })
                                Text(pl.name, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newTagName,
                            onValueChange = { newTagName = it },
                            placeholder = { Text("New tag name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalIconButton(
                            enabled = newTagName.isNotBlank(),
                            onClick = {
                                controller.createPlaylistTag(newTagName)
                                newTagName = ""
                            },
                        ) { Icon(painterResource(Res.drawable.add), "Add tag") }
                    }
                    tags.forEach { tag ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { editingTagId = tag.id }.padding(vertical = 6.dp)) {
                                Text(tag.name, fontWeight = FontWeight.SemiBold)
                                Text("${tag.playlistIds.size} playlists", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { controller.deletePlaylistTag(tag.id) }) {
                                Icon(painterResource(Res.drawable.delete), "Delete tag", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (editingTag != null) editingTagId = null else onDismiss() }) {
                Text(if (editingTag != null) "Back" else "Done")
            }
        },
    )
}

@Composable
private fun PlaylistTile(playlist: MusicPlaylist, modifier: Modifier, grid: Boolean, onClick: () -> Unit) {
    val body: @Composable () -> Unit = {
        CatalogArtwork(playlist.tracks.firstOrNull()?.artworkUrl, if (grid) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.size(64.dp))
        Column(Modifier.padding(if (grid) 4.dp else 12.dp)) {
            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text("Playlist • ${playlist.tracks.size} tracks", maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (grid) Column(modifier.clickable(onClick = onClick)) { body() } else Row(modifier.clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) { body() }
}

@Composable
private fun LibraryShortcut(
    title: String,
    subtitle: String,
    icon: DrawableResource,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .96f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ShortcutCardScale",
    )
    val background = Color(androidBlendArgb(MaterialTheme.colorScheme.surfaceContainer.toArgb(), color.toArgb(), if (dark) .08f else .06f))
    Column(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(26.dp))
            .background(background)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = if (dark) .16f else .10f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = color)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** ColorUtils.blendARGB uses byte-channel interpolation, rather than Compose's Oklab lerp. */
private fun androidBlendArgb(first: Int, second: Int, ratio: Float): Int {
    val inverse = 1f - ratio
    fun channel(shift: Int) = (((first ushr shift and 255) * inverse) + ((second ushr shift and 255) * ratio)).toInt()
    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

@Composable
private fun LibraryCollectionEmpty(onSearch: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(Res.drawable.music_note), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Text("No results found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(onClick = onSearch) { Text("Search") }
        }
    }
}

private fun formatLibraryBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) "${((mb / 1024.0) * 10).toInt() / 10.0} GB"
    else "${(mb * 10).toInt() / 10.0} MB"
}
