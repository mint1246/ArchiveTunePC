/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.desktop.VlcjPlaybackController

/** Adapts the real VLC controller to the headless stem coordinator's playback seam. */
internal class VlcjStemPlaybackBridge(
    private val playback: VlcjPlaybackController,
) : StemPlaybackBridge {
    override val state: StateFlow<PlaybackSnapshot> get() = playback.state

    override suspend fun decodeStemInput(item: PlaybackItem, output: Path): Path =
        playback.decodeStemInput(item, output)

    override suspend fun compressGeneratedStemSetForCache(directory: Path): List<Path> =
        playback.compressGeneratedStemSetForCache(directory)

    override suspend fun setExternalAudioRendererActive(active: Boolean) =
        playback.setExternalAudioRendererActive(active)
}
