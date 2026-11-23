/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class EqualPowerGains(
    val outgoing: Float,
    val incoming: Float,
)

fun equalPowerGains(progress: Float): EqualPowerGains {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val radians = clampedProgress.toDouble() * (PI / 2.0)
    return EqualPowerGains(
        outgoing = cos(radians).toFloat(),
        incoming = sin(radians).toFloat(),
    )
}
