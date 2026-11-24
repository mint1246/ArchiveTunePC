package moe.rukamori.archivetune.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.resources.*
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun AndroidSettingsContent(controller: MusicController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    AndroidFullSettingsContent(controller = controller, onBack = onBack, onOpenFriends = {}, modifier = modifier)
}

@Composable
internal fun AndroidHistoryContent(controller: MusicController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val history = controller.library?.collectAsState()?.value?.history.orEmpty()
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<MusicTrack?>(null) }

    menuTrack?.let { track ->
        AndroidSongMenuSheet(
            track = track,
            controller = controller,
            onDismiss = { menuTrack = null },
            extraActionLabel = "Remove from history",
            extraActionIcon = Res.drawable.delete_history,
            onExtraAction = { controller.removeFromHistory(track.id) },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear listening history?") },
            text = { Text("This clears your recent playback history list.") },
            confirmButton = {
                TextButton(onClick = { controller.clearHistory(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    val filtered = remember(history, query) {
        val q = query.trim()
        if (q.isEmpty()) history
        else history.filter { it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.arrow_back), "Back") }
            Text("History", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (history.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(painterResource(Res.drawable.clear_all), "Clear history")
                }
            }
        }
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No listening history yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter history") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 4.dp, bottom = 140.dp)) {
                items(filtered, key = { it.id }) { track ->
                    Row(
                        Modifier.fillMaxWidth().clickable { controller.playTrack(track) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CatalogArtwork(track.artworkUrl, Modifier.size(52.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { menuTrack = track }) {
                            Icon(painterResource(Res.drawable.more_vert), "More options")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AndroidNewsContent(controller: MusicController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val news = controller.news
    val state = news?.state?.collectAsState()?.value ?: MusicNewsState()
    val readIds = controller.library?.collectAsState()?.value?.readNewsIds.orEmpty()
    var query by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        RouteHeading("News") { if (state.article != null) news?.closeArticle() else onBack() }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Row(Modifier.padding(24.dp)) { Text(it, Modifier.weight(1f)); TextButton(onClick = { news?.load(true) }) { Text("Retry") } } }
        AnimatedContent(
            targetState = state.article,
            transitionSpec = { fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith fadeOut(spring(stiffness = Spring.StiffnessMediumLow)) },
            label = "news content",
        ) { article ->
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (article != null) item { Text(article, style = MaterialTheme.typography.bodyLarge) }
                else {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            label = { Text("Search news") },
                        )
                    }
                    items(state.items.filter { it.title.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) }, key = { it.id }) { item ->
                        val unread = item.id !in readIds
                        ElevatedCard(onClick = {
                            controller.markNewsRead(item.id)
                            news?.open(item.id)
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                item.image?.let { CatalogArtwork(it, Modifier.fillMaxWidth().height(180.dp)) }
                                Column(Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (unread) {
                                            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                        }
                                        Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    }
                                    Text(item.description, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                                    Text(item.author, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
