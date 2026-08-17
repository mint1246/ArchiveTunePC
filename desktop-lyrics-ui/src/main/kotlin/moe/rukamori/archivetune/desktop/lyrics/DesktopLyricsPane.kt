/*
 * Desktop lyrics pane. This is the public composable entry point used by the desktop
 * shell. Internally it now defers all rendering to `SpicyLyricsView` for timed lyrics
 * (matching SpicyLyrics' per-character motion model) and falls back to a simple
 * read-only text list for unsynchronised plain lyrics.
 */
package moe.rukamori.archivetune.desktop.lyrics

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.lyrics.anim.VibrantLyricsBackground
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchResult
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchStatus
import moe.rukamori.archivetune.lyrics.LyricsRequest

/**
 * Public entry point. Loads either the new SpicyLyrics-style render or the legacy
 * fallback for plain (unsynchronised) lyrics.
 */
@Composable
fun DesktopLyricsPane(presenter: DesktopLyricsPresenter, modifier: Modifier = Modifier) {
    DisposableEffect(presenter) {
        presenter.setActive(true)
        onDispose { presenter.setActive(false) }
    }

    val state by presenter.state.collectAsState()
    val searchState by presenter.searchState.collectAsState()
    val playback by presenter.playbackState.collectAsState()
    var showSearch by remember(searchState.itemId) { mutableStateOf(false) }
    var title by remember(searchState.itemId) { mutableStateOf(searchState.request?.title.orEmpty()) }
    var artist by remember(searchState.itemId) { mutableStateOf(searchState.request?.artist.orEmpty()) }
    var album by remember(searchState.itemId) { mutableStateOf(searchState.request?.album.orEmpty()) }
    val ready = state as? DesktopLyricsState.Ready
    val artwork = playback.currentItem?.metadata?.artworkUri

    LaunchedEffect(state) {
        if (state is DesktopLyricsState.Empty || state is DesktopLyricsState.Error) showSearch = true
    }

    Box(modifier.fillMaxSize()) {
        VibrantLyricsBackground(artworkUri = artwork, isPlaying = playback.isPlaying)
        Column(Modifier.fillMaxSize()) {
            HeaderRow(
                title = playback.currentItem?.metadata?.title ?: "Lyrics",
                artist = playback.currentItem?.metadata?.artistText,
                provider = ready?.providerName,
                onToggleSearch = { showSearch = !showSearch },
                showSearchToggle = searchState.request != null,
                showSearch = showSearch,
            )
            if (showSearch && searchState.request != null) {
                SearchPanel(
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
                    onSelect = { if (presenter.select(it)) showSearch = false },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val current = state) {
                    is DesktopLyricsState.Ready -> {
                        if (current.timeline.synchronized) {
                            val built = remember(current.itemId, current.timeline) {
                                CharTimelineBuilder.build(current.timeline)
                            }
                            SpicyLyricsView(
                                lyrics = built,
                                positionMsState = presenter.positionMsFlow,
                                artworkUri = artwork,
                                modifier = Modifier.fillMaxSize(),
                                onSeek = { presenter.seekToMs(it) },
                                isPlaying = playback.isPlaying,
                            )
                        } else {
                            SimpleLyricsView(
                                timeline = current.timeline,
                                modifier = Modifier.fillMaxSize(),
                                onSeek = { presenter.seekToMs(it) },
                            )
                        }
                    }
                    DesktopLyricsState.Idle -> LyricsMessage("Select a song to see lyrics", Modifier)
                    is DesktopLyricsState.Loading -> LyricsMessage("Finding lyrics…", Modifier, busy = true)
                    is DesktopLyricsState.Empty -> LyricsMessage(
                        message = if (searchState.results.any { it.document != null }) "Choose a provider result above" else current.message,
                        modifier = Modifier,
                        retry = if (searchState.results.any { it.document != null }) null else presenter::retry,
                    )
                    is DesktopLyricsState.Error -> LyricsMessage(
                        message = current.message,
                        modifier = Modifier,
                        retry = presenter::retry,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(
    title: String,
    artist: String?,
    provider: String?,
    onToggleSearch: () -> Unit,
    showSearchToggle: Boolean,
    showSearch: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(
                artist?.takeIf(String::isNotBlank),
                provider?.let { "Lyrics by $it" },
            ).joinToString("  ·  ").ifBlank { "Select a song to begin" }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showSearchToggle) {
            TextButton(onClick = onToggleSearch) {
                Text(if (showSearch) "Hide search" else "Find another version")
            }
        }
    }
}

@Composable
private fun LyricsMessage(
    message: String,
    modifier: Modifier,
    busy: Boolean = false,
    retry: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (busy) CircularProgressIndicator()
            Text(message, color = MaterialTheme.colorScheme.onSurface)
            retry?.let { action -> TextButton(onClick = action) { Text("Retry") } }
        }
    }
}

@Composable
private fun SimpleLyricsView(timeline: LyricsTimeline, modifier: Modifier, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(timeline) {
        if (timeline.lines.isNotEmpty()) {
            // No position info here; just keep the user at the top of the list.
        }
    }
    BoxWithConstraints(modifier) {
        val horizontal = (maxWidth * 0.08f).coerceAtLeast(24.dp)
        val top = (maxHeight * 0.20f).coerceIn(64.dp, 180.dp)
        val bottom = (maxHeight * 0.30f).coerceIn(80.dp, 220.dp)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = horizontal),
            state = listState,
            contentPadding = PaddingValues(top = top, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(timeline.lines, key = { i, _ -> i }) { i, line ->
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp,
                    ),
                    color = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(enabled = line.startTimeMs != null) {
                            line.startTimeMs?.let(onSeek)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchPanel(
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
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        val useCompactForm = maxWidth < 760.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (useCompactForm) {
                OutlinedTextField(title, onTitleChange, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artist, onArtistChange, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(album, onAlbumChange, label = { Text("Album (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = onSearch,
                    enabled = title.isNotBlank() && artist.isNotBlank() && !state.searching,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Search all") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(title, onTitleChange, label = { Text("Title") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(artist, onArtistChange, label = { Text("Artist") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(album, onAlbumChange, label = { Text("Album (optional)") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = onSearch, enabled = title.isNotBlank() && artist.isNotBlank() && !state.searching) { Text("Search all") }
                }
            }
            if (state.searching) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text("Searching lyrics providers…", color = MaterialTheme.colorScheme.onSurface)
                }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface) }
            if (state.results.isNotEmpty()) {
                val providerScroll = rememberScrollState()
                val scope = rememberCoroutineScope()
                val cardStepPx = with(LocalDensity.current) { 178.dp.toPx() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { scope.launch { providerScroll.animateScrollBy(-cardStepPx) } },
                        enabled = providerScroll.value > 0,
                    ) { Text("‹", style = MaterialTheme.typography.titleLarge) }
                    Row(
                        Modifier.weight(1f).heightIn(max = 118.dp).horizontalScroll(providerScroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) { state.results.forEach { result -> ProviderResultCard(result, onSelect) } }
                    IconButton(
                        onClick = { scope.launch { providerScroll.animateScrollBy(cardStepPx) } },
                        enabled = providerScroll.value < providerScroll.maxValue,
                    ) { Text("›", style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}

@Composable
private fun ProviderResultCard(result: LyricsProviderSearchResult, onSelect: (LyricsProviderSearchResult) -> Unit) {
    Card(Modifier.width(170.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(result.providerName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (result.status) {
                    LyricsProviderSearchStatus.SEARCHING -> "Searching…"
                    LyricsProviderSearchStatus.FOUND -> if (result.document?.synchronized == true) "Found · synced" else "Found · plain"
                    LyricsProviderSearchStatus.NO_MATCH -> "No match"
                    LyricsProviderSearchStatus.TIMED_OUT -> "Timed out"
                    LyricsProviderSearchStatus.FAILED -> result.failureMessage?.takeIf { it.isNotBlank() } ?: "Provider failed"
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (result.status == LyricsProviderSearchStatus.FOUND) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = { onSelect(result) }, enabled = result.document != null) {
                Text("Use lyrics")
            }
        }
    }
}
