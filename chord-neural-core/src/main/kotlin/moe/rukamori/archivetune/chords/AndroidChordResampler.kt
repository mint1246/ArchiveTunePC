/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.chords

fun androidChordStereoMean(left: Float, right: Float): Float = (left + right) * 0.5f

class AndroidChordLinearResampler(sourceRate: Int, targetRate: Int) {
    private val step = sourceRate.toDouble() / targetRate.toDouble()
    private var previous = 0f
    private var sourceIndex = -1L
    private var nextOutputPosition = 0.0

    init {
        require(sourceRate > 0 && targetRate > 0)
    }

    fun push(sample: Float, sink: (Float) -> Unit) {
        sourceIndex++
        if (sourceIndex == 0L) {
            previous = sample
            sink(sample)
            nextOutputPosition = step
            return
        }
        val currentIndex = sourceIndex.toDouble()
        val previousIndex = currentIndex - 1.0
        while (nextOutputPosition <= currentIndex + 1e-12) {
            val fraction = (nextOutputPosition - previousIndex).coerceIn(0.0, 1.0).toFloat()
            sink(previous + (sample - previous) * fraction)
            nextOutputPosition += step
        }
        previous = sample
    }
}
