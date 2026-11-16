/* ArchiveTune (2026) — GPL-3.0 License | Contributors: see git history */
package moe.rukamori.archivetune.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.rukamori.archivetune.shared.resources.Res
import moe.rukamori.archivetune.shared.resources.sfprodisplaybold
import org.jetbrains.compose.resources.Font
import kotlin.math.abs

/** Android V2 lyrics scene. Timings are always supplied by the lyrics provider. */
@Composable
fun AndroidTimedLyrics(
    lines: List<LyricsLine>, positionMs: Long, isPlaying: Boolean,
    onSeek: (Long) -> Unit, modifier: Modifier = Modifier,
) {
    val synced = remember(lines) { lines.any { it.startTimeMs != null } }
    val state = rememberLazyListState()
    var visualPosition by remember(lines) { mutableLongStateOf(positionMs) }
    val latestPosition by rememberUpdatedState(positionMs)
    LaunchedEffect(lines, positionMs, isPlaying) {
        visualPosition = positionMs
        if (isPlaying && synced) {
            var origin = 0L
            while (isActive) withFrameNanos { now ->
                if (origin == 0L) origin = now
                // A stalled transport sample must not run indefinitely ahead of playback.
                visualPosition = latestPosition + ((now - origin) / 1_000_000L).coerceIn(0L, 500L)
            }
        }
    }
    val active = remember(lines, visualPosition, synced) {
        if (!synced) -1 else lines.indexOfLast { (it.startTimeMs ?: Long.MAX_VALUE) <= visualPosition }
    }
    var manual by remember(lines) { mutableStateOf(false) }
    var manualGeneration by remember(lines) { mutableIntStateOf(0) }
    val nested = remember(lines) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) { manual = true; manualGeneration++ }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(manual, manualGeneration) { if (manual) { delay(3000); manual = false } }
    var anchor by remember(lines) { mutableIntStateOf(-1) }
    LaunchedEffect(active, manual, lines) {
        if (!synced || manual || active < 0) return@LaunchedEffect
        if (anchor < 0 || active < anchor || active - anchor >= 2 || lines[active].text.isBlank()) anchor = active
        val target = anchor + 1 // leading spacer
        var velocity = 0f
        var previous = 0L
        var settled = 0
        while (isActive) {
            val delta = withFrameNanos { now ->
                val info = state.layoutInfo
                val visible = info.visibleItemsInfo
                if (visible.isEmpty()) return@withFrameNanos 0f
                val item = visible.minBy { abs(it.index - target) }
                val extent = visible.map { it.size.toFloat() }.average().toFloat().coerceAtLeast(1f)
                val error = item.offset + item.size / 2f + (target - item.index) * extent - info.viewportSize.height * .45f
                val dt = if (previous == 0L) 1f / 60f else ((now - previous) / 1e9f).coerceIn(0f, .05f)
                previous = now
                velocity += (error * 80f - velocity * 18f) * dt
                if (abs(error) < .5f && abs(velocity) < 1f) settled++ else settled = 0
                velocity * dt
            }
            if (abs(delta) > .01f) state.scrollBy(delta)
            if (settled >= 2) break
        }
    }
    val font = FontFamily(Font(Res.font.sfprodisplaybold))
    val color = MaterialTheme.colorScheme.onBackground
    Box(modifier.fillMaxSize().padding(bottom = 12.dp)) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().nestedScroll(nested), horizontalAlignment = Alignment.CenterHorizontally) {
            if (synced) item { Spacer(Modifier.height(120.dp)) }
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                val distance = if (synced && active >= 0) abs(index - active) else 0
                val alpha by animateFloatAsState(if (!synced) .92f else (1f / (1f + distance * .8f)).coerceIn(.25f, 1f), spring(dampingRatio = .92f, stiffness = 180f), label = "lyric alpha")
                val scale by animateFloatAsState((1.05f - distance * .06f).coerceIn(.97f, 1.05f), spring(dampingRatio = .88f, stiffness = 180f), label = "lyric scale")
                val blur by animateFloatAsState(if (!synced || manual || distance == 0) 0f else (1.15f * distance).coerceAtMost(3.5f), spring(dampingRatio = 1f, stiffness = 220f), label = "lyric blur")
                if (synced && line.text.isBlank()) {
                    if (index == active) Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                        repeat(3) { i -> drawCircle(color.copy(alpha = .85f), 7.5.dp.toPx(), Offset(size.width / 2 + (i - 1) * 24.dp.toPx(), size.height / 2)) }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (index == 0) 0.dp else 10.4.dp, bottom = 10.4.dp)
                        .blur(blur.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                        .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
                        .clickable(enabled = line.startTimeMs != null) { line.startTimeMs?.let(onSeek) }) {
                        TimedParagraph(line, visualPosition, font, color, synced)
                    }
                }
            }
            if (synced) item { Spacer(Modifier.height(300.dp)) }
        }
        if (manual && synced) FilledTonalButton(onClick = { manual = false }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) { Text("Resume") }
    }
}

private data class TimedRange(val start: Int, val end: Int, val word: LyricsWord)

@Composable
private fun TimedParagraph(line: LyricsLine, position: Long, font: FontFamily, color: Color, synced: Boolean) {
    val ranges = remember(line) {
        var cursor = 0
        line.words.mapNotNull { word ->
            if (word.background || word.text.isEmpty()) return@mapNotNull null
            val start = line.text.indexOf(word.text, cursor)
            if (start < 0) null else { cursor = start + word.text.length; TimedRange(start, cursor, word) }
        }
    }
    var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
    val style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold,
        lineHeight = (26 * if (ranges.isEmpty()) 1.3f else 1.35f).sp, fontFamily = font,
        letterSpacing = 0.sp, textAlign = TextAlign.Center, textDirection = TextDirection.Content)
    val textModifier = Modifier.fillMaxWidth().padding(horizontal = if (ranges.isEmpty()) 0.dp else 24.dp)
    Box(Modifier.fillMaxWidth()) {
        Text(line.text, modifier = textModifier, style = style, color = color.copy(alpha = if (synced && ranges.isNotEmpty()) .38f else 1f), onTextLayout = { layout = it })
        if (ranges.isNotEmpty()) Text(line.text, style = style, color = color, modifier = textModifier.drawWithContent {
            val measured = layout ?: return@drawWithContent
            ranges.forEach { range ->
                val duration = (range.word.endTimeMs - range.word.startTimeMs).coerceAtLeast(1)
                val progress = ((position - range.word.startTimeMs).toFloat() / duration).coerceIn(0f, 1f)
                if (progress > 0f) {
                    // Clip each glyph separately so wrapping and bidirectional text retain shaping.
                    for (i in range.start until range.end.coerceAtMost(line.text.length)) {
                        val box = measured.getBoundingBox(i)
                        val local = (progress * (range.end - range.start) - (i - range.start)).coerceIn(0f, 1f)
                        if (local > 0f) clipRect(box.left, box.top, box.left + box.width * local, box.bottom) { this@drawWithContent.drawContent() }
                    }
                }
            }
        })
    }
}
