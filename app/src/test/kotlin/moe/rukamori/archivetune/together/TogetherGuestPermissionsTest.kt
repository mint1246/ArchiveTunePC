/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherGuestPermissionsTest {
    @Test
    fun defaultsMatchDesktopGuestPermissions() {
        val permissions = TogetherGuestPermissions()

        assertTrue(permissions.canEditQueue)
        assertTrue(permissions.canControlPlayback)
        assertFalse(permissions.canChangeSong)
        assertFalse(permissions.canSeek)
    }

    @Test
    fun permissionPolicyKeepsFourCapabilitiesIndependent() {
        val defaults = TogetherGuestPermissions()
        assertTrue(defaults.allows(ControlAction.Play))
        assertTrue(defaults.allows(ControlAction.Pause))
        assertFalse(defaults.allows(ControlAction.SkipNext))
        assertFalse(defaults.allows(ControlAction.SeekTo(1234L)))
        assertTrue(defaults.allows(addRequest(playNow = false)))
        assertFalse(defaults.allows(addRequest(playNow = true)))

        val changeOnly =
            TogetherGuestPermissions(
                canEditQueue = false,
                canControlPlayback = false,
                canChangeSong = true,
                canSeek = false,
            )
        assertTrue(changeOnly.allows(ControlAction.SkipPrevious))
        assertTrue(changeOnly.allows(ControlAction.SeekToIndex(index = 2, positionMs = 0L)))
        assertFalse(changeOnly.allows(ControlAction.SeekToIndex(index = 2, positionMs = 500L)))
        assertFalse(changeOnly.allows(ControlAction.SeekTo(500L)))
        assertFalse(changeOnly.allows(addRequest(playNow = false)))
        assertTrue(changeOnly.allows(addRequest(playNow = true)))

        val seekOnly = TogetherGuestPermissions(canEditQueue = false, canControlPlayback = false, canSeek = true)
        assertTrue(seekOnly.allows(ControlAction.SeekTo(500L)))
        assertFalse(seekOnly.allows(ControlAction.SkipNext))

        assertFalse(TogetherGuestPermissions.Full.allows(ControlAction.SetRepeatMode(1)))
        assertFalse(TogetherGuestPermissions.Full.allows(ControlAction.SetShuffleEnabled(true)))
    }

    @Test
    fun participantSerializationWithoutPermissionsRemainsBackwardCompatible() {
        val participant =
            TogetherJson.json.decodeFromString<TogetherParticipant>(
                """{"id":"guest-1","name":"Guest"}""",
            )

        assertNull(participant.permissions)
    }

    @Test
    fun additiveWireFieldsDecodeOldPayloads() {
        val addRequest =
            TogetherJson.json.decodeFromString<AddTrackRequest>(
                """{"sessionId":"session","participantId":"guest-1","track":{"id":"track","title":"Track"},"mode":"PLAY_NEXT"}""",
            )
        val welcome =
            TogetherJson.json.decodeFromString<ServerWelcome>(
                """{"protocolVersion":1,"sessionId":"session","participantId":"guest-1","role":"GUEST","isPending":false,"settings":{}}""",
            )

        assertFalse(addRequest.playNow)
        assertNull(welcome.permissions)
    }

    @Test
    fun missingParticipantPermissionsFallBackToLegacyRoomFlags() {
        val roomState =
            roomState(
                settings =
                    TogetherRoomSettings(
                        allowGuestsToAddTracks = false,
                        allowGuestsToControlPlayback = true,
                    ),
                participantPermissions = null,
            )

        assertEquals(
            TogetherGuestPermissions(
                canEditQueue = false,
                canControlPlayback = true,
                canChangeSong = true,
                canSeek = true,
            ),
            roomState.permissionsFor("guest-1"),
        )
        assertEquals(TogetherGuestPermissions.Full, roomState.permissionsFor("host"))
        assertNull(roomState.permissionsForKnownParticipant("spoofed"))
    }

    @Test
    fun plannerUsesSongChangePermissionAndDoesNotSmuggleSeeking() {
        val permissions =
            TogetherGuestPermissions(
                canEditQueue = false,
                canControlPlayback = false,
                canChangeSong = true,
                canSeek = false,
            )
        val existing =
            roomState(
                participantPermissions = permissions,
                queue = listOf(TogetherTrack(id = "target", title = "Target")),
            )

        val existingOps =
            TogetherGuestPlaybackPlanner.planPlayTrackNow(
                roomState = existing,
                participantId = "guest-1",
                track = TogetherTrack(id = "target", title = "Target"),
                positionMs = 42_000L,
                playWhenReady = true,
            )
        val existingChange = existingOps.single() as TogetherGuestOp.Control
        assertEquals(ControlAction.SeekToTrack("target", 0L), existingChange.action)

        val missingOps =
            TogetherGuestPlaybackPlanner.planPlayTrackNow(
                roomState = existing.copy(queue = emptyList()),
                participantId = "guest-1",
                track = TogetherTrack(id = "new", title = "New"),
                positionMs = 42_000L,
                playWhenReady = true,
            )
        val add = missingOps[0] as TogetherGuestOp.AddTrack
        assertTrue(add.playNow)
        assertEquals(AddTrackMode.PLAY_NEXT, add.mode)
        assertEquals(ControlAction.SeekToTrack("new", 0L), (missingOps[1] as TogetherGuestOp.Control).action)
    }

    @Test
    fun plannerBlocksPlayNewWhenSongChangingIsDisabled() {
        val roomState = roomState(participantPermissions = TogetherGuestPermissions())

        assertTrue(
            TogetherGuestPlaybackPlanner
                .planPlayTrackNow(
                    roomState = roomState,
                    participantId = "guest-1",
                    track = TogetherTrack(id = "target", title = "Target"),
                    positionMs = 0L,
                    playWhenReady = true,
                ).isEmpty(),
        )
    }

    private fun addRequest(playNow: Boolean): AddTrackRequest =
        AddTrackRequest(
            sessionId = "session",
            participantId = "guest-1",
            track = TogetherTrack(id = "track", title = "Track"),
            mode = AddTrackMode.PLAY_NEXT,
            playNow = playNow,
        )

    private fun roomState(
        settings: TogetherRoomSettings = TogetherRoomSettings(),
        participantPermissions: TogetherGuestPermissions?,
        queue: List<TogetherTrack> = emptyList(),
    ): TogetherRoomState =
        TogetherRoomState(
            sessionId = "session",
            hostId = "host",
            participants =
                listOf(
                    TogetherParticipant(
                        id = "host",
                        name = "Host",
                        isHost = true,
                    ),
                    TogetherParticipant(
                        id = "guest-1",
                        name = "Guest",
                        permissions = participantPermissions,
                    ),
                ),
            settings = settings,
            queue = queue,
            queueHash = "",
            currentIndex = 0,
            isPlaying = false,
            positionMs = 0L,
            repeatMode = 0,
            shuffleEnabled = false,
        )
}
