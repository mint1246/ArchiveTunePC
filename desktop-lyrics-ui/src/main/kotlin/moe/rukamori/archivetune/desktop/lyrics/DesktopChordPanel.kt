/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.lyrics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt
import moe.rukamori.archivetune.chords.ChordEvent
import moe.rukamori.archivetune.chords.ChordTrack
import moe.rukamori.archivetune.chords.ChordTrackSource

@Composable
internal fun DesktopChordPanel(
    track: ChordTrack,
    positionMsState: StateFlow<Long>,
    pitchSemitones: Float = 0f,
    modifier: Modifier = Modifier,
    seekEnabled: Boolean = true,
    onSeek: (Long) -> Unit,
) {
    val positionMs by positionMsState.collectAsState()
    val trackKey = remember(track) {
        "${track.source}:${track.songsterrSongId}:${track.artist}:${track.title}"
    }
    var manualTranspose by remember(trackKey) { mutableIntStateOf(0) }
    val pitchTranspose = chordTransposeForPitch(pitchSemitones)
    val transpose = pitchTranspose + manualTranspose
    val activeIndex = remember(positionMs, track.events) { activeChordIndexAt(positionMs, track.events) }
    val active = track.events.getOrNull(activeIndex)
    val upcoming =
        remember(activeIndex, track.events) {
            val start = (activeIndex + 1).coerceAtLeast(0)
            track.events.drop(start).take(3)
        }
    val progress =
        active?.let {
            if (it.endMs <= it.startMs) 0f
            else ((positionMs - it.startMs).toFloat() / (it.endMs - it.startMs).toFloat()).coerceIn(0f, 1f)
        } ?: 0f
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex, trackKey) {
        if (activeIndex >= 0 && track.events.isNotEmpty()) {
            listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
        }
    }

    Surface(
        modifier = modifier.fillMaxHeight().padding(start = 22.dp, end = 8.dp, bottom = 18.dp),
        color = Color.Black.copy(alpha = 0.12f),
        contentColor = Color.White,
        shape = RoundedCornerShape(30.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChordHeader(
                track,
                transpose,
                onTranspose = { manualTranspose = (manualTranspose + it).coerceIn(-11, 11) },
            )

            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    active?.section?.uppercase().orEmpty().ifBlank { "NOW" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.48f),
                    letterSpacing = 1.2.sp,
                )
                AnimatedContent(
                    targetState = active?.name ?: upcoming.firstOrNull()?.name ?: "—",
                    transitionSpec = {
                        (slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(180))) togetherWith
                            (slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(tween(150)))
                    },
                    label = "current-chord",
                ) { chord ->
                    Text(
                        text = transposeChordName(chord, transpose),
                        fontSize = 54.sp,
                        lineHeight = 58.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White.copy(alpha = if (active == null) 0.52f else 0.96f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.fillMaxWidth().height(3.dp)
                        .background(Color.White.copy(alpha = 0.10f), CircleShape),
                ) {
                    if (progress > 0f) {
                        Box(
                            Modifier.fillMaxWidth(progress).height(3.dp)
                                .background(Color.White.copy(alpha = 0.78f), CircleShape),
                        )
                    }
                }
                if (upcoming.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("next", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.42f))
                        upcoming.forEachIndexed { index, chord ->
                            Surface(
                                color = Color.White.copy(alpha = if (index == 0) 0.10f else 0.055f),
                                contentColor = Color.White.copy(alpha = if (index == 0) 0.82f else 0.52f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    transposeChordName(chord.name, transpose),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            Box(
                Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)),
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(track.events, key = { index, event -> "${event.startMs}:$index" }) { index, event ->
                    val previousSection = track.events.getOrNull(index - 1)?.section
                    val section = event.section
                    if (!section.isNullOrBlank() && section != previousSection) {
                        Text(
                            section.uppercase(),
                            modifier = Modifier.padding(start = 10.dp, top = if (index == 0) 0.dp else 12.dp, bottom = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.1.sp,
                            color = Color.White.copy(alpha = 0.38f),
                        )
                    }
                    ChordTimelineRow(
                        event = event,
                        displayName = transposeChordName(event.name, transpose),
                        active = index == activeIndex,
                        enabled = seekEnabled,
                        onClick = { onSeek(event.startMs) },
                    )
                }
            }

            val trackName = track.guitarTrackName?.substringAfterLast('|')?.trim().orEmpty()
            Text(
                buildString {
                    append(
                        when (track.source) {
                            ChordTrackSource.SONGSTERR_AUTHORED -> "Songsterr · authored chords"
                            ChordTrackSource.LOCAL_NEURAL -> "ArchiveTune · local neural analysis"
                            ChordTrackSource.SONGSTERR_DERIVED_FROM_TAB -> "Songsterr · derived from tab"
                        },
                    )
                    if (trackName.isNotBlank()) append(" · ").append(trackName)
                    if (track.timingScale != 1.0) append(" · synced to recording")
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.34f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChordHeader(track: ChordTrack, transpose: Int, onTranspose: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "CHORDS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = Color.White.copy(alpha = 0.74f),
            )
            Text(
                "${track.artist} · ${track.title}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.38f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            color = Color.White.copy(alpha = 0.06f),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onTranspose(-1) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.size(width = 34.dp, height = 32.dp),
                ) { Text("−", color = Color.White.copy(alpha = 0.72f), fontSize = 18.sp) }
                Text(
                    if (transpose == 0) "0" else if (transpose > 0) "+$transpose" else transpose.toString(),
                    modifier = Modifier.width(34.dp),
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    onClick = { onTranspose(1) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.size(width = 34.dp, height = 32.dp),
                ) { Text("+", color = Color.White.copy(alpha = 0.72f), fontSize = 18.sp) }
            }
        }
    }
}

@Composable
private fun ChordTimelineRow(
    event: ChordEvent,
    displayName: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = if (active) Color.White.copy(alpha = 0.105f) else Color.Transparent
    Row(
        Modifier.fillMaxWidth()
            .background(background, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = if (active) 9.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (active) 7.dp else 5.dp)
                .background(Color.White.copy(alpha = if (active) 0.86f else 0.18f), CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            displayName,
            modifier = Modifier.weight(1f),
            style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = Color.White.copy(alpha = if (active) 0.92f else 0.50f),
            maxLines = 1,
        )
        Text(
            formatChordTime(event.startMs),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = if (active) 0.48f else 0.24f),
        )
    }
}

internal fun activeChordIndexAt(positionMs: Long, events: List<ChordEvent>): Int {
    if (events.isEmpty() || positionMs < events.first().startMs) return -1
    var low = 0
    var high = events.lastIndex
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (events[mid].startMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

internal fun transposeChordName(chord: String, semitones: Int): String {
    if (semitones % 12 == 0 || chord == "—") return chord
    val parts = chord.split('/', limit = 2)
    val root = transposeChordPart(parts[0], semitones)
    if (parts.size == 1) return root
    val bass = transposeChordPart(parts[1], semitones)
    return "$root/$bass"
}

internal fun chordTransposeForPitch(pitchSemitones: Float): Int =
    pitchSemitones.takeIf(Float::isFinite)?.roundToInt()?.coerceIn(-12, 12) ?: 0

private fun transposeChordPart(part: String, semitones: Int): String {
    if (part.isEmpty()) return part
    val letter = part[0].uppercaseChar()
    if (letter !in 'A'..'G') return part
    var rootLength = 1
    var accidental = ""
    if (part.length > 1 && (part[1] == '#' || part[1] == 'b')) {
        accidental = part[1].toString()
        rootLength = 2
    }
    val pitch = NOTE_TO_PC["$letter$accidental"] ?: return part
    val useFlats = accidental == "b"
    val names = if (useFlats) FLAT_NAMES else SHARP_NAMES
    val moved = ((pitch + semitones) % 12 + 12) % 12
    return names[moved] + part.substring(rootLength)
}

private fun formatChordTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

private val NOTE_TO_PC =
    mapOf(
        "C" to 0, "C#" to 1, "Db" to 1,
        "D" to 2, "D#" to 3, "Eb" to 3,
        "E" to 4,
        "F" to 5, "F#" to 6, "Gb" to 6,
        "G" to 7, "G#" to 8, "Ab" to 8,
        "A" to 9, "A#" to 10, "Bb" to 10,
        "B" to 11,
    )

private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val FLAT_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
