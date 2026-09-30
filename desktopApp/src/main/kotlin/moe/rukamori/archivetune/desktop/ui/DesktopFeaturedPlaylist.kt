package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.pages.HomePage

/** Keeps featured shelf navigation tied to the server's playlist, including warm cached shelves. */
internal fun HomePage.Section.FeaturedCard.asDesktopPlaylist(): PlaylistItem = PlaylistItem(
    id = playEndpoint?.playlistId ?: endpoint?.browseId?.removePrefix("VL") ?: id.removePrefix("VL"),
    title = title,
    author = null,
    songCountText = null,
    thumbnail = thumbnail,
    playEndpoint = playEndpoint,
    shuffleEndpoint = shuffleEndpoint,
    radioEndpoint = radioEndpoint,
    description = subtitle,
)
