/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryListsTest {
    private data class Item(
        val id: String,
    )

    @Test
    fun `distinctById keeps first occurrence and preserves order`() {
        val items = listOf(Item("a"), Item("b"), Item("a"), Item("c"), Item("b"))
        assertEquals(listOf(Item("a"), Item("b"), Item("c")), DiscoveryLists.distinctById(items) { it.id })
    }

    @Test
    fun `distinctById drops items without an id`() {
        val items = listOf(Item("a"), Item(""), Item("b"))
        assertEquals(listOf(Item("a"), Item("b")), DiscoveryLists.distinctById(items) { it.id.takeIf(String::isNotBlank) })
    }

    @Test
    fun `takeDistinct deduplicates before applying the limit`() {
        val items = listOf(Item("a"), Item("a"), Item("b"), Item("c"))
        assertEquals(listOf(Item("a"), Item("b")), DiscoveryLists.takeDistinct(items, 2) { it.id })
    }

    @Test
    fun `takeDistinct clamps negative limits to empty`() {
        val items = listOf(Item("a"))
        assertEquals(emptyList(), DiscoveryLists.takeDistinct(items, -1) { it.id })
    }
}
