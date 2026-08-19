package moe.rukamori.archivetune.desktop.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.desktop.platform.DesktopBrowseRoute

class DesktopNavigationStateTest {
    @Test
    fun `detail routes are restored independently per destination`() {
        val history = DesktopBrowseHistory(DesktopDestination.SEARCH)
        val artist = DesktopBrowseRoute.Artist("artist")
        val album = DesktopBrowseRoute.Album("album")
        val playlist = DesktopBrowseRoute.Playlist("playlist")

        history.push(artist)
        history.push(album)
        history.activate(DesktopDestination.LIBRARY)
        history.push(playlist)

        assertEquals(playlist, history.current())
        assertEquals(album, history.activate(DesktopDestination.SEARCH))
        assertEquals(artist, history.pop())
        assertEquals(artist, history.current())
    }

    @Test
    fun `detail route history is bounded`() {
        val history = DesktopBrowseHistory(DesktopDestination.SEARCH, maxRoutesPerDestination = 3)
        repeat(8) { history.push(DesktopBrowseRoute.Album("album-$it")) }

        assertEquals(3, history.depth())
        assertEquals(DesktopBrowseRoute.Album("album-7"), history.current())
        assertEquals(DesktopBrowseRoute.Album("album-6"), history.pop())
        assertEquals(DesktopBrowseRoute.Album("album-5"), history.pop())
    }

    @Test
    fun `top level navigator back history is bounded`() {
        val navigator = DesktopNavigator(DesktopDestination.HOME, maxHistoryEntries = 3)
        navigator.navigate(DesktopDestination.SEARCH)
        navigator.navigate(DesktopDestination.LIBRARY)
        navigator.navigate(DesktopDestination.EXPLORE)

        assertTrue(navigator.navigateBack())
        assertEquals(DesktopDestination.LIBRARY, navigator.current)
        assertTrue(navigator.navigateBack())
        assertEquals(DesktopDestination.SEARCH, navigator.current)
        assertFalse(navigator.navigateBack())
    }

    @Test
    fun `top level navigator can move backward and forward`() {
        val navigator = DesktopNavigator(DesktopDestination.HOME)
        navigator.navigate(DesktopDestination.SEARCH)
        navigator.navigate(DesktopDestination.LIBRARY)

        assertTrue(navigator.navigateBack())
        assertEquals(DesktopDestination.SEARCH, navigator.current)
        assertTrue(navigator.canNavigateForward)
        assertTrue(navigator.navigateForward())
        assertEquals(DesktopDestination.LIBRARY, navigator.current)
        assertFalse(navigator.canNavigateForward)
    }

    @Test
    fun `new navigation after back discards forward branch`() {
        val navigator = DesktopNavigator(DesktopDestination.HOME)
        navigator.navigate(DesktopDestination.SEARCH)
        navigator.navigate(DesktopDestination.LIBRARY)
        navigator.navigate(DesktopDestination.DOWNLOADS)

        assertTrue(navigator.navigateBack())
        assertEquals(DesktopDestination.LIBRARY, navigator.current)
        assertTrue(navigator.canNavigateForward)

        navigator.navigate(DesktopDestination.SETTINGS)

        assertEquals(DesktopDestination.SETTINGS, navigator.current)
        assertFalse(navigator.canNavigateForward)
        assertFalse(navigator.navigateForward())
        assertTrue(navigator.navigateBack())
        assertEquals(DesktopDestination.LIBRARY, navigator.current)
    }
}
