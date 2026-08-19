/* ArchiveTune (2026) — GPL-3.0-or-later */
package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.platform.DesktopDownloadServices
import moe.rukamori.archivetune.downloads.DownloadSnapshot
import moe.rukamori.archivetune.downloads.DownloadStatus
import java.nio.file.Path

@Composable
fun DesktopDownloadsSurface(
    services: DesktopDownloadServices,
    onPlayFile: (Path, DownloadSnapshot) -> Unit,
    onPlayNextFile: (Path, DownloadSnapshot) -> Unit = { _, _ -> },
    onAddToQueueFile: (Path, DownloadSnapshot) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val downloads by services.downloads.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var message by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val listState = rememberPersistedLazyListState("downloads")
    LazyColumn(state = listState, modifier = modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Downloads", style = MaterialTheme.typography.headlineMedium); Text(services.downloadDirectory.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedButton(onClick = { scope.launch { chooseDownloadFolder()?.let { folder -> services.changeDirectory(folder).onFailure { message = it.message } } } }) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Change folder") }
            }
        }
        message?.let { value -> item { Text(value, color = MaterialTheme.colorScheme.error) } }
        if (downloads.isEmpty()) {
            item { EmptyDownloads() }
        } else {
            items(downloads, key = DownloadSnapshot::id) { download ->
                DownloadRow(
                    download,
                    onCancel = { services.cancel(download.id) },
                    onRetry = {
                        scope.launch {
                            val retry = services.retry(download.id)
                            if (retry == null) {
                                message = "This download was restored after restart. Search and download it again to obtain a fresh stream."
                            } else {
                                retry
                                    .onSuccess { message = null }
                                    .onFailure { message = it.message ?: "Could not resolve a fresh stream for this download." }
                            }
                        }
                    },
                    onPlay = {
                        services.completedFileForDownload(download.id)?.let { onPlayFile(it, download) }
                            ?: run { message = "The downloaded file is missing or unreadable. Download the song again." }
                    },
                    onPlayNext = {
                        services.completedFileForDownload(download.id)?.let { onPlayNextFile(it, download) }
                            ?: run { message = "The downloaded file is missing or unreadable. Download the song again." }
                    },
                    onAddToQueue = {
                        services.completedFileForDownload(download.id)?.let { onAddToQueueFile(it, download) }
                            ?: run { message = "The downloaded file is missing or unreadable. Download the song again." }
                    },
                )
            }
        }
    }
}

@Composable private fun EmptyDownloads() {
    val dynamicGlass = LocalDynamicThemeActive.current
    Card(
        Modifier.fillMaxWidth(),
        colors = if (dynamicGlass) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.58f))
        } else {
            CardDefaults.cardColors()
        },
        border = dynamicGlassBorder(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Download, null)
            Spacer(Modifier.width(12.dp))
            Text("Download songs from search to keep them available offline.")
        }
    }
}

@Composable private fun DownloadRow(item: DownloadSnapshot, onCancel: () -> Unit, onRetry: () -> Unit, onPlay: () -> Unit, onPlayNext: () -> Unit, onAddToQueue: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val dynamicGlass = LocalDynamicThemeActive.current
    Card(
        Modifier.fillMaxWidth(),
        colors = if (dynamicGlass) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.58f))
        } else {
            CardDefaults.cardColors()
        },
        border = dynamicGlassBorder(),
    ) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            item.artworkUrl?.let { artwork ->
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                item.artist?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item.albumTitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            when (item.status) { DownloadStatus.COMPLETED -> IconButton(onPlay) { Icon(Icons.Rounded.PlayArrow, "Play downloaded file") }; DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> IconButton(onCancel) { Icon(Icons.Rounded.Cancel, "Cancel download") }; DownloadStatus.FAILED, DownloadStatus.CANCELLED -> IconButton(onRetry) { Icon(Icons.Rounded.Refresh, "Retry download") } }
            if (item.status == DownloadStatus.COMPLETED) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More actions") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Play next") }, onClick = { menuOpen = false; onPlayNext() })
                        DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menuOpen = false; onAddToQueue() })
                    }
                }
            }
        }
        if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) { LinearProgressIndicator(progress = { (item.totalBytes?.let { item.bytesDownloaded.toFloat() / it } ?: 0f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()); Text(item.totalBytes?.let { "${item.bytesDownloaded / 1024} KB / ${it / 1024} KB" } ?: "${item.bytesDownloaded / 1024} KB downloaded", style = MaterialTheme.typography.labelSmall) }
        Text(item.error ?: item.status.name.lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelMedium, color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

private suspend fun chooseDownloadFolder(): Path? = withContext(Dispatchers.IO) {
    var selected: java.io.File? = null
    javax.swing.SwingUtilities.invokeAndWait { javax.swing.JFileChooser().apply { dialogTitle = "Select download folder"; fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; isAcceptAllFileFilterUsed = false; if (showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) selected = selectedFile } }
    selected?.toPath()
}
