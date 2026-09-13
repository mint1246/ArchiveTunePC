/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import moe.rukamori.archivetune.lyrics.LyricsGraphemeSpringState
import moe.rukamori.archivetune.lyrics.LyricsInlineSpringState
import moe.rukamori.archivetune.lyrics.LyricsLetterEmphasisProfile
import moe.rukamori.archivetune.lyrics.LyricsMotionPhysics
import moe.rukamori.archivetune.lyrics.LyricsTextGrapheme
import moe.rukamori.archivetune.lyrics.LyricsTextTimeline
import moe.rukamori.archivetune.lyrics.LyricsTextWord
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val SHAPED_GLYPH_FADE_FRACTION = 0.2f
private const val SHAPED_BRIGHT_OVERLAY_ALPHA = 0xE7 / 255f
private const val SHAPED_SUNG_GLYPH_GLOW = 0.80f
private const val SHAPED_SUNG_WORD_GLOW = 0.65f
internal val SHAPED_HORIZONTAL_OVERSCAN = 24.dp
private val SHAPED_GLOW_TIGHT_RADIUS = 8.dp
private val SHAPED_GLOW_WIDE_RADIUS = 22.dp
private val SHAPED_GLOW_LAYER_OVERSCAN = 72.dp

/**
 * Android's full-line counterpart to desktop SpicyPane's LineTextGeometry/ActiveLineText.
 *
 * The provider's [LyricsTextTimeline.displayText] is measured exactly once. All timed word,
 * grapheme, wrapping and BiDi geometry is then read back from that one [TextLayoutResult].
 * Animation redraws clipped pieces of that same shaped paragraph; graphemes are never measured
 * independently, so contextual Arabic/Hebrew shaping, ligatures and provider whitespace cannot
 * change when a timed region becomes active.
 */
@Composable
internal fun ShapedLyricsLine(
    timeline: LyricsTextTimeline,
    currentPositionMs: Long,
    isPlaying: Boolean,
    textStyle: TextStyle,
    textColor: Color,
    bounceFactor: Float,
    glowFactor: Float,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val boundedWidth = constraints.maxWidth != Constraints.Infinity
        val horizontalOverscanPx = with(density) { SHAPED_HORIZONTAL_OVERSCAN.roundToPx() }
        // LyricsV2 already supplies 12 dp of row padding. Reserving another 24 dp here
        // gives the animated paragraph the same effective 36 dp breathing room as the
        // desktop scene, so a 1.05–1.30x spring cannot hit the canvas edge.
        val requestedWidthPx =
            if (boundedWidth) {
                (constraints.maxWidth - horizontalOverscanPx * 2).coerceAtLeast(1)
            } else {
                null
            }
        val layout =
            remember(timeline.displayText, textStyle, requestedWidthPx, textMeasurer) {
                measureAuthoritativeLine(
                    measurer = textMeasurer,
                    text = timeline.displayText,
                    style = textStyle,
                    exactWidthPx = requestedWidthPx,
                )
            }
        val geometry = remember(timeline, layout) { ShapedLyricsLineGeometry.build(layout, timeline) }
        val motion = remember(geometry) { ShapedLyricsLineMotion(geometry) }
        val fontSizePx = with(density) { textStyle.fontSize.toPx() }
        val latestPosition by rememberUpdatedState(currentPositionMs)
        val pausedPositionKey = if (isPlaying) Long.MIN_VALUE else currentPositionMs
        var drawTick by remember(geometry) { mutableIntStateOf(0) }
        val glowTightRadiusPx = with(density) { SHAPED_GLOW_TIGHT_RADIUS.toPx() }
        val glowWideRadiusPx = with(density) { SHAPED_GLOW_WIDE_RADIUS.toPx() }
        val glowLayerOverscanPx = with(density) { SHAPED_GLOW_LAYER_OVERSCAN.roundToPx() }
        val contentCanvasWidthPx =
            if (boundedWidth) constraints.maxWidth.coerceAtLeast(1) else layout.size.width.coerceAtLeast(1)
        val contentCanvasHeightPx = layout.size.height.coerceAtLeast(1)
        val tightGlowRenderEffect =
            remember(glowTightRadiusPx) {
                BlurEffect(
                    radiusX = glowTightRadiusPx,
                    radiusY = glowTightRadiusPx,
                    edgeTreatment = TileMode.Decal,
                ).takeIf(RenderEffect::isSupported)
            }
        val wideGlowRenderEffect =
            remember(glowWideRadiusPx) {
                BlurEffect(
                    radiusX = glowWideRadiusPx,
                    radiusY = glowWideRadiusPx,
                    edgeTreatment = TileMode.Decal,
                ).takeIf(RenderEffect::isSupported)
            }

        LaunchedEffect(motion, isPlaying, pausedPositionKey) {
            if (!isPlaying) {
                motion.update(positionMs = latestPosition, dtSeconds = 0.0, playing = false)
                drawTick++
                return@LaunchedEffect
            }

            var lastFrameNanos = 0L
            while (isActive) {
                withFrameNanos { frameNanos ->
                    val elapsedNanos =
                        if (lastFrameNanos == 0L) {
                            16_666_667L
                        } else {
                            frameNanos - lastFrameNanos
                        }
                    lastFrameNanos = frameNanos
                    motion.update(
                        positionMs = latestPosition,
                        dtSeconds = (elapsedNanos / 1e9).coerceIn(0.001, 0.10),
                        playing = true,
                    )
                    drawTick++
                }
            }
        }

        val canvasModifier =
            if (boundedWidth) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.width(with(density) { layout.size.width.toDp() })
            }.height(with(density) { layout.size.height.toDp() })

        // Render glow on its own blurred layer. Blurring the completed glyph layer matches
        // desktop's DropShadowOnly path; blurring a saveLayer paint does not.
        if (wideGlowRenderEffect != null) {
            Canvas(
                modifier =
                    Modifier
                        .glowLayerOverscan(
                            contentWidthPx = contentCanvasWidthPx,
                            contentHeightPx = contentCanvasHeightPx,
                            overscanPx = glowLayerOverscanPx,
                        ).graphicsLayer {
                            renderEffect = wideGlowRenderEffect
                            clip = false
                        },
            ) {
                drawTick
                drawShapedLyricsGlow(
                    geometry = geometry,
                    motion = motion,
                    positionMs = currentPositionMs,
                    textColor = textColor,
                    fontSizePx = fontSizePx,
                    bounceFactor = bounceFactor.coerceAtLeast(0f),
                    glowFactor = glowFactor.coerceAtLeast(0f),
                    sourceAlphaMultiplier = 1.15f,
                    contentOffsetY = glowLayerOverscanPx.toFloat(),
                )
            }
        }

        if (tightGlowRenderEffect != null) {
            Canvas(
                modifier =
                    Modifier
                        .glowLayerOverscan(
                            contentWidthPx = contentCanvasWidthPx,
                            contentHeightPx = contentCanvasHeightPx,
                            overscanPx = glowLayerOverscanPx,
                        ).graphicsLayer {
                            renderEffect = tightGlowRenderEffect
                            clip = false
                        },
            ) {
                drawTick
                drawShapedLyricsGlow(
                    geometry = geometry,
                    motion = motion,
                    positionMs = currentPositionMs,
                    textColor = textColor,
                    fontSizePx = fontSizePx,
                    bounceFactor = bounceFactor.coerceAtLeast(0f),
                    glowFactor = glowFactor.coerceAtLeast(0f),
                    sourceAlphaMultiplier = 1.50f,
                    contentOffsetY = glowLayerOverscanPx.toFloat(),
                )
            }
        }

        Canvas(modifier = canvasModifier) {
            // drawTick is intentionally observed only by the draw node. Spring frames therefore
            // invalidate pixels without rebuilding the text layout or recomposing the line tree.
            drawTick
            drawShapedLyricsLine(
                geometry = geometry,
                motion = motion,
                positionMs = currentPositionMs,
                textColor = textColor,
                fontSizePx = fontSizePx,
                bounceFactor = bounceFactor.coerceAtLeast(0f),
            )
        }
    }
}

private fun measureAuthoritativeLine(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    exactWidthPx: Int?,
): TextLayoutResult {
    val textConstraints =
        if (exactWidthPx != null) {
            Constraints(maxWidth = exactWidthPx)
        } else {
            Constraints()
        }
    return measurer.measure(
        text = AnnotatedString(text),
        style = style,
        softWrap = true,
        constraints = textConstraints,
    )
}

internal data class ShapedLyricsGraphemeGeometry(
    val grapheme: LyricsTextGrapheme,
    val box: Rect,
    val lineIndex: Int,
    val rtl: Boolean,
)

internal data class ShapedLyricsWordGeometry(
    val word: LyricsTextWord,
    val graphemes: List<ShapedLyricsGraphemeGeometry>,
    val rowBands: List<Rect>,
    val bounds: Rect?,
    val rtl: Boolean,
    val graphemeMotion: Boolean,
)

internal class ShapedLyricsLineGeometry private constructor(
    val layout: TextLayoutResult,
    val words: List<ShapedLyricsWordGeometry>,
    val baseTextSegments: List<Rect>,
) {
    companion object {
        fun build(
            layout: TextLayoutResult,
            timeline: LyricsTextTimeline,
        ): ShapedLyricsLineGeometry {
            val textLength = timeline.displayText.length
            val words =
                timeline.words.map { word ->
                    val graphemes =
                        word.graphemes.mapNotNull { grapheme ->
                            if (grapheme.startUtf16 !in 0 until textLength) return@mapNotNull null
                            val safeEnd = grapheme.endUtf16.coerceIn(grapheme.startUtf16 + 1, textLength)
                            val box = clusterBox(layout, timeline.displayText, grapheme.startUtf16, safeEnd)
                            ShapedLyricsGraphemeGeometry(
                                grapheme = grapheme,
                                box = box,
                                lineIndex = layout.getLineForOffset(grapheme.startUtf16),
                                rtl =
                                    layout.getBidiRunDirection(grapheme.startUtf16) ==
                                        ResolvedTextDirection.Rtl,
                            )
                        }
                    val visible = graphemes.filter { it.box.width > 0f && it.box.height > 0f }
                    val rowBands =
                        visible
                            .groupBy(ShapedLyricsGraphemeGeometry::lineIndex)
                            .toSortedMap()
                            .values
                            .map { row -> unionRects(row.map(ShapedLyricsGraphemeGeometry::box)) }
                    val bounds = rowBands.takeIf { it.isNotEmpty() }?.let(::unionRects)
                    ShapedLyricsWordGeometry(
                        word = word,
                        graphemes = graphemes,
                        rowBands = rowBands,
                        bounds = bounds,
                        rtl = visible.firstOrNull()?.rtl ?: false,
                        graphemeMotion =
                            word.endMs - word.startMs >= LyricsMotionPhysics.LETTER_ANIMATION_MIN_MS &&
                                graphemes.isNotEmpty(),
                    )
                }

            val intervalsByRow = Array(layout.lineCount) { mutableListOf<Pair<Float, Float>>() }
            words.forEach { word ->
                word.rowBands.forEach { band ->
                    val row = layout.getLineForVerticalPosition((band.top + band.bottom) / 2f)
                    if (row in intervalsByRow.indices && band.right > band.left) {
                        intervalsByRow[row] += band.left to band.right
                    }
                }
            }

            val baseSegments = buildList {
                for (row in 0 until layout.lineCount) {
                    val rowTop = layout.getLineTop(row)
                    val rowBottom = layout.getLineBottom(row)
                    if (rowBottom <= rowTop) continue
                    shapedLyricsInverseSegments(
                        width = layout.size.width.toFloat(),
                        intervals = intervalsByRow[row],
                    ).forEach { (left, right) ->
                        add(Rect(left, rowTop, right, rowBottom))
                    }
                }
            }

            return ShapedLyricsLineGeometry(
                layout = layout,
                words = words,
                baseTextSegments = baseSegments,
            )
        }
    }
}

private fun clusterBox(
    layout: TextLayoutResult,
    text: String,
    startUtf16: Int,
    endUtf16: Int,
): Rect {
    val boxes = mutableListOf<Rect>()
    var offset = startUtf16
    while (offset < endUtf16) {
        boxes += layout.getBoundingBox(offset)
        val codePoint = text.codePointAt(offset)
        offset += Character.charCount(codePoint)
    }
    return unionRects(boxes)
}

private fun unionRects(rectangles: List<Rect>): Rect {
    if (rectangles.isEmpty()) return Rect.Zero
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    rectangles.forEach { rect ->
        left = min(left, rect.left)
        top = min(top, rect.top)
        right = max(right, rect.right)
        bottom = max(bottom, rect.bottom)
    }
    return Rect(left, top, right, bottom)
}

/** Merge timed bands and return the untouched horizontal pieces of a visual layout row. */
internal fun shapedLyricsInverseSegments(
    width: Float,
    intervals: List<Pair<Float, Float>>,
): List<Pair<Float, Float>> {
    if (width <= 0f) return emptyList()
    val normalized =
        intervals
            .mapNotNull { (rawLeft, rawRight) ->
                val left = min(rawLeft, rawRight).coerceIn(0f, width)
                val right = max(rawLeft, rawRight).coerceIn(0f, width)
                if (right > left) left to right else null
            }.sortedBy { it.first }
    if (normalized.isEmpty()) return listOf(0f to width)

    val merged = mutableListOf<Pair<Float, Float>>()
    normalized.forEach { interval ->
        val previous = merged.lastOrNull()
        if (previous == null || interval.first > previous.second) {
            merged += interval
        } else {
            merged[merged.lastIndex] = previous.first to max(previous.second, interval.second)
        }
    }

    return buildList {
        var cursor = 0f
        merged.forEach { (left, right) ->
            if (left > cursor) add(cursor to left)
            cursor = max(cursor, right)
        }
        if (cursor < width) add(cursor to width)
    }
}

private enum class ShapedLyricsWordState { NOT_SUNG, ACTIVE, SUNG }

private fun shapedWordState(
    positionMs: Long,
    startMs: Long,
    endMs: Long,
): ShapedLyricsWordState =
    when {
        positionMs < startMs -> ShapedLyricsWordState.NOT_SUNG
        positionMs >= endMs -> ShapedLyricsWordState.SUNG
        else -> ShapedLyricsWordState.ACTIVE
    }

private fun shapedProgress(
    positionMs: Long,
    startMs: Long,
    endMs: Long,
): Float = ((positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)

private fun shapedEaseSinOut(progress: Float): Float = sin(progress * (PI / 2.0)).toFloat()

internal typealias ShapedLyricsHeldEmphasis = LyricsLetterEmphasisProfile

internal fun shapedLyricsHeldEmphasis(durationMs: Long): ShapedLyricsHeldEmphasis =
    LyricsMotionPhysics.letterEmphasisProfile(durationMs)

internal fun shapedLyricsEmphasizedScale(
    baseScale: Float,
    emphasis: ShapedLyricsHeldEmphasis,
): Float =
    LyricsMotionPhysics.emphasizedLetterScale(baseScale, emphasis)

private class ShapedLyricsWordMotion(
    geometry: ShapedLyricsWordGeometry,
) {
    val word = LyricsInlineSpringState()
    val graphemes =
        if (geometry.graphemeMotion) {
            LyricsGraphemeSpringState(geometry.graphemes.size)
        } else {
            null
        }
}

private class ShapedLyricsLineMotion(
    private val geometry: ShapedLyricsLineGeometry,
) {
    val words = geometry.words.map(::ShapedLyricsWordMotion)

    fun update(
        positionMs: Long,
        dtSeconds: Double,
        playing: Boolean,
    ) {
        geometry.words.forEachIndexed { wordIndex, wordGeometry ->
            val word = wordGeometry.word
            val motion = words[wordIndex]
            val nearWord = LyricsMotionPhysics.shouldAdvanceSpring(positionMs, word.startMs, word.endMs)
            val snap = !playing || !nearWord
            motion.word.setTargets(
                LyricsMotionPhysics.inlineTarget(
                    text = word.text,
                    startMs = word.startMs,
                    endMs = word.endMs,
                    positionMs = positionMs,
                ),
                snap = snap,
            )

            if (wordGeometry.graphemeMotion) {
                motion.graphemes?.setTargets(
                    LyricsMotionPhysics.graphemeMotionTargets(
                        text = word.text,
                        startMs = word.startMs,
                        endMs = word.endMs,
                        positionMs = positionMs,
                    ),
                    snap = snap,
                )
            }

            if (playing && nearWord) {
                motion.word.step(dtSeconds)
                motion.graphemes?.step(dtSeconds)
            }
        }
    }
}

private fun DrawScope.drawShapedLyricsLine(
    geometry: ShapedLyricsLineGeometry,
    motion: ShapedLyricsLineMotion,
    positionMs: Long,
    textColor: Color,
    fontSizePx: Float,
    bounceFactor: Float,
) {
    val layout = geometry.layout
    val lineOffsetX = ((size.width - layout.size.width) / 2f).coerceAtLeast(0f)
    val textTopLeft = Offset(lineOffsetX, 0f)
    val dimColor = textColor.copy(alpha = textColor.alpha * 0.35f)
    val brightColor = textColor.copy(alpha = textColor.alpha * 0.85f)

    geometry.baseTextSegments.forEach { segment ->
        clipRect(lineOffsetX + segment.left, segment.top, lineOffsetX + segment.right, segment.bottom) {
            drawText(textLayoutResult = layout, color = dimColor, topLeft = textTopLeft)
        }
    }

    geometry.words.forEachIndexed wordLoop@ { wordIndex, wordGeometry ->
        val bounds = wordGeometry.bounds ?: return@wordLoop
        val word = wordGeometry.word
        val wordMotion = motion.words[wordIndex]
        val wordSpring = wordMotion.word.snapshot()
        val graphemeSnapshots = wordMotion.graphemes?.snapshot().orEmpty()
        val wordScale = 1f + (wordSpring.scale - 1f) * bounceFactor
        val wordYOffset = wordSpring.yOffsetFraction * fontSizePx.coerceAtLeast(1f) * bounceFactor
        val state = shapedWordState(positionMs, word.startMs, word.endMs)

        withNativeLyricsTransform(
            centerX = lineOffsetX + bounds.center.x,
            centerY = bounds.center.y,
            scale = wordScale,
            yOffset = wordYOffset,
        ) {
            when (state) {
                ShapedLyricsWordState.NOT_SUNG -> {
                    wordGeometry.rowBands.forEach { band ->
                        clipRect(lineOffsetX + band.left, band.top, lineOffsetX + band.right, band.bottom) {
                            drawText(textLayoutResult = layout, color = dimColor, topLeft = textTopLeft)
                        }
                    }
                }

                ShapedLyricsWordState.SUNG -> {
                    wordGeometry.rowBands.forEach { band ->
                        clipRect(lineOffsetX + band.left, band.top, lineOffsetX + band.right, band.bottom) {
                            drawText(textLayoutResult = layout, color = brightColor, topLeft = textTopLeft)
                        }
                    }
                }

                ShapedLyricsWordState.ACTIVE -> {
                    if (!wordGeometry.graphemeMotion) {
                        val progress = shapedProgress(positionMs, word.startMs, word.endMs)
                        val sweep =
                            -SHAPED_GLYPH_FADE_FRACTION +
                                (1f + SHAPED_GLYPH_FADE_FRACTION) * progress
                        wordGeometry.rowBands.forEach { band ->
                            drawProgressiveBand(
                                layout = layout,
                                band = band.shiftX(lineOffsetX),
                                textTopLeft = textTopLeft,
                                sweep = sweep,
                                rtl = wordGeometry.rtl,
                                dimColor = dimColor,
                                brightColor = brightColor,
                            )
                        }
                    } else {
                        val activeIndex =
                            wordGeometry.graphemes.indexOfFirst { geometry ->
                                val timed = geometry.grapheme
                                positionMs >= timed.startMs && positionMs < timed.endMs
                            }
                        wordGeometry.graphemes.forEachIndexed { graphemeIndex, graphemeGeometry ->
                            val band = graphemeGeometry.box
                            if (band.width <= 0f || band.height <= 0f) return@forEachIndexed
                            val timed = graphemeGeometry.grapheme
                            val spring = graphemeSnapshots[graphemeIndex]
                            val graphemeScale = 1f + (spring.scale - 1f) * bounceFactor
                            val graphemeYOffset =
                                shapedLyricsGraphemeYOffsetPx(
                                    yOffsetFraction = spring.yOffsetFraction,
                                    bounceFactor = bounceFactor,
                                )
                            val sweep =
                                when {
                                    positionMs >= timed.endMs -> 1f
                                    positionMs < timed.startMs -> -SHAPED_GLYPH_FADE_FRACTION
                                    graphemeIndex == activeIndex ->
                                        -SHAPED_GLYPH_FADE_FRACTION +
                                            (1f + SHAPED_GLYPH_FADE_FRACTION) *
                                                shapedEaseSinOut(
                                                    shapedProgress(positionMs, timed.startMs, timed.endMs),
                                                )

                                    else -> -SHAPED_GLYPH_FADE_FRACTION
                                }

                            withNativeLyricsTransform(
                                centerX = lineOffsetX + band.center.x,
                                centerY = band.center.y,
                                scale = graphemeScale,
                                yOffset = graphemeYOffset,
                            ) {
                                drawProgressiveBand(
                                    layout = layout,
                                    band = band.shiftX(lineOffsetX),
                                    textTopLeft = textTopLeft,
                                    sweep = sweep,
                                    rtl = graphemeGeometry.rtl,
                                    dimColor = dimColor,
                                    brightColor = brightColor,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Glow source pass. This is drawn into a separate RenderEffect-blurred canvas so the
 * halo expands outside the glyph clip instead of being clipped back to the glyph box.
 */
private fun DrawScope.drawShapedLyricsGlow(
    geometry: ShapedLyricsLineGeometry,
    motion: ShapedLyricsLineMotion,
    positionMs: Long,
    textColor: Color,
    fontSizePx: Float,
    bounceFactor: Float,
    glowFactor: Float,
    sourceAlphaMultiplier: Float,
    contentOffsetY: Float,
) {
    val layout = geometry.layout
    val lineOffsetX = ((size.width - layout.size.width) / 2f).coerceAtLeast(0f)
    val textTopLeft = Offset(lineOffsetX, contentOffsetY)
    val brightColor = textColor.copy(alpha = textColor.alpha * 0.85f)

    geometry.words.forEachIndexed wordLoop@ { wordIndex, wordGeometry ->
        val bounds = wordGeometry.bounds ?: return@wordLoop
        val word = wordGeometry.word
        val wordState = shapedWordState(positionMs, word.startMs, word.endMs)
        if (wordState == ShapedLyricsWordState.NOT_SUNG) {
            return@wordLoop
        }

        val wordMotion = motion.words[wordIndex]
        val wordSpring = wordMotion.word.snapshot()
        val wordScale = 1f + (wordSpring.scale - 1f) * bounceFactor
        val wordYOffset = wordSpring.yOffsetFraction * fontSizePx.coerceAtLeast(1f) * bounceFactor

        withNativeLyricsTransform(
            centerX = lineOffsetX + bounds.center.x,
            centerY = contentOffsetY + bounds.center.y,
            scale = wordScale,
            yOffset = wordYOffset,
        ) {
            if (!wordGeometry.graphemeMotion) {
                val animatedGlow = (wordSpring.glow * glowFactor).coerceIn(0f, 1.25f)
                val glow =
                    if (wordState == ShapedLyricsWordState.SUNG) {
                        max(animatedGlow, SHAPED_SUNG_WORD_GLOW)
                    } else {
                        animatedGlow
                    }
                if (glow <= 0.01f) return@withNativeLyricsTransform
                val glowColor =
                    brightColor.copy(
                        alpha = (glow * 0.92f * sourceAlphaMultiplier).coerceIn(0f, 1f),
                    )
                wordGeometry.rowBands.forEach { band ->
                    val shifted = band.shift(lineOffsetX, contentOffsetY)
                    clipRect(shifted.left, shifted.top, shifted.right, shifted.bottom) {
                        drawText(textLayoutResult = layout, color = glowColor, topLeft = textTopLeft)
                    }
                }
            } else {
                val graphemeSnapshots = wordMotion.graphemes?.snapshot().orEmpty()
                wordGeometry.graphemes.forEachIndexed { graphemeIndex, graphemeGeometry ->
                    val band = graphemeGeometry.box
                    if (band.width <= 0f || band.height <= 0f || graphemeIndex !in graphemeSnapshots.indices) {
                        return@forEachIndexed
                    }
                    val spring = graphemeSnapshots[graphemeIndex]
                    val animatedGlow = (spring.glow * glowFactor).coerceIn(0f, 1.25f)
                    // Once the sweep has passed a glyph, keep a lower residual halo instead
                    // of dropping immediately to zero. The currently active glyph still owns
                    // the much stronger spring-driven bloom above this floor.
                    val isSungGlyph = positionMs >= graphemeGeometry.grapheme.endMs
                    val glow =
                        if (isSungGlyph) {
                            max(animatedGlow, SHAPED_SUNG_GLYPH_GLOW)
                        } else {
                            animatedGlow
                        }
                    if (glow <= 0.01f) return@forEachIndexed
                    val graphemeScale = 1f + (spring.scale - 1f) * bounceFactor
                    val graphemeYOffset =
                        shapedLyricsGraphemeYOffsetPx(
                            yOffsetFraction = spring.yOffsetFraction,
                            bounceFactor = bounceFactor,
                        )
                    val shiftedBand = band.shift(lineOffsetX, contentOffsetY)
                    val glowColor =
                        brightColor.copy(
                            alpha = (glow * 0.92f * sourceAlphaMultiplier).coerceIn(0f, 1f),
                        )

                    withNativeLyricsTransform(
                        centerX = lineOffsetX + band.center.x,
                        centerY = contentOffsetY + band.center.y,
                        scale = graphemeScale,
                        yOffset = graphemeYOffset,
                    ) {
                        clipRect(shiftedBand.left, shiftedBand.top, shiftedBand.right, shiftedBand.bottom) {
                            drawText(textLayoutResult = layout, color = glowColor, topLeft = textTopLeft)
                        }
                    }
                }
            }
        }
    }
}

private fun Rect.shift(
    deltaX: Float,
    deltaY: Float,
): Rect = Rect(left + deltaX, top + deltaY, right + deltaX, bottom + deltaY)

private fun Rect.shiftX(delta: Float): Rect = shift(delta, 0f)

/**
 * Measure a glow canvas larger than the lyric row, but report the original row size to the
 * parent. The oversized child is placed with negative overscan so RenderEffect gets a real
 * transparent border to blur into without changing line spacing or text position.
 */
private fun Modifier.glowLayerOverscan(
    contentWidthPx: Int,
    contentHeightPx: Int,
    overscanPx: Int,
): Modifier =
    layout { measurable, constraints ->
        val safeOverscan = overscanPx.coerceAtLeast(0)
        val expandedWidth = (contentWidthPx + safeOverscan * 2).coerceAtLeast(1)
        val expandedHeight = (contentHeightPx + safeOverscan * 2).coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(expandedWidth, expandedHeight))
        val reportedWidth =
            contentWidthPx.coerceIn(
                constraints.minWidth,
                if (constraints.maxWidth == Constraints.Infinity) contentWidthPx else constraints.maxWidth,
            )
        val reportedHeight =
            contentHeightPx.coerceIn(
                constraints.minHeight,
                if (constraints.maxHeight == Constraints.Infinity) contentHeightPx else constraints.maxHeight,
            )
        layout(reportedWidth, reportedHeight) {
            placeable.placeRelative(-safeOverscan, -safeOverscan)
        }
    }

/** Desktop SpicyPane applies letter Y as the raw spline value times 2 px, not font size. */
internal fun shapedLyricsGraphemeYOffsetPx(
    yOffsetFraction: Float,
    bounceFactor: Float,
): Float = yOffsetFraction * 2f * bounceFactor

/**
 * Keep the transform order byte-for-byte equivalent in spirit to desktop SpicyPane's
 * native Skia canvas sequence. Using DrawTransform here subtly changed the composed
 * pivot when a glyph transform was nested inside a word transform, which could make
 * individually animated letters drift sideways on Android.
 */
private inline fun DrawScope.withNativeLyricsTransform(
    centerX: Float,
    centerY: Float,
    scale: Float,
    yOffset: Float,
    block: DrawScope.() -> Unit,
) {
    val native = drawContext.canvas.nativeCanvas
    val saveCount = native.save()
    native.translate(centerX, centerY)
    native.scale(scale, scale)
    native.translate(-centerX, -centerY + yOffset)
    try {
        block()
    } finally {
        native.restoreToCount(saveCount)
    }
}

private fun DrawScope.drawProgressiveBand(
    layout: TextLayoutResult,
    band: Rect,
    textTopLeft: Offset,
    sweep: Float,
    rtl: Boolean,
    dimColor: Color,
    brightColor: Color,
) {
    clipRect(band.left, band.top, band.right, band.bottom) {
        drawText(textLayoutResult = layout, color = dimColor, topLeft = textTopLeft)
    }

    when {
        sweep <= -SHAPED_GLYPH_FADE_FRACTION -> Unit
        sweep >= 1f -> {
            clipRect(band.left, band.top, band.right, band.bottom) {
                drawText(textLayoutResult = layout, color = brightColor, topLeft = textTopLeft)
            }
        }

        else -> {
            // Desktop draws the bright paragraph through a ~90.6% alpha mask over the
            // already-painted dim paragraph. Applying full bright alpha here made the
            // Android active glyph materially brighter than desktop.
            val brush =
                shapedSweepBrush(
                    band = band,
                    sweep = sweep,
                    rtl = rtl,
                    brightColor = brightColor.copy(alpha = brightColor.alpha * SHAPED_BRIGHT_OVERLAY_ALPHA),
                )
            clipRect(band.left, band.top, band.right, band.bottom) {
                drawText(textLayoutResult = layout, brush = brush, topLeft = textTopLeft)
            }
        }
    }
}

private fun shapedSweepBrush(
    band: Rect,
    sweep: Float,
    rtl: Boolean,
    brightColor: Color,
): Brush {
    val fadeEnd = sweep + SHAPED_GLYPH_FADE_FRACTION
    fun coverageAt(normalized: Float): Float =
        (1f - ((normalized - sweep) / SHAPED_GLYPH_FADE_FRACTION).coerceIn(0f, 1f)).coerceIn(0f, 1f)

    val stops = mutableListOf(0f to brightColor.copy(alpha = brightColor.alpha * coverageAt(0f)))
    if (sweep in 0f..1f) stops += sweep to brightColor
    if (fadeEnd in 0f..1f) stops += fadeEnd to brightColor.copy(alpha = 0f)
    stops += 1f to brightColor.copy(alpha = brightColor.alpha * coverageAt(1f))
    return Brush.horizontalGradient(
        *stops.toTypedArray(),
        startX = if (rtl) band.right else band.left,
        endX = if (rtl) band.left else band.right,
    )
}
