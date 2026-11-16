package moe.rukamori.archivetune.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyPlaylistAddBatchingTest {
    @Test
    fun `ordered add batches keep source order and duplicates`() {
        val source = listOf("a", "b", "a", "c", "d", "e", "f")

        val batches = orderedPlaylistAddBatches(source, batchSize = 3)

        assertEquals(listOf(listOf("a", "b", "a"), listOf("c", "d", "e"), listOf("f")), batches)
        assertEquals(source, batches.flatten())
    }
}
