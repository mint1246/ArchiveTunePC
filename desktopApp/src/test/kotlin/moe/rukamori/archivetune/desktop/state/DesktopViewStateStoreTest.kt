package moe.rukamori.archivetune.desktop.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopViewStateStoreTest {
    @Test
    fun `positions restore by key without retaining unbounded history`() {
        val store = DesktopViewStateStore(maxEntries = 3)
        store.save("home", 4, 25)
        store.save("search:a", 8, 10)
        store.save("album:a", 12, 2)

        assertEquals(DesktopListPosition(4, 25), store.position("home"))

        // Touching Home makes it most-recently used, so Search is evicted next.
        store.save("playlist:b", 3, 0)
        assertNull(store.position("search:a"))
        assertEquals(3, store.size())
        assertEquals(DesktopListPosition(4, 25), store.position("home"))
    }
}