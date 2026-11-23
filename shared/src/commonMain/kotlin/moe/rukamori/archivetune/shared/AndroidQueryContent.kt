package moe.rukamori.archivetune.shared

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.resources.*
import org.jetbrains.compose.resources.painterResource

private val resultFilters = listOf(
    "All" to null,
    "Songs" to "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D",
    "Videos" to "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D",
    "Albums" to "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D",
    "Artists" to "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D",
    "Podcasts" to "EgWKAQJQAWoKEAkQChAFEAMQBA%3D%3D",
    "Community playlists" to "EgeKAQQoAEABagoQAxAEEAoQCRAF",
    "Featured playlists" to "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D",
)

/** Android SearchBar expansion, segmented suggestions and real categorized remote results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidQueryContent(controller: MusicController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val library = controller.library?.collectAsState()?.value
    var query by rememberSaveable { mutableStateOf(state.query) }
    var editing by rememberSaveable { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val resultsScroll = rememberLazyListState()
    LaunchedEffect(Unit) { withFrameNanos { }; expanded = editing }
    LaunchedEffect(query, editing) { if (editing) controller.suggest(query) }
    fun submit(value: String) {
        if (value.isBlank()) return
        query = value; editing = false; expanded = false
        controller.search(value, resultFilters[filter].second)
    }
    Column(modifier.fillMaxSize()) {
        SearchBar(inputField = {
            SearchBarDefaults.InputField(query = query, onQueryChange = { query = it; editing = true },
                onSearch = ::submit, expanded = expanded, onExpandedChange = { editing = it; expanded = it },
                placeholder = { Text("Search YouTube Music") },
                leadingIcon = { IconButton(onClick = { if (expanded && state.searchItems.isNotEmpty()) { editing = false; expanded = false } else onBack() }) {
                    Icon(painterResource(Res.drawable.arrow_back), "Back")
                } },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; editing = true; expanded = true }) {
                    Icon(painterResource(Res.drawable.close), "Clear")
                } },
            )
        }, expanded = expanded, onExpandedChange = { editing = it; expanded = it },
            windowInsets = WindowInsets(0, 0, 0, 0), modifier = Modifier.fillMaxWidth(),
            colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            val history = library?.searchHistory.orEmpty().filter { it.contains(query, ignoreCase = true) }
                .let { if (query.isEmpty()) it else it.take(3) }
            val suggestions = state.suggestions.filterNot { it in history }
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (history.isNotEmpty()) item { SearchSectionTitle("Search history") }
                itemsIndexed(history, key = { _, value -> "history:$value" }) { index, value ->
                    SearchSuggestion(value, false, index, history.size, { submit(value) },
                        { controller.removeSearchHistory(value) }, { query = value }, Modifier.animateItem())
                }
                if (suggestions.isNotEmpty()) item { SearchSectionTitle("Suggestions") }
                itemsIndexed(suggestions, key = { _, value -> "suggestion:$value" }) { index, value ->
                    SearchSuggestion(value, true, index, suggestions.size, { submit(value) }, {},
                        { query = value }, Modifier.animateItem())
                }
            }
        }
        if (!expanded) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                resultFilters.forEachIndexed { index, (label, params) ->
                    FilterChip(selected = filter == index, onClick = { filter = index; controller.search(query, params) }, label = { Text(label) },
                        leadingIcon = if (filter == index) {{ Icon(painterResource(Res.drawable.done), null, Modifier.size(FilterChipDefaults.IconSize)) }} else null)
                }
            }
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { message -> Row(Modifier.padding(16.dp)) {
                Text(message, Modifier.weight(1f)); TextButton(onClick = { submit(query) }) { Text("Retry") }
            } }
            AnimatedContent(targetState = filter, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) }, label = "searchFilter") {
                LazyColumn(state = resultsScroll, contentPadding = PaddingValues(top = 8.dp, bottom = 110.dp)) {
                    itemsIndexed(state.searchItems, key = { index, item -> "$index:$item" }) { _, item ->
                        SearchCatalogRow(item, controller, Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionTitle(title: String) {
    Text(title, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun SearchSuggestion(query: String, online: Boolean, index: Int, count: Int,
    onClick: () -> Unit, onDelete: () -> Unit, onFill: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(topStart = if (index == 0) 24.dp else 6.dp,
        topEnd = if (index == 0) 24.dp else 6.dp,
        bottomStart = if (index == count - 1) 24.dp else 6.dp,
        bottomEnd = if (index == count - 1) 24.dp else 6.dp)
    Surface(onClick = onClick, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(40.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(if (online) Res.drawable.search else Res.drawable.history), null, Modifier.size(20.dp)) }
            }
            Spacer(Modifier.width(14.dp))
            Text(query, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (!online) IconButton(onClick = onDelete) { Icon(painterResource(Res.drawable.close), "Remove from history", Modifier.size(18.dp)) }
            IconButton(onClick = onFill) { Icon(painterResource(Res.drawable.arrow_top_left), "Fill query", Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun SearchCatalogRow(item: MusicCatalogItem, controller: MusicController, modifier: Modifier) {
    val track = (item as? MusicCatalogItem.Track)?.track
    val browse = item as? MusicCatalogItem.Browse
    Row(modifier.fillMaxWidth().clickable { if (track != null) controller.playTrack(track) else browse?.let(controller::openBrowse) }
        .heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CatalogArtwork(track?.artworkUrl ?: browse?.artworkUrl, Modifier.size(56.dp), browse?.kind == MusicBrowseKind.ARTIST)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track?.title ?: browse?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track?.artist ?: browse?.subtitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (track != null) IconButton(onClick = { controller.toggleFavorite(track) }) {
            Icon(painterResource(Res.drawable.favorite_border), "Toggle favorite")
        }
    }
}
