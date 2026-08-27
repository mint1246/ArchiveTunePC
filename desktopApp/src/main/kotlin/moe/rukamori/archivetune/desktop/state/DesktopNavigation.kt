/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class DesktopDestination(
    val key: String,
    val title: String,
    val description: String,
) {
    HOME("home", "Home", "Recommendations, quick picks and listening history"),
    EXPLORE("explore", "Explore", "New releases, charts, moods and genres"),
    SEARCH("search", "Search", "Search YouTube Music and your local library"),
    LIBRARY("library", "Library", "Songs, albums, artists and playlists"),
    DOWNLOADS("downloads", "Downloads", "Offline music stored on this computer"),
    STATISTICS("statistics", "Statistics", "Your listening habits and most played music"),
    SETTINGS("settings", "Settings", "Appearance, playback, accounts and integrations"),
    ;

    companion object {
        fun fromKey(key: String?): DesktopDestination = entries.firstOrNull { it.key == key } ?: HOME
    }
}

@Stable
class DesktopNavigator(
    initialDestination: DesktopDestination,
    private val maxHistoryEntries: Int = 32,
) {
    private val history = mutableStateListOf(initialDestination)
    private var historyIndex = 0

    init {
        require(maxHistoryEntries > 0) { "Navigation history must keep at least one destination" }
    }

    var current by mutableStateOf(initialDestination)
        private set

    val canNavigateBack: Boolean get() = historyIndex > 0
    val canNavigateForward: Boolean get() = historyIndex < history.lastIndex

    fun navigate(destination: DesktopDestination) {
        if (destination == current) return

        // Match browser history: once the user goes back, a new destination starts a new branch.
        while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
        history += destination
        historyIndex = history.lastIndex
        while (history.size > maxHistoryEntries) {
            history.removeAt(0)
            historyIndex--
        }
        current = destination
    }

    fun navigateBack(): Boolean {
        if (!canNavigateBack) return false
        historyIndex--
        current = history[historyIndex]
        return true
    }

    fun navigateForward(): Boolean {
        if (!canNavigateForward) return false
        historyIndex++
        current = history[historyIndex]
        return true
    }
}
