/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import moe.rukamori.archivetune.sdk.stems.DemucsContract
import moe.rukamori.archivetune.sdk.stems.DemucsDsp

internal typealias AndroidDemucsContract = DemucsContract
internal typealias AndroidDemucsDsp = DemucsDsp

internal fun interleavedDemucsMix(chunk: Array<FloatArray>): FloatArray =
    moe.rukamori.archivetune.sdk.stems.interleavedDemucsMix(chunk)

internal fun triangularDemucsWeight(): FloatArray =
    moe.rukamori.archivetune.sdk.stems.triangularDemucsWeight()

internal fun reflectedSample(input: FloatArray, rawIndex: Int): Float =
    moe.rukamori.archivetune.sdk.stems.reflectedSample(input, rawIndex)
