/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.platform.DesktopPlaylistSuggestionsRepository
import moe.rukamori.archivetune.innertube.models.SongItem

@Composable
internal fun DesktopPlaylistSuggestionsSection(
    repository: DesktopPlaylistSuggestionsRepository,
    playlistId: String,
    title: String,
    playlistSongs: List<SongItem>,
    onAdded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by repository.state.collectAsState()
    val accountScope by repository.accountScope.collectAsState()
    val scope = rememberCoroutineScope()
    val songSignature = playlistSongs.joinToString("\u001f") { it.id }

    LaunchedEffect(playlistId, title, songSignature, accountScope) {
        repository.activate(playlistId, title, playlistSongs)
    }

    Card(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Suggested songs", style = MaterialTheme.typography.titleLarge)
                    if (state.query.isNotBlank()) {
                        Text(
                            "${state.query}  •  ${state.currentQueryIndex + 1} / ${state.totalQueries}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(onClick = { scope.launch { repository.refresh() } }, enabled = !state.isLoading) {
                    Text("Refresh")
                }
            }
            if (state.isLoading && state.items.isEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.items.isEmpty() && !state.isLoading && state.error == null) {
                Text("No suggestions available for this playlist yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.items.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.items, key = SongItem::id) { song ->
                        Card(Modifier.width(190.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AsyncImage(
                                    model = song.thumbnail,
                                    contentDescription = song.title,
                                    modifier = Modifier.fillMaxWidth().size(150.dp),
                                )
                                Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        song.artists.joinToString { it.name },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            if (repository.addSuggestions(playlistId, listOf(song)).isSuccess) onAdded()
                                        }
                                    },
                                    enabled = !state.isAdding,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                                ) { Text("Add to playlist") }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { repository.changeQueryPage(-1) } },
                    enabled = state.hasPrevious && !state.isLoading,
                ) { Text("Previous") }
                OutlinedButton(
                    onClick = { scope.launch { repository.changeQueryPage(1) } },
                    enabled = state.hasNext && !state.isLoading,
                ) { Text("Next set") }
                Spacer(Modifier.weight(1f))
                if (state.isLoading && state.items.isNotEmpty()) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                OutlinedButton(
                    onClick = { scope.launch { repository.loadMore() } },
                    enabled = state.hasMore && !state.isLoading,
                ) { Text("More songs") }
            }
        }
    }
}
