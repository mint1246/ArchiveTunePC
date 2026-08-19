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
    init {
        require(maxHistoryEntries > 0) { "Navigation history must keep at least one destination" }
    }

    var current by mutableStateOf(initialDestination)
        private set

    val canNavigateBack: Boolean get() = history.size > 1

    fun navigate(destination: DesktopDestination) {
        if (destination == current) return
        history += destination
        while (history.size > maxHistoryEntries) history.removeAt(0)
        current = destination
    }

    fun navigateBack(): Boolean {
        if (!canNavigateBack) return false
        history.removeAt(history.lastIndex)
        current = history.last()
        return true
    }
}
