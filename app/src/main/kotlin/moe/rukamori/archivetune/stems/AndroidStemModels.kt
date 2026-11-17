/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import moe.rukamori.archivetune.sdk.stems.STEM_CACHE_VERSION
import moe.rukamori.archivetune.sdk.stems.STEM_OPUS_CACHE_FORMAT
import moe.rukamori.archivetune.sdk.stems.STEM_WAV_CACHE_FORMAT
import moe.rukamori.archivetune.sdk.stems.StemChannelControl
import moe.rukamori.archivetune.sdk.stems.StemControls
import moe.rukamori.archivetune.sdk.stems.StemEncoding
import moe.rukamori.archivetune.sdk.stems.StemSource

/** Portable cache/model contract shared with the current desktop HTDemucs 6-source output. */
const val ANDROID_STEM_CACHE_VERSION = STEM_CACHE_VERSION
const val ANDROID_STEM_CACHE_FORMAT = STEM_OPUS_CACHE_FORMAT
const val ANDROID_STEM_WAV_CACHE_FORMAT = STEM_WAV_CACHE_FORMAT

typealias AndroidStemEncoding = StemEncoding
typealias AndroidStemSource = StemSource
typealias AndroidStemChannelControl = StemChannelControl
typealias AndroidStemControls = StemControls
