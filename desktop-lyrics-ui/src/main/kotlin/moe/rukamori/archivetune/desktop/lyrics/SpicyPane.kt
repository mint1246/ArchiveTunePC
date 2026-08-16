/*
 * Lyrics surface — a faithful Compose port of SpicyLyrics' inline word animation.
 *
 * The old implementation animated per-character Text composables (one per glyph),
 * each carrying its own graphicsLayer, ColorFilter tint, and per-frame spring loop.
 * On the desktop the letter-by-letter bounce and sweep were laggy: every letter was
 * its own layer, the whole line recomposed per frame, and the glow was a second
 * blurred copy of the entire line.
 *
 * This rewrite draws the active line as ONE `TextLayoutResult` painted onto a single
 * `Canvas`. Everything per frame happens in the draw pass:
 *
 *   - each timed word's glyph paths are clipped out of the "dim" base text,
 *   - the spoken word is drawn letter-by-letter with per-letter spring scale,
 *     per-letter Y bounce, and a sweep gradient boundary that travels through it,
 *   - a soft glow is drawn once from the same glyph paths (Skia mask blur),
 *   - line depth/blur is applied per line via a single graphicsLayer.
 *
 * No per-character composables, no per-frame recomposition, no allocation in the
 * frame loop. The frame clock only touches state read inside draw lambdas.
 */
package moe.rukamori.archivetune.desktop.lyrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.lyrics.anim.Spring
import moe.rukamori.archivetune.desktop.lyrics.anim.VibrantLyricsBackground

/* ---------------------------------------------------------------------------------- */
/*  Typography / layout constants                                                      */
/* ---------------------------------------------------------------------------------- */

/** Exact SpicyLyrics font (LyricsBold.woff2 -> TTF); fall back to Poppins Bold. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val lyricsFontFamily: FontFamily = run {
    val candidates = listOf(
        "font/lyrics_bold.ttf",
        "font/lyrics_semibold.ttf",
        "font/poppins_bold.ttf",
    )
    for (path in candidates) {
        val stream = ClassLoader.getSystemClassLoader().getResourceAsStream(path) ?: continue
        val family = runCatching {
            stream.use {
                val awtFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, it)
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(awtFont)
                FontFamily(awtFont.family)
            }
        }.getOrNull()
        if (family != null) return@run family
    }
    FontFamily.SansSerif
}

private val LYRIC_FONT_SIZE = 48.sp
private val LYRIC_LINE_HEIGHT = 56.sp
private val LINE_GAP = 8.dp
private val HORIZONTAL_PADDING = 36.dp
/** Focal region sits in the upper-middle (~45%), leaving more upcoming lyrics below. */
private const val FOCAL_RATIO = 0.45f
/** Scene anchor spring: near-critical damping for a smooth, coherent slide. */
private const val ANCHOR_FREQ = 1.0
private const val ANCHOR_DAMPING = 0.9

/** Completed portion of the focal word: sharp near-white. */
private val BrightColor = Color(0xFFFFFFFF)
/** Upcoming/base portion of the focal word: dim but legible. */
private val DimColor = Color(0x99FFFFFF)

/** Progressive depth falloff as a function of continuous signed distance from the focal line. */
private fun alphaFor(distance: Float): Float = (1f / (1f + distance * 0.8f)).coerceIn(0.25f, 1f)

/** Restrained emphasis: near-identical sizes, sharpness/opacity do the heavy lifting. */
private fun scaleFor(distance: Float): Float = (1.05f - distance * 0.06f).coerceIn(0.97f, 1.05f)

/** Subtle spatial blur, quantised to whole-line distance so it is not re-applied per frame. */
private fun blurPxFor(dist: Int): Float = (1.15f * dist).coerceIn(0f, 3.5f)

/** SpicyLyrics' spring tuning for glyph scale (frequency Hz, damping ratio). */
private const val SCALE_FREQ = 0.88
private const val SCALE_DAMPING = 0.64
private const val Y_OFFSET_FREQ = 1.45
private const val Y_OFFSET_DAMPING = 0.40
private const val GLOW_FREQ = 1.18
private const val GLOW_DAMPING = 0.56

/** Soft bright band that trails the sweep boundary through the spoken word (px). */
private const val SWEEP_FEATHER_PX = 20f

/** Max per-letter glow alpha (SpicyLyrics' --text-shadow-opacity tops out near 1.0). */
private const val LETTER_GLOW_ALPHA = 0.85f

/** One glyph of a timed word, addressed by its char offset into `CharLine.text`. */
internal data class Letter(
    val ch: String,
    val charOffset: Int,
    val startMs: Long,
    val endMs: Long,
)

/** Per-property spring group for a letter. */
internal class LetterSprings(scale: Double, yOffset: Double, glow: Double) {
    val scale: Spring = Spring(scale, SCALE_FREQ, SCALE_DAMPING)
    val yOffset: Spring = Spring(yOffset, Y_OFFSET_FREQ, Y_OFFSET_DAMPING)
    val glow: Spring = Spring(glow, GLOW_FREQ, GLOW_DAMPING)
}

/** Smooth C1-continuous bell (0 → 1 → 0) peaking at progress 0.7, easing the corners. */
private fun bell(p: Float): Float {
    val k = if (p < 0.7f) p / 0.7f else (1f - p) / 0.3f
    return 0.5f * (1f - cos(PI * k.coerceIn(0f, 1f)).toFloat())
}

/** Letter "pop" as playback passes through a glyph (mirrors SpicyLyrics' LetterScaleRange). */
private fun letterScaleCurve(p: Float): Float = 0.95f + 0.225f * bell(p)

/** Letter Y bounce (fractions of font size; SpicyLyrics LetterYOffsetRange). */
private fun letterYOffsetCurve(p: Float): Float = 0.02f - 0.035f * bell(p)

/** Glow intensity through a word's life (SpicyLyrics GlowRange). */
private fun glowCurve(p: Float): Float {
    val k = when {
        p < 0.15f -> p / 0.15f
        p < 0.6f -> 1f
        else -> (1f - p) / 0.4f
    }
    return 0.5f * (1f - cos(PI * k.coerceIn(0f, 1f)).toFloat())
}

private fun progressOf(positionMs: Long, startMs: Long, endMs: Long): Float =
    ((positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)

/**
 * Split a timed word into grapheme clusters (a base code point plus any combining
 * marks / variation selectors / ZWJ sequences) with evenly divided timing.
 *
 * Clusters — not raw UTF-16 code units — are what the text engine maps to glyphs.
 * Splitting on code units alone orphans Hebrew niqqud marks and splits surrogate
 * pairs, producing broken, shuffled glyph bands. Keeping each cluster whole also
 * keeps the animated bands indexed by the same offsets the BiDi layout uses.
 */
internal fun lettersOf(line: CharLine, word: WordSpan): List<Letter> {
    val text = line.text
    val ranges = clusterRanges(text, word.startChar, word.endChar)
    val count = ranges.size.coerceAtLeast(1)
    val duration = (word.endMs - word.startMs).coerceAtLeast(1L)
    return ranges.mapIndexed { i, range ->
        Letter(
            ch = text.substring(range.first, range.last + 1),
            charOffset = range.first,
            startMs = word.startMs + duration * i / count,
            endMs = word.startMs + duration * (i + 1) / count,
        )
    }
}

/** Cluster-extend rule: a code point continues the current cluster if it is a Unicode
 * combining mark, a variation selector, or glued on by a zero-width joiner. */
private fun continuesCluster(text: String, i: Int): Boolean {
    val cp = text.codePointAt(i)
    return when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
    }
}

/** Grapheme-cluster boundaries over the UTF-16 sub-range [start, end). */
private fun clusterRanges(text: String, start: Int, end: Int): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var i = start
    while (i < end) {
        val clusterStart = i
        i += Character.charCount(text.codePointAt(i))
        while (i < end && continuesCluster(text, i)) {
            i += Character.charCount(text.codePointAt(i))
        }
        ranges += clusterStart until i
    }
    return ranges
}

/**
 * Precomputed glyph geometry for one whole line, derived entirely from the same single
 * `TextLayoutResult` that paints the dim base text.
 *
 * The old approach measured each letter as its own sub-layout and stitched those
 * pieces onto the line using `getHorizontalPosition`. Standalone letter measurements
 * lose kerning/ligatures, re-shape differently in context, and have different advance
 * widths than the run — for RTL runs (Hebrew) the off-by-context advances made the
 * active word look scrambled and overlapping. It also meant the dim base and the
 * highlighted letters came from two different shaping passes.
 *
 * Now every timed word keeps the glyph boxes the BiDi/shaping engine itself produced
 * (`getBoundingBox`), so the animated word is guaranteed to sit on top of the dim
 * line it replaces with identical glyphs, spacing and ordering.
 */
internal class LineTextGeometry(
    val layout: TextLayoutResult,
    /** word index -> per-cluster glyph box + timing. */
    val wordLetters: List<List<LetterText>>,
    /** word index -> visual left edge of the word in line-local X. */
    val wordLeft: List<Float>,
    /** word index -> visual right edge of the word in line-local X. */
    val wordRight: List<Float>,
    /** word index -> true when the word's run reads right-to-left (Hebrew, Arabic, …). */
    val wordRtl: List<Boolean>,
) {
    companion object {
        fun build(layout: TextLayoutResult, line: CharLine): LineTextGeometry {
            val wordLetters = line.words.map { word ->
                lettersOf(line, word).map { letter ->
                    val box = layout.getBoundingBox(letter.charOffset)
                    LetterText(
                        letter = letter,
                        box = box,
                        x = box.left,
                        width = box.width,
                    )
                }
            }
            val wordLeft = line.words.mapIndexed { wi, _ ->
                val g = wordLetters[wi]
                if (g.isEmpty()) 0f else g.minOf { it.box.left }
            }
            val wordRight = line.words.mapIndexed { wi, _ ->
                val g = wordLetters[wi]
                if (g.isEmpty()) 0f else g.maxOf { it.box.right }
            }
            val wordRtl = line.words.mapIndexed { wi, _ ->
                val g = wordLetters[wi]
                if (g.size < 2) false
                else {
                    // Geometric direction, per word: in an RTL run the first logical
                    // cluster sits to the RIGHT of the last one. This is measured from
                    // the actual layout, so mixed-direction words keep their own
                    // reading order and are never reversed by locale alone.
                    g.first().box.left > g.last().box.right
                }
            }
            return LineTextGeometry(layout, wordLetters, wordLeft, wordRight, wordRtl)
        }
    }
}

/** One grapheme cluster of a timed word: its glyph box from the full-line layout. */
internal data class LetterText(
    val letter: Letter,
    /** Glyph cluster box in line-local coordinates (living in `geometry.layout`). */
    val box: Rect,
    /** Line-local X of the cluster's left edge. */
    val x: Float,
    val width: Float,
)

/**
 * Position of a cluster's centre inside its word, measured from the visual edge the
 * inline sweep starts at — the word's reading-start side (left for LTR, right for RTL).
 * Rising sweep position means "later in the visual sweep", which for single-direction
 * words is also the logical timestamp order.
 */
internal fun sweepPositionRel(centerRelX: Float, wordWidth: Float, wordRtl: Boolean): Float =
    if (wordRtl) wordWidth - centerRelX else centerRelX

/* ---------------------------------------------------------------------------------- */
/*  Entry point                                                                        */
/* ---------------------------------------------------------------------------------- */

@Composable
fun SpicyLyricsView(
    lyrics: CharLyrics,
    positionMsState: StateFlow<Long>,
    artworkUri: String? = null,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        VibrantLyricsBackground(artworkUri = artworkUri)
        LyricScene(
            lyrics = lyrics,
            positionMsState = positionMsState,
            modifier = Modifier.fillMaxSize(),
            onSeek = onSeek,
        )
    }
}

/* ---------------------------------------------------------------------------------- */
/*  Scene: one animated anchor, everything else derived                                 */
/* ---------------------------------------------------------------------------------- */

@Composable
private fun LyricScene(
    lyrics: CharLyrics,
    positionMsState: StateFlow<Long>,
    modifier: Modifier,
    onSeek: (Long) -> Unit,
) {
    val density = LocalDensity.current

    val anchorState = remember(lyrics) { mutableFloatStateOf(0f) }
    val activeIndexState = remember(lyrics) { mutableIntStateOf(0) }
    val positionState = remember(lyrics) { mutableLongStateOf(0L) }

    val anchorSpring = remember { Spring(0.0, ANCHOR_FREQ, ANCHOR_DAMPING) }

    LaunchedEffect(lyrics) {
        var lastFrame = 0L
        // Latest position sample from the player (mpv's `time-pos` only fires a few
        // times per second). Between samples we dead-reckon at wall-clock rate so the
        // sweep/bounce animate smoothly instead of freezing and jumping per sample.
        var samplePos = positionMsState.value
        var sampleNanos = 0L
        val observer = launch {
            positionMsState.collect {
                samplePos = it
                sampleNanos = System.nanoTime()
            }
        }
        try {
            while (true) {
                withFrameNanos { now ->
                    val dt = if (lastFrame == 0L) 0.0167
                    else ((now - lastFrame) / 1e9).coerceIn(0.0, 0.1)
                    lastFrame = now

                    val elapsedSinceSample = if (sampleNanos == 0L) 0L
                    else ((now - sampleNanos) / 1_000_000L).coerceIn(0L, 500L)
                    val interpolatedPos = (samplePos + elapsedSinceSample).coerceAtLeast(0L)

                    val newActive = lyrics.lines.indexOfLast { interpolatedPos >= it.startMs }
                        .coerceAtLeast(0)
                    if (newActive != activeIndexState.intValue) activeIndexState.intValue = newActive

                    anchorSpring.setGoal(activeIndexState.intValue.toDouble())
                    anchorState.floatValue = anchorSpring.step(dt).toFloat()

                    positionState.longValue = interpolatedPos
                }
            }
        } finally { observer.cancel() }
    }

    val textStyle = remember(lyrics) {
        TextStyle(
            fontFamily = lyricsFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = LYRIC_FONT_SIZE,
            lineHeight = LYRIC_LINE_HEIGHT,
            color = Color.White,
            letterSpacing = 0.sp,
            // Content-based BiDi: Hebrew lines lay out right-to-left, English lines
            // left-to-right, mixed lines get per-run direction — never forced by locale.
            textDirection = TextDirection.Content,
        )
    }
    val translationStyle = MaterialTheme.typography.bodyLarge.copy(
        color = Color.White.copy(alpha = 0.65f),
        fontWeight = FontWeight.Medium,
    )

    BoxWithConstraints(modifier = modifier) {
        val paddingPx = with(density) { HORIZONTAL_PADDING.roundToPx() }
        val contentWidthPx = (constraints.maxWidth - 2 * paddingPx).coerceAtLeast(0)

        Layout(
            content = {
                lyrics.lines.forEachIndexed { i, line ->
                    LyricLineContent(
                        index = i,
                        line = line,
                        anchorState = anchorState,
                        activeIndexState = activeIndexState,
                        positionState = positionState,
                        textStyle = textStyle,
                        translationStyle = translationStyle,
                        contentWidthPx = contentWidthPx,
                        onSeek = onSeek,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val gapPx = LINE_GAP.roundToPx()
            val placeables = measurables.map {
                it.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = contentWidthPx))
            }

            val centers = FloatArray(placeables.size)
            var cursor = 0f
            for (i in placeables.indices) {
                val h = placeables[i].height.toFloat()
                centers[i] = cursor + h / 2f
                cursor += h + gapPx
            }

            val lastIndex = centers.size - 1
            val anchor = if (lastIndex < 0) 0f else anchorState.value.coerceIn(0f, lastIndex.toFloat())
            val lo = if (lastIndex < 0) 0 else anchor.toInt().coerceIn(0, lastIndex)
            val hi = if (lastIndex < 0) 0 else (lo + 1).coerceIn(0, lastIndex)
            val frac = (anchor - lo).coerceIn(0f, 1f)
            val focalCenter = if (lastIndex < 0) 0f else centers[lo] + (centers[hi] - centers[lo]) * frac
            val focalY = constraints.maxHeight * FOCAL_RATIO

            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { i, p ->
                    val top = (centers[i] - focalCenter + focalY - p.height / 2f).roundToInt()
                    p.placeRelative(paddingPx, top)
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------------- */
/*  One lyric line                                                                     */
/* ---------------------------------------------------------------------------------- */

@Composable
private fun LyricLineContent(
    index: Int,
    line: CharLine,
    anchorState: State<Float>,
    activeIndexState: State<Int>,
    positionState: State<Long>,
    textStyle: TextStyle,
    translationStyle: TextStyle,
    contentWidthPx: Int,
    onSeek: (Long) -> Unit,
) {
    val isActive = index == activeIndexState.value
    val blurRadius = blurPxFor(abs(index - activeIndexState.value)).dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val d = abs(index - anchorState.value)
                alpha = alphaFor(d)
                val s = scaleFor(d)
                scaleX = s
                scaleY = s
            }
            .blur(radius = blurRadius, edgeTreatment = BlurredEdgeTreatment.Rectangle)
            .clickable(onClick = { onSeek(line.startMs) })
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isActive) {
            ActiveLineText(
                line = line,
                positionState = positionState,
                textStyle = textStyle,
                contentWidthPx = contentWidthPx,
            )
        } else {
            Text(
                text = line.text,
                style = textStyle,
                softWrap = true,
                textAlign = TextAlign.Center,
            )
        }
        if (line.translation != null) {
            Text(
                text = line.translation,
                style = translationStyle,
                softWrap = true,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/* ---------------------------------------------------------------------------------- */
/*  Active line: single-layout, single-canvas word animator                            */
/* ---------------------------------------------------------------------------------- */

/**
 * The focal line, painted entirely on one Canvas:
 *
 *   1. "dim" base text (word glyphs clipped to the not-yet-spoken region),
 *   2. the spoken word redrawn letter-by-letter with per-letter springs,
 *   3. a soft glow over the spoken word, blurred from its glyph paths.
 *
 * The per-frame values (boundary, progress, springs) are updated by a frame loop
 * and read only inside the draw lambda, so the composable never recomposes per tick.
 */
@Composable
private fun ActiveLineText(
    line: CharLine,
    positionState: State<Long>,
    textStyle: TextStyle,
    contentWidthPx: Int,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = remember(line, textStyle, contentWidthPx) {
        textMeasurer.measure(
            text = AnnotatedString(line.text),
            style = textStyle,
            constraints = Constraints(maxWidth = contentWidthPx),
        )
    }
    val geometry = remember(line, layout) {
        LineTextGeometry.build(layout, line)
    }
    val fontPx = with(density) { textStyle.fontSize.toPx() }

    var activeWordIndex by remember(line) { mutableIntStateOf(-1) }
    val boundaryRel = remember(line) { mutableFloatStateOf(0f) }
    val wordProgress = remember(line) { mutableFloatStateOf(0f) }
    val wordScale = remember(line) { mutableFloatStateOf(1f) }
    val wordY = remember(line) { mutableFloatStateOf(0f) }
    val wordGlow = remember(line) { mutableFloatStateOf(0f) }
    val springs = remember(line) { mutableStateOf<List<LetterSprings>?>(null) }

    // Advance all animation state once per frame. Reading `positionState.value` and
    // writing to plain state holders here does not trigger any recomposition.
    LaunchedEffect(line, layout) {
        while (true) {
            withFrameNanos {
                val pos = positionState.value
                val index = line.words.indexOfLast { pos >= it.startMs }
                    .takeIf { line.words.isNotEmpty() } ?: -1

                if (index != activeWordIndex) {
                    activeWordIndex = index
                    if (index >= 0) {
                        springs.value = lettersOf(line, line.words[index]).map {
                            LetterSprings(
                                letterScaleCurve(0f).toDouble(),
                                letterYOffsetCurve(0f).toDouble(),
                                glowCurve(0f).toDouble(),
                            )
                        }
                    } else {
                        springs.value = null
                    }
                }

                if (index >= 0) {
                    val word = line.words[index]
                    val p = progressOf(pos, word.startMs, word.endMs)
                    wordProgress.floatValue = p

                    val letterList = geometry.wordLetters[index]
                    val leadingX = geometry.wordLeft[index]
                    val trailingX = geometry.wordRight[index]
                    val wordWidth = (trailingX - leadingX).coerceAtLeast(1f)
                    boundaryRel.floatValue = wordWidth * p

                    val dt = (16.7 / 1000.0).coerceIn(0.001, 0.1)

                    // Whole-word emphasis (mirrors SpicyLyrics' ScaleRange/YOffsetRange).
                    wordScale.floatValue += (1f + 0.05f * bell(p) - wordScale.floatValue) * 0.2f
                    wordY.floatValue += (letterYOffsetCurve(p) * fontPx * 0.5f - wordY.floatValue) * 0.2f
                    // Uniform word-level glow (SpicyLyrics' word --text-shadow), added on
                    // top of the per-letter proximity glow so the whole word reads as lit.
                    wordGlow.floatValue += (glowCurve(p) - wordGlow.floatValue) * 0.2f

                    val springsList = springs.value ?: return@withFrameNanos
                    val activeLetterIndex =
                        (p * letterList.size).toInt().coerceIn(0, (letterList.size - 1).coerceAtLeast(0))
                    // All letters animate from the ACTIVE letter's own progress, blunted by
                    // distance — SpicyLyrics' proximity model. (Driving glow from curve(1f)
                    // yields 0 because the curve returns to rest at the end, which is why
                    // the glow never appeared before.)
                    val activeLp = if (activeLetterIndex in letterList.indices) {
                        progressOf(pos, letterList[activeLetterIndex].letter.startMs, letterList[activeLetterIndex].letter.endMs)
                    } else 0f
                    val baseScale = letterScaleCurve(activeLp)
                    val baseY = letterYOffsetCurve(activeLp)
                    val baseGlow = glowCurve(activeLp)
                    val restScale = letterScaleCurve(0f)
                    val restY = letterYOffsetCurve(0f)
                    letterList.forEachIndexed { i, _ ->
                        val s = springsList[i]
                        val distance = abs(i - activeLetterIndex)
                        val falloff = (1f / (1f + distance.toDouble().pow(2.8))).toFloat()
                        val glowFalloff = 1f / (1f + distance * 0.9f)
                        s.scale.setGoal((restScale + (baseScale - restScale) * falloff).toDouble())
                        s.yOffset.setGoal((restY + (baseY - restY) * falloff).toDouble())
                        s.glow.setGoal((baseGlow * glowFalloff).toDouble())
                        s.scale.step(dt)
                        s.yOffset.step(dt)
                        s.glow.step(dt)
                    }
                }
            }
        }
    }

    // Render the whole line. The per-frame states are captured by the closure and read
    // *inside* the draw lambda, so they subscribe only the draw phase and the
    // composable never recomposes per tick.
    //
    // The Text is drawn transparent: it exists purely to size/lay out the line, and all
    // glyph painting happens in the drawBehind pass (dim base + animated word).
    Text(
        text = line.text,
        style = textStyle.copy(color = Color.Transparent),
        softWrap = true,
        textAlign = TextAlign.Center,
        onTextLayout = {},
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawAnimatedLine(
                    geometry = geometry,
                    activeWordIndex = activeWordIndex,
                    boundaryRel = boundaryRel.floatValue,
                    wordProgress = wordProgress.floatValue,
                    wordScale = wordScale.floatValue,
                    wordY = wordY.floatValue,
                    wordGlow = wordGlow.floatValue,
                    springs = springs.value,
                    fontPx = fontPx,
                )
            },
    )
}

/**
 * All the per-frame drawing for the active line.
 *
 * `geometry` holds the single BiDi-shaped `TextLayoutResult` plus, for each timed
 * word, the exact glyph-cluster boxes that layout produced. Every glyph the viewer
 * sees — dim base, bright word, sweep — is painted from that one layout by clipping
 * it to the boxes; no letter is ever re-typed, re-measured or pieced together from
 * individual text runs. The draw order for one active word is:
 *
 *   1. the line without the word, dim (clipped around the word's boxes),
 *   2. the word itself, drawn one clip-band at a time, each band carrying its own
 *      spring scale/bounce, sweep colour and glow.
 *
 * The sweep travels from the word's reading-start edge in its visual direction, so a
 * Hebrew word illuminates right-to-left while the underlying timestamps stay logical.
 */
private fun DrawScope.drawAnimatedLine(
    geometry: LineTextGeometry,
    activeWordIndex: Int,
    boundaryRel: Float,
    wordProgress: Float,
    wordScale: Float,
    wordY: Float,
    wordGlow: Float,
    springs: List<LetterSprings>?,
    fontPx: Float,
) {
    val layout = geometry.layout
    // The sizing Text is fillMaxWidth with textAlign=Center, but the measured layout
    // is only as wide as the text itself. Mirror that centring here so the drawn line
    // lines up with where the (invisible) Text sits.
    val lineOffsetX = ((size.width - layout.size.width) / 2f).coerceAtLeast(0f)

    if (activeWordIndex < 0) {
        // No timed word yet — draw the whole line dim (pre-sung state).
        drawText(layout, color = DimColor, topLeft = Offset(lineOffsetX, 0f))
        return
    }

    val letterList = geometry.wordLetters[activeWordIndex]
    if (letterList.isEmpty() || springs == null) return

    val leadingX = geometry.wordLeft[activeWordIndex]
    val trailingX = geometry.wordRight[activeWordIndex]
    val wordWidth = (trailingX - leadingX).coerceAtLeast(1f)
    val wordRtl = geometry.wordRtl[activeWordIndex]
    val native = drawContext.canvas.nativeCanvas

    // 1. Dim base: the whole line EXCLUDING the active word, drawn in two clipped
    //    regions straight from the one real layout — identical glyphs, spacing and
    //    ordering to the inactive state. The word is painted on top from the same
    //    layout, so no dim copy can ghost out from under it.
    val lineTop = 0f
    val lineBottom = layout.size.height.toFloat()
    if (leadingX > 0f) {
        clipRect(left = lineOffsetX, top = lineTop, right = lineOffsetX + leadingX, bottom = lineBottom) {
            drawText(layout, color = DimColor, topLeft = Offset(lineOffsetX, 0f))
        }
    }
    if (trailingX < layout.size.width) {
        clipRect(
            left = lineOffsetX + trailingX,
            top = lineTop,
            right = lineOffsetX + layout.size.width,
            bottom = lineBottom,
        ) {
            drawText(layout, color = DimColor, topLeft = Offset(lineOffsetX, 0f))
        }
    }

    // 2. The whole word gently pops/lifts; clusters are drawn inside that transform so
    //    they stay glued to their positions under the group scale.
    native.save()
    val wordCenterX = lineOffsetX + (leadingX + trailingX) / 2f
    val wordBottom = layout.size.height.toFloat() - 2f // approx baseline zone
    native.translate(wordCenterX, wordBottom)
    native.scale(wordScale, wordScale)
    native.translate(-wordCenterX, -wordBottom + wordY)

    val wordDone = wordProgress >= 1f

    // RTL runs (Hebrew/Arabic) rely on connected, contextually-shaped glyphs. Drawing
    // them cluster-by-cluster splits ligatures and leaves overlapping, broken letters.
    // Paint the whole word as ONE shaped unit and sweep its colour as a single band,
    // with only the word-level spring/glow (no per-cluster transforms).
    if (wordRtl) {
        val wordBandLeft = lineOffsetX + leadingX
        val wordBandRight = lineOffsetX + trailingX
        clipRect(left = wordBandLeft, top = lineTop, right = wordBandRight, bottom = lineBottom) {
            val glow = wordGlow
            val brush = if (wordDone) {
                SolidColor(BrightColor)
            } else {
                val boundaryFrac = (boundaryRel / wordWidth).coerceIn(0f, 1f)
                val fadeFrac = ((boundaryRel + SWEEP_FEATHER_PX) / wordWidth).coerceIn(0f, 1f)
                Brush.horizontalGradient(
                    0f to BrightColor,
                    boundaryFrac to BrightColor,
                    fadeFrac to DimColor,
                    1f to DimColor,
                    startX = wordBandRight,
                    endX = wordBandLeft, // RTL: bright edge sweeps right → left
                )
            }
            val shadow = if (glow > 0.01f) {
                Shadow(
                    color = BrightColor.copy(alpha = (glow * LETTER_GLOW_ALPHA).coerceIn(0f, 1f)),
                    offset = Offset.Zero,
                    blurRadius = 4f + glow * 12f,
                )
            } else null
            drawText(
                layout,
                brush = brush,
                topLeft = Offset(lineOffsetX, 0f),
                shadow = shadow,
            )
        }
        native.restore()
        return
    }

    letterList.forEachIndexed { i, lt ->
        val s = springs[i]
        val scale = s.scale.snapshotPosition().toFloat()
        val yOff = s.yOffset.snapshotPosition().toFloat() * fontPx
        // Per-cluster proximity glow + the uniform word-level glow. Together they light
        // the whole word, strongest at the cluster being spoken (SpicyLyrics' model).
        val glow = (s.glow.snapshotPosition().toFloat() + wordGlow * 0.4f).coerceIn(0f, 1f)

        val bandLeft = lineOffsetX + lt.box.left
        val bandRight = lineOffsetX + lt.box.right
        val bandCenterX = (bandLeft + bandRight) / 2f

        // Paint the whole laid-out line, clipped to this cluster's real glyph box and
        // nudged by the cluster's spring so the actual shaped glyph pops/bounces instead
        // of a re-typed letter.
        clipRect(left = bandLeft, top = lineTop, right = bandRight, bottom = lineBottom) {
            native.save()
            // Bottom-anchored scale, matching SpicyLyrics' per-letter transform origin.
            val centerX = bandCenterX
            native.translate(centerX, 0f)
            native.scale(scale, scale)
            native.translate(-centerX, yOff)

            // Per-cluster colour from the sweep: clusters before the boundary are
            // bright, clusters at/after it are dim, with a soft band in between. The
            // sweep position is measured from the word's visual start edge.
            val centerRelX = bandCenterX - lineOffsetX - leadingX
            val t = sweepPositionRel(centerRelX, wordWidth, wordRtl)
            val color = if (wordDone || t <= boundaryRel) {
                BrightColor
            } else {
                val fade = ((t - boundaryRel) / SWEEP_FEATHER_PX).coerceIn(0f, 1f)
                lerp(BrightColor, DimColor, fade)
            }

            val shadow = if (glow > 0.01f) {
                Shadow(
                    color = BrightColor.copy(alpha = (glow * LETTER_GLOW_ALPHA).coerceIn(0f, 1f)),
                    offset = Offset.Zero,
                    blurRadius = 4f + glow * 12f, // matches SpicyLyrics' --text-shadow-blur-radius
                )
            } else {
                null
            }

            drawText(
                layout,
                color = color,
                topLeft = Offset(lineOffsetX, 0f),
                shadow = shadow,
            )
            native.restore()
        }
    }
    native.restore()
}
