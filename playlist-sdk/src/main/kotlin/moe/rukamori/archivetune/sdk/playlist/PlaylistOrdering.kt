/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.sdk.playlist

enum class PlaylistSongSort(val label: String) {
    ORIGINAL("Original order"),
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DURATION("Duration"),
}

data class PlaylistSongOccurrence<T>(
    val song: T,
    val originalIndex: Int,
    val id: String,
) {
    val stableKey: String
        get() = "$id:$originalIndex"
}

fun <T> playlistSongOccurrences(
    songs: List<T>,
    query: String,
    id: (T) -> String,
    title: (T) -> String,
    artists: (T) -> List<String>,
    album: (T) -> String?,
): List<PlaylistSongOccurrence<T>> =
    songs.mapIndexedNotNull { index, song ->
        if (
            query.isBlank() ||
            title(song).contains(query, ignoreCase = true) ||
            artists(song).any { it.contains(query, ignoreCase = true) } ||
            album(song)?.contains(query, ignoreCase = true) == true
        ) {
            PlaylistSongOccurrence(song = song, originalIndex = index, id = id(song))
        } else {
            null
        }
    }

fun <T> sortPlaylistSongOccurrences(
    occurrences: List<PlaylistSongOccurrence<T>>,
    sort: PlaylistSongSort,
    title: (T) -> String,
    firstArtist: (T) -> String,
    album: (T) -> String,
    duration: (T) -> Long?,
): List<PlaylistSongOccurrence<T>> = when (sort) {
    PlaylistSongSort.ORIGINAL -> occurrences.sortedBy(PlaylistSongOccurrence<T>::originalIndex)
    PlaylistSongSort.TITLE -> occurrences.sortedWith(
        compareBy<PlaylistSongOccurrence<T>, String>(String.CASE_INSENSITIVE_ORDER) { title(it.song) }
            .thenBy(PlaylistSongOccurrence<T>::originalIndex),
    )
    PlaylistSongSort.ARTIST -> occurrences.sortedWith(
        compareBy<PlaylistSongOccurrence<T>, String>(String.CASE_INSENSITIVE_ORDER) { firstArtist(it.song) }
            .thenBy(PlaylistSongOccurrence<T>::originalIndex),
    )
    PlaylistSongSort.ALBUM -> occurrences.sortedWith(
        compareBy<PlaylistSongOccurrence<T>, String>(String.CASE_INSENSITIVE_ORDER) { album(it.song) }
            .thenBy(PlaylistSongOccurrence<T>::originalIndex),
    )
    PlaylistSongSort.DURATION -> occurrences.sortedWith(
        compareBy<PlaylistSongOccurrence<T>> { duration(it.song) ?: Long.MAX_VALUE }
            .thenBy(PlaylistSongOccurrence<T>::originalIndex),
    )
}

fun <T> sortBySongSort(
    items: List<T>,
    sort: PlaylistSongSort,
    title: (T) -> String,
    firstArtist: (T) -> String,
    album: (T) -> String,
    duration: (T) -> Long?,
): List<T> = when (sort) {
    PlaylistSongSort.ORIGINAL -> items
    PlaylistSongSort.TITLE -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, title))
    PlaylistSongSort.ARTIST -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, firstArtist))
    PlaylistSongSort.ALBUM -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, album))
    PlaylistSongSort.DURATION -> items.sortedBy { duration(it) ?: Long.MAX_VALUE }
}

data class LibraryFilterResult<P, T>(
    val playlists: List<P>,
    val tracks: List<T>,
)

fun <P, T> filterLibraryContent(
    playlists: List<P>,
    tracks: List<T>,
    query: String,
    playlistTitle: (P) -> String,
    playlistAuthor: (P) -> String?,
    trackTitle: (T) -> String,
    trackArtist: (T) -> String,
    trackAlbum: (T) -> String?,
): LibraryFilterResult<P, T> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return LibraryFilterResult(playlists, tracks)
    return LibraryFilterResult(
        playlists = playlists.filter { playlist ->
            playlistTitle(playlist).contains(trimmedQuery, ignoreCase = true) ||
                playlistAuthor(playlist)?.contains(trimmedQuery, ignoreCase = true) == true
        },
        tracks = tracks.filter { track ->
            trackTitle(track).contains(trimmedQuery, ignoreCase = true) ||
                trackArtist(track).contains(trimmedQuery, ignoreCase = true) ||
                trackAlbum(track)?.contains(trimmedQuery, ignoreCase = true) == true
        },
    )
}
