/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem

/** Shared, self-contained media primitives used by the desktop Home and Explore surfaces. */

@Composable
fun DesktopMediaArtwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(MaterialTheme.shapes.small),
        )
    } else {
        Box(
            modifier = modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun DesktopSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DesktopMediaGridCard(
    item: YTItem,
    isActive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onSetLiked: ((Boolean) -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    onNotInterested: (() -> Unit)? = null,
    isLiked: Boolean = false,
    isPinned: Boolean = false,
) {
    val thumbnailShape = if (item is ArtistItem) CircleShape else RoundedCornerShape(8.dp)
    val hasMenu =
        onPlayNext != null || onAddToQueue != null || onDownload != null || onAddToPlaylist != null ||
            onSetLiked != null || onPin != null || onNotInterested != null
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box {
            DesktopMediaArtwork(
                url = item.thumbnail,
                contentDescription = item.title,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(thumbnailShape),
            )
            if (hasMenu) {
                MediaOverflowMenu(
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onDownload = onDownload,
                    onAddToPlaylist = onAddToPlaylist,
                    onSetLiked = onSetLiked,
                    onPin = onPin,
                    onNotInterested = onNotInterested,
                    isLiked = isLiked,
                    isPinned = isPinned,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
            if (isActive && isPlaying) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.VolumeUp, contentDescription = "Now playing", modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = desktopItemSubtitle(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DesktopMediaListRow(
    song: SongItem,
    isActive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onSetLiked: ((Boolean) -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    onNotInterested: (() -> Unit)? = null,
    isLiked: Boolean = false,
    isPinned: Boolean = false,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp)) {
            DesktopMediaArtwork(song.thumbnail, song.title, Modifier.fillMaxWidth().height(56.dp))
            if (isActive && isPlaying) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.VolumeUp, contentDescription = "Now playing", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        song.duration?.let { duration ->
            Text(
                text = desktopFormatDuration(duration * 1000L),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (
            onPlayNext != null || onAddToQueue != null || onDownload != null || onAddToPlaylist != null ||
                onSetLiked != null || onPin != null || onNotInterested != null
        ) {
            MediaOverflowMenu(
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onDownload = onDownload,
                onAddToPlaylist = onAddToPlaylist,
                onSetLiked = onSetLiked,
                onPin = onPin,
                onNotInterested = onNotInterested,
                isLiked = isLiked,
                isPinned = isPinned,
            )
        }
    }
}

@Composable
internal fun MediaOverflowMenu(
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onSetLiked: ((Boolean) -> Unit)?,
    onPin: (() -> Unit)?,
    onNotInterested: (() -> Unit)?,
    isLiked: Boolean,
    isPinned: Boolean,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.82f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = CircleShape,
        ) {
            IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More actions", modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onPlayNext?.let { action ->
                DropdownMenuItem(text = { Text("Play next") }, onClick = { open = false; action() })
            }
            onAddToQueue?.let { action ->
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { open = false; action() })
            }
            onDownload?.let { action ->
                DropdownMenuItem(
                    text = { Text("Download") },
                    leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                    onClick = { open = false; action() },
                )
            }
            onSetLiked?.let { action ->
                DropdownMenuItem(
                    text = { Text(if (isLiked) "Remove like" else "Like song") },
                    leadingIcon = {
                        Icon(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription = null)
                    },
                    onClick = { open = false; action(!isLiked) },
                )
            }
            onAddToPlaylist?.let { action ->
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) },
                    onClick = { open = false; action() },
                )
            }
            onPin?.let { action ->
                DropdownMenuItem(
                    text = { Text(if (isPinned) "Unpin from speed dial" else "Pin to speed dial") },
                    onClick = { open = false; action() },
                )
            }
            onNotInterested?.let { action ->
                DropdownMenuItem(
                    text = { Text("Not interested") },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}

@Composable
fun DesktopPlayPauseIcon(isPlaying: Boolean, contentDescription: String?, modifier: Modifier = Modifier) {
    Icon(
        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

internal fun desktopItemSubtitle(item: YTItem): String =
    when (item) {
        is SongItem ->
            buildString {
                append(item.artists.joinToString { it.name })
                item.album?.let { append(" • ").append(it.name) }
                item.duration?.let { append(" • ").append(desktopFormatDuration(it * 1000L)) }
            }
        is AlbumItem -> listOfNotNull("Album", item.artists?.joinToString { it.name }).joinToString(" • ")
        is ArtistItem -> "Artist"
        is PlaylistItem -> listOfNotNull("Playlist", item.author?.name, item.songCountText).joinToString(" • ")
    }

internal fun desktopFormatDuration(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
