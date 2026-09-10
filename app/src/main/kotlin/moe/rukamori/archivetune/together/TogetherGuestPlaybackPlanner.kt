/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

internal sealed interface TogetherGuestOp {
    data class Control(
        val action: ControlAction,
    ) : TogetherGuestOp

    data class AddTrack(
        val track: TogetherTrack,
        val mode: AddTrackMode,
        val playNow: Boolean = false,
    ) : TogetherGuestOp
}

internal object TogetherGuestPlaybackPlanner {
    fun planPlayTrackNow(
        roomState: TogetherRoomState,
        participantId: String,
        track: TogetherTrack,
        positionMs: Long,
        playWhenReady: Boolean,
    ): List<TogetherGuestOp> {
        val permissions = roomState.permissionsFor(participantId)
        if (!permissions.canChangeSong) return emptyList()
        val trackId = track.id.trim()
        if (trackId.isBlank()) return emptyList()

        val shouldRequestPlay = playWhenReady && !roomState.isPlaying && permissions.canControlPlayback
        val safePos = positionMs.coerceAtLeast(0L)

        val existsInHostQueue = roomState.queue.any { it.id == trackId }
        return when {
            existsInHostQueue -> {
                buildList {
                    add(
                        TogetherGuestOp.Control(
                            ControlAction.SeekToTrack(
                                trackId = trackId,
                                positionMs = if (permissions.canSeek) safePos else 0L,
                            ),
                        ),
                    )
                    if (shouldRequestPlay) add(TogetherGuestOp.Control(ControlAction.Play))
                }
            }

            else -> {
                buildList {
                    add(TogetherGuestOp.AddTrack(track.copy(id = trackId), AddTrackMode.PLAY_NEXT, playNow = true))
                    add(TogetherGuestOp.Control(ControlAction.SeekToTrack(trackId = trackId, positionMs = 0L)))
                    if (shouldRequestPlay) add(TogetherGuestOp.Control(ControlAction.Play))
                }
            }
        }
    }
}
