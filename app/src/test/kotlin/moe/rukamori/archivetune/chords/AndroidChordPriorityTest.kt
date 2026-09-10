package moe.rukamori.archivetune.chords

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidChordPriorityTest {
    @Test
    fun `authored wins without invoking local or derived`() = runBlocking {
        val calls = mutableListOf<String>()
        val result =
            resolveAndroidChordPriority(
                authored = { calls += "authored"; Result.success(track(ChordTrackSource.SONGSTERR_AUTHORED)) },
                localNeural = { calls += "local"; Result.success(track(ChordTrackSource.LOCAL_NEURAL)) },
                derived = { calls += "derived"; Result.success(track(ChordTrackSource.SONGSTERR_DERIVED_FROM_TAB)) },
            )
        assertEquals(ChordTrackSource.SONGSTERR_AUTHORED, result.getOrThrow().source)
        assertEquals(listOf("authored"), calls)
    }

    @Test
    fun `local neural is tried before tab-derived fallback`() = runBlocking {
        val calls = mutableListOf<String>()
        val result =
            resolveAndroidChordPriority(
                authored = { calls += "authored"; Result.failure(ChordUnavailableException("none")) },
                localNeural = { calls += "local"; Result.success(track(ChordTrackSource.LOCAL_NEURAL)) },
                derived = { calls += "derived"; Result.success(track(ChordTrackSource.SONGSTERR_DERIVED_FROM_TAB)) },
            )
        assertEquals(ChordTrackSource.LOCAL_NEURAL, result.getOrThrow().source)
        assertEquals(listOf("authored", "local"), calls)
    }

    @Test
    fun `tab-derived is used only after authored and local fail`() = runBlocking {
        val calls = mutableListOf<String>()
        val result =
            resolveAndroidChordPriority(
                authored = { calls += "authored"; Result.failure(ChordUnavailableException("none")) },
                localNeural = { calls += "local"; Result.failure(ChordUnavailableException("model unavailable")) },
                derived = { calls += "derived"; Result.success(track(ChordTrackSource.SONGSTERR_DERIVED_FROM_TAB)) },
            )
        assertEquals(ChordTrackSource.SONGSTERR_DERIVED_FROM_TAB, result.getOrThrow().source)
        assertEquals(listOf("authored", "local", "derived"), calls)
    }

    private fun track(source: ChordTrackSource) =
        ChordTrack(
            songsterrSongId = 0,
            title = "Song",
            artist = "Artist",
            guitarTrackName = null,
            source = source,
            events = listOf(ChordEvent(0, 1000, "C")),
            tabDurationMs = 1000,
        )
}
