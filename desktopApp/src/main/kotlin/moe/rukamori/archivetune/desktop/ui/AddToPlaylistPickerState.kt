package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.innertube.models.SongItem

internal typealias AddToPlaylistPickerSession =
    moe.rukamori.archivetune.sdk.playlist.AddToPlaylistPickerSession<SongItem>

internal typealias AddToPlaylistPickerPresentation =
    moe.rukamori.archivetune.sdk.playlist.AddToPlaylistPickerPresentation

internal fun addToPlaylistPickerPresentation(
    playlistCount: Int,
    refreshing: Boolean,
    refreshError: String?,
): AddToPlaylistPickerPresentation =
    moe.rukamori.archivetune.sdk.playlist.addToPlaylistPickerPresentation(
        playlistCount = playlistCount,
        refreshing = refreshing,
        refreshError = refreshError,
    )
