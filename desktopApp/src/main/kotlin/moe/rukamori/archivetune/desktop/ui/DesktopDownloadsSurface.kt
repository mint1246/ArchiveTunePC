/* ArchiveTune (2026) — GPL-3.0-or-later */
package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier,
) {
    val downloads by services.downloads.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var message by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Downloads", style = MaterialTheme.typography.headlineMedium); Text(services.downloadDirectory.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedButton(onClick = { scope.launch { chooseDownloadFolder()?.let { folder -> services.changeDirectory(folder).onFailure { message = it.message } } } }) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Change folder") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (downloads.isEmpty()) EmptyDownloads() else downloads.forEach { item -> DownloadRow(item, onCancel = { services.cancel(item.id) }, onRetry = { if (services.retry(item.id) == null) message = "This download was restored after restart. Search and download it again to obtain a fresh stream." }, onPlay = { onPlayFile(services.downloadDirectory.resolve(item.fileName), item) }) }
    }
}

@Composable private fun EmptyDownloads() = Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(12.dp)); Text("Download songs from search to keep them available offline.") } }

@Composable private fun DownloadRow(item: DownloadSnapshot, onCancel: () -> Unit, onRetry: () -> Unit, onPlay: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium); item.artist?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; when (item.status) { DownloadStatus.COMPLETED -> IconButton(onPlay) { Icon(Icons.Rounded.PlayArrow, "Play downloaded file") }; DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> IconButton(onCancel) { Icon(Icons.Rounded.Cancel, "Cancel download") }; DownloadStatus.FAILED, DownloadStatus.CANCELLED -> IconButton(onRetry) { Icon(Icons.Rounded.Refresh, "Retry download") } } }
        if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) { LinearProgressIndicator(progress = { (item.totalBytes?.let { item.bytesDownloaded.toFloat() / it } ?: 0f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()); Text(item.totalBytes?.let { "${item.bytesDownloaded / 1024} KB / ${it / 1024} KB" } ?: "${item.bytesDownloaded / 1024} KB downloaded", style = MaterialTheme.typography.labelSmall) }
        Text(item.error ?: item.status.name.lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelMedium, color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

private suspend fun chooseDownloadFolder(): Path? = withContext(Dispatchers.IO) {
    var selected: java.io.File? = null
    javax.swing.SwingUtilities.invokeAndWait { javax.swing.JFileChooser().apply { dialogTitle = "Select download folder"; fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; isAcceptAllFileFilterUsed = false; if (showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) selected = selectedFile } }
    selected?.toPath()
}
