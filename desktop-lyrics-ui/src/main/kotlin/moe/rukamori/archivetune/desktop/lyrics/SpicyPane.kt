/*
 * Lyrics surface rebuilt to mirror SpicyLyrics' scene-motion model.
 *
 * Scene motion (one continuous spring-animated `anchor`, variable measured line
 * heights) is unchanged. This file now also replaces the old per-character karaoke
 * with a continuous word-level sweep:
 *
 *   - each line is laid out as a single Text, preserving native kerning/whitespace,
 *   - the focal line is painted with a horizontal gradient whose soft boundary is the
 *     continuous playback progress through the current timed word, so illumination
 *     travels *through* the word rather than turning characters on one at a time,
 *   - emphasis comes from sharpness/opacity/colour plus a restrained scale, never from
 *     oversized glyphs.
 *
 * Position is consumed through `positionMsState` (a hot StateFlow); the frame loop
 * reads the current value without restarting on each tick and only the focal line
 * recomposes per frame.
 */
package moe.rukamori.archivetune.desktop.lyrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
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

private val LYRIC_FONT_SIZE = 40.sp
private val LYRIC_LINE_HEIGHT = 48.sp
private val LINE_GAP = 8.dp
private val HORIZONTAL_PADDING = 36.dp
/** Glow halo: blur radius and the padding that gives the blur room to expand without clipping. */
private val GLOW_BLUR = 14.dp
private val GLOW_PADDING = 18.dp
/** Focal region sits in the upper-middle (~45%), leaving more upcoming lyrics below. */
private const val FOCAL_RATIO = 0.45f
/** Scene anchor spring: near-critical damping for a smooth, coherent slide. */
private const val ANCHOR_FREQ = 1.0
private const val ANCHOR_DAMPING = 0.9

/** Completed portion of the focal word: sharp near-white. */
private val BrightColor = Color(0xFFFFFFFF)
/** Upcoming/base portion of the focal word: dim but legible. */
private val DimColor = Color(0x99FFFFFF)

/**
 * Progressive depth falloff as a function of continuous signed distance from the focal
 * line. Active (d=0) is crisp and full; adjacent lines are still clearly legible.
 */
private fun alphaFor(distance: Float): Float = (1f / (1f + distance * 0.8f)).coerceIn(0.25f, 1f)

/** Restrained emphasis: near-identical sizes, sharpness/opacity do the heavy lifting. */
private fun scaleFor(distance: Float): Float = (1.05f - distance * 0.06f).coerceIn(0.97f, 1.05f)

/** Subtle spatial blur, quantised to whole-line distance so it is not re-applied per frame. */
private fun blurPxFor(dist: Int): Float = (1.15f * dist).coerceIn(0f, 3.5f)

/** SpicyLyrics' spring tuning for glyph scale (frequency Hz, damping ratio). */
private const val SCALE_FREQ = 0.88
private const val SCALE_DAMPING = 0.64

/** One glyph of a timed word, addressed by its char offset into `CharLine.text`. */
private data class Letter(
    val ch: String,
    val charOffset: Int,
    val startMs: Long,
    val endMs: Long,
)

/** Split a timed word into equally-timed letters indexed into the line text. */
private fun lettersOf(line: CharLine, word: WordSpan): List<Letter> {
    val text = line.text
    val count = (word.endChar - word.startChar).coerceAtLeast(1)
    val duration = (word.endMs - word.startMs).coerceAtLeast(1L)
    return (0 until count).map { i ->
        val offset = word.startChar + i
        Letter(
            ch = text[offset].toString(),
            charOffset = offset,
            startMs = word.startMs + duration * i / count,
            endMs = word.startMs + duration * (i + 1) / count,
        )
    }
}

private fun progressOf(positionMs: Long, startMs: Long, endMs: Long): Float =
    ((positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)

/** Colour of the sweep gradient at a given word-relative X. */
private fun sweepColorAt(wordRelX: Float, boundaryWordRel: Float, feather: Float): Color = when {
    wordRelX <= boundaryWordRel -> BrightColor
    wordRelX >= boundaryWordRel + feather -> DimColor
    else -> lerp(BrightColor, DimColor, (wordRelX - boundaryWordRel) / feather.coerceAtLeast(1f))
}

/** Smooth C1-continuous bell (0 → 1 → 0) peaking at progress 0.7, easing the corners. */
private fun bell(p: Float): Float {
    val k = if (p < 0.7f) p / 0.7f else (1f - p) / 0.3f
    return 0.5f * (1f - cos(PI * k.coerceIn(0f, 1f)).toFloat())
}

/** Letter "pop" as playback passes through a glyph: rises to ~1.175 then settles. */
private fun bounceLetterScale(p: Float): Float = 1.0f + 0.175f * bell(p)

/** Whole-word growth as playback passes through it: rises to ~1.05 then settles. */
private fun bounceWordScale(p: Float): Float = 1.0f + 0.05f * bell(p)

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
        var currentPos = positionMsState.value
        val observer = launch { positionMsState.collect { currentPos = it } }
        try {
            while (true) {
                withFrameNanos { now ->
                    val dt = if (lastFrame == 0L) 0.0167
                    else ((now - lastFrame) / 1e9).coerceIn(0.0, 0.1)
                    lastFrame = now

                    val newActive = lyrics.lines.indexOfLast { currentPos >= it.startMs }
                        .coerceAtLeast(0)
                    if (newActive != activeIndexState.intValue) activeIndexState.intValue = newActive

                    anchorSpring.setGoal(activeIndexState.intValue.toDouble())
                    anchorState.floatValue = anchorSpring.step(dt).toFloat()

                    positionState.longValue = currentPos
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

            // Cumulative measured centres (variable heights — lyrics may wrap).
            val centers = FloatArray(placeables.size)
            var cursor = 0f
            for (i in placeables.indices) {
                val h = placeables[i].height.toFloat()
                centers[i] = cursor + h / 2f
                cursor += h + gapPx
            }

            // Interpolate the focal centre through the measured positions of the anchor.
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
                positionMs = positionState.value,
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

/**
 * Focal-line renderer: a single Text painted with a horizontal gradient whose soft
 * boundary tracks continuous progress through the current timed word. A bouncing
 * overlay word adds the "spoken word pops" feel.
 */
@Composable
private fun ActiveLineText(
    line: CharLine,
    positionMs: Long,
    textStyle: TextStyle,
    contentWidthPx: Int,
) {
    val textMeasurer = rememberTextMeasurer()
    val glowPaddingPx = with(LocalDensity.current) { GLOW_PADDING.roundToPx() }
    // The glow padding reduces the available text width; measure at that width so the
    // sweep gradient is aligned with the rendered (padded) glyphs.
    val layout = remember(line, textStyle, contentWidthPx, glowPaddingPx) {
        textMeasurer.measure(
            text = AnnotatedString(line.text),
            style = textStyle,
            constraints = Constraints(maxWidth = (contentWidthPx - 2 * glowPaddingPx).coerceAtLeast(0)),
        )
    }
    val sweep = remember(layout, line, positionMs) {
        computeSweep(layout, line, positionMs)
    }
    val glowStyle = textStyle.copy(brush = SolidColor(Color.White))

    // Clip the full-line Text to exclude the active word region. Using a single Text
    // (rather than two substrings) preserves cross-boundary kerning, so the visible
    // portions stay perfectly aligned with the bouncing letters' positions.
    val totalWidth = layout.size.width.toFloat()
    val wordLeft = sweep.wordLeft
    val wordRight = sweep.wordRight
    val clipPrefixRight = if (sweep.word == null) totalWidth else wordLeft
    val clipSuffixLeft = if (sweep.word == null) totalWidth else wordRight

    // The glow is a blurred copy of the glyphs, padded so the blur spreads freely
    // beyond the letterforms. Without the padding the blur clips to the text's
    // rectangular layout bounds, producing a visible box around the line.
    Box {
        // Glow layer — full line, blurred, unclipped so the halo stays smooth around
        // every glyph (including the active word's letters on top of it).
        Text(
            text = line.text,
            style = glowStyle,
            softWrap = true,
            modifier = Modifier
                .blur(radius = GLOW_BLUR, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .padding(GLOW_PADDING),
        )
        // Base layer — full line, clipped to the regions outside the active word so the
        // sharp sweep never draws the spoken word (the bouncing letters draw it instead).
        Text(
            text = line.text,
            style = textStyle.copy(brush = sweep.lineBrush),
            softWrap = true,
            modifier = Modifier
                .padding(GLOW_PADDING)
                .drawWithContent {
                    val h = size.height
                    if (clipPrefixRight > 0f) {
                        clipRect(left = 0f, top = 0f, right = clipPrefixRight, bottom = h) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    if (clipSuffixLeft < totalWidth) {
                        clipRect(left = clipSuffixLeft, top = 0f, right = totalWidth, bottom = h) {
                            this@drawWithContent.drawContent()
                        }
                    }
                },
        )
        // Bouncing letters (the active word, only place it is drawn).
        BouncingWord(
            line = line,
            sweep = sweep,
            layout = layout,
            textStyle = textStyle,
            glowPaddingPx = glowPaddingPx,
            positionMs = positionMs,
        )
    }
}

/**
 * The spoken word's glyphs, rendered individually so the active letter (and the one or
 * two after it) bounces up in scale as playback passes through them, while the whole
 * word gently grows and settles — mirroring SpicyLyrics' per-letter springs.
 */
@Composable
private fun BouncingWord(
    line: CharLine,
    sweep: SweepState,
    layout: TextLayoutResult,
    textStyle: TextStyle,
    glowPaddingPx: Int,
    positionMs: Long,
) {
    val word = sweep.word ?: return
    val letters = remember(line, word) { lettersOf(line, word) }
    if (letters.isEmpty()) return

    val boundaryWordRel = sweep.boundary - sweep.wordLeft
    val feather = sweep.feather

    // SpicyLyrics steps each glyph's scale spring once per frame with real delta-time;
    // do the same here so the swell is smooth rather than re-targeted by animateFloatAsState.
    val latestPositionMs = rememberUpdatedState(positionMs)
    val letterSprings = remember(line, word) {
        letters.associate { it.charOffset to Spring(1.0, SCALE_FREQ, SCALE_DAMPING) }
    }
    val wordSpring = remember(line, word) { Spring(1.0, SCALE_FREQ, SCALE_DAMPING) }
    val letterScales = remember(line, word) { mutableStateOf(letters.associate { it.charOffset to 1f }) }
    val wordScale = remember(line, word) { mutableFloatStateOf(1f) }

    LaunchedEffect(line, word) {
        var lastFrame = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastFrame == 0L) 0.0167 else ((now - lastFrame) / 1e9).coerceIn(0.0, 0.1)
                lastFrame = now
                val pos = latestPositionMs.value

                val activeIndex = letters.indexOfFirst { pos in it.startMs until it.endMs }
                val activeProgress = if (activeIndex >= 0) {
                    progressOf(pos, letters[activeIndex].startMs, letters[activeIndex].endMs)
                } else 0f

                val wordProgress = progressOf(pos, word.startMs, word.endMs)
                wordSpring.setGoal(bounceWordScale(wordProgress).toDouble())
                wordScale.floatValue = wordSpring.step(dt).toFloat()

                val baseScale = bounceLetterScale(activeProgress)
                val updated = HashMap<Int, Float>(letters.size)
                for ((i, letter) in letters.withIndex()) {
                    val spring = letterSprings[letter.charOffset] ?: continue
                    val distance = abs(i - activeIndex)
                    val falloff = (1f / (1f + distance.toDouble().pow(2.8))).toFloat()
                    spring.setGoal((1f + (baseScale - 1f) * falloff).toDouble())
                    updated[letter.charOffset] = spring.step(dt).toFloat()
                }
                letterScales.value = updated
            }
        }
    }

    letters.forEachIndexed { _, letter ->
        key(letter.charOffset) {
            val scale = letterScales.value[letter.charOffset] ?: 1f

            val letterLeft = layout.getHorizontalPosition(letter.charOffset, false)
            val letterRight = layout.getHorizontalPosition(letter.charOffset + 1, false)
            val leftColor = sweepColorAt(letterLeft - sweep.wordLeft, boundaryWordRel, feather)
            val rightColor = sweepColorAt(letterRight - sweep.wordLeft, boundaryWordRel, feather)
            val brush = if (leftColor == rightColor) SolidColor(leftColor)
            else Brush.horizontalGradient(0f to leftColor, 1f to rightColor)

            Text(
                text = letter.ch,
                style = textStyle.copy(brush = brush),
                softWrap = false,
                modifier = Modifier
                    .offset { IntOffset((glowPaddingPx + letterLeft).roundToInt(), glowPaddingPx) }
                    .graphicsLayer {
                        val s = wordScale.floatValue * scale
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    },
            )
        }
    }
}

private data class SweepState(
    val lineBrush: Brush,
    val word: WordSpan?,
    val wordLeft: Float,
    val wordRight: Float,
    val boundary: Float,
    val feather: Float,
)

private fun computeSweep(layout: TextLayoutResult, line: CharLine, positionMs: Long): SweepState {
    val fallback = SweepState(SolidColor(BrightColor), null, 0f, 0f, 0f, 0f)
    if (line.words.isEmpty()) return fallback
    // A wrapped focal line has no single left-to-right sweep; render it bright instead.
    if (layout.lineCount != 1) return fallback

    val wordIndex = line.words.indexOfLast { positionMs >= it.startMs }.coerceAtLeast(0)
    val word = line.words[wordIndex]
    val progress = ((positionMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1L))
        .coerceIn(0f, 1f)

    val width = layout.size.width.toFloat()
    if (width <= 0f) return fallback

    val wordLeft = layout.getHorizontalPosition(word.startChar, false)
    val wordRight = layout.getHorizontalPosition(word.endChar, false)
    val wordWidth = (wordRight - wordLeft).coerceAtLeast(1f)
    val boundary = wordLeft + wordWidth * progress
    // Soft glow trails *ahead* of the boundary: everything up to the boundary is fully
    // bright (so the word is complete when progress reaches 1), then it feathers into
    // the upcoming part of the word.
    val feather = (wordWidth * 0.5f + 16f).coerceIn(16f, 80f)

    val lineBright = (boundary / width).coerceIn(0f, 1f)
    val lineDim = ((boundary + feather) / width).coerceIn(0f, 1f)
    val lineBrush = Brush.horizontalGradient(
        0f to BrightColor,
        lineBright to BrightColor,
        lineDim to DimColor,
        1f to DimColor,
    )

    return SweepState(lineBrush, word, wordLeft, wordRight, boundary, feather)
}
