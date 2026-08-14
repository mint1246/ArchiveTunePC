package moe.rukamori.archivetune.desktop.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DesktopLyricsPane(presenter: DesktopLyricsPresenter, modifier: Modifier = Modifier) {
    val state by presenter.state.collectAsState()
    val listState = rememberLazyListState()
    val ready = state as? DesktopLyricsState.Ready
    LaunchedEffect(ready?.itemId, ready?.activeLineIndex) {
        ready?.activeLineIndex?.takeIf { it >= 0 }?.let { listState.animateScrollToItem(it.coerceAtLeast(0)) }
    }
    when (val current = state) {
        is DesktopLyricsState.Ready -> LazyColumn(
            modifier = modifier.fillMaxSize(), state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(current.timeline.lines.size) { index ->
                val line = current.timeline.lines[index]
                val active = index == current.activeLineIndex
                Text(
                    line.text,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                line.translation?.let { Text(it, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                line.romanization?.let { Text(it, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        DesktopLyricsState.Idle -> LyricsMessage("Select a song to see lyrics", modifier)
        is DesktopLyricsState.Loading -> LyricsMessage("Loading lyrics…", modifier)
        is DesktopLyricsState.Empty -> LyricsMessage(current.message, modifier, presenter::retry)
        is DesktopLyricsState.Error -> LyricsMessage(current.message, modifier, presenter::retry)
    }
}

@Composable
private fun LyricsMessage(message: String, modifier: Modifier, retry: (() -> Unit)? = null) = Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        retry?.let { action -> TextButton(onClick = action) { Text("Retry") } }
    }
}
