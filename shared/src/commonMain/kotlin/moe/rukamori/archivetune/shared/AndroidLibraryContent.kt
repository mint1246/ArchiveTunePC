package moe.rukamori.archivetune.shared

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import moe.rukamori.archivetune.sdk.playlist.PlaylistSongSort
import moe.rukamori.archivetune.sdk.playlist.sortBySongSort
import moe.rukamori.archivetune.shared.resources.Res
import moe.rukamori.archivetune.shared.resources.favorite
import moe.rukamori.archivetune.shared.resources.library_filled
import moe.rukamori.archivetune.shared.resources.music_note
import moe.rukamori.archivetune.shared.resources.queue_music
import moe.rukamori.archivetune.shared.resources.offline
import moe.rukamori.archivetune.shared.resources.cached
import moe.rukamori.archivetune.shared.resources.snippet_folder
import moe.rukamori.archivetune.shared.resources.trending_up
import moe.rukamori.archivetune.shared.resources.playlist_add
import moe.rukamori.archivetune.shared.resources.graphic_eq
import moe.rukamori.archivetune.shared.resources.mic
import moe.rukamori.archivetune.shared.resources.album
import moe.rukamori.archivetune.shared.resources.person
import moe.rukamori.archivetune.shared.resources.add
import moe.rukamori.archivetune.shared.resources.expand_more
import moe.rukamori.archivetune.shared.resources.arrow_back

/** Local collections use the same filter and shortcut language as Android's Library. */
@Composable
internal fun AndroidLibraryContent(controller: MusicController, modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    val libraryFlow = controller.library
    if (libraryFlow == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Library unavailable") }
        return
    }
    val library by libraryFlow.collectAsState()
    val filters = listOf("Library", "Playlists", "Podcasts", "Songs", "Artists", "Albums")
    val pagerState = rememberPagerState(pageCount = { filters.size })
    val scope = rememberCoroutineScope()
    val pageStateHolder = rememberSaveableStateHolder()
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var nameDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var addTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var sort by rememberSaveable { mutableStateOf("Recently added") }
    var sortMenu by remember { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    val collectionTitle = mapOf("liked" to "Liked songs", "offline" to "Offline", "cached" to "Cached", "local" to "Local files", "top" to "My Top")[detail]
    val selected = library.playlists.firstOrNull { it.id == detail }
    val detailSongs = if (detail == "liked") library.favorites else selected?.tracks.orEmpty()

    if (nameDialog != null) {
        val editing = library.playlists.firstOrNull { it.id == nameDialog }
        var value by remember(nameDialog) { mutableStateOf(editing?.name.orEmpty()) }
        AlertDialog(onDismissRequest = { nameDialog = null }, icon = { Icon(painterResource(Res.drawable.playlist_add), null) }, title = { Text(if (editing == null) "Create playlist" else "Rename playlist") },
            text = { OutlinedTextField(value, { value = it }, placeholder = { Text("Playlist name") }, singleLine = true) },
            confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = {
                if (editing == null) controller.createPlaylist(value) else controller.renamePlaylist(editing.id, value)
                nameDialog = null
            }) { Text(if (editing == null) "Create" else "Save") } },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Cancel") } })
    }
    deleteDialog?.let { id ->
        AlertDialog(onDismissRequest = { deleteDialog = null }, title = { Text("Delete playlist?") },
            text = { Text("This removes the playlist from your library.") },
            confirmButton = { TextButton(onClick = { controller.deletePlaylist(id); if (detail == id) detail = null; deleteDialog = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteDialog = null }) { Text("Cancel") } })
    }
    addTrack?.let { track ->
        AlertDialog(onDismissRequest = { addTrack = null }, title = { Text("Add to playlist") },
            text = { Column { if (library.playlists.isEmpty()) Text("Create a playlist first.") else library.playlists.forEach { playlist ->
                Text(playlist.name, Modifier.fillMaxWidth().clickable { controller.addToPlaylist(playlist.id, track); addTrack = null }.padding(vertical = 12.dp))
            } } }, confirmButton = { TextButton(onClick = { addTrack = null }) { Text("Close") } })
    }
    Column(modifier.fillMaxSize()) {
        if (detail != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { detail = null }) { Icon(painterResource(Res.drawable.arrow_back), "Back") }
                Text(when (detail) { "liked" -> "Liked songs"; "offline" -> "Offline"; "cached" -> "Cached"; "local" -> "Local files"; "top" -> "My Top 50"; else -> selected?.name.orEmpty() }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selected != null) {
                    TextButton(onClick = { nameDialog = selected.id }) { Text("Edit") }
                    TextButton(onClick = { deleteDialog = selected.id }) { Text("Delete") }
                }
            }
            Text("${detailSongs.size} tracks", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (detailSongs.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp)) {
                Button(onClick = { controller.playAt(0, detailSongs) }) { Text("Play") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { controller.playShuffled(detailSongs) }) { Text("Shuffle") }
            }
            if (detailSongs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (detail in listOf("offline", "cached", "local", "top")) "This collection is not available yet" else "No songs yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(detailSongs) { index, track ->
                    Row(Modifier.fillMaxWidth().clickable { controller.playAt(index, detailSongs) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CatalogArtwork(track.artworkUrl, Modifier.size(52.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (detail == "liked") TextButton(onClick = { addTrack = track }) { Text("Add") }
                        else TextButton(onClick = { selected?.let { controller.removeFromPlaylist(it.id, index) } }) { Text("Remove") }
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
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 190.dp), verticalArrangement = Arrangement.spacedBy(if (filter == "Library") 24.dp else 12.dp)) {
                if (filter == "Library") item(key = "most_played_album_spotlight") { Spacer(Modifier.height(0.dp)) }
                if (filter == "Library") item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LibraryShortcut("Liked songs", "${library.favorites.size} tracks", Res.drawable.favorite, MaterialTheme.colorScheme.error, Modifier.weight(1f)) { detail = "liked" }
                            LibraryShortcut("Offline", "Downloaded", Res.drawable.offline, MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { detail = "offline" }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LibraryShortcut("Cached", "Instant playback", Res.drawable.cached, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f)) { detail = "cached" }
                            LibraryShortcut("Local Files", "On device", Res.drawable.snippet_folder, MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { detail = "local" }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LibraryShortcut("My top 50", "All time", Res.drawable.trending_up, MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { detail = "top" }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (filter == "Songs") {
                    if (library.favorites.isEmpty()) item { LibraryCollectionEmpty(onSearch) }
                    itemsIndexed(library.favorites) { index, track ->
                        Row(Modifier.fillMaxWidth().clickable { controller.playAt(index, library.favorites) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            CatalogArtwork(track.artworkUrl, Modifier.size(52.dp)); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(track.title, maxLines = 1); Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                            TextButton(onClick = { addTrack = track }) { Text("Add") }
                        }
                    }
                } else if (filter == "Playlists" || (filter == "Library" && library.playlists.isNotEmpty())) {
                    item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Playlists", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { nameDialog = "new" }, colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.size(40.dp)) { Icon(painterResource(Res.drawable.add), "Create playlist", Modifier.size(18.dp)) }
                    } }
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        Box { TextButton(onClick = { sortMenu = true }) { Text(sort); Icon(painterResource(Res.drawable.expand_more), null, Modifier.size(16.dp)) }; DropdownMenu(sortMenu, { sortMenu = false }) { listOf("Recently added", "Name").forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { sort = choice; sortMenu = false }) } } }
                        Spacer(Modifier.weight(1f)); TextButton(onClick = { grid = !grid }) { Text(if (grid) "List" else "Grid") }
                    } }
                    val playlists = if (sort == "Name") {
                        sortBySongSort(
                            items = library.playlists,
                            sort = PlaylistSongSort.TITLE,
                            title = { it.name },
                            firstArtist = { "" },
                            album = { "" },
                            duration = { null },
                        )
                    } else {
                        library.playlists.asReversed()
                    }
                    if (playlists.isEmpty()) item {
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(Res.drawable.playlist_add), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                                }
                                Text("No results found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Button(onClick = { nameDialog = "new" }) { Text("Create playlist") }
                            }
                        }
                    }
                    if (grid) items(playlists.chunked(2)) { row -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { playlist -> PlaylistTile(playlist, Modifier.weight(1f), true) { detail = playlist.id } }; if (row.size == 1) Spacer(Modifier.weight(1f)) } }
                    else items(playlists, key = { it.id }) { playlist -> PlaylistTile(playlist, Modifier.fillMaxWidth().animateItem(), false) { detail = playlist.id } }
                } else if (filter == "Artists" || filter == "Albums") {
                    item { LibraryCollectionEmpty(onSearch) }
                }
            }
            }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                filters.forEachIndexed { index, label ->
                    val chosen = pagerState.currentPage == index
                    val chipColor by animateColorAsState(if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), animationSpec = spring(stiffness = Spring.StiffnessMedium))
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) .92f else if (chosen) 1.05f else 1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                    Row(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(chipColor).clickable(interactionSource = interaction, indication = null) { scope.launch { pagerState.animateScrollToPage(index) } }.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(when (label) { "Playlists" -> Res.drawable.queue_music; "Songs" -> Res.drawable.music_note; "Podcasts" -> Res.drawable.mic; "Artists" -> Res.drawable.person; "Albums" -> Res.drawable.album; else -> Res.drawable.graphic_eq }), null, Modifier.size(20.dp), tint = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(if (label == "Podcasts") "Podcast" else label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            }
        }
    }
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
private fun LibraryShortcut(title: String, subtitle: String, icon: org.jetbrains.compose.resources.DrawableResource, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "ShortcutCardScale")
    val background = Color(androidBlendArgb(MaterialTheme.colorScheme.surfaceContainer.toArgb(), color.toArgb(), if (dark) .08f else .06f))
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(26.dp)).background(background).clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
