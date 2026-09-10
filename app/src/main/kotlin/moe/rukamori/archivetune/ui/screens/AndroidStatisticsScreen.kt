/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.statistics.AndroidStatisticsMedia
import moe.rukamori.archivetune.statistics.AndroidStatisticsOverview
import moe.rukamori.archivetune.statistics.AndroidStatisticsRange
import moe.rukamori.archivetune.statistics.AndroidStatisticsSummary
import moe.rukamori.archivetune.statistics.AndroidStatisticsTarget
import moe.rukamori.archivetune.statistics.AndroidStatisticsUiState
import moe.rukamori.archivetune.statistics.AndroidStatisticsViewModel
import moe.rukamori.archivetune.constants.CountPartyListeningInStatisticsKey
import moe.rukamori.archivetune.utils.rememberPreference

/** Route hook intentionally has no NavController dependency; global navigation can wire callbacks. */
@Composable
fun AndroidStatisticsRoute(
    onOpenSong: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AndroidStatisticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val (countPartyListening, onCountPartyListeningChange) =
        rememberPreference(CountPartyListeningInStatisticsKey, defaultValue = true)
    when (val current = state) {
        AndroidStatisticsUiState.Loading ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

        is AndroidStatisticsUiState.Error ->
            StatisticsMessage(
                title = "Statistics unavailable",
                subtitle = current.throwable.message ?: "Could not load listening statistics.",
                modifier = modifier,
            )

        is AndroidStatisticsUiState.Content ->
            AndroidStatisticsScreen(
                overview = current.overview,
                selectedRange = current.selectedRange,
                onRangeSelected = viewModel::selectRange,
                countPartyListening = countPartyListening,
                onCountPartyListeningChange = onCountPartyListeningChange,
                onOpenTarget = { target ->
                    when (target) {
                        is AndroidStatisticsTarget.Song -> onOpenSong(target.id)
                        is AndroidStatisticsTarget.Artist -> onOpenArtist(target.id)
                        is AndroidStatisticsTarget.Album -> onOpenAlbum(target.id)
                    }
                },
                modifier = modifier,
            )
    }
}

@Composable
fun AndroidStatisticsScreen(
    overview: AndroidStatisticsOverview,
    selectedRange: AndroidStatisticsRange,
    onRangeSelected: (AndroidStatisticsRange) -> Unit,
    countPartyListening: Boolean,
    onCountPartyListeningChange: (Boolean) -> Unit,
    onOpenTarget: (AndroidStatisticsTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "statistics_header") {
            StatisticsHeader(
                selectedRange = selectedRange,
                onRangeSelected = onRangeSelected,
                countPartyListening = countPartyListening,
                onCountPartyListeningChange = onCountPartyListeningChange,
            )
        }

        if (overview.isEmpty) {
            item(key = "statistics_empty") {
                StatisticsMessage(
                    title = "No listening statistics yet",
                    subtitle = "Play some music and your audible listening time will appear here.",
                )
            }
        } else {
            item(key = "statistics_summary") {
                StatisticsSummary(overview.summary)
            }

            if (overview.topSongs.isNotEmpty()) {
                item { StatisticsSectionTitle("Top songs") }
                itemsIndexed(overview.topSongs, key = { _, item -> "song_${item.stableId}" }) { index, item ->
                    RankedSong(index + 1, item, onOpenTarget)
                }
            }

            if (overview.topArtists.isNotEmpty()) {
                item { MediaShelf("Top artists", overview.topArtists, circular = true, onOpenTarget) }
            }

            if (overview.topAlbums.isNotEmpty()) {
                item { MediaShelf("Top albums", overview.topAlbums, circular = false, onOpenTarget) }
            }
        }
    }
}

@Composable
private fun StatisticsHeader(
    selectedRange: AndroidStatisticsRange,
    onRangeSelected: (AndroidStatisticsRange) -> Unit,
    countPartyListening: Boolean,
    onCountPartyListeningChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Statistics", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Your listening, over time",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AndroidStatisticsRange.entries.forEach { range ->
                AssistChip(
                    onClick = { onRangeSelected(range) },
                    label = { Text(range.label, fontWeight = if (range == selectedRange) FontWeight.SemiBold else null) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Count party listening", fontWeight = FontWeight.SemiBold)
                Text(
                    "When off, time spent hosting or joining Play Together is excluded from these statistics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = countPartyListening, onCheckedChange = onCountPartyListeningChange)
        }
    }
}

@Composable
private fun StatisticsSummary(summary: AndroidStatisticsSummary) {
    val cards =
        remember(summary) {
            listOf(
                Triple("Listening time", formatListeningTime(summary.totalListeningMs), Icons.Rounded.Schedule),
                Triple("Plays", summary.totalPlays.toString(), Icons.Rounded.MusicNote),
                Triple("Songs", summary.uniqueSongs.toString(), Icons.Rounded.BarChart),
                Triple("Artists", summary.uniqueArtists.toString(), Icons.Rounded.Person),
                Triple("Albums", summary.uniqueAlbums.toString(), Icons.Rounded.Album),
            )
        }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(cards, key = { it.first }) { card ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(
                    Modifier.width(150.dp).height(104.dp).padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(card.third, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(card.second, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(card.first, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RankedSong(
    rank: Int,
    item: AndroidStatisticsMedia,
    onOpenTarget: (AndroidStatisticsTarget) -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .then(item.target?.let { Modifier.clickable { onOpenTarget(it) } } ?: Modifier),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(rank.toString(), modifier = Modifier.width(26.dp), fontWeight = FontWeight.Bold)
            StatisticsArtwork(item, circular = false, modifier = Modifier.size(50.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                item.subtitle?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                mediaStatsLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MediaShelf(
    title: String,
    items: List<AndroidStatisticsMedia>,
    circular: Boolean,
    onOpenTarget: (AndroidStatisticsTarget) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatisticsSectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { "$title-${it.stableId}" }) { item ->
                Column(
                    Modifier
                        .width(140.dp)
                        .then(item.target?.let { Modifier.clickable { onOpenTarget(it) } } ?: Modifier),
                ) {
                    StatisticsArtwork(item, circular = circular, modifier = Modifier.size(140.dp))
                    Spacer(Modifier.height(7.dp))
                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        mediaStatsLine(item),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatisticsArtwork(
    item: AndroidStatisticsMedia,
    circular: Boolean,
    modifier: Modifier,
) {
    val shape = if (circular) CircleShape else RoundedCornerShape(10.dp)
    if (!item.artworkUrl.isNullOrBlank()) {
        AsyncImage(
            model = item.artworkUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    } else {
        Box(
            modifier = modifier.clip(shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (circular) Icons.Rounded.Person else Icons.Rounded.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatisticsSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun StatisticsMessage(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.BarChart, contentDescription = null, modifier = Modifier.size(42.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun mediaStatsLine(item: AndroidStatisticsMedia): String =
    buildList {
        if (item.listeningMs > 0L) add(formatListeningTime(item.listeningMs))
        if (item.playCount > 0L) add("${item.playCount} ${if (item.playCount == 1L) "play" else "plays"}")
    }.joinToString(" · ")

private fun formatListeningTime(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0m"
    val totalMinutes = milliseconds / 60_000L
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes / 60L) % 24L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L && hours > 0L -> "${days}d ${hours}h"
        days > 0L -> "${days}d"
        totalMinutes >= 60L && minutes > 0L -> "${totalMinutes / 60L}h ${minutes}m"
        totalMinutes >= 60L -> "${totalMinutes / 60L}h"
        totalMinutes > 0L -> "${totalMinutes}m"
        else -> "<1m"
    }
}
