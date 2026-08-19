/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.state

import java.util.LinkedHashMap

internal data class DesktopListPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
)

/**
 * Bounded compact view state. It deliberately stores only primitive scroll coordinates rather than
 * retaining Compose screens/LazyListState objects across navigation.
 */
internal class DesktopViewStateStore(private val maxEntries: Int = 64) {
    private val lock = Any()
    private val positions = object : LinkedHashMap<String, DesktopListPosition>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DesktopListPosition>?): Boolean =
            size > maxEntries
    }

    init {
        require(maxEntries > 0) { "View-state cache must retain at least one entry" }
    }

    fun position(key: String): DesktopListPosition? = synchronized(lock) { positions[key] }

    fun save(key: String, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        if (key.isBlank()) return
        synchronized(lock) {
            positions[key] = DesktopListPosition(
                firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
                firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0),
            )
        }
    }

    fun clear() = synchronized(lock) { positions.clear() }

    internal fun size(): Int = synchronized(lock) { positions.size }
}

internal val desktopViewStateStore = DesktopViewStateStore()