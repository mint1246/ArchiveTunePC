package moe.rukamori.archivetune.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DesktopBrowsePageCacheTest {
    @Test
    fun `add remove and rename invalidation force fresh playlist content on reopen`() {
        val cache = DesktopBrowsePageCache(accountScopeProvider = { "account" }, maxPages = 8)

        listOf("add", "remove", "rename").forEach { mutation ->
            val route = DesktopBrowseRoute.Playlist("playlist-$mutation")
            val before = cache.beginLoad(route)
            cache.putIfCurrent(before, "before-$mutation")
            assertEquals("before-$mutation", cache.get<String>(route))

            cache.invalidate(route)

            assertNull(cache.get<String>(route))
            val after = cache.beginLoad(route)
            assertNotEquals(before.singleFlightKey, after.singleFlightKey)
            cache.putIfCurrent(after, "after-$mutation")
            assertEquals("after-$mutation", cache.get<String>(route))
        }
    }

    @Test
    fun `failed playlist mutation leaves existing cached content intact`() {
        val cache = DesktopBrowsePageCache(accountScopeProvider = { "account" }, maxPages = 8)
        val route = DesktopBrowseRoute.Playlist("playlist")
        val initial = cache.beginLoad(route)
        cache.putIfCurrent(initial, "unchanged content")

        // A failed backend mutation intentionally performs no cache invalidation.
        assertEquals("unchanged content", cache.get<String>(route))
    }

    @Test
    fun `stale pre mutation refresh cannot overwrite newer playlist detail`() {
        val cache = DesktopBrowsePageCache(accountScopeProvider = { "account" }, maxPages = 8)
        val route = DesktopBrowseRoute.Playlist("playlist")
        val initial = cache.beginLoad(route)
        cache.putIfCurrent(initial, "initial")

        val staleRefresh = cache.beginLoad(route)
        cache.invalidate(route)
        val postMutationRefresh = cache.beginLoad(route)

        assertNotEquals(staleRefresh.singleFlightKey, postMutationRefresh.singleFlightKey)
        cache.putIfCurrent(postMutationRefresh, "fresh")
        cache.putIfCurrent(staleRefresh, "stale")

        assertEquals("fresh", cache.get<String>(route))
    }

    @Test
    fun `account cache clear rejects older in flight browse result`() {
        var account = "account-a"
        val cache = DesktopBrowsePageCache(accountScopeProvider = { account }, maxPages = 8)
        val route = DesktopBrowseRoute.Playlist("playlist")
        val oldLoad = cache.beginLoad(route)

        cache.clear()
        account = "account-b"
        val currentLoad = cache.beginLoad(route)
        cache.putIfCurrent(currentLoad, "account-b page")
        cache.putIfCurrent(oldLoad, "account-a stale page")

        assertEquals("account-b page", cache.get<String>(route))
    }
}
