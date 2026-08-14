package moe.rukamori.archivetune.desktop.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchResult
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchStatus
import moe.rukamori.archivetune.lyrics.LyricsRequest

@Composable
fun DesktopLyricsPane(presenter: DesktopLyricsPresenter, modifier: Modifier = Modifier) {
    val state by presenter.state.collectAsState()
    val searchState by presenter.searchState.collectAsState()
    val listState = rememberLazyListState()
    var showSearch by remember(searchState.itemId) { mutableStateOf(false) }
    var title by remember(searchState.itemId) { mutableStateOf(searchState.request?.title.orEmpty()) }
    var artist by remember(searchState.itemId) { mutableStateOf(searchState.request?.artist.orEmpty()) }
    var album by remember(searchState.itemId) { mutableStateOf(searchState.request?.album.orEmpty()) }
    val ready = state as? DesktopLyricsState.Ready
    LaunchedEffect(ready?.itemId, ready?.activeLineIndex) {
        ready?.activeLineIndex?.takeIf { it >= 0 }?.let { listState.animateScrollToItem(it.coerceAtLeast(0)) }
    }
    LaunchedEffect(state) {
        if (state is DesktopLyricsState.Empty || state is DesktopLyricsState.Error) showSearch = true
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ready?.let { "Lyrics · ${it.providerName}" } ?: "Lyrics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (searchState.request != null) TextButton(onClick = { showSearch = !showSearch }) { Text(if (showSearch) "Hide search" else "Search providers") }
        }
        if (showSearch && searchState.request != null) {
            LyricsSearchPanel(
                title = title,
                artist = artist,
                album = album,
                onTitleChange = { title = it },
                onArtistChange = { artist = it },
                onAlbumChange = { album = it },
                state = searchState,
                onSearch = {
                    val original = searchState.request
                    presenter.search(
                        LyricsRequest(
                            title = title,
                            artist = artist,
                            album = album.ifBlank { null },
                            durationSeconds = original?.durationSeconds ?: -1,
                            videoId = original?.videoId,
                        ),
                    )
                },
                onSelect = { presenter.select(it); showSearch = false },
            )
        }
        Box(Modifier.weight(1f)) {
            when (val current = state) {
                is DesktopLyricsState.Ready -> LazyColumn(
                    modifier = Modifier.fillMaxSize(), state = listState,
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
                DesktopLyricsState.Idle -> LyricsMessage("Select a song to see lyrics", Modifier)
                is DesktopLyricsState.Loading -> LyricsMessage("Loading lyrics…", Modifier)
                is DesktopLyricsState.Empty -> LyricsMessage(
                    if (searchState.results.any { it.document != null }) "Lyrics found — choose a provider result above" else current.message,
                    Modifier,
                    presenter::retry,
                )
                is DesktopLyricsState.Error -> LyricsMessage(current.message, Modifier, presenter::retry)
            }
        }
    }
}

@Composable
private fun LyricsSearchPanel(
    title: String,
    artist: String,
    album: String,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onAlbumChange: (String) -> Unit,
    state: DesktopLyricsSearchState,
    onSearch: () -> Unit,
    onSelect: (LyricsProviderSearchResult) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(title, onTitleChange, label = { Text("Title") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(artist, onArtistChange, label = { Text("Artist") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(album, onAlbumChange, label = { Text("Album (optional)") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = onSearch, enabled = title.isNotBlank() && artist.isNotBlank() && !state.searching) { Text("Search all") }
        }
        if (state.searching) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.width(18.dp))
            Text("Searching all lyrics providers…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.results.isNotEmpty()) Row(
            Modifier.fillMaxWidth().heightIn(max = 118.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.results.forEach { result -> ProviderResultCard(result, onSelect) }
        }
    }
}

@Composable
private fun ProviderResultCard(result: LyricsProviderSearchResult, onSelect: (LyricsProviderSearchResult) -> Unit) {
    Card(Modifier.width(190.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(result.providerName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                when (result.status) {
                    LyricsProviderSearchStatus.SEARCHING -> "Searching…"
                    LyricsProviderSearchStatus.FOUND -> if (result.document?.synchronized == true) "Found · synced" else "Found · plain"
                    LyricsProviderSearchStatus.NO_MATCH -> "No match"
                    LyricsProviderSearchStatus.TIMED_OUT -> "Timed out"
                    LyricsProviderSearchStatus.FAILED -> "Provider failed"
                },
                color = if (result.status == LyricsProviderSearchStatus.FOUND) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (result.document != null) TextButton(onClick = { onSelect(result) }) { Text("Use lyrics") }
        }
    }
}

@Composable
private fun LyricsMessage(message: String, modifier: Modifier, retry: (() -> Unit)? = null) = Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        retry?.let { action -> TextButton(onClick = action) { Text("Retry") } }
    }
}
