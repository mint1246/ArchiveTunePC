/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.playback.desktop.VlcjCanvasVideoPlayer
import javax.swing.JPanel

/** Video occupies the artwork slot only after VLC accepts it; the poster remains the failure path. */
@Composable
internal fun DesktopCanvasSurface(
    mediaId: String?,
    videoUri: String?,
    artworkUri: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val player by produceState<VlcjCanvasVideoPlayer?>(null, mediaId, !videoUri.isNullOrBlank()) {
        if (videoUri.isNullOrBlank()) {
            value = null
            return@produceState
        }
        var created: VlcjCanvasVideoPlayer? = null
        try {
            // Retain ownership even if cancellation happens while returning from the IO dispatcher.
            withContext(Dispatchers.IO) { created = runCatching { VlcjCanvasVideoPlayer() }.getOrNull() }
            value = created
            awaitDispose { }
        } finally {
            created?.close()
        }
    }
    val activePlayer = player
    val ready by (player?.ready ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    LaunchedEffect(player, videoUri, isPlaying) {
        withContext(Dispatchers.IO) { player?.show(videoUri, isPlaying) }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (activePlayer != null) {
            // SwingPanel's factory is only called on insertion, so a replacement player needs a new panel.
            key(activePlayer) {
                SwingPanel(
                    factory = { activePlayer.awtComponent as JPanel },
                    modifier = Modifier.fillMaxSize(),
                    update = { _ -> Unit },
                )
            }
        }
        if (!ready || player == null) {
            if (!artworkUri.isNullOrBlank()) {
                AsyncImage(artworkUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
