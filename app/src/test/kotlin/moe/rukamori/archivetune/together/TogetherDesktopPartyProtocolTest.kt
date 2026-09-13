/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherDesktopPartyProtocolTest {
    @Test
    fun desktopFullSnapshotDecodesIntoAndroidRoomState() {
        val message =
            TogetherDesktopPartyProtocol.parse(
                """{"type":"full","queueRevision":"rev-1","currentIndex":1,"positionMs":4200,"playWhenReady":true,"repeatMode":"ALL","shuffleEnabled":true,"queue":[{"id":"a","title":"First","artists":[{"id":"","name":"Artist A"}],"durationHintMs":123000},{"id":"b","title":"Second","artists":[{"id":"","name":"Artist B"}],"artworkUri":"https://img","durationHintMs":456000}]}""",
            )!!

        val state =
            TogetherDesktopPartyProtocol.mergeRemoteSnapshot(
                message = message,
                sessionId = "session",
                selfParticipantId = "android-self",
                previous = null,
                permissions = TogetherGuestPermissions(),
                nowElapsedRealtimeMs = 1234L,
            )!!

        assertEquals("rev-1", state.queueHash)
        assertEquals(1, state.currentIndex)
        assertEquals(4200L, state.positionMs)
        assertTrue(state.isPlaying)
        assertEquals(2, state.repeatMode)
        assertTrue(state.shuffleEnabled)
        assertEquals(listOf("a", "b"), state.queue.map { it.id })
        assertEquals(listOf("Artist B"), state.queue[1].artists)
        assertEquals("https://img", state.queue[1].thumbnailUrl)
        assertEquals(456, state.queue[1].durationSec)
        assertEquals(TogetherDesktopPartyProtocol.DESKTOP_HOST_ID, state.hostId)
        assertEquals(2, state.participants.size)
    }

    @Test
    fun desktopPermissionsStayIndependent() {
        val message =
            TogetherDesktopPartyProtocol.parse(
                """{"type":"permissions","canEditQueue":false,"canControlPlayback":true,"canChangeSong":true,"canSeek":false}""",
            )!!
        val permissions = TogetherDesktopPartyProtocol.decodePermissions(message)

        assertFalse(permissions.canEditQueue)
        assertTrue(permissions.canControlPlayback)
        assertTrue(permissions.canChangeSong)
        assertFalse(permissions.canSeek)
    }

    @Test
    fun androidRoomStateEncodesDesktopFullAndTransportFrames() {
        val state =
            TogetherRoomState(
                sessionId = "session",
                hostId = "host",
                queue = listOf(TogetherTrack(id = "track", title = "Track", artists = listOf("Artist"), durationSec = 180)),
                queueHash = "hash",
                currentIndex = 0,
                isPlaying = true,
                positionMs = 9000L,
                repeatMode = 1,
                shuffleEnabled = false,
            )

        val full = TogetherDesktopPartyProtocol.parse(TogetherDesktopPartyProtocol.encodeFull(state))!!
        val transport = TogetherDesktopPartyProtocol.parse(TogetherDesktopPartyProtocol.encodeTransport(state))!!

        assertEquals("full", TogetherDesktopPartyProtocol.type(full))
        assertEquals("hash", full["queueRevision"]?.toString()?.trim('"'))
        assertTrue(full["queue"].toString().contains("track"))
        assertEquals("transport", TogetherDesktopPartyProtocol.type(transport))
        assertTrue(transport["playWhenReady"].toString().toBoolean())
    }

    @Test
    fun desktopGuestControlsMapToAndroidActions() {
        val play = TogetherDesktopPartyProtocol.parse("""{"type":"set_playing","playWhenReady":true}""")!!
        val seek = TogetherDesktopPartyProtocol.parse("""{"type":"seek","positionMs":5000}""")!!
        val select = TogetherDesktopPartyProtocol.parse("""{"type":"queue_select","logicalIndex":3}""")!!

        assertEquals(ControlAction.Play, TogetherDesktopPartyProtocol.decodeGuestControl(play))
        assertEquals(ControlAction.SeekTo(5000L), TogetherDesktopPartyProtocol.decodeGuestControl(seek))
        assertEquals(ControlAction.SeekToIndex(3, 0L), TogetherDesktopPartyProtocol.decodeGuestControl(select))
    }

    @Test
    fun playNowUsesDesktopQueueReplaceWithInsertedTrackSelected() {
        val state =
            TogetherRoomState(
                sessionId = "session",
                hostId = TogetherDesktopPartyProtocol.DESKTOP_HOST_ID,
                queue =
                    listOf(
                        TogetherTrack(id = "a", title = "A"),
                        TogetherTrack(id = "b", title = "B"),
                        TogetherTrack(id = "c", title = "C"),
                    ),
                currentIndex = 1,
            )

        val message =
            TogetherDesktopPartyProtocol.parse(
                TogetherDesktopPartyProtocol.encodePlayNowTrack(TogetherTrack(id = "new", title = "New"), state),
            )!!
        val decoded = TogetherDesktopPartyProtocol.decodeGuestQueueReplace(message)!!

        assertEquals(2, decoded.first)
        assertEquals(listOf("a", "b", "new", "c"), decoded.second.map { it.id })
    }

    @Test
    fun desktopQueueReplaceAndRemoveDecodeForAndroidHost() {
        val replace =
            TogetherDesktopPartyProtocol.parse(
                """{"type":"queue_replace","startIndex":1,"items":[{"id":"a","title":"A","artists":[]},{"id":"b","title":"B","artists":[]}]}""",
            )!!
        val remove =
            TogetherDesktopPartyProtocol.parse(
                """{"type":"queue_remove","logicalIndex":2,"expectedId":"c","queueRevision":"rev"}""",
            )!!

        val decodedReplace = TogetherDesktopPartyProtocol.decodeGuestQueueReplace(replace)!!
        assertEquals(1, decodedReplace.first)
        assertEquals(listOf("a", "b"), decodedReplace.second.map { it.id })
        assertEquals(2 to "c", TogetherDesktopPartyProtocol.decodeGuestQueueRemove(remove))
    }
}
