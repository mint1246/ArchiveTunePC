/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlistexport

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.playlist.export.ExportablePlaylist
import moe.rukamori.archivetune.playlist.export.PlaylistExportCoordinator
import javax.inject.Inject

/** Android DI wrapper around the headless playlist export coordinator. */
class ObserveExportablePlaylistsUseCase
    @Inject
    constructor(
        store: AndroidPlaylistExportStore,
    ) {
    private val coordinator = PlaylistExportCoordinator(store)

    operator fun invoke(): Flow<List<ExportablePlaylist>> = coordinator.observe()
}

/** Android DI wrapper around the headless playlist export coordinator. */
class ExportPlaylistAsCsvUseCase
    @Inject
    constructor(
        store: AndroidPlaylistExportStore,
    ) {
    private val coordinator = PlaylistExportCoordinator(store)

    suspend operator fun invoke(
        playlistId: String,
        destination: Uri,
    ) = coordinator.export(playlistId, destination)
}
