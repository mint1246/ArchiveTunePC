/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.sdk.playlist

data class AddToPlaylistPickerSession<T>(
    val song: T,
    val songs: List<T> = listOf(song),
    val adding: Boolean = false,
    val addError: String? = null,
) {
    fun beginAdd(): AddToPlaylistPickerSession<T> = copy(adding = true, addError = null)

    fun finishAdd(result: Result<*>): AddToPlaylistPickerSession<T>? = result.fold(
        onSuccess = { null },
        onFailure = { failure ->
            copy(
                adding = false,
                addError = failure.message ?: "Could not add song to playlist",
            )
        },
    )
}

data class AddToPlaylistPickerPresentation(
    val showInitialLoading: Boolean,
    val showEmptyState: Boolean,
    val refreshError: String?,
)

fun addToPlaylistPickerPresentation(
    playlistCount: Int,
    refreshing: Boolean,
    refreshError: String?,
): AddToPlaylistPickerPresentation = AddToPlaylistPickerPresentation(
    showInitialLoading = refreshing && playlistCount == 0,
    showEmptyState = !refreshing && refreshError == null && playlistCount == 0,
    refreshError = refreshError,
)
