/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.windowsmedia

import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32

/**
 * Opportunistically asks Windows to evict cold pages from ArchiveTune's resident working set.
 *
 * This intentionally does not free application allocations and must not be called continuously:
 * pages that become hot again are faulted back in by Windows. Callers should only use it at
 * natural low-activity boundaries such as a long minimize or after leaving a heavyweight view.
 */
object WindowsWorkingSetTrimmer {
    fun trimCurrentProcess(): Boolean {
        if (!Platform.isWindows()) return false

        return runCatching {
            val process = Kernel32.INSTANCE.GetCurrentProcess()
            NativeLibrary.getInstance("psapi")
                .getFunction("EmptyWorkingSet")
                .invokeInt(arrayOf<Any>(process)) != 0
        }.getOrDefault(false)
    }
}
