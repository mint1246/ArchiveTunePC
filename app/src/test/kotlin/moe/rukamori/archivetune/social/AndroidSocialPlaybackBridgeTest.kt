package moe.rukamori.archivetune.social

import moe.rukamori.archivetune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSocialPlaybackBridgeTest {
    @Test
    fun queueOmitsLocalMediaAndRebasesCurrentIndex() {
        val queue = buildSocialSharedQueue(
            title = "mix",
            metadata = listOf(
                metadata("content://local/one", "Local"),
                metadata("remote-a", "A"),
                metadata("remote-b", "B"),
            ),
            currentIndex = 2,
        )

        requireNotNull(queue)
        assertEquals(listOf("remote-a", "remote-b"), queue.songs.map { it.id })
        assertEquals(1, queue.currentIndex)
    }

    @Test
    fun presenceOnlyPublishesRemoteAudibleSong() {
        val playing = socialPresenceForPlayback(metadata("remote", "Song"), isPlaying = true)
        assertEquals("remote", playing.songId)
        assertEquals("Artist", playing.artist)

        val paused = socialPresenceForPlayback(metadata("remote", "Song"), isPlaying = false)
        assertNull(paused.songId)
        assertFalse(paused.partyJoinable)

        val local = socialPresenceForPlayback(metadata("file:///music/song.flac", "Local"), isPlaying = true)
        assertNull(local.songId)
        assertTrue(!local.online)
    }

    private fun metadata(id: String, title: String) = MediaMetadata(
        id = id,
        title = title,
        artists = listOf(MediaMetadata.Artist(id = null, name = "Artist")),
        duration = 180,
        thumbnailUrl = "https://example.com/$id.jpg",
    )
}
