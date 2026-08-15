/*
 * Per-character animator state, computed each frame for the current playback position.
 *
 * Output is a flat snapshot the render layer reads: each `CharSpan` knows the spring
 * values it should display right now, scaled between a "rest" state, the highlighted
 * peak, and the settled state. The Spring values come from the same physics expressed
 * in SpicyLyrics' `LyricsAnimator.ts`.
 */
package moe.rukamori.archivetune.desktop.lyrics.anim

import moe.rukamori.archivetune.desktop.lyrics.anim.AnimCurves
import moe.rukamori.archivetune.desktop.lyrics.anim.LetterSprings
import moe.rukamori.archivetune.desktop.lyrics.anim.WordSprings
import moe.rukamori.archivetune.desktop.lyrics.CharLine
import moe.rukamori.archivetune.desktop.lyrics.CharLyrics
import moe.rukamori.archivetune.desktop.lyrics.CharSpan

enum class ElementState { NotSung, Active, Sung }

private fun stateOf(positionMs: Long, startMs: Long, endMs: Long): ElementState = when {
    positionMs < startMs -> ElementState.NotSung
    positionMs >= endMs -> ElementState.Sung
    else -> ElementState.Active
}

private fun progress(positionMs: Long, startMs: Long, endMs: Long): Double {
    val span = (endMs - startMs).coerceAtLeast(1L).toDouble()
    return ((positionMs - startMs).toDouble() / span).coerceIn(0.0, 1.0)
}

/**
 * State emitted to the render layer for one `CharSpan`. Values are spring outputs
 * (already eased) so the render pass is allocation-free.
 */
data class CharVisualState(
    val scale: Float,
    val yOffsetFraction: Float, // multiplied by --DefaultLyricsSize at paint time
    val glowFraction: Float,    // 0..1 → drives text-shadow
    val gradientPosition: Float, // 0..1 → drives highlight gradient stop
    val lineBlurPx: Float,      // applied at the line level
)

class CharAnimator(
    private val curves: AnimCurves = AnimCurves.Standard,
) {
    private val wordSprings = HashMap<CharSpan, WordSprings>()
    private val letterSprings = HashMap<CharSpan, LetterSprings>()
    private var deltaSec: Double = 0.0167

    fun setDelta(deltaSec: Double) { this.deltaSec = deltaSec.coerceAtLeast(0.001) }

    private fun springFor(letter: CharSpan, height: Float): LetterSprings =
        letterSprings.getOrPut(letter) {
            val s0 = curves.letterScale.at(0.0)
            val y0 = curves.letterYOffset.at(0.0) * height
            val g0 = curves.wordGlow.at(0.0)
            LetterSprings(s0, y0, g0).also {
                it.scale.setGoal(s0, snap = true)
                it.yOffset.setGoal(y0, snap = true)
                it.glow.setGoal(g0, snap = true)
            }
        }

    private fun springFor(word: CharSpan, first: Boolean, height: Float): WordSprings =
        wordSprings.getOrPut(word) {
            val s0 = curves.wordScale.at(0.0)
            val y0 = curves.wordYOffset.at(0.0) * height
            val g0 = curves.wordGlow.at(0.0)
            WordSprings(s0, y0, g0).also {
                it.scale.setGoal(s0, snap = first)
                it.yOffset.setGoal(y0, snap = first)
                it.glow.setGoal(g0, snap = first)
            }
        }

    /** Compute visual state for every character at the current playback position. */
    fun evaluate(lyrics: CharLyrics, positionMs: Long, lineHeightSp: Float): List<LineVisualState> {
        val out = ArrayList<LineVisualState>(lyrics.lines.size)
        for (line in lyrics.lines) {
            out += evaluateLine(line, positionMs, lineHeightSp)
        }
        return out
    }

    /** Compute visual state for every character in a single line at the current position. */
    fun evaluateLine(line: CharLine, positionMs: Long, height: Float): LineVisualState {
        val lineState = stateOf(positionMs, line.startMs, line.endMs)
        val charsState = ArrayList<CharVisualState>(line.chars.size)
        for (letter in line.chars) {
            charsState += evaluateChar(letter, line, positionMs, lineState, height)
        }
        val lineBlur = when (lineState) {
            ElementState.Active -> 0f
            else -> 1.1f
        }
        return LineVisualState(
            state = lineState,
            chars = charsState,
            lineBlurPx = lineBlur,
        )
    }

    private fun evaluateChar(
        letter: CharSpan,
        line: CharLine,
        positionMs: Long,
        lineState: ElementState,
        height: Float,
    ): CharVisualState {
        val letterState = stateOf(positionMs, letter.startMs, letter.endMs)
        val wordStart = line.startMs
        val wordEnd = line.endMs
        val wordState = stateOf(positionMs, wordStart, wordEnd)
        val wordProgress = progress(positionMs, wordStart, wordEnd)
        val letterProgress = progress(positionMs, letter.startMs, letter.endMs)

        val springs = springFor(letter, height)
        val targetScale: Double
        val targetYOffset: Double
        val targetGlow: Double
        val targetGradient: Double

        if (letterState == ElementState.Active) {
            targetScale = curves.letterScale.at(letterProgress)
            targetYOffset = curves.letterYOffset.at(letterProgress) * height
            targetGlow = curves.wordGlow.at(letterProgress)
            targetGradient = -0.2 + 1.2 * letterProgress
        } else if (lineState == ElementState.Active) {
            val p = wordProgress
            targetScale = curves.letterScale.at(p)
            targetYOffset = curves.letterYOffset.at(p) * height
            targetGlow = curves.wordGlow.at(p) * 0.6
            targetGradient = -0.2
        } else if (lineState == ElementState.NotSung) {
            targetScale = curves.letterScale.at(0.0)
            targetYOffset = curves.letterYOffset.at(0.0) * height
            targetGlow = 0.0
            targetGradient = -0.2
        } else {
            targetScale = curves.letterScale.at(1.0)
            targetYOffset = curves.letterYOffset.at(1.0) * height
            targetGlow = 0.2
            targetGradient = 1.0
        }

        springs.scale.setGoal(targetScale)
        springs.yOffset.setGoal(targetYOffset)
        springs.glow.setGoal(targetGlow)

        val s = springs.scale.step(deltaSec)
        val y = springs.yOffset.step(deltaSec)
        val g = springs.glow.step(deltaSec)

        return CharVisualState(
            scale = s.toFloat(),
            yOffsetFraction = y.toFloat() / height.coerceAtLeast(0.0001f),
            glowFraction = g.toFloat(),
            gradientPosition = targetGradient.toFloat(),
            lineBlurPx = 0f,
        )
    }
}

data class LineVisualState(
    val state: ElementState,
    val chars: List<CharVisualState>,
    val lineBlurPx: Float,
)
