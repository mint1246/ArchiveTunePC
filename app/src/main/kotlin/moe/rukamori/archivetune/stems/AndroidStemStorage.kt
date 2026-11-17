/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import moe.rukamori.archivetune.sdk.stems.StemStorage
import moe.rukamori.archivetune.sdk.stems.StemStorageState
import moe.rukamori.archivetune.sdk.stems.StemStorageUsage
import moe.rukamori.archivetune.sdk.stems.formatStemBytes

typealias AndroidStemStorageUsage = StemStorageUsage
typealias AndroidStemStorageState = StemStorageState
typealias AndroidStemStorage = StemStorage

internal fun formatAndroidStemStorageBytes(bytes: Long): String = formatStemBytes(bytes)
