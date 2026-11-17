/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.equalizer

import kotlin.math.ceil
import kotlin.math.floor

/** Resamples stored band levels to the number of bands the current device actually exposes. */
fun resampleLevels(
    levelsMb: List<Int>,
    targetCount: Int,
): List<Int> {
    if (targetCount <= 0) return emptyList()
    if (levelsMb.isEmpty()) return List(targetCount) { 0 }
    if (levelsMb.size == targetCount) return levelsMb
    if (targetCount == 1) return listOf(levelsMb.average().toInt())
    val lastIndex = levelsMb.lastIndex.toFloat().coerceAtLeast(1f)
    return List(targetCount) { index ->
        val position = index * lastIndex / (targetCount - 1)
        val lower = floor(position).toInt().coerceIn(0, levelsMb.lastIndex)
        val upper = ceil(position).toInt().coerceIn(0, levelsMb.lastIndex)
        (levelsMb[lower] + ((levelsMb[upper] - levelsMb[lower]) * (position - lower))).toInt()
    }
}

/** Band indices controlled by a tone, using centre frequencies when available and thirds otherwise. */
fun equalizerToneIndices(
    tone: EqualizerTone,
    frequenciesHz: List<Int>,
    bandCount: Int,
): List<Int> {
    if (bandCount <= 0) return emptyList()
    val frequencyMatches =
        frequenciesHz
            .takeIf { it.size == bandCount && it.any { frequency -> frequency > 0 } }
            ?.indices
            ?.filter { index ->
                when (tone) {
                    EqualizerTone.BASS -> frequenciesHz[index] <= 250
                    EqualizerTone.MIDRANGE -> frequenciesHz[index] in 251..4_000
                    EqualizerTone.TREBLE -> frequenciesHz[index] > 4_000
                }
            }.orEmpty()
    if (frequencyMatches.isNotEmpty()) return frequencyMatches

    val firstBoundary = ceil(bandCount / 3.0).toInt()
    val secondBoundary = ceil(bandCount * 2 / 3.0).toInt()
    val range =
        when (tone) {
            EqualizerTone.BASS -> 0 until firstBoundary
            EqualizerTone.MIDRANGE -> firstBoundary until secondBoundary
            EqualizerTone.TREBLE -> secondBoundary until bandCount
        }.toList()
    if (range.isNotEmpty()) return range
    return listOf(
        when (tone) {
            EqualizerTone.BASS -> 0
            EqualizerTone.MIDRANGE -> (bandCount - 1) / 2
            EqualizerTone.TREBLE -> bandCount - 1
        },
    )
}

/** Computes the new band levels after a tone slider moves to [targetLevelMb]. */
fun adjustToneBands(
    tone: EqualizerTone,
    targetLevelMb: Int,
    configuration: EqualizerConfiguration,
): List<Int> {
    val capabilities = configuration.capabilities ?: return configuration.settings.bandLevelsMb
    val current = resampleLevels(configuration.settings.bandLevelsMb, capabilities.bandCount).toMutableList()
    val indices = equalizerToneIndices(tone, capabilities.centerFreqHz, capabilities.bandCount)
    if (indices.isEmpty()) return current
    val average = indices.sumOf { current[it] } / indices.size
    val delta = targetLevelMb - average
    indices.forEach { index ->
        current[index] = (current[index] + delta).coerceIn(capabilities.minBandLevelMb, capabilities.maxBandLevelMb)
    }
    return current
}
