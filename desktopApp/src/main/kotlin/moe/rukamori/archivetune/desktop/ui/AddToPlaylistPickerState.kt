package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.innertube.models.SongItem

internal data class AddToPlaylistPickerSession(
    val song: SongItem,
    val adding: Boolean = false,
    val addError: String? = null,
) {
    fun beginAdd(): AddToPlaylistPickerSession = copy(adding = true, addError = null)

    fun finishAdd(result: Result<*>): AddToPlaylistPickerSession? = result.fold(
        onSuccess = { null },
        onFailure = { failure ->
            copy(
                adding = false,
                addError = failure.message ?: "Could not add song to playlist",
            )
        },
    )
}

internal data class AddToPlaylistPickerPresentation(
    val showInitialLoading: Boolean,
    val showEmptyState: Boolean,
    val refreshError: String?,
)

internal fun addToPlaylistPickerPresentation(
    playlistCount: Int,
    refreshing: Boolean,
    refreshError: String?,
): AddToPlaylistPickerPresentation = AddToPlaylistPickerPresentation(
    showInitialLoading = refreshing && playlistCount == 0,
    showEmptyState = !refreshing && refreshError == null && playlistCount == 0,
    refreshError = refreshError,
)
