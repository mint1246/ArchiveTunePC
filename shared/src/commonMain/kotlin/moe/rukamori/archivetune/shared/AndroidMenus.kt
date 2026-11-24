/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.shared.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

internal data class AndroidMenuAction(
    val icon: DrawableResource,
    val label: String,
    val enabled: Boolean = true,
    val highlighted: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun menuSheetSurface(): Color =
    if (MaterialTheme.colorScheme.surface.alpha < 0.9f) Color(0xFF1C1B1F) else MaterialTheme.colorScheme.surface

@Composable
private fun menuCardSurface(): Color =
    if (MaterialTheme.colorScheme.surface.alpha < 0.9f) Color(0xFF28272D) else MaterialTheme.colorScheme.surfaceContainerLow

@Composable
private fun menuTileSurface(): Color =
    if (MaterialTheme.colorScheme.surface.alpha < 0.9f) Color(0xFF35343C) else MaterialTheme.colorScheme.surfaceContainerHigh

@Composable
internal fun AndroidMenuSurfaceSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = menuCardSurface(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
internal fun AndroidActionGrid(
    actions: List<AndroidMenuAction>,
    columns: Int = 3,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    val rows = actions.chunked(columns.coerceAtLeast(1))
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { action ->
                    val container = if (action.highlighted) MaterialTheme.colorScheme.primaryContainer
                    else menuTileSurface()
                    val content = if (action.highlighted) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                    FilledTonalButton(
                        onClick = action.onClick,
                        enabled = action.enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 88.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = container,
                            contentColor = content,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(painterResource(action.icon), null, Modifier.size(24.dp))
                            Text(
                                text = action.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun AndroidMenuRow(
    icon: DrawableResource,
    title: String,
    subtitle: String? = null,
    tint: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val effectiveTint = if (tint.isSpecified) tint else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(title, color = effectiveTint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(painterResource(icon), null, Modifier.size(22.dp), tint = effectiveTint) },
        trailingContent = trailing,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun AndroidPlayerMenuSheet(
    track: MusicTrack,
    controller: MusicController,
    onDismiss: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onCollapsePlayer: () -> Unit,
) {
    val library = controller.library?.collectAsState()?.value
    val options by controller.playbackOptions.collectAsState()
    val downloading by controller.downloading.collectAsState()
    val isDownloaded = library?.downloads?.any { it.track.id == track.id } == true
    val dlProgress = downloading[track.id]
    val isPinned = library?.speedDialPins?.any { it.type == "song" && it.id == track.id } == true
    val sleepActive = options.sleepTimerMinutes != null || options.sleepAfterCurrent
    val isLocal = track.localFilePath != null

    var showPlaylistSheet by remember { mutableStateOf(false) }
    var showTempoPitch by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    if (showPlaylistSheet) {
        AndroidAddToPlaylistSheet(
            tracks = listOf(track),
            controller = controller,
            onDismiss = { showPlaylistSheet = false },
        )
    }
    if (showTempoPitch) {
        AndroidTempoPitchDialog(
            speed = options.speed,
            pitch = library?.settings?.playbackPitch ?: 0f,
            onSpeedChange = { s ->
                controller.setSpeed(s)
                controller.updateSettings { it.copy(playbackSpeed = s) }
            },
            onPitchChange = { p -> controller.updateSettings { it.copy(playbackPitch = p) } },
            onDismiss = { showTempoPitch = false },
        )
    }
    if (showDetails) {
        AndroidSongDetailsDialog(track = track, controller = controller, onDismiss = { showDetails = false })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = if (MaterialTheme.colorScheme.surface.alpha < 0.9f) Color(0xFF1C1B1F) else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Now Playing header card
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = menuCardSurface(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CatalogArtwork(track.artworkUrl, Modifier.size(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Now Playing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(
                                track.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.basicMarquee(),
                            )
                            if (track.artist.isNotBlank()) {
                                Text(
                                    track.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.basicMarquee(),
                                )
                            }
                        }
                    }
                }
            }

            // Volume card
            if (controller.canAdjustVolume) {
                item {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = menuCardSurface(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                painterResource(if (options.volume <= 0.01f) Res.drawable.volume_off else Res.drawable.volume_up),
                                null,
                                Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = options.volume,
                                onValueChange = controller::setVolume,
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${(options.volume * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Primary expressive action grid
            item {
                AndroidMenuSurfaceSection {
                    AndroidActionGrid(
                        actions = buildList {
                            add(AndroidMenuAction(Res.drawable.bedtime, "Sleep timer", highlighted = sleepActive, onClick = {
                                onDismiss()
                                onOpenSleepTimer()
                            }))
                            if (!isLocal) {
                                add(AndroidMenuAction(Res.drawable.radio, "Start radio", onClick = {
                                    controller.playTrack(track)
                                    onDismiss()
                                }))
                            }
                            add(AndroidMenuAction(Res.drawable.playlist_add, "Add to playlist", onClick = {
                                showPlaylistSheet = true
                            }))
                            add(AndroidMenuAction(
                                icon = if (isPinned) Res.drawable.bookmark_filled else Res.drawable.bookmark,
                                label = if (isPinned) "Unpin" else "Speed dial",
                                highlighted = isPinned,
                                onClick = {
                                    controller.toggleSpeedDialPin(
                                        MusicSpeedDialPin("song", track.id, track.title, track.artist, track.artworkUrl),
                                    )
                                    onDismiss()
                                },
                            ))
                            if (!isLocal) {
                                add(AndroidMenuAction(Res.drawable.link, "Copy link", onClick = {
                                    controller.copyText("https://music.youtube.com/watch?v=${track.id}")
                                    onDismiss()
                                }))
                            }
                            if (controller.canShare) {
                                add(AndroidMenuAction(Res.drawable.share, "Share", onClick = {
                                    controller.shareTrack(track)
                                    onDismiss()
                                }))
                            }
                        },
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // Artist & Album navigation
            if (!isLocal && (track.artistId != null || track.albumId != null)) {
                item {
                    AndroidMenuSurfaceSection {
                        if (track.artistId != null) {
                            AndroidMenuRow(Res.drawable.artist, "View artist", track.artist.takeIf(String::isNotBlank)) {
                                controller.openBrowse(
                                    MusicCatalogItem.Browse(
                                        id = track.artistId,
                                        title = track.artist.ifBlank { "Artist" },
                                        kind = MusicBrowseKind.ARTIST,
                                    ),
                                )
                                onDismiss()
                                onCollapsePlayer()
                            }
                        }
                        if (track.artistId != null && track.albumId != null) {
                            HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        if (track.albumId != null) {
                            AndroidMenuRow(Res.drawable.album, "View album", track.albumName) {
                                controller.openBrowse(
                                    MusicCatalogItem.Browse(
                                        id = track.albumId,
                                        title = track.albumName ?: "Album",
                                        subtitle = track.artist,
                                        artworkUrl = track.artworkUrl,
                                        kind = MusicBrowseKind.ALBUM,
                                    ),
                                )
                                onDismiss()
                                onCollapsePlayer()
                            }
                        }
                    }
                }
            }

            // Offline download section
            if (!isLocal && controller.platformBridge.canDownload) {
                item {
                    AndroidMenuSurfaceSection {
                        when {
                            isDownloaded -> AndroidMenuRow(
                                icon = Res.drawable.offline,
                                title = "Remove download",
                                tint = MaterialTheme.colorScheme.error,
                            ) {
                                controller.removeDownload(track.id)
                                onDismiss()
                            }
                            dlProgress != null -> AndroidMenuRow(
                                icon = Res.drawable.download,
                                title = "Downloading (${(dlProgress * 100).roundToInt()}%)",
                                trailing = { CircularWavyProgressIndicator(Modifier.size(22.dp)) },
                            ) {
                                controller.removeDownload(track.id)
                            }
                            else -> AndroidMenuRow(Res.drawable.download, "Download") {
                                controller.downloadTrack(track)
                                onDismiss()
                            }
                        }
                    }
                }
            }

            // Audio controls & song info
            item {
                AndroidMenuSurfaceSection {
                    AndroidMenuRow(
                        icon = Res.drawable.tune,
                        title = "Tempo & Pitch",
                        subtitle = "${options.speed}x speed",
                    ) { showTempoPitch = true }
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    AndroidMenuRow(Res.drawable.info, "Details") { showDetails = true }
                }
            }
        }
    }
}

@Composable
internal fun AndroidSongMenuSheet(
    track: MusicTrack,
    controller: MusicController,
    onDismiss: () -> Unit,
    extraActionLabel: String? = null,
    extraActionIcon: DrawableResource = Res.drawable.delete,
    onExtraAction: (() -> Unit)? = null,
) {
    val library = controller.library?.collectAsState()?.value
    val downloading by controller.downloading.collectAsState()
    val isFav = library?.favorites?.any { it.id == track.id } == true
    val isDownloaded = library?.downloads?.any { it.track.id == track.id } == true
    val dlProgress = downloading[track.id]
    val isPinned = library?.speedDialPins?.any { it.type == "song" && it.id == track.id } == true
    val isLocal = track.localFilePath != null

    var showPlaylistSheet by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    if (showPlaylistSheet) {
        AndroidAddToPlaylistSheet(listOf(track), controller, onDismiss = { showPlaylistSheet = false })
    }
    if (showDetails) {
        AndroidSongDetailsDialog(track, controller, onDismiss = { showDetails = false })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = if (MaterialTheme.colorScheme.surface.alpha < 0.9f) Color(0xFF1C1B1F) else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CatalogArtwork(track.artworkUrl, Modifier.size(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { controller.toggleFavorite(track) }) {
                        Icon(
                            painterResource(if (isFav) Res.drawable.favorite else Res.drawable.favorite_border),
                            "Favorite",
                            tint = if (isFav) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            item {
                AndroidMenuSurfaceSection {
                    AndroidActionGrid(
                        actions = buildList {
                            if (!isLocal) {
                                add(AndroidMenuAction(Res.drawable.radio, "Start radio") {
                                    controller.playTrack(track)
                                    onDismiss()
                                })
                            }
                            add(AndroidMenuAction(Res.drawable.playlist_play, "Play next") {
                                controller.playNext(listOf(track))
                                onDismiss()
                            })
                            add(AndroidMenuAction(Res.drawable.queue_music, "Add to queue") {
                                controller.addToQueue(listOf(track))
                                onDismiss()
                            })
                            add(AndroidMenuAction(Res.drawable.playlist_add, "Add to playlist") {
                                showPlaylistSheet = true
                            })
                            add(AndroidMenuAction(
                                icon = if (isPinned) Res.drawable.bookmark_filled else Res.drawable.bookmark,
                                label = if (isPinned) "Unpin" else "Speed dial",
                                highlighted = isPinned,
                            ) {
                                controller.toggleSpeedDialPin(
                                    MusicSpeedDialPin("song", track.id, track.title, track.artist, track.artworkUrl),
                                )
                                onDismiss()
                            })
                            if (controller.canShare) {
                                add(AndroidMenuAction(Res.drawable.share, "Share") {
                                    controller.shareTrack(track)
                                    onDismiss()
                                })
                            }
                        },
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            item {
                AndroidMenuSurfaceSection {
                    if (!isLocal && controller.platformBridge.canDownload) {
                        when {
                            isDownloaded -> AndroidMenuRow(Res.drawable.offline, "Remove download", tint = MaterialTheme.colorScheme.error) {
                                controller.removeDownload(track.id)
                                onDismiss()
                            }
                            dlProgress != null -> AndroidMenuRow(
                                Res.drawable.download,
                                "Downloading (${(dlProgress * 100).roundToInt()}%)",
                                trailing = { CircularWavyProgressIndicator(Modifier.size(22.dp)) },
                            ) { controller.removeDownload(track.id) }
                            else -> AndroidMenuRow(Res.drawable.download, "Download") {
                                controller.downloadTrack(track)
                                onDismiss()
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (track.artistId != null) {
                        AndroidMenuRow(Res.drawable.artist, "View artist", track.artist) {
                            controller.openBrowse(MusicCatalogItem.Browse(track.artistId, track.artist.ifBlank { "Artist" }, kind = MusicBrowseKind.ARTIST))
                            onDismiss()
                        }
                        HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (track.albumId != null) {
                        AndroidMenuRow(Res.drawable.album, "View album", track.albumName) {
                            controller.openBrowse(MusicCatalogItem.Browse(track.albumId, track.albumName ?: "Album", track.artist, track.artworkUrl, MusicBrowseKind.ALBUM))
                            onDismiss()
                        }
                        HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (!isLocal) {
                        AndroidMenuRow(Res.drawable.link, "Copy link") {
                            controller.copyText("https://music.youtube.com/watch?v=${track.id}")
                            onDismiss()
                        }
                        HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    AndroidMenuRow(Res.drawable.info, "Details") { showDetails = true }
                    if (extraActionLabel != null && onExtraAction != null) {
                        HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        AndroidMenuRow(extraActionIcon, extraActionLabel, tint = MaterialTheme.colorScheme.error) {
                            onExtraAction()
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AndroidBrowseMenuSheet(
    item: MusicCatalogItem.Browse,
    tracks: List<MusicTrack>,
    controller: MusicController,
    onDismiss: () -> Unit,
) {
    val library = controller.library?.collectAsState()?.value
    val isSaved = when (item.kind) {
        MusicBrowseKind.ALBUM -> library?.savedAlbums?.any { it.id == item.id } == true
        MusicBrowseKind.ARTIST -> library?.savedArtists?.any { it.id == item.id } == true
        MusicBrowseKind.PLAYLIST -> library?.savedPlaylists?.any { it.id == item.id } == true
        MusicBrowseKind.OTHER -> library?.savedPodcasts?.any { it.id == item.id } == true
    }
    var showAddPlaylist by remember { mutableStateOf(false) }
    if (showAddPlaylist && tracks.isNotEmpty()) {
        AndroidAddToPlaylistSheet(tracks, controller, onDismiss = { showAddPlaylist = false })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = if (MaterialTheme.colorScheme.surface.alpha < 0.9f) Color(0xFF1C1B1F) else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CatalogArtwork(item.artworkUrl, Modifier.size(56.dp), circular = item.kind == MusicBrowseKind.ARTIST)
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            item {
                AndroidMenuSurfaceSection {
                    AndroidActionGrid(
                        actions = buildList {
                            if (tracks.isNotEmpty()) {
                                add(AndroidMenuAction(Res.drawable.play, "Play") {
                                    controller.playAt(0, tracks)
                                    onDismiss()
                                })
                                add(AndroidMenuAction(Res.drawable.shuffle, "Shuffle") {
                                    controller.playShuffled(tracks)
                                    onDismiss()
                                })
                                add(AndroidMenuAction(Res.drawable.playlist_play, "Play next") {
                                    controller.playNext(tracks)
                                    onDismiss()
                                })
                            }
                            add(AndroidMenuAction(
                                icon = if (isSaved) Res.drawable.library_add_check else Res.drawable.library_add,
                                label = if (isSaved) "In library" else "Save",
                                highlighted = isSaved,
                            ) {
                                controller.toggleSavedBrowse(item)
                                onDismiss()
                            })
                            if (tracks.isNotEmpty()) {
                                add(AndroidMenuAction(Res.drawable.playlist_add, "Add to playlist") {
                                    showAddPlaylist = true
                                })
                            }
                            if (controller.canShare) {
                                add(AndroidMenuAction(Res.drawable.share, "Share") {
                                    controller.shareText("${item.title}\nhttps://music.youtube.com/browse/${item.id}")
                                    onDismiss()
                                })
                            }
                        },
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (tracks.isNotEmpty()) {
                item {
                    AndroidMenuSurfaceSection {
                        AndroidMenuRow(Res.drawable.queue_music, "Add all to queue") {
                            controller.addToQueue(tracks)
                            onDismiss()
                        }
                        if (controller.platformBridge.canDownload) {
                            HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            AndroidMenuRow(Res.drawable.download, "Download all (${tracks.size} songs)") {
                                controller.downloadTracks(tracks)
                                onDismiss()
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        AndroidMenuRow(Res.drawable.link, "Copy link") {
                            controller.copyText("https://music.youtube.com/browse/${item.id}")
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AndroidAddToPlaylistSheet(
    tracks: List<MusicTrack>,
    controller: MusicController,
    onDismiss: () -> Unit,
) {
    val library = controller.library?.collectAsState()?.value
    val playlists = library?.playlists.orEmpty()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            icon = { Icon(painterResource(Res.drawable.playlist_add), null) },
            title = { Text("Create playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val id = controller.createPlaylist(newName)
                        if (id != null) {
                            tracks.forEach { controller.addToPlaylist(id, it) }
                        }
                        newName = ""
                        creating = false
                        onDismiss()
                    },
                ) { Text("Create & Add") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = if (MaterialTheme.colorScheme.surface.alpha < 0.9f) Color(0xFF1C1B1F) else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Add to playlist", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { creating = true }) {
                    Icon(painterResource(Res.drawable.add), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New")
                }
            }
            if (playlists.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Text("No playlists yet. Tap New to create one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        val hasFirst = tracks.size == 1 && playlist.tracks.any { it.id == tracks.first().id }
                        Surface(
                            onClick = {
                                tracks.forEach { controller.addToPlaylist(playlist.id, it) }
                                onDismiss()
                            },
                            shape = RoundedCornerShape(18.dp),
                            color = menuCardSurface(),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CatalogArtwork(playlist.tracks.firstOrNull()?.artworkUrl, Modifier.size(48.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${playlist.tracks.size} tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (hasFirst) {
                                    Icon(painterResource(Res.drawable.check), null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AndroidTempoPitchDialog(
    speed: Float,
    pitch: Float,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var localSpeed by remember(speed) { mutableFloatStateOf(speed) }
    var localPitch by remember(pitch) { mutableFloatStateOf(pitch) }
    AndroidActionPromptDialog(
        title = "Tempo & Pitch",
        onDismiss = onDismiss,
        onConfirm = {
            onSpeedChange((localSpeed * 20f).roundToInt() / 20f)
            onPitchChange((localPitch * 2f).roundToInt() / 2f)
            onDismiss()
        },
        onCancel = onDismiss,
        onReset = {
            localSpeed = 1f
            localPitch = 0f
            onSpeedChange(1f)
            onPitchChange(0f)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Playback speed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${((localSpeed * 100).roundToInt() / 100f)}x", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = localSpeed,
                onValueChange = { localSpeed = it },
                valueRange = 0.25f..3.0f,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Pitch", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val semitones = (localPitch * 2f).roundToInt() / 2f
                Text("${if (semitones > 0) "+" else ""}$semitones st", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = localPitch,
                onValueChange = { localPitch = it },
                valueRange = -12f..12f,
                steps = 47,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun AndroidSongDetailsDialog(
    track: MusicTrack,
    controller: MusicController,
    onDismiss: () -> Unit,
) {
    val ytUrl = "https://music.youtube.com/watch?v=${track.id}"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(Res.drawable.info), null) },
        title = { Text("Song details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Title", track.title)
                if (track.artist.isNotBlank()) DetailRow("Artist", track.artist)
                track.albumName?.takeIf(String::isNotBlank)?.let { DetailRow("Album", it) }
                track.durationMs?.takeIf { it > 0L }?.let { DetailRow("Duration", formatTime(it)) }
                DetailRow("ID", track.id)
                if (track.localFilePath != null) DetailRow("Source", "Local file")
                else DetailRow("URL", ytUrl)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                controller.copyText(if (track.localFilePath != null) "${track.title} — ${track.artist}" else ytUrl)
                onDismiss()
            }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
