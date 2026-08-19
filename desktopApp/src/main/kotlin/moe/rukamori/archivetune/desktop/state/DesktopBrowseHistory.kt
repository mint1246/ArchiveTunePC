/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.state

import java.util.EnumMap
import moe.rukamori.archivetune.desktop.platform.DesktopBrowseRoute

/** Compact route history. Loaded page objects deliberately live outside this class. */
internal class DesktopBrowseHistory(
    initialDestination: DesktopDestination = DesktopDestination.HOME,
    private val maxRoutesPerDestination: Int = 16,
) {
    private val stacks = EnumMap<DesktopDestination, ArrayDeque<DesktopBrowseRoute>>(DesktopDestination::class.java)
    var activeDestination: DesktopDestination = initialDestination
        private set

    init {
        require(maxRoutesPerDestination > 0) { "Browse history must retain at least one route" }
    }

    fun activate(destination: DesktopDestination): DesktopBrowseRoute? {
        activeDestination = destination
        return current()
    }

    fun push(route: DesktopBrowseRoute): DesktopBrowseRoute {
        val stack = stack()
        if (stack.lastOrNull() == route) return route
        stack.addLast(route)
        while (stack.size > maxRoutesPerDestination) stack.removeFirst()
        return route
    }

    fun replace(route: DesktopBrowseRoute): DesktopBrowseRoute {
        val stack = stack()
        if (stack.isNotEmpty()) stack.removeLast()
        stack.addLast(route)
        return route
    }

    fun pop(): DesktopBrowseRoute? {
        val stack = stack()
        if (stack.isNotEmpty()) stack.removeLast()
        return stack.lastOrNull()
    }

    fun clearCurrent() {
        stack().clear()
    }

    fun clearAll() {
        stacks.clear()
    }

    fun current(): DesktopBrowseRoute? = stack().lastOrNull()

    fun depth(destination: DesktopDestination = activeDestination): Int = stacks[destination]?.size ?: 0

    private fun stack(): ArrayDeque<DesktopBrowseRoute> =
        stacks.getOrPut(activeDestination) { ArrayDeque() }
}