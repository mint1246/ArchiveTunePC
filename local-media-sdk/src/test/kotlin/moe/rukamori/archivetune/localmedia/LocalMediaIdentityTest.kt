/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmedia

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalMediaIdentityTest {
    @Test
    fun `normalize collapses compatibility forms whitespace and case`() {
        assertEquals("beyoncé!", LocalMediaIdentity.normalize("  Ｂｅｙｏｎｃé!  "))
    }

    @Test
    fun `normalize is stable for already normalized artist keys`() {
        val source = "AC/DC"
        assertEquals(LocalMediaIdentity.normalize(source), LocalMediaIdentity.normalize(source))
        assertEquals("ac/dc", LocalMediaIdentity.normalize(source))
    }

    @Test
    fun `normalize keeps distinct album keys distinct`() {
        assertEquals("album a", LocalMediaIdentity.normalize("  Album   A  "))
        assertEquals("album b", LocalMediaIdentity.normalize("Album B"))
    }
}
