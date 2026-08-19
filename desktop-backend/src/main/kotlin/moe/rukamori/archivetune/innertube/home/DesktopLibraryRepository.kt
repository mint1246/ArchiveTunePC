/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.innertube.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.cache.DesktopRequestPriority
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import org.json.JSONArray
import org.json.JSONObject

data class DesktopRemoteLibraryState(
    val accountScope: String = "",
    val playlists: List<PlaylistItem> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val updatedAtEpochMillis: Long? = null,
)

/** Canonical account-playlist state shared by Library, Home and playlist pickers. */
class DesktopLibraryRepository(
    private val backend: ArchiveTuneJvmClient,
    private val coordinator: DesktopResourceCoordinator,
    private val scope: CoroutineScope,
    private val accountScopeProvider: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(DesktopRemoteLibraryState())
    val state: StateFlow<DesktopRemoteLibraryState> = _state.asStateFlow()

    suspend fun activate(forceRefresh: Boolean = false) {
        val accountScope = accountScopeProvider()
        ensureScope(accountScope)
        val current = _state.value
        if (forceRefresh || current.playlists.isEmpty() || isStale(current.updatedAtEpochMillis)) {
            if (current.playlists.isEmpty()) refresh(DesktopRequestPriority.FOREGROUND)
            else scope.launch(Dispatchers.IO) { refresh(DesktopRequestPriority.REFRESH) }
        }
    }

    suspend fun onAccountScopeChanged() {
        ensureScope(accountScopeProvider())
    }

    suspend fun refresh(priority: DesktopRequestPriority = DesktopRequestPriority.REFRESH): Result<List<PlaylistItem>> {
        val accountScope = accountScopeProvider()
        ensureScope(accountScope)
        val hadContent = _state.value.playlists.isNotEmpty()
        _state.value = _state.value.copy(
            isInitialLoading = !hadContent,
            isRefreshing = hadContent,
            error = null,
        )
        val result = coordinator.singleFlight("library-playlists:$accountScope", priority) {
            withContext(Dispatchers.IO) {
                backend.libraryPlaylists().map { page ->
                    page.items.filterIsInstance<PlaylistItem>().filterNot { it.id == "SE" }.distinctBy(PlaylistItem::id)
                }
            }
        }
        result.onSuccess { playlists ->
            val updatedAt = now()
            _state.value = DesktopRemoteLibraryState(
                accountScope = accountScope,
                playlists = playlists,
                updatedAtEpochMillis = updatedAt,
            )
            coordinator.putMemory(cacheKey(accountScope), playlists, updatedAt)
            scope.launch(Dispatchers.IO) {
                coordinator.writePersistent(cacheKey(accountScope), encodePlaylists(playlists), updatedAt)
            }
        }.onFailure { failure ->
            _state.value = _state.value.copy(
                isInitialLoading = false,
                isRefreshing = false,
                error = failure.message ?: "Could not load your YouTube Music playlists.",
            )
        }
        return result
    }

    fun renameCached(playlistId: String, name: String) {
        _state.value = _state.value.copy(
            playlists = _state.value.playlists.map { if (it.id == playlistId) it.copy(title = name) else it },
        )
        persistCurrentSoon()
    }

    fun removeCached(playlistId: String) {
        _state.value = _state.value.copy(playlists = _state.value.playlists.filterNot { it.id == playlistId })
        persistCurrentSoon()
    }

    fun addCached(playlistId: String, name: String) {
        if (_state.value.playlists.any { it.id == playlistId }) return
        upsertCached(
            PlaylistItem(
                id = playlistId,
                title = name,
                author = null,
                songCountText = null,
                thumbnail = null,
                playEndpoint = null,
                shuffleEndpoint = null,
                radioEndpoint = null,
                isEditable = true,
            ),
        )
    }

    fun upsertCached(playlist: PlaylistItem) {
        _state.value = _state.value.copy(
            playlists = listOf(playlist) + _state.value.playlists.filterNot { it.id == playlist.id },
        )
        persistCurrentSoon()
    }

    fun invalidateFreshness() {
        _state.value = _state.value.copy(updatedAtEpochMillis = null)
    }

    private suspend fun ensureScope(accountScope: String) {
        if (_state.value.accountScope == accountScope) return
        val key = cacheKey(accountScope)
        val memory = coordinator.memoryValue<List<PlaylistItem>>(key)
        if (memory != null) {
            _state.value = DesktopRemoteLibraryState(accountScope, memory.first, updatedAtEpochMillis = memory.second)
            return
        }
        val persisted = coordinator.readPersistent(key)
        val playlists = persisted?.payload?.let(::decodePlaylists).orEmpty()
        if (playlists.isNotEmpty() && persisted != null) {
            coordinator.putMemory(key, playlists, persisted.storedAtEpochMillis)
        }
        _state.value = DesktopRemoteLibraryState(
            accountScope = accountScope,
            playlists = playlists,
            updatedAtEpochMillis = persisted?.storedAtEpochMillis,
        )
    }

    private fun persistCurrentSoon() {
        val snapshot = _state.value
        if (snapshot.accountScope.isBlank()) return
        coordinator.putMemory(cacheKey(snapshot.accountScope), snapshot.playlists, snapshot.updatedAtEpochMillis ?: now())
        scope.launch(Dispatchers.IO) {
            coordinator.writePersistent(
                cacheKey(snapshot.accountScope),
                encodePlaylists(snapshot.playlists),
                snapshot.updatedAtEpochMillis ?: now(),
            )
        }
    }

    private fun isStale(updatedAt: Long?): Boolean = updatedAt == null || now() - updatedAt >= PLAYLIST_TTL_MS
    private fun cacheKey(scope: String) = "library-playlists-cache:$scope"

    private companion object {
        const val PLAYLIST_TTL_MS = 5L * 60L * 1000L
    }
}

internal fun encodePlaylists(playlists: List<PlaylistItem>): String =
    JSONArray().apply {
        playlists.forEach { playlist ->
            put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("title", playlist.title)
                    .put("authorName", playlist.author?.name ?: JSONObject.NULL)
                    .put("authorId", playlist.author?.id ?: JSONObject.NULL)
                    .put("songCountText", playlist.songCountText ?: JSONObject.NULL)
                    .put("thumbnail", playlist.thumbnail ?: JSONObject.NULL)
                    .put("editable", playlist.isEditable)
                    .put("description", playlist.description ?: JSONObject.NULL),
            )
        }
    }.toString()

internal fun decodePlaylists(raw: String): List<PlaylistItem> = runCatching {
    val array = JSONArray(raw)
    buildList(array.length()) {
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val id = json.optString("id").takeIf(String::isNotBlank) ?: continue
            val title = json.optString("title").takeIf(String::isNotBlank) ?: continue
            add(
                PlaylistItem(
                    id = id,
                    title = title,
                    author = json.optString("authorName").takeIf(String::isNotBlank)?.let { name ->
                        Artist(name = name, id = json.optString("authorId").takeIf(String::isNotBlank))
                    },
                    songCountText = json.optString("songCountText").takeIf(String::isNotBlank),
                    thumbnail = json.optString("thumbnail").takeIf(String::isNotBlank),
                    playEndpoint = null,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                    isEditable = json.optBoolean("editable"),
                    description = json.optString("description").takeIf(String::isNotBlank),
                ),
            )
        }
    }
}.getOrDefault(emptyList())