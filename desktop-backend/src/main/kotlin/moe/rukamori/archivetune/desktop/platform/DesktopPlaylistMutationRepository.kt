/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.home.DesktopLibraryRepository

interface DesktopPlaylistMutationBackend {
    suspend fun createPlaylist(name: String, videoIds: List<String>): Result<String>
    suspend fun renamePlaylist(playlistId: String, name: String): Result<Unit>
    suspend fun deletePlaylist(playlistId: String): Result<Unit>
    suspend fun addSongsToPlaylist(playlistId: String, videoIds: List<String>): Result<Unit>
    suspend fun removeSongFromPlaylist(playlistId: String, song: SongItem): Result<Unit>
}

interface DesktopPlaylistBrowseEffects {
    fun invalidatePlaylist(playlistId: String)
    fun refreshPlaylist(playlistId: String)
    fun clearCurrentStack()
    fun currentlyOpenPlaylistId(): String?

    object NoOp : DesktopPlaylistBrowseEffects {
        override fun invalidatePlaylist(playlistId: String) = Unit
        override fun refreshPlaylist(playlistId: String) = Unit
        override fun clearCurrentStack() = Unit
        override fun currentlyOpenPlaylistId(): String? = null
    }
}

/**
 * Headless browse-cache invalidation adapter for callers without a UI navigation controller.
 */
class DesktopBrowseRepositoryPlaylistEffects(
    private val browseRepository: DesktopBrowseRepository,
) : DesktopPlaylistBrowseEffects {
    override fun invalidatePlaylist(playlistId: String) {
        browseRepository.invalidate(DesktopBrowseRoute.Playlist(playlistId))
    }

    override fun refreshPlaylist(playlistId: String) {
        browseRepository.invalidate(DesktopBrowseRoute.Playlist(playlistId))
    }

    override fun clearCurrentStack() = Unit

    override fun currentlyOpenPlaylistId(): String? = null
}

/**
 * Reusable, UI-independent desktop playlist mutation boundary.
 *
 * Preserves full [PlaylistItem] / [SongItem] metadata, track order and duplicates, existing
 * browse/library cache effects, and structured-concurrency cancellation, while guarding
 * account-scoped cache and browse writes across suspension boundaries.
 * The account epoch must change synchronously on session replacement or logout, even when
 * the eventual account scope is the same.
 */
class DesktopPlaylistMutationRepository(
    private val backend: DesktopPlaylistMutationBackend,
    private val libraryRepository: DesktopLibraryRepository,
    private val browseEffects: DesktopPlaylistBrowseEffects = DesktopPlaylistBrowseEffects.NoOp,
    private val accountScopeProvider: () -> String,
    private val accountEpochProvider: () -> Long,
) {
    constructor(
        backend: ArchiveTuneJvmClient,
        libraryRepository: DesktopLibraryRepository,
        browseEffects: DesktopPlaylistBrowseEffects = DesktopPlaylistBrowseEffects.NoOp,
        accountScopeProvider: () -> String,
        accountEpochProvider: () -> Long,
    ) : this(
        backend = ArchiveTuneDesktopPlaylistMutationBackend(backend),
        libraryRepository = libraryRepository,
        browseEffects = browseEffects,
        accountScopeProvider = accountScopeProvider,
        accountEpochProvider = accountEpochProvider,
    )

    private data class ScopeSnapshot(
        val accountScope: String,
        val accountEpoch: Long,
    )

    suspend fun createPlaylist(name: String, songs: List<SongItem> = emptyList()): Result<String> =
        createPlaylistFromIds(name, songs.map(SongItem::id))

    suspend fun createPlaylistFromIds(name: String, videoIds: List<String>): Result<String> {
        val snapshot = captureScopeSnapshot()
        val result = backend.createPlaylist(name, videoIds).rethrowCancellation()
        if (!isSameScope(snapshot)) return result
        return result.onSuccess { playlistId ->
            libraryRepository.addCached(playlistId, name, expectedScope = snapshot.accountScope)
        }
    }

    suspend fun renamePlaylist(original: PlaylistItem, name: String): Result<Unit> {
        val snapshot = captureScopeSnapshot()
        val optimisticApplied = libraryMatchesScope(snapshot.accountScope)
        if (optimisticApplied) {
            libraryRepository.renameCached(original.id, name, expectedScope = snapshot.accountScope)
        }
        val result = try {
            backend.renamePlaylist(original.id, name).rethrowCancellation()
        } catch (cancelled: CancellationException) {
            if (optimisticApplied && isSameScope(snapshot)) {
                libraryRepository.upsertCached(original, expectedScope = snapshot.accountScope)
            }
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        if (!isSameScope(snapshot)) return result
        return result
            .onSuccess { browseEffects.refreshPlaylist(original.id) }
            .onFailure {
                if (optimisticApplied) {
                    libraryRepository.upsertCached(original, expectedScope = snapshot.accountScope)
                }
            }
    }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> {
        val snapshot = captureScopeSnapshot()
        val result = backend.deletePlaylist(playlistId).rethrowCancellation()
        if (!isSameScope(snapshot)) return result
        return result.onSuccess {
            browseEffects.invalidatePlaylist(playlistId)
            libraryRepository.removeCached(playlistId, expectedScope = snapshot.accountScope)
            browseEffects.clearCurrentStack()
        }
    }

    suspend fun addSongsToPlaylist(
        playlistId: String,
        songs: List<SongItem>,
        browseStillActive: () -> Boolean = { true },
    ): Result<Unit> = addVideoIdsToPlaylist(playlistId, songs.map(SongItem::id), browseStillActive)

    suspend fun addVideoIdsToPlaylist(
        playlistId: String,
        videoIds: List<String>,
        browseStillActive: () -> Boolean = { true },
    ): Result<Unit> {
        val snapshot = captureScopeSnapshot()
        val result = backend.addSongsToPlaylist(playlistId, videoIds).rethrowCancellation()
        if (!isSameScope(snapshot) || !browseStillActive()) return result
        return result.onSuccess {
            if (browseEffects.currentlyOpenPlaylistId() == playlistId) {
                browseEffects.refreshPlaylist(playlistId)
            } else {
                browseEffects.invalidatePlaylist(playlistId)
            }
        }
    }

    suspend fun removeSongFromPlaylist(playlistId: String, song: SongItem): Result<Unit> {
        val snapshot = captureScopeSnapshot()
        val result = backend.removeSongFromPlaylist(playlistId, song).rethrowCancellation()
        if (!isSameScope(snapshot)) return result
        return result.onSuccess {
            browseEffects.refreshPlaylist(playlistId)
        }
    }

    private fun captureScopeSnapshot(): ScopeSnapshot =
        ScopeSnapshot(
            accountScope = accountScopeProvider(),
            accountEpoch = accountEpochProvider(),
        )

    private fun isSameScope(snapshot: ScopeSnapshot): Boolean =
        accountScopeProvider() == snapshot.accountScope &&
            accountEpochProvider() == snapshot.accountEpoch &&
            libraryMatchesScope(snapshot.accountScope)

    private fun libraryMatchesScope(expectedScope: String): Boolean {
        val repoScope = libraryRepository.state.value.accountScope
        return repoScope.isEmpty() || repoScope == expectedScope
    }

    private fun <T> Result<T>.rethrowCancellation(): Result<T> = onFailure { error ->
        if (error is CancellationException) throw error
    }
}

private class ArchiveTuneDesktopPlaylistMutationBackend(
    private val backend: ArchiveTuneJvmClient,
) : DesktopPlaylistMutationBackend {
    override suspend fun createPlaylist(name: String, videoIds: List<String>): Result<String> =
        backend.createPlaylist(name, videoIds)

    override suspend fun renamePlaylist(playlistId: String, name: String): Result<Unit> =
        backend.renamePlaylist(playlistId, name)

    override suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        backend.deletePlaylist(playlistId)

    override suspend fun addSongsToPlaylist(playlistId: String, videoIds: List<String>): Result<Unit> =
        backend.addSongsToPlaylist(playlistId, videoIds)

    override suspend fun removeSongFromPlaylist(playlistId: String, song: SongItem): Result<Unit> =
        backend.removeSongFromPlaylist(playlistId, song)
}
