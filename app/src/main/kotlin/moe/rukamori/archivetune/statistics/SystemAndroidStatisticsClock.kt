/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.statistics

import android.os.SystemClock

object SystemAndroidStatisticsClock : AndroidStatisticsClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun wallTimeEpochMs(): Long = System.currentTimeMillis()
}
