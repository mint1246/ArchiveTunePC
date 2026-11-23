package moe.rukamori.archivetune.shared

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** The lyrics view follows transport state without owning or recreating the audio services. */
@Composable
internal fun NowPlayingLyricsPane(controller: MusicController, modifier: Modifier = Modifier) {
    val lyrics by controller.lyrics.collectAsState()
    val transport by controller.playback.collectAsState()
    val list = rememberLazyListState()
    val active = lyrics.activeLines(transport.positionMs)
    LaunchedEffect(lyrics.trackId) { list.scrollToItem(0) }
    LaunchedEffect(lyrics.trackId, active?.first) {
        active?.let { list.animateScrollToItem((it.first - 2).coerceAtLeast(0)) }
    }
    Box(modifier) {
        transport.track?.artworkUrl?.let { artwork ->
            AsyncImage(model = artwork, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(32.dp).alpha(0.22f))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        ))))
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Text(transport.track?.title.orEmpty(), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text(transport.track?.artist.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
            when {
                lyrics.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                lyrics.error != null -> Column(Modifier.padding(vertical = 32.dp)) {
                    Text(lyrics.error?.message.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = controller::retryLyrics) { Text("Try again") }
                }
                lyrics.lines.isEmpty() -> Text("No lyrics available for this song.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 32.dp))
                else -> LazyColumn(state = list, contentPadding = PaddingValues(top = 12.dp, bottom = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    itemsIndexed(lyrics.lines, key = { index, _ -> "${lyrics.trackId}:$index" }) { index, line ->
                        val highlighted = active?.contains(index) == true
                        val color by animateColorAsState(when {
                            highlighted -> Color.White
                            line.startTimeMs == null -> Color.White
                            else -> Color.White.copy(alpha = 0.46f)
                        })
                        Text(line.text.ifEmpty { "♪" }, color = color,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = line.startTimeMs != null) {
                                line.startTimeMs?.let(controller::seekTo)
                            })
                    }
                }
            }
        }
    }
}
