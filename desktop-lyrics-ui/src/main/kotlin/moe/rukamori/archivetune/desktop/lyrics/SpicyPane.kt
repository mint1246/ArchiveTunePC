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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.lyrics.anim.CubicSpline
import moe.rukamori.archivetune.desktop.lyrics.anim.Spring
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Shader

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
                val awtFont = stream.use { java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, it) }
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(awtFont)
                FontFamily(awtFont.family)
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
/** Match SpicyLyrics: only vocal gaps of at least three seconds become musical dot lines. */
internal const val INTERLUDE_MIN_MS = 3_000L
/** SpicyLyrics starts collapsing the dot group shortly before vocals resume. */
private const val INTERLUDE_PREHIDE_MS = 500L
/** Its three timed dots finish just before that collapse starts. */
private const val INTERLUDE_TAIL_PADDING_MS = 550L
/** Keep one newly-current lyric below the pinned scroll anchor before moving the page. */
internal const val SCROLL_PIN_LOOKAHEAD = 2

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

/** SpicyLyrics dot animation tuning. Only three dots exist, so this is intentionally literal. */
private const val DOT_SCALE_FREQ = 0.7
private const val DOT_SCALE_DAMPING = 0.6
private const val DOT_Y_FREQ = 1.25
private const val DOT_Y_DAMPING = 0.4
private const val DOT_GLOW_FREQ = 1.0
private const val DOT_GLOW_DAMPING = 0.5
private const val DOT_OPACITY_FREQ = 1.0
private const val DOT_OPACITY_DAMPING = 0.5
private const val DOT_GROUP_SCALE_FREQ = 5.0
private const val DOT_GROUP_SCALE_DAMPING = 0.7

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
private val DotScaleSpline = CubicSpline(
    doubleArrayOf(0.0, 0.7, 1.0),
    doubleArrayOf(0.75, 1.05, 1.0),
)
private val DotYOffsetSpline = CubicSpline(
    doubleArrayOf(0.0, 0.9, 1.0),
    doubleArrayOf(0.0, -0.12, 0.0),
)
private val DotGlowSpline = CubicSpline(
    doubleArrayOf(0.0, 0.6, 1.0),
    doubleArrayOf(0.0, 1.0, 1.0),
)
private val DotOpacitySpline = CubicSpline(
    doubleArrayOf(0.0, 0.6, 1.0),
    doubleArrayOf(0.35, 1.0, 1.0),
)

private fun wordScaleCurve(p: Float): Float = WordScaleSpline.at(p.toDouble()).toFloat()
private fun letterScaleCurve(p: Float): Float = LetterScaleSpline.at(p.toDouble()).toFloat()
private fun wordYOffsetCurve(p: Float): Float = WordYOffsetSpline.at(p.toDouble()).toFloat()
private fun letterYOffsetCurve(p: Float): Float = LetterYOffsetSpline.at(p.toDouble()).toFloat()
private fun glowCurve(p: Float): Float = GlowSpline.at(p.toDouble()).toFloat()
private fun dotScaleCurve(p: Float): Float = DotScaleSpline.at(p.toDouble()).toFloat()
private fun dotYOffsetCurve(p: Float): Float = DotYOffsetSpline.at(p.toDouble()).toFloat()
private fun dotGlowCurve(p: Float): Float = DotGlowSpline.at(p.toDouble()).toFloat()
private fun dotOpacityCurve(p: Float): Float = DotOpacitySpline.at(p.toDouble()).toFloat()

/**
 * Keep letter-by-letter motion common, but skip words too short for a human eye to read
 * as sequential glyph motion. 500 ms is intentionally much more permissive than the
 * web renderer's ~1 s emphasis gate.
 */
internal const val LETTER_ANIMATION_MIN_MS = 500L
private const val FAR_WORD_SNAP_MS = 500L
private const val SUNG_LETTER_GLOW_PROGRESS = 0.2f
private const val GEOMETRY_PREWARM_BEHIND = 2
private const val GEOMETRY_PREWARM_AHEAD = 6

/**
 * Long held words should not merely run the same animation more slowly. Their active
 * glyph gets physically stronger, the neighbouring glyphs participate a little more,
 * and the lift/glow rise modestly. Values are precomputed per word and capped so a
 * multi-second vocal hold stays expressive rather than exploding the layout.
 */
internal data class LetterEmphasisProfile(
    val scaleExcursionMultiplier: Float,
    val falloffExponent: Double,
    val yMultiplier: Float,
    val glowMultiplier: Float,
)

internal fun letterEmphasisProfile(durationMs: Long): LetterEmphasisProfile {
    val raw = ((durationMs - LETTER_ANIMATION_MIN_MS).toFloat() / 2_500f).coerceIn(0f, 1f)
    val intensity = raw * raw * (3f - 2f * raw)
    return LetterEmphasisProfile(
        scaleExcursionMultiplier = 1f + 0.70f * intensity,
        falloffExponent = 2.8 - 0.5 * intensity,
        yMultiplier = 1f + 0.35f * intensity,
        glowMultiplier = 1f + 0.25f * intensity,
    )
}

/** Amplify only the portion above 1x so the 0.95 resting scale stays unchanged. */
internal fun emphasizedLetterScale(baseScale: Float, profile: LetterEmphasisProfile): Float =
    if (baseScale <= 1f) baseScale
    else (1f + (baseScale - 1f) * profile.scaleExcursionMultiplier).coerceAtMost(1.30f)

internal sealed interface LyricsSceneItem {
    val startMs: Long

    data class Vocal(
        val lineIndex: Int,
        override val startMs: Long,
    ) : LyricsSceneItem

    data class Interlude(
        val previousLineIndex: Int?,
        val nextLineIndex: Int,
        override val startMs: Long,
        val endMs: Long,
    ) : LyricsSceneItem
}

/**
 * Build explicit timed musical-line items from the UNION of all vocal intervals.
 * Overlapping lines must never create a fake interlude just because one shorter line
 * ends before the next line starts while another vocal is still running underneath it.
 */
internal fun buildLyricsSceneItems(lyrics: CharLyrics): List<LyricsSceneItem> = buildList {
    val lines = lyrics.lines
    if (lines.isEmpty()) return@buildList

    val first = lines.first()
    if (first.startMs >= INTERLUDE_MIN_MS) {
        add(
            LyricsSceneItem.Interlude(
                previousLineIndex = null,
                nextLineIndex = 0,
                startMs = 0L,
                endMs = first.startMs,
            ),
        )
    }

    add(LyricsSceneItem.Vocal(lineIndex = 0, startMs = first.startMs))
    var coveredUntilMs = first.endMs
    var coverageEndLineIndex = 0

    for (index in 1..lines.lastIndex) {
        val line = lines[index]
        if (line.startMs - coveredUntilMs >= INTERLUDE_MIN_MS) {
            add(
                LyricsSceneItem.Interlude(
                    previousLineIndex = coverageEndLineIndex,
                    nextLineIndex = index,
                    startMs = coveredUntilMs,
                    endMs = line.startMs,
                ),
            )
        }
        add(LyricsSceneItem.Vocal(lineIndex = index, startMs = line.startMs))
        if (line.endMs > coveredUntilMs) {
            coveredUntilMs = line.endMs
            coverageEndLineIndex = index
        }
    }
}

/** A vocal line stays live for its full provider interval, even if another line overlaps it. */
internal fun isVocalLineActiveAt(positionMs: Long, line: CharLine): Boolean =
    positionMs >= line.startMs && positionMs < line.endMs

/** Binary search because this runs every frame. Scene items are ordered by start time. */
internal fun activeSceneIndexAt(positionMs: Long, items: List<LyricsSceneItem>): Int {
    if (items.isEmpty()) return -1
    var low = 0
    var high = items.lastIndex
    var answer = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (items[mid].startMs <= positionMs) {
            answer = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return answer.coerceAtLeast(0)
}

/**
 * Separate vocal focus from page movement: line N+1 can become active without hauling
 * it to the focal point. The page advances when playback reaches N+2, or immediately
 * after an interlude/backward seek/large discontinuity.
 */
internal fun pinnedScrollAnchorLine(
    previousAnchorLine: Int,
    activeLine: Int,
    forceReset: Boolean = false,
): Int = when {
    previousAnchorLine < 0 -> activeLine
    forceReset -> activeLine
    activeLine < previousAnchorLine -> activeLine
    activeLine - previousAnchorLine >= SCROLL_PIN_LOOKAHEAD -> activeLine
    else -> previousAnchorLine
}


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
    /** Stable-color layouts: painting these without overrides avoids dirtying/re-laying out the paragraph. */
    val dimLayout: TextLayoutResult,
    val brightLayout: TextLayoutResult,
    /** word index -> per-cluster glyph box + timing. */
    val wordLetters: List<List<LetterText>>,
    /** word index -> visual left edge of the word in line-local X. */
    val wordLeft: List<Float>,
    /** word index -> visual right edge of the word in line-local X. */
    val wordRight: List<Float>,
    /** word index -> visual top edge of the word in line-local Y. */
    val wordTop: List<Float>,
    /** word index -> visual bottom edge of the word in line-local Y. */
    val wordBottom: List<Float>,
    /** word index -> true when the word's run reads right-to-left (Hebrew, Arabic, …). */
    val wordRtl: List<Boolean>,
    /** Canvas renderer split: timed non-RTL words >= 500 ms become letter groups. */
    val wordLetterCapable: List<Boolean>,
    /** Provider-accurate animation end for each word. */
    val wordAnimationEndMs: List<Long>,
    /**
     * Rectangles where the untimed base text is allowed to paint. These are split per
     * visual layout row so a word on a wrapped row never punches a same-X hole through
     * every other row in the paragraph.
     */
    val baseTextSegments: List<Rect>,
) {
    companion object {
        fun build(
            layout: TextLayoutResult,
            line: CharLine,
            measurer: TextMeasurer,
            style: TextStyle,
        ): LineTextGeometry {
            val text = AnnotatedString(line.text)
            val constraints = layout.layoutInput.constraints
            val dimLayout = measurer.measure(
                text = text,
                style = style.copy(color = DimColor),
                constraints = constraints,
            )
            val brightLayout = measurer.measure(
                text = text,
                style = style.copy(color = BrightColor),
                constraints = constraints,
            )
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
                    val visualLine = layout.getLineForOffset(letter.charOffset)
                    LetterText(
                        letter = letter,
                        box = box,
                        width = box.width,
                        lineIndex = visualLine,
                    )
                }
            }
            val wordLeft = line.words.mapIndexed { wi, _ ->
                wordLetters[wi].filter { it.width > 0f }.minOfOrNull { it.box.left } ?: 0f
            }
            val wordRight = line.words.mapIndexed { wi, _ ->
                wordLetters[wi].filter { it.width > 0f }.maxOfOrNull { it.box.right } ?: 0f
            }
            val wordTop = line.words.mapIndexed { wi, _ ->
                wordLetters[wi].filter { it.width > 0f }.minOfOrNull { it.box.top } ?: 0f
            }
            val wordBottom = line.words.mapIndexed { wi, _ ->
                wordLetters[wi].filter { it.width > 0f }.maxOfOrNull { it.box.bottom } ?: 0f
            }

            // The previous base pass treated every timed word as an X-only interval.
            // Once Compose wrapped a lyric into 2+ rows, a word on row 2 therefore cut
            // the same horizontal band out of row 1 (and vice versa). Build the inverse
            // clip regions per visual text row instead.
            val wordIntervalsByLine = Array(layout.lineCount) { mutableListOf<Pair<Float, Float>>() }
            wordLetters.forEach { letters ->
                letters
                    .filter { it.width > 0f }
                    .groupBy { it.lineIndex }
                    .forEach { (lineIndex, rowLetters) ->
                        if (lineIndex in wordIntervalsByLine.indices) {
                            val left = rowLetters.minOf { it.box.left }
                            val right = rowLetters.maxOf { it.box.right }
                            if (right > left) wordIntervalsByLine[lineIndex] += left to right
                        }
                    }
            }
            val baseTextSegments = buildList {
                for (lineIndex in 0 until layout.lineCount) {
                    val top = layout.getLineTop(lineIndex)
                    val bottom = layout.getLineBottom(lineIndex)
                    if (bottom <= top) continue
                    var cursor = 0f
                    wordIntervalsByLine[lineIndex]
                        .sortedBy { it.first }
                        .forEach { (left, right) ->
                            if (left > cursor) add(Rect(cursor, top, left, bottom))
                            cursor = maxOf(cursor, right)
                        }
                    if (cursor < layout.size.width) {
                        add(Rect(cursor, top, layout.size.width.toFloat(), bottom))
                    }
                }
            }
            return LineTextGeometry(
                layout = layout,
                dimLayout = dimLayout,
                brightLayout = brightLayout,
                wordLetters = wordLetters,
                wordLeft = wordLeft,
                wordRight = wordRight,
                wordTop = wordTop,
                wordBottom = wordBottom,
                wordRtl = wordRtl,
                wordLetterCapable = wordLetterCapable,
                wordAnimationEndMs = wordAnimationEndMs,
                baseTextSegments = baseTextSegments,
            )
        }
    }
}

/** One grapheme cluster of a timed word: its glyph box from the full-line layout. */
internal class LetterText(
    val letter: Letter,
    /** Glyph cluster box in line-local coordinates (living in `geometry.layout`). */
    val box: Rect,
    val width: Float,
    /** Visual row in the wrapped TextLayoutResult that owns this cluster. */
    val lineIndex: Int,
)

/**
 * Native effects around immutable, already-shaped Compose paragraphs.
 *
 * The expensive/leaky path was repeatedly calling drawText with a new brush/shadow,
 * which dirties the paragraph foreground and creates fresh native paragraph state.
 * Here the paragraph itself is never mutated: we only draw the cached dim/bright layouts
 * and use Skia layers as masks/effects around those draws.
 */
private class NativeTextEffects : AutoCloseable {
    private val layerPaint = SkiaPaint().apply { isAntiAlias = true }
    private val maskPaint = SkiaPaint().apply {
        isAntiAlias = true
        blendMode = BlendMode.DST_IN
    }

    fun drawBrightSweep(
        canvas: org.jetbrains.skia.Canvas,
        bounds: SkiaRect,
        startX: Float,
        endX: Float,
        t: Float,
        drawBright: () -> Unit,
    ) {
        if (t <= -GLYPH_FADE_FRACTION) return
        if (t >= 1f) {
            drawBright()
            return
        }

        val p1 = t.coerceIn(0f, 1f)
        val p2 = (t + GLYPH_FADE_FRACTION).coerceIn(0f, 1f)
        // BrightLayout is already alpha 0xD9. Over the dim 0x59 base, ~90.6% layer
        // opacity lands back at the intended 0xD9 composite alpha rather than over-brightening.
        val brightOverlayAlpha = 0xE7
        val colors = intArrayOf(
            (brightOverlayAlpha shl 24) or 0x00FFFFFF,
            (brightOverlayAlpha shl 24) or 0x00FFFFFF,
            0x00FFFFFF,
            0x00FFFFFF,
        )
        val positions = floatArrayOf(0f, p1, p2, 1f)
        Shader.makeLinearGradient(
            Point(startX, 0f),
            Point(endX, 0f),
            colors,
            positions,
        ).use { shader ->
            canvas.saveLayer(bounds, layerPaint)
            try {
                drawBright()
                maskPaint.shader = shader
                canvas.drawRect(bounds, maskPaint)
                maskPaint.shader = null
            } finally {
                canvas.restore()
                maskPaint.shader = null
            }
        }
    }

    fun drawGlow(
        canvas: org.jetbrains.skia.Canvas,
        bounds: SkiaRect,
        sigma: Float,
        argb: Int,
        drawSource: () -> Unit,
    ) {
        if (sigma <= 0f || (argb ushr 24) == 0) return
        ImageFilter.makeDropShadowOnly(0f, 0f, sigma, sigma, argb).use { glow ->
            layerPaint.imageFilter = glow
            canvas.saveLayer(bounds, layerPaint)
            try {
                drawSource()
            } finally {
                canvas.restore()
                layerPaint.imageFilter = null
            }
        }
    }

    override fun close() {
        layerPaint.close()
        maskPaint.close()
    }
}

private fun whiteArgb(alpha: Float): Int =
    ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or 0x00FFFFFF

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

    fun retainRange(range: IntRange) {
        entries.keys.removeAll { it !in range }
    }

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
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit = {},
    isPlaying: Boolean = true,
) {
    LyricScene(
        lyrics = lyrics,
        positionMsState = positionMsState,
        modifier = modifier.fillMaxSize(),
        onSeek = onSeek,
        isPlaying = isPlaying,
    )
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
    val sceneItems = remember(lyrics) { buildLyricsSceneItems(lyrics) }
    val vocalSceneIndices = remember(sceneItems, lyrics.lines.size) {
        IntArray(lyrics.lines.size) { -1 }.also { indices ->
            sceneItems.forEachIndexed { sceneIndex, item ->
                if (item is LyricsSceneItem.Vocal && item.lineIndex in indices.indices) {
                    indices[item.lineIndex] = sceneIndex
                }
            }
        }
    }

    val anchorState = remember(lyrics) { mutableFloatStateOf(0f) }
    val activeSceneIndexState = remember(lyrics) { mutableIntStateOf(0) }
    val activeVocalLineIndicesState = remember(lyrics) { mutableStateOf<Set<Int>>(emptySet()) }
    val activeVocalLineIndexState = remember(lyrics) { mutableIntStateOf(0) }
    val scrollAnchorLineState = remember(lyrics) { mutableIntStateOf(-1) }
    val positionState = remember(lyrics) { mutableLongStateOf(0L) }

    val anchorSpring = remember(lyrics) { Spring(0.0, ANCHOR_FREQ, ANCHOR_DAMPING) }

    LaunchedEffect(lyrics, sceneItems, isPlaying) {
        var lastFrame = 0L
        var anchorWasInterlude = false
        // Reused every frame so overlap tracking does not allocate a collection at 60/120 Hz.
        val activeVocalMembership = BooleanArray(lyrics.lines.size)
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

                    var activeMembershipChanged = false
                    lyrics.lines.forEachIndexed { index, line ->
                        val active = isVocalLineActiveAt(interpolatedPos, line)
                        if (activeVocalMembership[index] != active) {
                            activeVocalMembership[index] = active
                            activeMembershipChanged = true
                        }
                    }
                    if (activeMembershipChanged) {
                        activeVocalLineIndicesState.value = buildSet {
                            activeVocalMembership.forEachIndexed { index, active ->
                                if (active) add(index)
                            }
                        }
                    }

                    val newActiveScene = activeSceneIndexAt(interpolatedPos, sceneItems)
                    if (newActiveScene >= 0 && newActiveScene != activeSceneIndexState.intValue) {
                        activeSceneIndexState.intValue = newActiveScene
                    }

                    val anchorTargetScene = when (val activeItem = sceneItems.getOrNull(newActiveScene)) {
                        is LyricsSceneItem.Interlude -> {
                            // During a real instrumental gap the dots become the focal scene item.
                            // Prewarm the lyric that will arrive after them while they animate.
                            activeVocalLineIndexState.intValue = activeItem.nextLineIndex
                            anchorWasInterlude = true
                            newActiveScene
                        }
                        is LyricsSceneItem.Vocal -> {
                            val activeLine = activeItem.lineIndex
                            activeVocalLineIndexState.intValue = activeLine
                            val anchorLine = pinnedScrollAnchorLine(
                                previousAnchorLine = scrollAnchorLineState.intValue,
                                activeLine = activeLine,
                                forceReset = anchorWasInterlude,
                            )
                            scrollAnchorLineState.intValue = anchorLine
                            anchorWasInterlude = false
                            vocalSceneIndices.getOrNull(anchorLine)?.takeIf { it >= 0 }
                                ?: newActiveScene
                        }
                        null -> 0
                    }

                    anchorSpring.setGoal(anchorTargetScene.toDouble())
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
            // This must live in the measured style as well as the Text composables.
            // Otherwise cached geometry uses start-aligned wrapped rows while inactive
            // Text uses centered rows, making a line jump sideways exactly when active.
            textAlign = TextAlign.Center,
            // Content-based BiDi: Hebrew lines lay out right-to-left, English lines
            // left-to-right, mixed lines get per-run direction — never forced by locale.
            textDirection = TextDirection.Content,
        )
    }
    val translationStyle = MaterialTheme.typography.bodyLarge.copy(
        color = Color.White.copy(alpha = 0.65f),
        fontWeight = FontWeight.Medium,
    )

    // Lines are deliberately placed above/below the viewport as the active line moves.
    // Without clipping, those off-screen composables can extend into the lyrics chrome and
    // still participate in hit testing, so clicks on playback/search controls seek a lyric
    // underneath instead. Keep both drawing and pointer hit-testing inside this viewport.
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val paddingPx = with(density) { HORIZONTAL_PADDING.roundToPx() }
        val contentWidthPx = (constraints.maxWidth - 2 * paddingPx).coerceAtLeast(0)
        // Keep a couple of lyric rows mounted just outside the clipped viewport so
        // their layers are ready before they slide into view. Lines farther away are
        // still measured (the anchor math needs their heights), but are not placed and
        // therefore don't need live graphics/blur layers while completely invisible.
        val placementOverscanPx = with(density) { (LYRIC_LINE_HEIGHT.toPx() * 2f).roundToInt() }

        // Building a line's layout + glyph boxes on the exact frame it becomes active
        // can stall the animation. Keep only a small moving window around the current
        // line prewarmed instead of retaining every line in the song for the lifetime
        // of the lyrics screen.
        val textMeasurer = rememberTextMeasurer()
        val geometryCache = remember(lyrics, textStyle, contentWidthPx) {
            LineGeometryCache(textMeasurer, textStyle, contentWidthPx)
        }
        LaunchedEffect(lyrics, textStyle, contentWidthPx, geometryCache) {
            val all = lyrics.lines
            snapshotFlow { activeVocalLineIndexState.intValue }
                .collectLatest { active ->
                    if (all.isEmpty()) return@collectLatest
                    val clamped = active.coerceIn(0, all.lastIndex)
                    val first = (clamped - GEOMETRY_PREWARM_BEHIND).coerceAtLeast(0)
                    val last = (clamped + GEOMETRY_PREWARM_AHEAD).coerceAtMost(all.lastIndex)
                    geometryCache.retainRange(first..last)

                    // Current/next lines first, then the small look-behind window. One
                    // geometry build per frame keeps the UI responsive while ensuring
                    // sequential playback normally reaches an already-shaped line.
                    val order = (clamped..last) + (first until clamped)
                    for (next in order) {
                        withFrameNanos {
                            if (geometryCache.get(next) == null) {
                                geometryCache.geometryFor(all, next)
                            }
                        }
                    }
                }
        }

        Layout(
            content = {
                sceneItems.forEachIndexed { sceneIndex, item ->
                    when (item) {
                        is LyricsSceneItem.Vocal -> {
                            val line = lyrics.lines[item.lineIndex]
                            val activeSceneIndex = activeSceneIndexState.value
                            val activeItem = sceneItems.getOrNull(activeSceneIndex)
                            val activeVocalLines = activeVocalLineIndicesState.value
                            val focusDistance = when {
                                activeVocalLines.isNotEmpty() ->
                                    activeVocalLines.minOf { activeLine -> abs(item.lineIndex - activeLine) }
                                activeItem is LyricsSceneItem.Vocal ->
                                    abs(item.lineIndex - activeItem.lineIndex)
                                else -> abs(sceneIndex - activeSceneIndex)
                            }
                            LyricLineContent(
                                lineIndex = item.lineIndex,
                                lines = lyrics.lines,
                                line = line,
                                isActive = item.lineIndex in activeVocalLines,
                                focusDistance = focusDistance,
                                positionState = positionState,
                                textStyle = textStyle,
                                translationStyle = translationStyle,
                                contentWidthPx = contentWidthPx,
                                geometryCache = geometryCache,
                                onSeek = onSeek,
                                isPlaying = isPlaying,
                            )
                        }
                        is LyricsSceneItem.Interlude -> InterludeDots(
                            interlude = item,
                            positionState = positionState,
                            isPlaying = isPlaying,
                            isActive = sceneIndex == activeSceneIndexState.value,
                        )
                    }
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
            var hasVisibleItem = false
            for (i in placeables.indices) {
                val h = placeables[i].height.toFloat()
                if (h <= 0f) {
                    centers[i] = cursor
                    continue
                }
                if (hasVisibleItem) cursor += gapPx
                centers[i] = cursor + h / 2f
                cursor += h
                hasVisibleItem = true
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
                    val bottom = top + p.height
                    if (
                        bottom >= -placementOverscanPx &&
                        top <= constraints.maxHeight + placementOverscanPx
                    ) {
                        p.placeRelative(paddingPx, top)
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------------- */
/*  One lyric line                                                                     */
/* ---------------------------------------------------------------------------------- */

private class DotAnimationState {
    val scale = Spring(dotScaleCurve(0f).toDouble(), DOT_SCALE_FREQ, DOT_SCALE_DAMPING)
    val yOffset = Spring(dotYOffsetCurve(0f).toDouble(), DOT_Y_FREQ, DOT_Y_DAMPING)
    val glow = Spring(dotGlowCurve(0f).toDouble(), DOT_GLOW_FREQ, DOT_GLOW_DAMPING)
    val opacity = Spring(dotOpacityCurve(0f).toDouble(), DOT_OPACITY_FREQ, DOT_OPACITY_DAMPING)
}

private class InterludeFrameRuntime(var positionMs: Long)

/**
 * Three-dot musical break copied from SpicyLyrics' timing model: the gap is split into
 * thirds, the dots spring in one after another, then the whole group collapses during
 * the final 500 ms before vocals return. Inactive interludes measure at zero height.
 */
@Composable
private fun InterludeDots(
    interlude: LyricsSceneItem.Interlude,
    positionState: State<Long>,
    isPlaying: Boolean,
    isActive: Boolean,
) {
    if (!isActive) {
        Box(Modifier.fillMaxWidth().height(0.dp))
        return
    }

    val density = LocalDensity.current
    val fontPx = with(density) { LYRIC_FONT_SIZE.toPx() }
    val effectiveEndMs = remember(interlude) {
        (interlude.endMs - INTERLUDE_TAIL_PADDING_MS).coerceAtLeast(interlude.startMs + 3L)
    }
    val dotTimes = remember(interlude, effectiveEndMs) {
        val duration = (effectiveEndMs - interlude.startMs).coerceAtLeast(3L)
        List(3) { i ->
            val start = interlude.startMs + duration * i / 3
            val end = interlude.startMs + duration * (i + 1) / 3
            start to end.coerceAtLeast(start + 1L)
        }
    }
    val dots = remember(interlude) { List(3) { DotAnimationState() } }
    val groupScale = remember(interlude) {
        Spring(1.0, DOT_GROUP_SCALE_FREQ, DOT_GROUP_SCALE_DAMPING)
    }
    val runtime = remember(interlude) { InterludeFrameRuntime(positionState.value) }
    val drawTick = remember(interlude) { mutableIntStateOf(0) }

    LaunchedEffect(interlude, isPlaying) {
        var lastFrame = 0L
        while (true) {
            withFrameNanos { now ->
                val elapsedNanos = if (lastFrame == 0L) 16_666_667L else now - lastFrame
                val dt = (elapsedNanos / 1e9).coerceIn(0.001, 0.10)
                lastFrame = now

                val pos = positionState.value
                val positionChanged = pos != runtime.positionMs
                runtime.positionMs = pos
                val snap = !isPlaying

                val hiding = pos >= interlude.endMs - INTERLUDE_PREHIDE_MS
                groupScale.setGoal(if (hiding) 0.0 else 1.0, snap = snap)
                advanceSpring(groupScale, dt, isPlaying)

                dots.forEachIndexed { index, animation ->
                    val (startMs, endMs) = dotTimes[index]
                    val state = wordStateAt(pos, startMs, endMs)
                    val progress = progressOf(pos, startMs, endMs)
                    val curveProgress = when (state) {
                        WordState.NOT_SUNG -> 0f
                        WordState.ACTIVE -> progress
                        WordState.SUNG -> 1f
                    }

                    animation.scale.setGoal(dotScaleCurve(curveProgress).toDouble(), snap = snap)
                    animation.yOffset.setGoal(dotYOffsetCurve(curveProgress).toDouble(), snap = snap)
                    animation.glow.setGoal(dotGlowCurve(curveProgress).toDouble(), snap = snap)
                    animation.opacity.setGoal(dotOpacityCurve(curveProgress).toDouble(), snap = snap)
                    advanceSpring(animation.scale, dt, isPlaying)
                    advanceSpring(animation.yOffset, dt, isPlaying)
                    advanceSpring(animation.glow, dt, isPlaying)
                    advanceSpring(animation.opacity, dt, isPlaying)
                }

                if (isPlaying || positionChanged) drawTick.intValue++
            }
        }
    }

    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        drawTick.intValue
        val baseRadius = 7.5.dp.toPx()
        val gap = 9.dp.toPx()
        val step = baseRadius * 2f + gap
        val group = groupScale.snapshotPosition().toFloat().coerceIn(0f, 1.15f)

        dots.forEachIndexed { index, animation ->
            val scale = animation.scale.snapshotPosition().toFloat().coerceAtLeast(0f) * group
            val opacity = animation.opacity.snapshotPosition().toFloat().coerceIn(0f, 1f) * group
            val glow = animation.glow.snapshotPosition().toFloat().coerceIn(0f, 1.15f) * group
            val radius = baseRadius * scale
            if (radius <= 0.1f || opacity <= 0.001f) return@forEachIndexed

            val center = Offset(
                x = size.width / 2f + (index - 1) * step,
                y = size.height / 2f + animation.yOffset.snapshotPosition().toFloat() * fontPx,
            )

            // A couple of translucent bloom discs approximate SpicyLyrics' shadow glow
            // without allocating a native blur filter every frame.
            if (glow > 0.01f) {
                drawCircle(
                    color = Color.White.copy(alpha = (0.08f * glow * opacity).coerceIn(0f, 0.16f)),
                    radius = radius * 2.6f,
                    center = center,
                )
                drawCircle(
                    color = Color.White.copy(alpha = (0.16f * glow * opacity).coerceIn(0f, 0.28f)),
                    radius = radius * 1.75f,
                    center = center,
                )
            }
            drawCircle(
                color = Color.White.copy(alpha = (0.90f * opacity).coerceIn(0f, 1f)),
                radius = radius,
                center = center,
            )
        }
    }
}

@Composable
private fun LyricLineContent(
    lineIndex: Int,
    lines: List<CharLine>,
    line: CharLine,
    isActive: Boolean,
    focusDistance: Int,
    positionState: State<Long>,
    textStyle: TextStyle,
    translationStyle: TextStyle,
    contentWidthPx: Int,
    geometryCache: LineGeometryCache?,
    onSeek: (Long) -> Unit,
    isPlaying: Boolean,
) {
    val blurRadius = blurPxFor(focusDistance).dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                // Visual focus follows the actually sung scene item. Scroll position is
                // intentionally independent, so a current lyric can stay lower on screen.
                val d = focusDistance.toFloat()
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
                index = lineIndex,
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
    val nativeTextEffects = remember { NativeTextEffects() }
    DisposableEffect(nativeTextEffects) {
        onDispose { nativeTextEffects.close() }
    }

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
    val emphasisProfiles = remember(line.words) {
        line.words.map { word -> letterEmphasisProfile(word.endMs - word.startMs) }
    }
    val runtime = remember(line) { InlineFrameRuntime(positionState.value) }
    val drawTick = remember(line) { mutableIntStateOf(0) }

    LaunchedEffect(line, isPlaying, geometry) {
        var lastFrame = 0L
        while (true) {
            withFrameNanos { now ->
                val elapsedNanos = if (lastFrame == 0L) 16_666_667L else now - lastFrame
                val dt = (elapsedNanos / 1e9).coerceIn(0.001, 0.10)
                lastFrame = now

                val pos = positionState.value
                val positionChanged = pos != runtime.positionMs
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
                    val emphasis = emphasisProfiles[wordIndex]
                    val activeLetterIndex = if (state == WordState.ACTIVE) {
                        wordLetters.indexOfFirst { pos >= it.startMs && pos < it.endMs }
                    } else -1
                    val activeLetterProgress = if (activeLetterIndex >= 0) {
                        val letter = wordLetters[activeLetterIndex]
                        progressOf(pos, letter.startMs, letter.endMs)
                    } else 0f

                    val activeScale = emphasizedLetterScale(
                        letterScaleCurve(activeLetterProgress),
                        emphasis,
                    )
                    val activeY = letterYOffsetCurve(activeLetterProgress) * emphasis.yMultiplier
                    val activeGlow = (glowCurve(activeLetterProgress) * emphasis.glowMultiplier)
                        .coerceAtMost(1.25f)
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
                                    val falloff = (
                                        1f / (1f + distance.toDouble().pow(emphasis.falloffExponent))
                                    ).toFloat()
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

                // While playing, draw invalidation happens AFTER every spring has
                // advanced. When paused the springs intentionally do not advance, so
                // repeatedly invalidating the exact same frame only recreates Skia
                // gradient/shadow state for no visual change. A paused seek still
                // redraws immediately because the player position changed.
                if (isPlaying || positionChanged) {
                    drawTick.intValue++
                }
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
                    nativeTextEffects = nativeTextEffects,
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
    nativeTextEffects: NativeTextEffects,
) {
    val layout = geometry.layout
    val lineOffsetX = ((size.width - layout.size.width) / 2f).coerceAtLeast(0f)
    val topLeft = Offset(lineOffsetX, 0f)
    val native = drawContext.canvas.nativeCanvas

    // Paint untimed text through row-aware clip rectangles. An X-only mask corrupts
    // wrapped lyrics because the same X coordinate can contain different words on
    // different visual rows.
    geometry.baseTextSegments.forEach { segment ->
        clipRect(
            lineOffsetX + segment.left,
            segment.top,
            lineOffsetX + segment.right,
            segment.bottom,
        ) {
            drawText(geometry.dimLayout, topLeft = topLeft)
        }
    }

    fun beginWordTransform(index: Int): Boolean {
        val left = geometry.wordLeft[index]
        val right = geometry.wordRight[index]
        val top = geometry.wordTop[index]
        val bottom = geometry.wordBottom[index]
        if (right <= left || bottom <= top) return false
        val animation = animations[index]
        val centerX = lineOffsetX + (left + right) / 2f
        // Scale/bounce around the word's actual visual row. Using half the paragraph
        // height is only correct for single-row lyrics; on row 2 it makes the glyphs
        // orbit around row 1 during every spring update.
        val centerY = (top + bottom) / 2f
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

    fun drawStaticWord(index: Int, bright: Boolean) {
        val left = geometry.wordLeft[index]
        val right = geometry.wordRight[index]
        val top = geometry.wordTop[index]
        val bottom = geometry.wordBottom[index]
        if (!beginWordTransform(index)) return
        clipRect(lineOffsetX + left, top, lineOffsetX + right, bottom) {
            drawText(if (bright) geometry.brightLayout else geometry.dimLayout, topLeft = topLeft)
        }
        native.restore()
    }

    fun drawProgressiveRegion(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        t: Float,
        rtl: Boolean,
        glow: Float,
    ) {
        if (right <= left || bottom <= top) return

        fun drawDim() {
            clipRect(left, top, right, bottom) {
                drawText(geometry.dimLayout, topLeft = topLeft)
            }
        }

        fun drawBright() {
            clipRect(left, top, right, bottom) {
                drawText(geometry.brightLayout, topLeft = topLeft)
            }
        }

        if (glow > 0.01f) {
            val pad = 24f
            val glowBounds = SkiaRect(left - pad, top - pad, right + pad, bottom + pad)
            clipRect(glowBounds.left, glowBounds.top, glowBounds.right, glowBounds.bottom) {
                nativeTextEffects.drawGlow(
                    canvas = native,
                    bounds = glowBounds,
                    sigma = 4f + 12f * glow,
                    argb = whiteArgb((glow * 0.82f).coerceIn(0f, 0.92f)),
                ) {
                    drawBright()
                }
            }
        }

        when {
            t <= -GLYPH_FADE_FRACTION -> drawDim()
            t >= 1f -> drawBright()
            else -> {
                drawDim()
                nativeTextEffects.drawBrightSweep(
                    canvas = native,
                    bounds = SkiaRect(left, top, right, bottom),
                    startX = if (rtl) right else left,
                    endX = if (rtl) left else right,
                    t = t,
                ) {
                    drawBright()
                }
            }
        }
    }

    words.forEachIndexed wordLoop@ { index, word ->
        if (index !in geometry.wordLetters.indices || index !in animations.indices) return@wordLoop
        val endMs = geometry.wordAnimationEndMs[index]
        val state = wordStateAt(positionMs, word.startMs, endMs)
        val letterCapable = geometry.wordLetterCapable[index]

        when (state) {
            WordState.NOT_SUNG -> drawStaticWord(index, bright = false)
            WordState.SUNG -> drawStaticWord(index, bright = true)
            WordState.ACTIVE -> {
                val animation = animations[index]
                val left = geometry.wordLeft[index]
                val right = geometry.wordRight[index]
                val wordTop = geometry.wordTop[index]
                val wordBottom = geometry.wordBottom[index]
                if (right <= left || wordBottom <= wordTop) return@wordLoop

                if (!beginWordTransform(index)) return@wordLoop
                if (!letterCapable) {
                    val progress = progressOf(positionMs, word.startMs, endMs)
                    val t = -GLYPH_FADE_FRACTION + (1f + GLYPH_FADE_FRACTION) * progress
                    drawProgressiveRegion(
                        left = lineOffsetX + left,
                        top = wordTop,
                        right = lineOffsetX + right,
                        bottom = wordBottom,
                        t = t,
                        rtl = geometry.wordRtl[index],
                        glow = animation.glow.snapshotPosition().toFloat(),
                    )
                } else {
                        // Letter-capable words reach this path. The word wrapper keeps its
                        // own spring while each glyph adds duration-scaled scale/Y/glow,
                        // and only the actually sung letter moves the highlight sweep.
                        val letters = geometry.wordLetters[index]
                        val activeLetterIndex = letters.indexOfFirst { lt ->
                            positionMs >= lt.letter.startMs && positionMs < lt.letter.endMs
                        }

                        letters.forEachIndexed letterLoop@ { letterIndex, letterText ->
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
                            val bandCenterY = (letterText.box.top + letterText.box.bottom) / 2f
                            val letterGlow = spring.glow.snapshotPosition().toFloat()
                            val letterScale = spring.scale.snapshotPosition().toFloat()

                            native.save()
                            native.translate(bandCenterX, bandCenterY)
                            native.scale(letterScale, letterScale)
                            native.translate(
                                -bandCenterX,
                                -bandCenterY + spring.yOffset.snapshotPosition().toFloat() * 2f,
                            )
                            drawProgressiveRegion(
                                left = bandLeft,
                                top = letterText.box.top,
                                right = bandRight,
                                bottom = letterText.box.bottom,
                                t = t,
                                rtl = false,
                                glow = letterGlow,
                            )
                            native.restore()
                        }
                    }
                native.restore()
            }
        }
    }
}
