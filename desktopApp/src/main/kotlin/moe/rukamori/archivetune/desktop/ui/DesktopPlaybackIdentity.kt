/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.playback.api.PlaybackController

internal data class DesktopPlaybackIdentity(
    val isPlaying: Boolean,
    val currentItemId: String?,
    val currentAlbumId: String?,
)

@Composable
internal fun rememberPlaybackIdentity(playback: PlaybackController): DesktopPlaybackIdentity {
    val initial = remember(playback) { playback.state.value.toDesktopPlaybackIdentity() }
    val identityFlow = remember(playback) {
        playback.state.map { it.toDesktopPlaybackIdentity() }.distinctUntilChanged()
    }
    val identity by identityFlow.collectAsState(initial)
    return identity
}

private fun moe.rukamori.archivetune.playback.api.PlaybackSnapshot.toDesktopPlaybackIdentity() =
    DesktopPlaybackIdentity(
        isPlaying = isPlaying,
        currentItemId = currentItem?.metadata?.id,
        currentAlbumId = currentItem?.metadata?.album?.id,
    )