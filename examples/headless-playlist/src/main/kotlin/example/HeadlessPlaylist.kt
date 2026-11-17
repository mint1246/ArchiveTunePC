package example

import moe.rukamori.archivetune.sdk.playlist.AddToPlaylistPickerSession
import moe.rukamori.archivetune.sdk.playlist.AiContentFilterMatcher
import moe.rukamori.archivetune.sdk.playlist.LibraryTopMixPolicy
import moe.rukamori.archivetune.sdk.playlist.PlaylistCsvExporter
import moe.rukamori.archivetune.sdk.playlist.PlaylistCsvSong
import moe.rukamori.archivetune.sdk.playlist.PlaylistSongSort
import moe.rukamori.archivetune.sdk.playlist.PlaylistTagPolicy
import moe.rukamori.archivetune.sdk.playlist.WeightedMixCandidate
import moe.rukamori.archivetune.sdk.playlist.addToPlaylistPickerPresentation
import moe.rukamori.archivetune.sdk.playlist.playlistSongOccurrences
import moe.rukamori.archivetune.sdk.playlist.sortPlaylistSongOccurrences

private data class SampleTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    val isPodcast: Boolean = false,
    val isExplicit: Boolean = false,
)

fun main() {
    val tracks = listOf(
        SampleTrack("t1", "Midnight Signal", listOf("Human Band"), "City Lights", 210_000L),
        SampleTrack("t2", "Synthetic Echo", listOf("@AI_Slop_Channel"), "Generated", 180_000L),
        SampleTrack("t1", "Midnight Signal", listOf("Human Band"), "City Lights", 210_000L),
        SampleTrack("t3", "Morning Coffee", listOf("Acoustic Trio"), "Cafe", 155_000L, isExplicit = true),
    )

    // 1. AI Content Filter matching
    val blocklist = AiContentFilterMatcher.parseChannelList("! Comment\n@AI_Slop_Channel\n")
    val blockedKeys = AiContentFilterMatcher.effectiveBlockedKeys(blocklist, emptySet(), includeModerateConfidence = false)
    val safeTracks = AiContentFilterMatcher.filterItems(tracks, enabled = true, blockedChannelKeys = blockedKeys) {
        it.artists.asSequence()
    }
    check(safeTracks.size == 3)

    // 2. Playlist ordering & duplicate occurrence preservation
    val occurrences = playlistSongOccurrences(
        songs = safeTracks,
        query = "",
        id = SampleTrack::id,
        title = SampleTrack::title,
        artists = SampleTrack::artists,
        album = SampleTrack::album,
    )
    val sortedByDuration = sortPlaylistSongOccurrences(
        occurrences = occurrences,
        sort = PlaylistSongSort.DURATION,
        title = SampleTrack::title,
        firstArtist = { it.artists.firstOrNull().orEmpty() },
        album = { it.album.orEmpty() },
        duration = SampleTrack::durationMs,
    )
    check(sortedByDuration.first().id == "t3")

    // 3. CSV export
    val csvRecords = PlaylistCsvExporter.records(
        songs = safeTracks.map { PlaylistCsvSong(it.id, it.title, it.artists, it.album) },
    )
    val csvBytes = PlaylistCsvExporter.encode(csvRecords)
    val fileName = PlaylistCsvExporter.suggestedFileName("Late Night: Mix?")
    check(csvBytes.isNotEmpty() && fileName == "Late Night_ Mix_.csv")

    // 4. Add-to-playlist picker session
    val session = AddToPlaylistPickerSession(safeTracks.first()).beginAdd()
    check(session.adding)
    check(session.finishAdd(Result.success(Unit)) == null)
    val presentation = addToPlaylistPickerPresentation(playlistCount = 0, refreshing = false, refreshError = null)
    check(presentation.showEmptyState)

    // 5. Playlist tag policies
    val draft = PlaylistTagPolicy.normalizeDraft("  Focus  ", " #4ECDC4 ")
    val parsedTags = PlaylistTagPolicy.parseTagNames("Focus, chill, FOCUS, Ambient")
    val assignment = PlaylistTagPolicy.planReplacePlaylistTags("pl-1", listOf("tag-1", "tag-1", "tag-2"))
    check(draft?.name == "Focus" && parsedTags == listOf("Focus", "chill", "Ambient"))
    check(assignment?.tagIds == listOf("tag-1", "tag-2"))

    // 6. Library top-mix policies
    val eligibleForMix = LibraryTopMixPolicy.filterEligibleTracks(
        tracks = safeTracks,
        hideExplicit = true,
        isPodcast = SampleTrack::isPodcast,
        isExplicit = SampleTrack::isExplicit,
    )
    val weighted = LibraryTopMixPolicy.selectWeightedMixTracks(
        candidates = eligibleForMix.mapIndexed { index, track ->
            WeightedMixCandidate(track, track.id, weight = (index + 1).toDouble())
        },
        limit = LibraryTopMixPolicy.DISPLAY_LIMIT,
    )
    check(weighted.size == 1 && weighted.single().id == "t1")

    println("Exercised playlist-sdk policies headlessly without UI")
}
