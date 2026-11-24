/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Exact Android Listening Stats surface backed by audible-time statistics. */
@Composable
internal fun AndroidStatisticsContent(
    controller: MusicController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library = controller.library?.collectAsState()?.value
    val settings = library?.settings ?: MusicSettings()
    val statsData = library?.statistics ?: MusicStatisticsStoreData()
    var selectedRange by rememberSaveable { mutableStateOf(MusicStatisticsRange.DAYS_7) }
    val overview = remember(statsData, selectedRange, settings.topLength) {
        MusicStatisticsQuery.overview(
            data = statsData,
            range = selectedRange,
            nowEpochMs = currentEpochMillis(),
            topLimit = settings.topLength,
        )
    }

    Column(modifier.fillMaxSize()) {
        RouteHeading("Statistics", onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Your listening, over time",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MusicStatisticsRange.entries.forEach { range ->
                            val selected = range == selectedRange
                            FilterChip(
                                selected = selected,
                                onClick = { selectedRange = range },
                                label = { Text(range.label, fontWeight = if (selected) FontWeight.SemiBold else null) },
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
                        Switch(
                            checked = settings.countPartyListening,
                            onCheckedChange = { enabled ->
                                controller.updateSettings { it.copy(countPartyListening = enabled) }
                            },
                        )
                    }
                }
            }

            if (overview.isEmpty) {
                item(key = "empty") {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        Column(
                            Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(painterResource(Res.drawable.stats), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("No listening statistics yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "Play some music and your audible listening time will appear here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                item(key = "summary") {
                    val cards: List<Triple<String, String, DrawableResource>> = listOf(
                        Triple("Listening time", MusicStatisticsQuery.formatListeningTime(overview.totalListeningMs), Res.drawable.timer),
                        Triple("Plays", overview.totalPlays.toString(), Res.drawable.music_note),
                        Triple("Songs", overview.uniqueSongs.toString(), Res.drawable.stats),
                        Triple("Artists", overview.uniqueArtists.toString(), Res.drawable.person),
                        Triple("Albums", overview.uniqueAlbums.toString(), Res.drawable.album),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(cards, key = { it.first }) { (label, value, icon) ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Column(
                                    Modifier.widthIn(min = 120.dp).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                if (overview.dailyTrend.size >= 2) {
                    item(key = "trend") {
                        StatsTrendCard("Listening trend", overview.dailyTrend)
                    }
                }

                if (overview.byDayOfWeek.isNotEmpty()) {
                    item(key = "weekday") {
                        StatsBarsCard("By day of week", overview.byDayOfWeek)
                    }
                }

                if (overview.byHour.isNotEmpty()) {
                    item(key = "hour") {
                        StatsBarsCard("By hour", overview.byHour.filter { it.second > 0L }.take(12))
                    }
                }

                if (overview.topSongs.isNotEmpty()) {
                    item {
                        Text(
                            "Top songs",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    itemsIndexed(overview.topSongs, key = { _, it -> "song_${it.id}" }) { index, item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val tracks = overview.topSongs.mapNotNull { it.track }
                                    val trackIndex = tracks.indexOfFirst { it.id == item.id }
                                    if (trackIndex >= 0) controller.playAt(trackIndex, tracks)
                                }
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(28.dp),
                            )
                            CatalogArtwork(item.artworkUrl, Modifier.size(50.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(MusicStatisticsQuery.formatListeningTime(item.listeningMs), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text("${item.playCount} plays", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (overview.topArtists.isNotEmpty()) {
                    item {
                        StatsMediaShelf("Top artists", overview.topArtists, circular = true) { item ->
                            if (item.id.startsWith("UC")) {
                                controller.openBrowse(MusicCatalogItem.Browse(item.id, item.title, item.subtitle, item.artworkUrl, MusicBrowseKind.ARTIST))
                            }
                        }
                    }
                }

                if (overview.topAlbums.isNotEmpty()) {
                    item {
                        StatsMediaShelf("Top albums", overview.topAlbums, circular = false) { item ->
                            if (item.id.startsWith("MPRE")) {
                                controller.openBrowse(MusicCatalogItem.Browse(item.id, item.title, item.subtitle, item.artworkUrl, MusicBrowseKind.ALBUM))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsTrendCard(title: String, points: List<Pair<String, Long>>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val maxVal = points.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                val path = Path()
                val stepX = if (points.size > 1) size.width / (points.size - 1) else size.width
                points.forEachIndexed { i, (_, value) ->
                    val x = i * stepX
                    val y = size.height - (value.toFloat() / maxVal.toFloat()) * (size.height - 16f) - 8f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x, y))
                }
                drawPath(path, color = lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun StatsBarsCard(title: String, buckets: List<Pair<String, Long>>) {
    if (buckets.isEmpty()) return
    val maxVal = buckets.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            buckets.forEach { (label, ms) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
                    Canvas(Modifier.weight(1f).height(10.dp)) {
                        drawRoundRect(trackColor, cornerRadius = CornerRadius(5.dp.toPx()))
                        val frac = (ms.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
                        if (frac > 0f) {
                            drawRoundRect(barColor, size = Size(size.width * frac, size.height), cornerRadius = CornerRadius(5.dp.toPx()))
                        }
                    }
                    Text(
                        MusicStatisticsQuery.formatListeningTime(ms),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsMediaShelf(
    title: String,
    items: List<MusicRankedItem>,
    circular: Boolean,
    onClick: (MusicRankedItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                Column(
                    Modifier.width(124.dp).clickable { onClick(item) },
                    horizontalAlignment = if (circular) Alignment.CenterHorizontally else Alignment.Start,
                ) {
                    CatalogArtwork(item.artworkUrl, Modifier.size(124.dp), circular = circular)
                    Spacer(Modifier.height(6.dp))
                    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        MusicStatisticsQuery.formatListeningTime(item.listeningMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
