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
 *   - the active word is removed from the dim base text,
 *   - the spoken word reuses cached shaped glyph paths for spring scale, Y bounce,
 *     and a sweep gradient boundary that travels through it,
 *   - a soft glow is drawn once from the cached word path,
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
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
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.lyrics.anim.CubicSpline
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

/** SpicyLyrics `--gradient-alpha`: bright portion of a glyph. */
private val BrightColor = Color(0xD9FFFFFF)
/** SpicyLyrics `--gradient-alpha-end`: resting portion of a glyph. */
private val DimColor = Color(0x59FFFFFF)
private val BrightBrush = SolidColor(BrightColor)
private val DimBrush = SolidColor(DimColor)
/** Gradient fade width as a fraction of one glyph (CSS `+20%` stop). */
private const val GLYPH_FADE_FRACTION = 0.2f

/** d3 easeSinOut, used by SpicyLyrics for the in-glyph gradient travel. */
private fun easeSinOut(p: Float): Float = sin(p * (PI / 2f).toFloat())

/**
 * The traveling bright/dim gradient across one glyph, SpicyLyrics' per-element
 * `linear-gradient(90deg, bright var(--gradient-position), dim calc(pos + 20%))`.
 * `t` is the bright edge in glyph-width fractions: -0.2 fully dim, 1 fully bright.
 */
internal fun glyphProgressBrush(startX: Float, endX: Float, t: Float): Brush {
    if (t <= -GLYPH_FADE_FRACTION) return DimBrush
    if (t >= 1f) return BrightBrush
    fun colorAt(x: Float) = lerp(
        BrightColor,
        DimColor,
        ((x - t) / GLYPH_FADE_FRACTION).coerceIn(0f, 1f),
    )
    val stops = mutableListOf(0f to colorAt(0f))
    if (t >= 0f) stops += t to BrightColor
    if (t + GLYPH_FADE_FRACTION <= 1f) stops += (t + GLYPH_FADE_FRACTION) to DimColor
    stops += 1f to colorAt(1f)
    return Brush.horizontalGradient(
        *stops.toTypedArray(),
        startX = startX,
        endX = endX,
    )
}

internal enum class WordState { NOT_SUNG, ACTIVE, SUNG }

internal fun wordStateAt(positionMs: Long, word: WordSpan): WordState = when {
    positionMs < word.startMs -> WordState.NOT_SUNG
    positionMs >= word.endMs -> WordState.SUNG
    else -> WordState.ACTIVE
}

internal fun activeWordIndexAt(positionMs: Long, words: List<WordSpan>): Int =
    words.indexOfFirst { wordStateAt(positionMs, it) == WordState.ACTIVE }

/** Progressive depth falloff as a function of continuous signed distance from the focal line. */
private fun alphaFor(distance: Float): Float = (1f / (1f + distance * 0.8f)).coerceIn(0.25f, 1f)

/** Restrained emphasis: near-identical sizes, sharpness/opacity do the heavy lifting. */
private fun scaleFor(distance: Float): Float = (1.05f - distance * 0.06f).coerceIn(0.97f, 1.05f)

/** Subtle spatial blur, quantised to whole-line distance so it is not re-applied per frame. */
private fun blurPxFor(dist: Int): Float = (1.15f * dist).coerceIn(0f, 3.5f)

/** SpicyLyrics spring tuning for the active word's glyph motion. */
private const val SCALE_FREQ = 0.88
private const val SCALE_DAMPING = 0.64
private const val Y_OFFSET_FREQ = 1.45
private const val Y_OFFSET_DAMPING = 0.40
private const val GLOW_FREQ = 1.18
private const val GLOW_DAMPING = 0.56

/** One glyph of a timed word, addressed by its char offset into `CharLine.text`. */
internal data class Letter(
    val ch: String,
    val charOffset: Int,
    val startMs: Long,
    val endMs: Long,
)

/** Per-glyph motion state. The glyph itself is drawn from a cached shaped path. */
internal class LetterSprings(scale: Double, yOffset: Double, glow: Double) {
    val scale: Spring = Spring(scale, SCALE_FREQ, SCALE_DAMPING)
    val yOffset: Spring = Spring(yOffset, Y_OFFSET_FREQ, Y_OFFSET_DAMPING)
    val glow: Spring = Spring(glow, GLOW_FREQ, GLOW_DAMPING)
}

/** Exact animation curves used by SpicyLyrics (npm `cubic-spline`, natural cubic). */
private val WordScaleSpline = CubicSpline(
    doubleArrayOf(0.0, 0.7, 1.0),
    doubleArrayOf(0.95, 1.0505, 1.0),
)
private val LetterScaleSpline = CubicSpline(
    doubleArrayOf(0.0, 0.7, 1.0),
    doubleArrayOf(0.95, 1.175, 1.0),
)
private val WordYOffsetSpline = CubicSpline(
    doubleArrayOf(0.0, 0.9, 1.0),
    doubleArrayOf(1.0 / 100.0, -(1.0 / 60.0), 0.0),
)
private val LetterYOffsetSpline = CubicSpline(
    doubleArrayOf(0.0, 0.9, 1.0),
    doubleArrayOf(1.0 / 100.0, -(1.0 / 56.0), 0.0),
)
private val GlowSpline = CubicSpline(
    doubleArrayOf(0.0, 0.15, 0.6, 1.0),
    doubleArrayOf(0.0, 1.0, 1.0, 0.0),
)

private fun wordScaleCurve(p: Float): Float = WordScaleSpline.at(p.toDouble()).toFloat()
private fun letterScaleCurve(p: Float): Float = LetterScaleSpline.at(p.toDouble()).toFloat()
private fun wordYOffsetCurve(p: Float): Float = WordYOffsetSpline.at(p.toDouble()).toFloat()
private fun letterYOffsetCurve(p: Float): Float = LetterYOffsetSpline.at(p.toDouble()).toFloat()
private fun glowCurve(p: Float): Float = GlowSpline.at(p.toDouble()).toFloat()

/**
 * Desktop tuning: use the SpicyLyrics letter animation on essentially every word long
 * enough for the eye to perceive it. The web renderer keeps a ~1 s promotion gate to
 * control DOM/layer cost; this renderer is one Canvas, so that tradeoff does not apply.
 */
private const val LETTER_ANIMATION_MIN_MS = 180L
private const val FAR_WORD_SNAP_MS = 500L
private const val SUNG_LETTER_GLOW_PROGRESS = 0.2f


private fun containsStrongRtl(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
            -> return true
        }
        i += Character.charCount(cp)
    }
    return false
}

private fun isLetterCapable(word: WordSpan, rtl: Boolean): Boolean =
    !rtl && word.endMs - word.startMs >= LETTER_ANIMATION_MIN_MS

/*
 * Keep the provider's real word boundary. Cutting 250 ms off every promoted word made
 * short/medium desktop words finish early, then visibly "hand off" to the next word.
 * The DOM renderer can hide that inside element transitions; on a single Canvas the
 * discontinuity is obvious.
 */
private fun animationEndMs(word: WordSpan, letterCapable: Boolean): Long =
    word.endMs.coerceAtLeast(word.startMs + 1L)

private fun wordStateAt(positionMs: Long, startMs: Long, endMs: Long): WordState = when {
    positionMs < startMs -> WordState.NOT_SUNG
    positionMs >= endMs -> WordState.SUNG
    else -> WordState.ACTIVE
}

/** Persistent spring state for one timed word and, only when SpicyLyrics would use it, its letters. */
internal class WordAnimationState(
    letters: List<Letter>,
    letterCapable: Boolean = true,
) {
    val scale = Spring(wordScaleCurve(0f).toDouble(), SCALE_FREQ, SCALE_DAMPING)
    val yOffset = Spring(wordYOffsetCurve(0f).toDouble(), Y_OFFSET_FREQ, Y_OFFSET_DAMPING)
    val glow = Spring(glowCurve(0f).toDouble(), GLOW_FREQ, GLOW_DAMPING)
    val letters = if (letterCapable) {
        letters.map {
            LetterSprings(
                letterScaleCurve(0f).toDouble(),
                letterYOffsetCurve(0f).toDouble(),
                glowCurve(0f).toDouble(),
            )
        }
    } else {
        emptyList()
    }
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
internal fun lettersOf(line: CharLine, word: WordSpan): List<Letter> =
    lettersOf(line, word, word.endMs.coerceAtLeast(word.startMs + 1L))

private fun lettersOf(line: CharLine, word: WordSpan, effectiveEndMs: Long): List<Letter> {
    val text = line.text
    val ranges = clusterRanges(text, word.startChar, word.endChar)
    val count = ranges.size.coerceAtLeast(1)
    val duration = (effectiveEndMs - word.startMs).coerceAtLeast(1L)
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
    /** Canvas renderer split: timed non-RTL words >= 180 ms become letter groups. */
    val wordLetterCapable: List<Boolean>,
    /** Provider-accurate animation end for each word. */
    val wordAnimationEndMs: List<Long>,
    /** Pre-sorted base-pass holes, built once rather than allocated every frame. */
    val wordIntervals: List<Pair<Float, Float>>,
) {
    companion object {
        fun build(
            layout: TextLayoutResult,
            line: CharLine,
            measurer: TextMeasurer,
            style: TextStyle,
        ): LineTextGeometry {
            val rawLetters = line.words.map { word -> lettersOf(line, word) }
            val rawBoxes = rawLetters.map { letters -> letters.map { layout.getBoundingBox(it.charOffset) } }
            val wordRtl = rawBoxes.mapIndexed { wi, boxes ->
                val visible = boxes.filter { it.width > 0f }
                val geometricRtl = visible.size >= 2 && visible.first().left > visible.last().right
                val word = line.words[wi]
                val logicalText = line.text.substring(word.startChar, word.endChar)
                geometricRtl || containsStrongRtl(logicalText)
            }
            val wordLetterCapable = line.words.mapIndexed { i, word -> isLetterCapable(word, wordRtl[i]) }
            val wordAnimationEndMs = line.words.mapIndexed { i, word ->
                animationEndMs(word, wordLetterCapable[i])
            }

            val wordLetters = line.words.mapIndexed { wi, word ->
                val letters = if (wordLetterCapable[wi]) {
                    lettersOf(line, word, wordAnimationEndMs[wi])
                } else {
                    rawLetters[wi]
                }
                val boxes = rawBoxes[wi]
                letters.mapIndexed { i, letter ->
                    val box = boxes[i]
                    // SpicyLyrics only creates per-letter inline elements for emphasis
                    // words. Short words stay a single word element; avoiding standalone
                    // shaping for them removes most of the desktop port's hot-path work.
                    val letterLayout = if (wordLetterCapable[wi]) {
                        runCatching {
                            measurer.measure(
                                text = AnnotatedString(letter.ch),
                                style = style,
                                constraints = Constraints(),
                            )
                        }.getOrNull()
                    } else null
                    LetterText(
                        letter = letter,
                        box = box,
                        x = box.left,
                        width = box.width,
                        letterLayout = letterLayout,
                        topY = letterLayout?.let { layout.firstBaseline - it.firstBaseline } ?: 0f,
                    )
                }
            }
            val wordLeft = line.words.mapIndexed { wi, _ ->
                wordLetters[wi].filter { it.width > 0f }.minOfOrNull { it.box.left } ?: 0f
            }
            val wordRight = line.words.mapIndexed { wi, _ ->
                wordLetters[wi].filter { it.width > 0f }.maxOfOrNull { it.box.right } ?: 0f
            }
            val wordIntervals = wordLeft.indices
                .map { wordLeft[it] to wordRight[it] }
                .filter { it.second > it.first }
                .sortedBy { it.first }
            return LineTextGeometry(
                layout = layout,
                wordLetters = wordLetters,
                wordLeft = wordLeft,
                wordRight = wordRight,
                wordRtl = wordRtl,
                wordLetterCapable = wordLetterCapable,
                wordAnimationEndMs = wordAnimationEndMs,
                wordIntervals = wordIntervals,
            )
        }
    }
}

/** One grapheme cluster of a timed word: its glyph box from the full-line layout. */
internal class LetterText(
    val letter: Letter,
    /** Glyph cluster box in line-local coordinates (living in `geometry.layout`). */
    val box: Rect,
    /** Line-local X of the cluster's left edge. */
    val x: Float,
    val width: Float,
    /** Standalone measurement of this cluster, baseline-aligned to the line (LTR words). */
    val letterLayout: TextLayoutResult?,
    /** Y at which `letterLayout`'s baseline meets the line's baseline. */
    val topY: Float,
)

/**
 * Position of a cluster's centre inside its word, measured from the visual edge the
 * inline sweep starts at — the word's reading-start side (left for LTR, right for RTL).
 * Rising sweep position means "later in the visual sweep", which for single-direction
 * words is also the logical timestamp order.
 */
internal fun sweepPositionRel(centerRelX: Float, wordWidth: Float, wordRtl: Boolean): Float =
    if (wordRtl) wordWidth - centerRelX else centerRelX

/**
 * Shared, precomputed per-line text geometry.
 *
 * Building a line's layout + glyph boxes (`LineTextGeometry.build`) runs on the UI
 * thread, and it is exactly the work the first frame of a newly active line pays.
 * The logs show `lyrics_new_line` frames of tens of ms up to ~2 s, with fps
 * collapsing to single digits right after each line change. The scene prewarms one
 * line per frame here, so activating a line never re-measures/re-shapes text on the
 * frame it appears.
 */
private class LineGeometryCache(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val maxWidthPx: Int,
) {
    private val entries = HashMap<Int, LineTextGeometry>()

    fun get(index: Int): LineTextGeometry? = entries[index]

    fun geometryFor(lines: List<CharLine>, index: Int): LineTextGeometry {
        val existing = entries[index]
        if (existing != null) return existing
        val line = lines[index]
        val layout = measurer.measure(
            text = AnnotatedString(line.text),
            style = style,
            constraints = Constraints(maxWidth = maxWidthPx),
        )
        return LineTextGeometry.build(layout, line, measurer, style).also { entries[index] = it }
    }
}

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
    isPlaying: Boolean = true,
) {
    Box(modifier = modifier.fillMaxSize()) {
        VibrantLyricsBackground(artworkUri = artworkUri, isPlaying = isPlaying)
        LyricScene(
            lyrics = lyrics,
            positionMsState = positionMsState,
            modifier = Modifier.fillMaxSize(),
            onSeek = onSeek,
            isPlaying = isPlaying,
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
    isPlaying: Boolean,
) {
    val density = LocalDensity.current

    val anchorState = remember(lyrics) { mutableFloatStateOf(0f) }
    val activeIndexState = remember(lyrics) { mutableIntStateOf(0) }
    val positionState = remember(lyrics) { mutableLongStateOf(0L) }

    val anchorSpring = remember { Spring(0.0, ANCHOR_FREQ, ANCHOR_DAMPING) }

    LaunchedEffect(lyrics, isPlaying) {
        var lastFrame = 0L
        var lastActiveIndex = -1
        // Latest position sample from the player; the backend provides controlled timing samples.
        // times per second). Between samples we dead-reckon at wall-clock rate so the
        // sweep/bounce animate smoothly instead of freezing and jumping per sample.
        var samplePos = positionMsState.value
        var sampleNanos = System.nanoTime()
        var smoothPosMs = samplePos.toDouble()
        var haveSmoothClock = false
        val observer = launch {
            positionMsState.collect {
                samplePos = it
                sampleNanos = System.nanoTime()
                LyricsFrameStats.recordSample()
            }
        }
        try {
            // Player position samples are not perfectly phase-locked to the Compose
            // frame clock. Never snap the visual clock to each new sample: doing that
            // creates a tiny backward/forward step every sample and makes word handoffs
            // look choppy even at a perfect 60/120 Hz render rate.
            while (true) {
                // One loop, always running. The position feed and active-line tracking
                // must never stall (that would freeze lyric sync during a buffer or
                // seek). Playback time itself is frozen while paused, but the visual
                // anchor can still settle after a seek or line change.
                withFrameNanos { now ->
                    val dt = if (lastFrame == 0L) 0.0167
                    else ((now - lastFrame) / 1e9).coerceIn(0.0, 0.1)
                    val frameMs = (now - lastFrame) / 1_000_000.0
                    if (isPlaying) LyricsFrameStats.recordFrame(LoopId.SCENE, now - lastFrame, now)
                    lastFrame = now

                    val elapsedSinceSample = ((System.nanoTime() - sampleNanos) / 1_000_000.0)
                        .coerceIn(0.0, 750.0)
                    val predictedPos = samplePos.toDouble() + if (isPlaying) elapsedSinceSample else 0.0

                    if (!haveSmoothClock) {
                        smoothPosMs = predictedPos
                        haveSmoothClock = true
                    } else if (isPlaying) {
                        // Free-run at playback speed, then gently phase-lock to the latest
                        // backend sample. Small sample jitter is absorbed instead of drawn.
                        val freeRun = smoothPosMs + dt * 1000.0
                        val error = predictedPos - freeRun
                        smoothPosMs = if (kotlin.math.abs(error) > 220.0) {
                            // Real seek/discontinuity: don't spend seconds easing to it.
                            predictedPos
                        } else {
                            val correction = 1.0 - kotlin.math.exp(-dt * 10.0)
                            freeRun + error * correction
                        }
                    } else {
                        // Paused position should converge immediately and stay still.
                        smoothPosMs = predictedPos
                    }
                    val interpolatedPos = smoothPosMs.toLong().coerceAtLeast(0L)

                    val newActive = lyrics.lines.indexOfLast { interpolatedPos >= it.startMs }
                        .coerceAtLeast(0)
                    if (newActive != activeIndexState.intValue) {
                        activeIndexState.intValue = newActive
                        if (newActive != lastActiveIndex) {
                            lastActiveIndex = newActive
                            // Geometry (layout + glyph boxes) for new lines is prewarmed
                            // ahead of time, so this transition frame stays cheap.
                            LyricsFrameStats.logFrame(
                                tag = "lyrics_new_line",
                                frameMillis = frameMs.toFloat(),
                                message = "active_line_change index=$newActive lines=${lyrics.lines.size}",
                                nowNanos = now,
                            )
                        }
                    }

                    anchorSpring.setGoal(activeIndexState.intValue.toDouble())
                    anchorState.floatValue = anchorSpring.step(dt).toFloat()

                    positionState.longValue = interpolatedPos

                    // Rolling telemetry: a compact line only when the window was
                    // janky or stalled (quiet windows log nothing).
                    if (isPlaying) {
                        LyricsFrameStats.maybeEmitSummary(
                            nowNanos = now,
                            activeLines = lyrics.lines.size,
                            activeWordLetterCount = lyrics.lines.getOrNull(newActive)?.let { line ->
                                line.words.lastOrNull { interpolatedPos >= it.startMs }
                                    ?.let { lettersOf(line, it).size } ?: 0
                            } ?: 0,
                        )
                    }
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

        // One shared geometry store for every line. Building a line's layout + glyph
        // boxes on the frame it becomes active stalls the whole render loop (visible
        // in the logs as `lyrics_new_line` frames of tens of ms to seconds with fps
        // collapsing afterwards), so all lines are prewarmed here, one per frame.
        val textMeasurer = rememberTextMeasurer()
        val geometryCache = remember(lyrics, textStyle, contentWidthPx) {
            LineGeometryCache(textMeasurer, textStyle, contentWidthPx)
        }
        LaunchedEffect(lyrics, textStyle, contentWidthPx, geometryCache) {
            // Prewarm from the current active line outward (those are the transitions
            // about to happen), then cover the rest from the start of the song.
            val all = lyrics.lines
            val start = activeIndexState.intValue.coerceIn(0, (all.size - 1).coerceAtLeast(0))
            val order = (start until all.size) + (0 until start)
            for (next in order) {
                withFrameNanos {
                    if (next in all.indices && geometryCache.get(next) == null) {
                        geometryCache.geometryFor(all, next)
                    }
                }
            }
        }

        Layout(
            content = {
                lyrics.lines.forEachIndexed { i, line ->
                    LyricLineContent(
                        index = i,
                        lines = lyrics.lines,
                        line = line,
                        anchorState = anchorState,
                        activeIndexState = activeIndexState,
                        positionState = positionState,
                        textStyle = textStyle,
                        translationStyle = translationStyle,
                        contentWidthPx = contentWidthPx,
                        geometryCache = geometryCache,
                        onSeek = onSeek,
                        isPlaying = isPlaying,
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
    lines: List<CharLine>,
    line: CharLine,
    anchorState: State<Float>,
    activeIndexState: State<Int>,
    positionState: State<Long>,
    textStyle: TextStyle,
    translationStyle: TextStyle,
    contentWidthPx: Int,
    geometryCache: LineGeometryCache?,
    onSeek: (Long) -> Unit,
    isPlaying: Boolean,
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
                index = index,
                lines = lines,
                line = line,
                positionState = positionState,
                textStyle = textStyle,
                contentWidthPx = contentWidthPx,
                isPlaying = isPlaying,
                geometryCache = geometryCache,
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
 *   2. the spoken word redrawn from cached glyph paths with per-letter springs,
 *   3. a soft glow over the spoken word, blurred from its complete glyph path.
 *
 * The per-frame values (boundary, progress, springs) are updated by a frame loop
 * and read only inside the draw lambda, so the composable never recomposes per tick.
 */
private class InlineFrameRuntime(var positionMs: Long)

private fun advanceSpring(spring: Spring, dt: Double, isPlaying: Boolean) {
    if (isPlaying && !spring.canSleep()) spring.step(dt)
}

/**
 * Active-line inline animation. Line anchoring/scroll/depth are intentionally outside
 * this function; this loop only drives the word/letter motion from SpicyLyrics.
 */
@Composable
private fun ActiveLineText(
    index: Int,
    lines: List<CharLine>,
    line: CharLine,
    positionState: State<Long>,
    textStyle: TextStyle,
    contentWidthPx: Int,
    isPlaying: Boolean,
    geometryCache: LineGeometryCache?,
) {
    val fallbackMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val geometry = remember(line, textStyle, contentWidthPx, geometryCache, lines) {
        geometryCache?.geometryFor(lines, index)
            ?: LineTextGeometry.build(
                fallbackMeasurer.measure(
                    text = AnnotatedString(line.text),
                    style = textStyle,
                    constraints = Constraints(maxWidth = contentWidthPx),
                ),
                line,
                fallbackMeasurer,
                textStyle,
            )
    }
    val fontPx = with(density) { textStyle.fontSize.toPx() }

    // Reuse the geometry's already-built timing. Short words intentionally get zero
    // per-letter spring objects because SpicyLyrics renders them as one word element.
    val timedLetters = remember(geometry) {
        geometry.wordLetters.map { glyphs -> glyphs.map { it.letter } }
    }
    val animations = remember(geometry) {
        timedLetters.mapIndexed { wordIndex, letters ->
            WordAnimationState(letters, geometry.wordLetterCapable[wordIndex])
        }
    }
    val runtime = remember(line) { InlineFrameRuntime(positionState.value) }
    val drawTick = remember(line) { mutableIntStateOf(0) }

    LaunchedEffect(line, isPlaying, geometry) {
        var lastFrame = 0L
        while (true) {
            withFrameNanos { now ->
                val elapsedNanos = if (lastFrame == 0L) 16_666_667L else now - lastFrame
                val dt = (elapsedNanos / 1e9).coerceIn(0.001, 0.10)
                if (isPlaying) LyricsFrameStats.recordFrame(LoopId.ACTIVE_LINE, elapsedNanos, now)
                lastFrame = now

                val pos = positionState.value
                runtime.positionMs = pos

                animations.forEachIndexed { wordIndex, animation ->
                    val word = line.words[wordIndex]
                    val endMs = geometry.wordAnimationEndMs[wordIndex]
                    val state = wordStateAt(pos, word.startMs, endMs)
                    val p = progressOf(pos, word.startMs, endMs)
                    val farFromWord = pos < word.startMs - FAR_WORD_SNAP_MS || pos > endMs + FAR_WORD_SNAP_MS

                    val wordScaleTarget = when (state) {
                        WordState.NOT_SUNG -> wordScaleCurve(0f)
                        WordState.ACTIVE -> wordScaleCurve(p)
                        WordState.SUNG -> wordScaleCurve(1f)
                    }
                    val wordYTarget = when (state) {
                        WordState.NOT_SUNG -> wordYOffsetCurve(0f)
                        WordState.ACTIVE -> wordYOffsetCurve(p)
                        WordState.SUNG -> wordYOffsetCurve(1f)
                    }
                    val wordGlowTarget = when (state) {
                        WordState.NOT_SUNG -> glowCurve(0f)
                        WordState.ACTIVE -> glowCurve(p)
                        WordState.SUNG -> glowCurve(1f)
                    }

                    animation.scale.setGoal(wordScaleTarget.toDouble(), snap = farFromWord)
                    animation.yOffset.setGoal((wordYTarget * fontPx).toDouble(), snap = farFromWord)
                    animation.glow.setGoal(wordGlowTarget.toDouble(), snap = farFromWord)
                    if (!farFromWord) {
                        advanceSpring(animation.scale, dt, isPlaying)
                        advanceSpring(animation.yOffset, dt, isPlaying)
                        advanceSpring(animation.glow, dt, isPlaying)
                    }

                    if (!geometry.wordLetterCapable[wordIndex]) return@forEachIndexed

                    val letterSprings = animation.letters
                    val wordLetters = timedLetters[wordIndex]
                    val activeLetterIndex = if (state == WordState.ACTIVE) {
                        wordLetters.indexOfFirst { pos >= it.startMs && pos < it.endMs }
                    } else -1
                    val activeLetterProgress = if (activeLetterIndex >= 0) {
                        val letter = wordLetters[activeLetterIndex]
                        progressOf(pos, letter.startMs, letter.endMs)
                    } else 0f

                    val activeScale = letterScaleCurve(activeLetterProgress)
                    val activeY = letterYOffsetCurve(activeLetterProgress)
                    val activeGlow = glowCurve(activeLetterProgress)
                    val restScale = letterScaleCurve(0f)
                    val restY = letterYOffsetCurve(0f)
                    val restGlow = glowCurve(0f)
                    val sungScale = letterScaleCurve(1f)
                    val sungY = letterYOffsetCurve(1f)
                    val sungGlow = glowCurve(1f)
                    val noActiveSungGlow = glowCurve(SUNG_LETTER_GLOW_PROGRESS)

                    letterSprings.forEachIndexed { letterIndex, spring ->
                        val letter = wordLetters[letterIndex]
                        val letterState = wordStateAt(pos, letter.startMs, letter.endMs)

                        var targetScale = restScale
                        var targetY = restY
                        var targetGlow = restGlow

                        when (state) {
                            WordState.NOT_SUNG -> Unit
                            WordState.SUNG -> {
                                targetScale = sungScale
                                targetY = sungY
                                targetGlow = sungGlow
                            }
                            WordState.ACTIVE -> {
                                if (activeLetterIndex >= 0) {
                                    val distance = abs(letterIndex - activeLetterIndex)
                                    val falloff = (1f / (1f + distance.toDouble().pow(2.8))).toFloat()
                                    val glowFalloff = 1f / (1f + distance * 0.9f)
                                    targetScale = restScale + (activeScale - restScale) * falloff
                                    targetY = restY + (activeY - restY) * falloff
                                    targetGlow = restGlow + (activeGlow - restGlow) * glowFalloff
                                }
                                // This override is present in SpicyLyrics: future letters
                                // do not start moving just because the neighbouring one is active.
                                if (letterState == WordState.NOT_SUNG) {
                                    targetScale = restScale
                                    targetY = restY
                                    targetGlow = restGlow
                                } else if (letterState == WordState.SUNG && activeLetterIndex < 0) {
                                    targetGlow = noActiveSungGlow
                                }
                            }
                        }

                        spring.scale.setGoal(targetScale.toDouble(), snap = farFromWord)
                        spring.yOffset.setGoal(targetY.toDouble(), snap = farFromWord)
                        spring.glow.setGoal(targetGlow.toDouble(), snap = farFromWord)
                        if (!farFromWord) {
                            advanceSpring(spring.scale, dt, isPlaying)
                            advanceSpring(spring.yOffset, dt, isPlaying)
                            advanceSpring(spring.glow, dt, isPlaying)
                        }
                    }
                }

                // Draw invalidation happens AFTER every spring has advanced. This keeps
                // paint and physics on the same frame instead of racing the position flow.
                drawTick.intValue++
            }
        }
    }

    Text(
        text = line.text,
        style = textStyle.copy(color = Color.Transparent),
        softWrap = true,
        textAlign = TextAlign.Center,
        onTextLayout = {},
        modifier = Modifier
            .fillMaxWidth()
            // No nested full-line offscreen layer here. The parent line already owns
            // its graphics layer; another large offscreen target made inline motion jank.
            .drawBehind {
                drawTick.intValue
                drawAnimatedLine(
                    geometry = geometry,
                    animations = animations,
                    words = line.words,
                    positionMs = runtime.positionMs,
                    fontPx = fontPx,
                )
            },
    )
}

/** Paint one active line without per-frame layout or per-frame word-list allocation. */
private fun DrawScope.drawAnimatedLine(
    geometry: LineTextGeometry,
    animations: List<WordAnimationState>,
    words: List<WordSpan>,
    positionMs: Long,
    fontPx: Float,
) {
    val layout = geometry.layout
    val lineOffsetX = ((size.width - layout.size.width) / 2f).coerceAtLeast(0f)
    val lineTop = 0f
    val lineBottom = layout.size.height.toFloat()
    val topLeft = Offset(lineOffsetX, 0f)
    val native = drawContext.canvas.nativeCanvas

    // Base text is painted only in the gaps between timed words. Intervals are sorted
    // once in LineTextGeometry instead of allocating/sorting them every frame.
    var cursor = 0f
    geometry.wordIntervals.forEach { (left, right) ->
        if (left > cursor) {
            clipRect(lineOffsetX + cursor, lineTop, lineOffsetX + left, lineBottom) {
                drawText(layout, color = DimColor, topLeft = topLeft)
            }
        }
        cursor = maxOf(cursor, right)
    }
    if (cursor < layout.size.width) {
        clipRect(lineOffsetX + cursor, lineTop, lineOffsetX + layout.size.width, lineBottom) {
            drawText(layout, color = DimColor, topLeft = topLeft)
        }
    }

    fun beginWordTransform(index: Int): Boolean {
        val left = geometry.wordLeft[index]
        val right = geometry.wordRight[index]
        if (right <= left) return false
        val animation = animations[index]
        val centerX = lineOffsetX + (left + right) / 2f
        val centerY = lineBottom / 2f
        val scale = animation.scale.snapshotPosition().toFloat()
        native.save()
        native.translate(centerX, centerY)
        native.scale(scale, scale)
        native.translate(
            -centerX,
            -centerY + animation.yOffset.snapshotPosition().toFloat(),
        )
        return true
    }

    fun drawStaticWord(index: Int, color: Color) {
        val left = geometry.wordLeft[index]
        val right = geometry.wordRight[index]
        if (!beginWordTransform(index)) return
        clipRect(lineOffsetX + left, lineTop, lineOffsetX + right, lineBottom) {
            drawText(layout, color = color, topLeft = topLeft)
        }
        native.restore()
    }

    words.forEachIndexed wordLoop@ { index, word ->
        if (index !in geometry.wordLetters.indices || index !in animations.indices) return@wordLoop
        val endMs = geometry.wordAnimationEndMs[index]
        val state = wordStateAt(positionMs, word.startMs, endMs)
        val letterCapable = geometry.wordLetterCapable[index]

        when (state) {
            WordState.NOT_SUNG -> drawStaticWord(index, DimColor)
            WordState.SUNG -> drawStaticWord(index, BrightColor)
            WordState.ACTIVE -> {
                val animation = animations[index]
                val left = geometry.wordLeft[index]
                val right = geometry.wordRight[index]
                if (right <= left) return@wordLoop

                if (!beginWordTransform(index)) return@wordLoop
                if (!letterCapable) {
                        // Short words are one SpicyLyrics word element: one gradient,
                        // one spring transform and one inexpensive word shadow. The old
                        // port incorrectly exploded EVERY LTR word into per-letter draws.
                        val progress = progressOf(positionMs, word.startMs, endMs)
                        val t = -GLYPH_FADE_FRACTION + (1f + GLYPH_FADE_FRACTION) * progress
                        val glow = animation.glow.snapshotPosition().toFloat()
                        val shadow = if (glow > 0.01f) {
                            Shadow(
                                color = Color.White.copy(alpha = (glow * 0.35f).coerceIn(0f, 1f)),
                                offset = Offset.Zero,
                                blurRadius = 4f + 2f * glow,
                            )
                        } else null
                        val brush = if (geometry.wordRtl[index]) {
                            glyphProgressBrush(lineOffsetX + right, lineOffsetX + left, t)
                        } else {
                            glyphProgressBrush(lineOffsetX + left, lineOffsetX + right, t)
                        }
                        clipRect(
                            lineOffsetX + left - 24f,
                            lineTop - 24f,
                            lineOffsetX + right + 24f,
                            lineBottom + 24f,
                        ) {
                            if (glow > 0.01f) {
                                drawText(
                                    layout,
                                    color = Color.White.copy(alpha = 0.035f * glow),
                                    topLeft = topLeft,
                                    shadow = Shadow(
                                        color = Color.White.copy(alpha = (glow * 0.40f).coerceIn(0f, 1f)),
                                        offset = Offset.Zero,
                                        blurRadius = 5f + 5f * glow,
                                    ),
                                )
                            }
                            drawText(layout, brush = brush, topLeft = topLeft, shadow = shadow)
                        }
                    } else {
                        // Only true SpicyLyrics emphasis words reach this path. The word
                        // wrapper has its own spring above; each letter then adds its own
                        // scale/Y/glow and only the actually sung letter moves the sweep.
                        val letters = geometry.wordLetters[index]
                        val activeLetterIndex = letters.indexOfFirst { lt ->
                            positionMs >= lt.letter.startMs && positionMs < lt.letter.endMs
                        }
                        val centerY = lineBottom / 2f

                        letters.forEachIndexed letterLoop@ { letterIndex, letterText ->
                            val letterLayout = letterText.letterLayout ?: return@letterLoop
                            val spring = animation.letters[letterIndex]
                            val timed = letterText.letter
                            val t = when {
                                positionMs >= timed.endMs -> 1f
                                positionMs < timed.startMs -> -GLYPH_FADE_FRACTION
                                letterIndex == activeLetterIndex ->
                                    -GLYPH_FADE_FRACTION + (1f + GLYPH_FADE_FRACTION) *
                                        easeSinOut(progressOf(positionMs, timed.startMs, timed.endMs))
                                else -> -GLYPH_FADE_FRACTION
                            }
                            val bandLeft = lineOffsetX + letterText.box.left
                            val bandRight = lineOffsetX + letterText.box.right
                            val bandCenterX = (bandLeft + bandRight) / 2f
                            val letterGlow = spring.glow.snapshotPosition().toFloat()
                            val shadow = if (letterGlow > 0.01f) {
                                Shadow(
                                    color = Color.White.copy(
                                        alpha = (letterGlow * 1.85f).coerceIn(0f, 1f),
                                    ),
                                    offset = Offset.Zero,
                                    blurRadius = 4f + 12f * letterGlow,
                                )
                            } else null
                            val letterScale = spring.scale.snapshotPosition().toFloat()

                            native.save()
                            native.translate(bandCenterX, centerY)
                            native.scale(letterScale, letterScale)
                            native.translate(
                                -bandCenterX,
                                -centerY + spring.yOffset.snapshotPosition().toFloat() * 2f,
                            )
                            val letterTopLeft = Offset(bandLeft, letterText.topY)
                            if (letterGlow > 0.005f) {
                                // Compose/Skia text shadows are intentionally drawn as a
                                // separate halo pass. Relying on the main gradient paint's
                                // shadow made the glow nearly disappear on some desktop
                                // backends, especially with translucent glyph brushes.
                                val haloAlpha = (letterGlow * 1.85f).coerceIn(0f, 1f)
                                drawText(
                                    letterLayout,
                                    color = Color.White.copy(alpha = 0.055f * haloAlpha),
                                    topLeft = letterTopLeft,
                                    shadow = Shadow(
                                        color = Color.White.copy(alpha = 0.92f * haloAlpha),
                                        offset = Offset.Zero,
                                        blurRadius = 5f + 14f * letterGlow,
                                    ),
                                )
                            }
                            drawText(
                                letterLayout,
                                brush = glyphProgressBrush(bandLeft, bandRight, t),
                                topLeft = letterTopLeft,
                                shadow = shadow,
                            )
                            native.restore()
                        }
                    }
                native.restore()
            }
        }
    }
}
