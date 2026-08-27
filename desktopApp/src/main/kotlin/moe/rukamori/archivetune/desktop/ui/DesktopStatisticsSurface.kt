/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.max

/**
 * Backend-agnostic input for the desktop statistics page.
 *
 * The statistics module can map a store snapshot/query result into this model without making
 * this Compose surface depend on its persistence/query API.
 */
data class DesktopStatisticsUiModel(
    val summary: DesktopStatisticsSummary = DesktopStatisticsSummary(),
    val favoriteArtist: DesktopStatisticsMedia? = null,
    val favoriteSong: DesktopStatisticsMedia? = null,
    val dailyListening: List<DesktopStatisticsBucket> = emptyList(),
    val listeningByDayOfWeek: List<DesktopStatisticsBucket> = emptyList(),
    val listeningByHour: List<DesktopStatisticsBucket> = emptyList(),
    val topSongs: List<DesktopStatisticsMedia> = emptyList(),
    val topArtists: List<DesktopStatisticsMedia> = emptyList(),
    val topAlbums: List<DesktopStatisticsMedia> = emptyList(),
) {
    val isEmpty: Boolean
        get() =
            summary.totalListeningMillis <= 0L &&
                summary.totalPlays <= 0L &&
                favoriteArtist == null &&
                favoriteSong == null &&
                dailyListening.none { it.listeningMillis > 0L } &&
                listeningByDayOfWeek.none { it.listeningMillis > 0L } &&
                listeningByHour.none { it.listeningMillis > 0L } &&
                topSongs.isEmpty() &&
                topArtists.isEmpty() &&
                topAlbums.isEmpty()
}

data class DesktopStatisticsSummary(
    val totalListeningMillis: Long = 0L,
    val totalPlays: Long = 0L,
    val uniqueSongs: Int = 0,
    val uniqueArtists: Int = 0,
    val uniqueAlbums: Int = 0,
)

data class DesktopStatisticsMedia(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val listeningMillis: Long = 0L,
    val playCount: Long = 0L,
)

data class DesktopStatisticsBucket(
    val label: String,
    val listeningMillis: Long,
)

enum class DesktopStatisticsRange(
    val label: String,
) {
    DAYS_7("7 days"),
    MONTH_1("1 month"),
    MONTHS_3("3 months"),
    MONTHS_6("6 months"),
    YEAR_1("1 year"),
    ALL_TIME("All time"),
}

@Composable
fun DesktopStatisticsSurface(
    model: DesktopStatisticsUiModel,
    selectedRange: DesktopStatisticsRange,
    onRangeSelected: (DesktopStatisticsRange) -> Unit,
    onPlaySong: (String) -> Unit,
    onOpenSong: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleTopSongs = remember(model.topSongs) { model.topSongs.take(20) }
    val visibleTopArtists = remember(model.topArtists) { model.topArtists.take(12) }
    val visibleTopAlbums = remember(model.topAlbums) { model.topAlbums.take(12) }

    if (model.isEmpty) {
        Column(modifier.fillMaxSize()) {
            StatisticsHeader(
                selectedRange = selectedRange,
                onRangeSelected = onRangeSelected,
            )
            StatisticsEmptyState(Modifier.fillMaxWidth().weight(1f))
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "statistics_header") {
            StatisticsHeader(
                selectedRange = selectedRange,
                onRangeSelected = onRangeSelected,
            )
        }

        item(key = "statistics_summary") {
            StatisticsSummarySection(model.summary)
        }

        if (model.favoriteArtist != null || model.favoriteSong != null) {
            item(key = "statistics_favorites") {
                StatisticsFavorites(
                    favoriteArtist = model.favoriteArtist,
                    favoriteSong = model.favoriteSong,
                    onOpenArtist = onOpenArtist,
                    onOpenSong = onOpenSong,
                    onPlaySong = onPlaySong,
                )
            }
        }

        if (model.dailyListening.isNotEmpty()) {
            item(key = "statistics_daily_trend") {
                StatisticsDailyTrend(model.dailyListening)
            }
        }

        if (model.listeningByDayOfWeek.isNotEmpty() || model.listeningByHour.isNotEmpty()) {
            item(key = "statistics_patterns") {
                StatisticsPatterns(
                    dayBuckets = model.listeningByDayOfWeek,
                    hourBuckets = model.listeningByHour,
                )
            }
        }

        if (visibleTopSongs.isNotEmpty()) {
            item(key = "statistics_top_songs_header") {
                DesktopSectionHeader(
                    title = "Top songs",
                    label = "Most listened",
                )
            }
            itemsIndexed(
                items = visibleTopSongs,
                key = { _, song -> "statistics_song_" + song.id },
                contentType = { _, _ -> "statistics_song" },
            ) { index, song ->
                StatisticsRankedSong(
                    song = song,
                    rank = index + 1,
                    onPlay = { onPlaySong(song.id) },
                    onOpen = { onOpenSong(song.id) },
                )
            }
        }

        if (visibleTopArtists.isNotEmpty()) {
            item(key = "statistics_top_artists") {
                StatisticsMediaShelf(
                    title = "Top artists",
                    label = "Your most played artists",
                    mediaItems = visibleTopArtists,
                    circularArtwork = true,
                    onOpen = onOpenArtist,
                )
            }
        }

        if (visibleTopAlbums.isNotEmpty()) {
            item(key = "statistics_top_albums") {
                StatisticsMediaShelf(
                    title = "Top albums",
                    label = "Your most played albums",
                    mediaItems = visibleTopAlbums,
                    circularArtwork = false,
                    onOpen = onOpenAlbum,
                )
            }
        }
    }
}

@Composable
private fun StatisticsHeader(
    selectedRange: DesktopStatisticsRange,
    onRangeSelected: (DesktopStatisticsRange) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = "Statistics",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Your listening, over time",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DesktopStatisticsRange.entries.forEach { range ->
                val selected = range == selectedRange
                AssistChip(
                    onClick = { onRangeSelected(range) },
                    label = {
                        Text(
                            text = range.label,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    leadingIcon =
                        if (selected) {
                            {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun StatisticsSummarySection(summary: DesktopStatisticsSummary) {
    val cards =
        remember(summary) {
            listOf(
                StatisticsMetric(
                    "Listening time",
                    formatListeningTime(summary.totalListeningMillis),
                    StatisticsMetricIcon.TIME,
                ),
                StatisticsMetric("Plays", formatCount(summary.totalPlays), StatisticsMetricIcon.PLAYS),
                StatisticsMetric("Unique songs", formatCount(summary.uniqueSongs.toLong()), StatisticsMetricIcon.SONGS),
                StatisticsMetric("Unique artists", formatCount(summary.uniqueArtists.toLong()), StatisticsMetricIcon.ARTISTS),
                StatisticsMetric("Unique albums", formatCount(summary.uniqueAlbums.toLong()), StatisticsMetricIcon.ALBUMS),
            )
        }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards, key = { it.label }) { metric ->
            StatisticsMetricCard(
                metric = metric,
                modifier = Modifier.width(if (metric.icon == StatisticsMetricIcon.TIME) 250.dp else 178.dp),
            )
        }
    }
}

@Composable
private fun StatisticsMetricCard(
    metric: StatisticsMetric,
    modifier: Modifier = Modifier,
) {
    val highlighted = metric.icon == StatisticsMetricIcon.TIME
    Surface(
        modifier = modifier.height(118.dp),
        shape = RoundedCornerShape(18.dp),
        color =
            if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector =
                    when (metric.icon) {
                        StatisticsMetricIcon.TIME -> Icons.Rounded.Schedule
                        StatisticsMetricIcon.PLAYS -> Icons.Rounded.PlayArrow
                        StatisticsMetricIcon.SONGS -> Icons.Rounded.MusicNote
                        StatisticsMetricIcon.ARTISTS -> Icons.Rounded.Person
                        StatisticsMetricIcon.ALBUMS -> Icons.Rounded.LibraryMusic
                    },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint =
                    if (highlighted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
            Column {
                Text(
                    text = metric.value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color =
                        if (highlighted) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metric.label,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (highlighted) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

@Composable
private fun StatisticsFavorites(
    favoriteArtist: DesktopStatisticsMedia?,
    favoriteSong: DesktopStatisticsMedia?,
    onOpenArtist: (String) -> Unit,
    onOpenSong: (String) -> Unit,
    onPlaySong: (String) -> Unit,
) {
    Column {
        DesktopSectionHeader(title = "Favorites", label = "Highlights for this range")
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            val expanded = maxWidth >= 720.dp && favoriteArtist != null && favoriteSong != null
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    favoriteArtist.let { artist ->
                        StatisticsFavoriteCard(
                            eyebrow = "Favorite artist",
                            media = artist,
                            circularArtwork = true,
                            onClick = { onOpenArtist(artist.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    favoriteSong.let { song ->
                        StatisticsFavoriteCard(
                            eyebrow = "Favorite song",
                            media = song,
                            circularArtwork = false,
                            onClick = { onOpenSong(song.id) },
                            onPlay = { onPlaySong(song.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    favoriteArtist?.let { artist ->
                        StatisticsFavoriteCard(
                            eyebrow = "Favorite artist",
                            media = artist,
                            circularArtwork = true,
                            onClick = { onOpenArtist(artist.id) },
                        )
                    }
                    favoriteSong?.let { song ->
                        StatisticsFavoriteCard(
                            eyebrow = "Favorite song",
                            media = song,
                            circularArtwork = false,
                            onClick = { onOpenSong(song.id) },
                            onPlay = { onPlaySong(song.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsFavoriteCard(
    eyebrow: String,
    media: DesktopStatisticsMedia,
    circularArtwork: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 112.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatisticsArtwork(
                media = media,
                circular = circularArtwork,
                modifier = Modifier.size(78.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                media.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = mediaStatsLine(media),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onPlay != null) {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play " + media.title)
                }
            }
        }
    }
}

@Composable
private fun StatisticsDailyTrend(buckets: List<DesktopStatisticsBucket>) {
    val visibleBuckets = remember(buckets) { buckets.takeLast(120) }
    val peak = remember(visibleBuckets) { visibleBuckets.maxOfOrNull { it.listeningMillis } ?: 0L }
    Column {
        DesktopSectionHeader(
            title = "Listening trend",
            label = if (peak > 0L) "Peak " + formatListeningTime(peak) else null,
        )
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ListeningTrendCanvas(
                    buckets = visibleBuckets,
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                )
                ChartAxisLabels(visibleBuckets)
            }
        }
    }
}

@Composable
private fun ListeningTrendCanvas(
    buckets: List<DesktopStatisticsBucket>,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val normalized =
        remember(buckets) {
            val maxValue = max(1L, buckets.maxOfOrNull { it.listeningMillis } ?: 1L)
            buckets.map { it.listeningMillis.toFloat() / maxValue.toFloat() }
        }

    Canvas(modifier) {
        if (normalized.isEmpty()) return@Canvas
        repeat(4) { index ->
            val y = size.height * index / 3f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        val horizontalStep = if (normalized.size <= 1) 0f else size.width / (normalized.size - 1)
        val path = Path()
        normalized.forEachIndexed { index, value ->
            val x = index * horizontalStep
            val y = size.height - value * (size.height * 0.88f) - size.height * 0.06f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = primary, style = Stroke(width = 3f))

        normalized.forEachIndexed { index, value ->
            if (normalized.size <= 31 || index == normalized.lastIndex || index % 7 == 0) {
                val x = index * horizontalStep
                val y = size.height - value * (size.height * 0.88f) - size.height * 0.06f
                drawCircle(primary, radius = 3.4f, center = Offset(x, y))
            }
        }
    }
}

@Composable
private fun ChartAxisLabels(buckets: List<DesktopStatisticsBucket>) {
    if (buckets.isEmpty()) return
    val labels =
        remember(buckets) {
            when {
                buckets.size == 1 -> listOf(buckets.first().label)
                buckets.size == 2 -> buckets.map { it.label }
                else -> listOf(buckets.first().label, buckets[buckets.lastIndex / 2].label, buckets.last().label)
            }
        }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatisticsPatterns(
    dayBuckets: List<DesktopStatisticsBucket>,
    hourBuckets: List<DesktopStatisticsBucket>,
) {
    Column {
        DesktopSectionHeader(title = "Listening patterns", label = "When you listen")
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            val showSideBySide = maxWidth >= 760.dp && dayBuckets.isNotEmpty() && hourBuckets.isNotEmpty()
            if (showSideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PatternCard(
                        title = "By day",
                        buckets = dayBuckets,
                        modifier = Modifier.weight(1f),
                    )
                    PatternCard(
                        title = "By hour",
                        buckets = hourBuckets,
                        modifier = Modifier.weight(1f),
                        compactLabels = true,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (dayBuckets.isNotEmpty()) {
                        PatternCard(title = "By day", buckets = dayBuckets)
                    }
                    if (hourBuckets.isNotEmpty()) {
                        PatternCard(title = "By hour", buckets = hourBuckets, compactLabels = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternCard(
    title: String,
    buckets: List<DesktopStatisticsBucket>,
    modifier: Modifier = Modifier,
    compactLabels: Boolean = false,
) {
    val maxValue = remember(buckets) { max(1L, buckets.maxOfOrNull { it.listeningMillis } ?: 1L) }
    val peakIndex = remember(buckets) { buckets.indices.maxByOrNull { buckets[it].listeningMillis } ?: -1 }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (peakIndex >= 0) {
                    Text(
                        text = "Peak · " + buckets[peakIndex].label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(126.dp),
                horizontalArrangement = Arrangement.spacedBy(if (buckets.size > 12) 3.dp else 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                buckets.forEachIndexed { index, bucket ->
                    val fraction = (bucket.listeningMillis.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
                    val highlighted = index == peakIndex
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 3.dp)
                                    .fillMaxHeight(fraction.coerceAtLeast(0.025f))
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (highlighted) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                    ),
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        if (!compactLabels || buckets.size <= 12 || index % 3 == 0 || index == buckets.lastIndex) {
                            Text(
                                text = bucket.label,
                                style = MaterialTheme.typography.labelSmall,
                                color =
                                    if (highlighted) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                        } else {
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsRankedSong(
    song: DesktopStatisticsMedia,
    rank: Int,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 2.dp)
                .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = rank.toString(),
                modifier = Modifier.width(28.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            StatisticsArtwork(song, circular = false, modifier = Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                song.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = mediaStatsLine(song),
                modifier = Modifier.widthIn(min = 126.dp, max = 190.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play " + song.title)
            }
        }
    }
}

@Composable
private fun StatisticsMediaShelf(
    title: String,
    label: String,
    mediaItems: List<DesktopStatisticsMedia>,
    circularArtwork: Boolean,
    onOpen: (String) -> Unit,
) {
    Column {
        DesktopSectionHeader(title = title, label = label)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(mediaItems, key = { title + "_" + it.id }) { media ->
                Column(
                    modifier = Modifier.width(156.dp).clickable { onOpen(media.id) },
                ) {
                    StatisticsArtwork(
                        media = media,
                        circular = circularArtwork,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = media.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = mediaStatsLine(media),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatisticsArtwork(
    media: DesktopStatisticsMedia,
    circular: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = if (circular) CircleShape else RoundedCornerShape(10.dp)
    if (!media.artworkUrl.isNullOrBlank()) {
        AsyncImage(
            model = media.artworkUrl,
            contentDescription = media.title,
            modifier = modifier.clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (circular) Icons.Rounded.Person else Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun StatisticsEmptyState(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.BarChart,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = "No listening statistics yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Play some music and your listening trends, favorites, and top music will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private data class StatisticsMetric(
    val label: String,
    val value: String,
    val icon: StatisticsMetricIcon,
)

private enum class StatisticsMetricIcon {
    TIME,
    PLAYS,
    SONGS,
    ARTISTS,
    ALBUMS,
}

private fun mediaStatsLine(media: DesktopStatisticsMedia): String {
    val parts =
        buildList {
            if (media.listeningMillis > 0L) add(formatListeningTime(media.listeningMillis))
            if (media.playCount > 0L) {
                add(formatCount(media.playCount) + " " + if (media.playCount == 1L) "play" else "plays")
            }
        }
    return parts.joinToString(" · ").ifBlank { "No listens in this range" }
}

private fun formatListeningTime(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0m"
    val totalMinutes = milliseconds / 60_000L
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes / 60L) % 24L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L && hours > 0L -> days.toString() + "d " + hours + "h"
        days > 0L -> days.toString() + "d"
        totalMinutes >= 60L && minutes > 0L -> (totalMinutes / 60L).toString() + "h " + minutes + "m"
        totalMinutes >= 60L -> (totalMinutes / 60L).toString() + "h"
        totalMinutes > 0L -> totalMinutes.toString() + "m"
        else -> "<1m"
    }
}

private fun formatCount(value: Long): String =
    when {
        value >= 1_000_000L -> {
            val whole = value / 1_000_000L
            val decimal = (value % 1_000_000L) / 100_000L
            if (decimal == 0L) whole.toString() + "M" else whole.toString() + "." + decimal + "M"
        }
        value >= 1_000L -> {
            val whole = value / 1_000L
            val decimal = (value % 1_000L) / 100L
            if (decimal == 0L) whole.toString() + "K" else whole.toString() + "." + decimal + "K"
        }
        else -> value.toString()
    }
