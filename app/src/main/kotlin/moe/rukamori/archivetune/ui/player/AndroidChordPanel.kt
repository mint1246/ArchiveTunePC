/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import moe.rukamori.archivetune.chords.ChordEvent
import moe.rukamori.archivetune.chords.ChordTrack
import moe.rukamori.archivetune.chords.ChordTrackSource
import kotlin.math.roundToInt

@Composable
internal fun AndroidChordPanel(
    track: ChordTrack,
    positionMs: Long,
    pitchMultiplier: Float,
    modifier: Modifier = Modifier,
    seekEnabled: Boolean = true,
    onSeek: (Long) -> Unit,
) {
    val trackKey = remember(track) { "${track.source}:${track.songsterrSongId}:${track.artist}:${track.title}" }
    var manualTranspose by remember(trackKey) { mutableIntStateOf(0) }
    val pitchTranspose = remember(pitchMultiplier) { chordTransposeForPitchMultiplier(pitchMultiplier) }
    val transpose = pitchTranspose + manualTranspose
    val activeIndex = remember(positionMs, track.events) { activeChordIndexAt(positionMs, track.events) }
    val active = track.events.getOrNull(activeIndex)
    val upcoming = remember(activeIndex, track.events) {
        track.events.drop((activeIndex + 1).coerceAtLeast(0)).take(3)
    }
    val progress = active?.let {
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
        modifier = modifier.fillMaxHeight(),
        color = Color.Black.copy(alpha = 0.16f),
        contentColor = Color.White,
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "CHORDS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = Color.White.copy(alpha = 0.76f),
                    )
                    Text(
                        "${track.artist} · ${track.title}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.45f),
                    )
                }
                Surface(color = Color.White.copy(alpha = 0.07f), shape = RoundedCornerShape(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { manualTranspose = (manualTranspose - 1).coerceAtLeast(-11) }) {
                            Text("−", color = Color.White)
                        }
                        Text(
                            if (transpose > 0) "+$transpose" else transpose.toString(),
                            modifier = Modifier.width(32.dp),
                            color = Color.White.copy(alpha = 0.82f),
                            fontWeight = FontWeight.Bold,
                        )
                        TextButton(onClick = { manualTranspose = (manualTranspose + 1).coerceAtMost(11) }) {
                            Text("+", color = Color.White)
                        }
                    }
                }
            }

            Text(
                active?.section?.uppercase().orEmpty().ifBlank { "NOW" },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.48f),
                letterSpacing = 1.1.sp,
            )
            AnimatedContent(
                targetState = active?.name ?: upcoming.firstOrNull()?.name ?: "—",
                transitionSpec = {
                    (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(160))) togetherWith
                        (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(130)))
                },
                label = "android-current-chord",
            ) { chord ->
                Text(
                    transposeChordName(chord, transpose),
                    fontSize = 46.sp,
                    lineHeight = 50.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.10f), CircleShape)) {
                if (progress > 0f) {
                    Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Color.White.copy(alpha = 0.76f), CircleShape))
                }
            }
            if (upcoming.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("next", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.42f))
                    upcoming.forEachIndexed { index, chord ->
                        Surface(
                            color = Color.White.copy(alpha = if (index == 0) 0.11f else 0.055f),
                            shape = RoundedCornerShape(11.dp),
                        ) {
                            Text(
                                transposeChordName(chord.name, transpose),
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = if (index == 0) 0.86f else 0.55f),
                            )
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(track.events, key = { index, event -> "${event.startMs}:$index" }) { index, event ->
                    val previousSection = track.events.getOrNull(index - 1)?.section
                    if (!event.section.isNullOrBlank() && event.section != previousSection) {
                        Text(
                            event.section.uppercase(),
                            modifier = Modifier.padding(start = 10.dp, top = if (index == 0) 0.dp else 10.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp,
                            color = Color.White.copy(alpha = 0.38f),
                        )
                    }
                    ChordTimelineRow(
                        event = event,
                        name = transposeChordName(event.name, transpose),
                        active = index == activeIndex,
                        enabled = seekEnabled,
                        onClick = { onSeek(event.startMs) },
                    )
                }
            }
            Text(
                when (track.source) {
                    ChordTrackSource.SONGSTERR_AUTHORED -> "Songsterr · authored chords"
                    ChordTrackSource.LOCAL_NEURAL -> "ArchiveTune · local neural analysis"
                    ChordTrackSource.SONGSTERR_DERIVED_FROM_TAB -> "Songsterr · derived from tab"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.34f),
            )
        }
    }
}

@Composable
private fun ChordTimelineRow(
    event: ChordEvent,
    name: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) Color.White.copy(alpha = 0.11f) else Color.Transparent, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (active) 9.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (active) 7.dp else 5.dp)
                .background(Color.White.copy(alpha = if (active) 0.88f else 0.20f), CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            modifier = Modifier.weight(1f),
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = Color.White.copy(alpha = if (active) 0.94f else 0.55f),
            maxLines = 1,
        )
        Text(formatChordTime(event.startMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.34f))
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
    return "$root/${transposeChordPart(parts[1], semitones)}"
}

internal fun chordTransposeForPitchMultiplier(pitchMultiplier: Float): Int {
    if (!pitchMultiplier.isFinite() || pitchMultiplier <= 0f) return 0
    return (12f * kotlin.math.log2(pitchMultiplier)).roundToInt().coerceIn(-12, 12)
}

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
    val names = if (accidental == "b") FLAT_NAMES else SHARP_NAMES
    val moved = ((pitch + semitones) % 12 + 12) % 12
    return names[moved] + part.substring(rootLength)
}

private fun formatChordTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

private val NOTE_TO_PC = mapOf(
    "C" to 0, "C#" to 1, "Db" to 1, "D" to 2, "D#" to 3, "Eb" to 3,
    "E" to 4, "F" to 5, "F#" to 6, "Gb" to 6, "G" to 7, "G#" to 8,
    "Ab" to 8, "A" to 9, "A#" to 10, "Bb" to 10, "B" to 11,
)
private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val FLAT_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
