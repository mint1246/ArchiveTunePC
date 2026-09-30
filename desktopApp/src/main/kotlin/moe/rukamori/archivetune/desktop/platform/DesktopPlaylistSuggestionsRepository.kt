/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.text.Normalizer
import java.time.Year
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.cache.DesktopRequestPriority
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.pages.SearchResult
import org.json.JSONArray
import org.json.JSONObject

internal data class DesktopPlaylistSuggestionsState(
    val playlistId: String? = null,
    val query: String = "",
    val currentQueryIndex: Int = 0,
    val totalQueries: Int = 0,
    val items: List<SongItem> = emptyList(),
    val continuation: String? = null,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isAdding: Boolean = false,
    val error: String? = null,
)

internal interface DesktopPlaylistSuggestionsBackend {
    suspend fun search(query: String): Result<SearchResult>
    suspend fun continuation(token: String): Result<SearchResult>
    suspend fun addSongs(playlistId: String, videoIds: List<String>): Result<Unit>
}

/** Account-scoped, persistent YouTube song suggestions for editable playlists. */
internal class DesktopPlaylistSuggestionsRepository(
    private val backend: DesktopPlaylistSuggestionsBackend,
    private val coordinator: DesktopResourceCoordinator,
    private val accountScopeProvider: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        backend: ArchiveTuneJvmClient,
        coordinator: DesktopResourceCoordinator,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        accountScopeProvider: () -> String,
        now: () -> Long = System::currentTimeMillis,
    ) : this(ArchiveTuneDesktopPlaylistSuggestionsBackend(backend), coordinator, accountScopeProvider, now)

    private data class QueryPage(
        val query: String,
        val items: List<SongItem>,
        val continuation: String?,
    )

    private data class PlaylistCache(
        val accountScope: String,
        val playlistId: String,
        val title: String,
        val playlistSongIds: List<String>,
        val queries: List<String>,
        val pages: Map<Int, QueryPage>,
        val currentQueryIndex: Int,
        val updatedAt: Long,
    )

    private val mutex = Mutex()
    private val accountGeneration = AtomicLong(0L)
    private val caches = object : LinkedHashMap<String, PlaylistCache>(16, 0.75f, true) {}
    private var persistentCacheLoaded = false
    private var activeCacheKey: String? = null
    private var activeRequest: ActiveRequest? = null
    private val _state = MutableStateFlow(DesktopPlaylistSuggestionsState())
    val state: StateFlow<DesktopPlaylistSuggestionsState> = _state.asStateFlow()
    private val _accountScope = MutableStateFlow(accountScopeProvider())
    val accountScope: StateFlow<String> = _accountScope.asStateFlow()

    private data class ActiveRequest(
        val playlistId: String,
        val title: String,
        val playlistSongs: List<SongItem>,
        val accountScope: String,
        val accountGeneration: Long,
    )

    suspend fun activate(
        playlistId: String,
        title: String,
        playlistSongs: List<SongItem>,
        forceRefresh: Boolean = false,
    ) = mutex.withLock {
        val normalizedPlaylistId = playlistId.trim().removePrefix("VL")
        if (normalizedPlaylistId.isEmpty()) {
            _state.value = DesktopPlaylistSuggestionsState(error = "Playlist ID is required")
            return@withLock
        }
        loadPersistentCache()
        val account = accountScopeProvider()
        adoptAccountScope(account)
        val request = ActiveRequest(
            normalizedPlaylistId,
            title,
            playlistSongs.distinctBy(SongItem::id),
            account,
            accountGeneration.get(),
        )
        activeRequest = request
        val key = cacheKey(account, normalizedPlaylistId)
        activeCacheKey = key
        val existingIds = request.playlistSongs.map(SongItem::id)
        val existing = caches[key]
        val validCache =
            existing != null &&
                existing.title == title &&
                existing.playlistSongIds == existingIds &&
                now() - existing.updatedAt < CACHE_TTL_MS

        if (validCache && !forceRefresh) {
            val cached = existing!!
            if (cached.pages[cached.currentQueryIndex] != null || cached.queries.isEmpty()) {
                publish(cached, isLoading = false)
            } else {
                publish(cached, isLoading = true)
                fetchQueryPage(key, cached, cached.currentQueryIndex, request.accountGeneration)
            }
            return@withLock
        }

        if (validCache) {
            publish(existing!!, isLoading = true)
        } else {
            val replacement =
                PlaylistCache(
                    accountScope = account,
                    playlistId = normalizedPlaylistId,
                    title = title,
                    playlistSongIds = existingIds,
                    queries = buildQueries(title, request.playlistSongs),
                    pages = emptyMap(),
                    currentQueryIndex = 0,
                    updatedAt = now(),
                )
            caches[key] = replacement
            if (replacement.queries.isEmpty()) {
                publish(replacement, isLoading = false)
                persistCache()
                return@withLock
            }
            publish(replacement, isLoading = true)
        }
        fetchQueryPage(key, cache = caches.getValue(key), queryIndex = caches.getValue(key).currentQueryIndex, request.accountGeneration)
    }

    suspend fun changeQueryPage(direction: Int) = mutex.withLock {
        if (direction != -1 && direction != 1) return@withLock
        val key = activeCacheKey ?: return@withLock
        val request = activeRequest ?: return@withLock
        if (!isCurrentAccount(request.accountScope, request.accountGeneration)) return@withLock
        val cache = caches[key] ?: return@withLock
        val targetIndex = cache.currentQueryIndex + direction
        if (targetIndex !in cache.queries.indices) return@withLock
        caches[key] = cache.copy(currentQueryIndex = targetIndex, updatedAt = now())
        val target = caches.getValue(key)
        val page = target.pages[targetIndex]
        if (page != null) {
            publish(target, isLoading = false)
        } else {
            publish(target, isLoading = true)
            fetchQueryPage(key, target, targetIndex, request.accountGeneration)
        }
    }

    suspend fun loadMore() = mutex.withLock {
        val key = activeCacheKey ?: return@withLock
        val request = activeRequest ?: return@withLock
        if (!isCurrentAccount(request.accountScope, request.accountGeneration)) return@withLock
        val cache = caches[key] ?: return@withLock
        val page = cache.pages[cache.currentQueryIndex] ?: return@withLock
        var continuation = page.continuation ?: return@withLock
        val seenIds = excludedIds(cache, cache.currentQueryIndex, includeCurrentPage = true).toMutableSet()
        publish(cache, isLoading = true)
        try {
            var updatedPage = page
            var hops = 0
            while (continuation.isNotBlank() && updatedPage.items.size < MAX_ITEMS_PER_QUERY && hops < MAX_CONTINUATION_HOPS) {
                val result =
                    coordinator.singleFlight(
                        key = "playlist-suggestions:${cache.accountScope}:${cache.playlistId}:${continuation.hashCode()}",
                        priority = DesktopRequestPriority.FOREGROUND,
                    ) { backend.continuation(continuation) }.getOrThrow()
                if (!isCurrentAccount(request.accountScope, request.accountGeneration)) return@withLock
                val additional = filterPlaylistSuggestionItems(result.items.filterIsInstance<SongItem>(), seenIds, PAGE_SIZE)
                seenIds.addAll(additional.map(SongItem::id))
                val nextContinuation = result.continuation?.takeIf { updatedPage.items.size + additional.size < MAX_ITEMS_PER_QUERY }
                updatedPage = updatedPage.copy(items = (updatedPage.items + additional).take(MAX_ITEMS_PER_QUERY), continuation = nextContinuation)
                continuation = nextContinuation.orEmpty()
                hops++
                if (additional.isNotEmpty()) break
            }
            val updated = cache.copy(
                pages = cache.pages + (cache.currentQueryIndex to updatedPage),
                updatedAt = now(),
            )
            if (!isCurrentAccount(request.accountScope, request.accountGeneration)) return@withLock
            caches[key] = updated
            publish(updated, isLoading = false)
            persistCache()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (isCurrentAccount(request.accountScope, request.accountGeneration)) {
                _state.value = _state.value.copy(isLoading = false, error = failure.message ?: "Could not load more suggestions")
            }
        }
    }

    suspend fun addSuggestions(
        playlistId: String,
        songs: List<SongItem>,
    ): Result<Unit> = mutex.withLock {
        val backendPlaylistId = playlistId.trim()
        val normalizedPlaylistId = backendPlaylistId.removePrefix("VL")
        if (normalizedPlaylistId.isEmpty()) return@withLock Result.failure(IllegalArgumentException("Playlist ID is required"))
        val account = accountScopeProvider()
        loadPersistentCache()
        val key = cacheKey(account, normalizedPlaylistId)
        val request = activeRequest
        if (
            request == null ||
                request.playlistId != normalizedPlaylistId ||
                key != activeCacheKey ||
                !isCurrentAccount(request.accountScope, request.accountGeneration)
        ) {
            return@withLock Result.failure(IllegalStateException("Playlist suggestions are no longer active for this account"))
        }
        val cache = caches[key]
        val existingIds = cache?.playlistSongIds.orEmpty().toSet()
        // Keep the caller's visible order. A single ordered edit avoids racing independent appends.
        val additions = songs.distinctBy(SongItem::id).filter { it.id !in existingIds }
        if (additions.isEmpty()) return@withLock Result.success(Unit)
        if (key == activeCacheKey) _state.value = _state.value.copy(isAdding = true, error = null)
        val result =
            try {
                if (!isCurrentAccount(request.accountScope, request.accountGeneration)) {
                    return@withLock Result.failure(IllegalStateException("Account changed before playlist suggestions were added"))
                }
                backend.addSongs(backendPlaylistId, additions.map(SongItem::id))
            } catch (failure: CancellationException) {
                if (key == activeCacheKey && isCurrentAccount(request.accountScope, request.accountGeneration)) {
                    _state.value = _state.value.copy(isAdding = false)
                }
                throw failure
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        if (result.isSuccess) {
            if (!isCurrentAccount(request.accountScope, request.accountGeneration)) {
                return@withLock Result.failure(IllegalStateException("Account changed while adding playlist suggestions"))
            }
            cache?.let { current ->
                val addedIds = additions.mapTo(mutableSetOf(), SongItem::id)
                val updated = current.copy(
                    playlistSongIds = current.playlistSongIds + additions.map(SongItem::id),
                    pages = current.pages.mapValues { (_, page) -> page.copy(items = page.items.filterNot { it.id in addedIds }) },
                    updatedAt = now(),
                )
                caches[key] = updated
                if (key == activeCacheKey) publish(updated, isLoading = false)
                persistCache()
            }
        }
        if (key == activeCacheKey && isCurrentAccount(request.accountScope, request.accountGeneration)) {
            _state.value = _state.value.copy(
                isAdding = false,
                error = result.exceptionOrNull()?.message,
            )
        }
        result
    }

    suspend fun refresh() = mutex.withLock {
        val request = activeRequest ?: return@withLock
        val key = activeCacheKey ?: return@withLock
        if (!isCurrentAccount(request.accountScope, request.accountGeneration)) return@withLock
        val previous = caches[key]
        val replacement =
            PlaylistCache(
                accountScope = request.accountScope,
                playlistId = request.playlistId,
                title = request.title,
                playlistSongIds = previous?.playlistSongIds ?: request.playlistSongs.map(SongItem::id),
                queries = buildQueries(request.title, request.playlistSongs),
                pages = emptyMap(),
                currentQueryIndex = 0,
                updatedAt = now(),
            )
        if (!isCurrentAccount(request.accountScope, request.accountGeneration)) return@withLock
        caches[key] = replacement
        publish(replacement, isLoading = replacement.queries.isNotEmpty())
        if (replacement.queries.isEmpty()) return@withLock
        fetchQueryPage(key, replacement, 0, request.accountGeneration)
    }

    suspend fun onAccountScopeChanged() {
        val observedAccount = accountScopeProvider()
        if (observedAccount != _accountScope.value) {
            accountGeneration.incrementAndGet()
            _accountScope.value = observedAccount
            _state.value = DesktopPlaylistSuggestionsState()
        }
        mutex.withLock {
            val newAccount = accountScopeProvider()
            if (newAccount != _accountScope.value) {
                accountGeneration.incrementAndGet()
                _accountScope.value = newAccount
                _state.value = DesktopPlaylistSuggestionsState()
            }
            if (activeRequest?.accountScope != newAccount) {
                activeCacheKey = null
                activeRequest = null
                _state.value = DesktopPlaylistSuggestionsState()
            }
        }
    }

    private fun adoptAccountScope(account: String) {
        if (account == _accountScope.value) return
        accountGeneration.incrementAndGet()
        _accountScope.value = account
        activeCacheKey = null
        activeRequest = null
        _state.value = DesktopPlaylistSuggestionsState()
    }

    private fun isCurrentAccount(account: String, generation: Long): Boolean =
        account == accountScopeProvider() &&
            account == _accountScope.value &&
            accountGeneration.get() == generation

    private suspend fun fetchQueryPage(key: String, cache: PlaylistCache, queryIndex: Int, expectedGeneration: Long) {
        val query = cache.queries.getOrNull(queryIndex) ?: return
        if (!isCurrentAccount(cache.accountScope, expectedGeneration)) return
        try {
            var result =
                coordinator.singleFlight(
                    key = "playlist-suggestions:${cache.accountScope}:${cache.playlistId}:q$queryIndex",
                    priority = DesktopRequestPriority.FOREGROUND,
                ) { backend.search(query) }.getOrThrow()
            if (!isCurrentAccount(cache.accountScope, expectedGeneration)) return
            val excluded = excludedIds(cache, queryIndex).toMutableSet()
            var items = filterPlaylistSuggestionItems(result.items.filterIsInstance<SongItem>(), excluded, PAGE_SIZE)
            excluded.addAll(items.map(SongItem::id))
            var continuation = result.continuation
            var hops = 0
            while (items.isEmpty() && !continuation.isNullOrBlank() && hops < MAX_EMPTY_PAGE_HOPS) {
                result = backend.continuation(continuation).getOrThrow()
                if (!isCurrentAccount(cache.accountScope, expectedGeneration)) return
                continuation = result.continuation
                items = filterPlaylistSuggestionItems(result.items.filterIsInstance<SongItem>(), excluded, PAGE_SIZE)
                excluded.addAll(items.map(SongItem::id))
                hops++
            }
            if (!isCurrentAccount(cache.accountScope, expectedGeneration)) return
            val current = caches[key] ?: return
            val page = QueryPage(query, items, continuation)
            val updated = current.copy(
                pages = current.pages + (queryIndex to page),
                currentQueryIndex = queryIndex,
                updatedAt = now(),
            )
            caches[key] = updated
            trimCache()
            if (key == activeCacheKey && isCurrentAccount(cache.accountScope, expectedGeneration)) publish(updated, isLoading = false)
            persistCache()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (key == activeCacheKey && isCurrentAccount(cache.accountScope, expectedGeneration)) {
                _state.value = _state.value.copy(isLoading = false, error = failure.message ?: "Could not load suggestions")
            }
        }
    }

    private fun excludedIds(cache: PlaylistCache, targetIndex: Int, includeCurrentPage: Boolean = false): Set<String> = buildSet {
        addAll(cache.playlistSongIds)
        cache.pages.forEach { (index, page) ->
            if (index != targetIndex || includeCurrentPage) page.items.forEach { add(it.id) }
        }
    }

    private fun publish(cache: PlaylistCache, isLoading: Boolean) {
        val page = cache.pages[cache.currentQueryIndex]
        val items =
            page?.items.orEmpty().filter { song ->
                song.id !in cache.playlistSongIds &&
                    cache.pages.all { (index, otherPage) -> index == cache.currentQueryIndex || otherPage.items.none { it.id == song.id } }
            }
        _state.value =
            DesktopPlaylistSuggestionsState(
                playlistId = cache.playlistId,
                query = cache.queries.getOrNull(cache.currentQueryIndex).orEmpty(),
                currentQueryIndex = cache.currentQueryIndex,
                totalQueries = cache.queries.size,
                items = items,
                continuation = page?.continuation,
                hasPrevious = cache.currentQueryIndex > 0,
                hasNext = cache.currentQueryIndex < cache.queries.lastIndex,
                hasMore = page?.continuation != null && page.items.size < MAX_ITEMS_PER_QUERY,
                isLoading = isLoading,
            )
    }

    private suspend fun loadPersistentCache() {
        if (persistentCacheLoaded) return
        val value = coordinator.readPersistent(PERSISTENT_KEY)
        value?.payload?.let { raw -> decodeCaches(raw).forEach { (key, cache) -> caches[key] = cache } }
        trimCache()
        persistentCacheLoaded = true
    }

    private suspend fun persistCache() {
        trimCache()
        coordinator.writePersistent(PERSISTENT_KEY, encodeCaches(caches.values.toList()), now())
    }

    private fun trimCache() {
        val byAccount = caches.values.groupBy(PlaylistCache::accountScope)
        byAccount.values.forEach { accountEntries ->
            accountEntries.sortedByDescending(PlaylistCache::updatedAt)
                .drop(MAX_PLAYLISTS_PER_ACCOUNT)
                .forEach { caches.remove(cacheKey(it.accountScope, it.playlistId)) }
        }
        while (caches.size > MAX_PLAYLIST_CACHES) {
            caches.remove(caches.values.minByOrNull(PlaylistCache::updatedAt)?.let { cacheKey(it.accountScope, it.playlistId) })
        }
    }

    private fun buildQueries(title: String, songs: List<SongItem>): List<String> =
        buildPlaylistSuggestionQueries(title, songs, now()).take(MAX_QUERIES)

    private fun cacheKey(account: String, playlistId: String) = "$account:$playlistId"

    private fun encodeCaches(values: List<PlaylistCache>): String = JSONObject().put(
        "entries",
        JSONArray().apply {
            values.forEach { cache ->
                put(
                    JSONObject()
                        .put("account", cache.accountScope)
                        .put("playlistId", cache.playlistId)
                        .put("title", cache.title)
                        .put("playlistSongIds", JSONArray(cache.playlistSongIds))
                        .put("queries", JSONArray(cache.queries))
                        .put("currentQueryIndex", cache.currentQueryIndex)
                        .put("updatedAt", cache.updatedAt)
                        .put(
                            "pages",
                            JSONArray().apply {
                                cache.pages.forEach { (index, page) ->
                                    put(
                                        JSONObject()
                                            .put("index", index)
                                            .put("query", page.query)
                                            .put("continuation", page.continuation)
                                            .put("items", JSONArray().apply { page.items.forEach { put(it.toJson()) } }),
                                    )
                                }
                            },
                        ),
                )
            }
        },
    ).toString()

    private fun decodeCaches(raw: String): List<Pair<String, PlaylistCache>> = runCatching {
        val entries = JSONObject(raw).optJSONArray("entries") ?: return emptyList()
        buildList {
            for (entryIndex in 0 until entries.length()) {
                val value = entries.optJSONObject(entryIndex) ?: continue
                val account = value.optString("account").takeIf(String::isNotBlank) ?: continue
                val playlistId = value.optString("playlistId").takeIf(String::isNotBlank) ?: continue
                val queries = value.optJSONArray("queries").stringValues().take(MAX_QUERIES)
                if (queries.isEmpty()) continue
                val pages = linkedMapOf<Int, QueryPage>()
                val pageValues = value.optJSONArray("pages")
                for (pageIndex in 0 until (pageValues?.length() ?: 0)) {
                    val page = pageValues?.optJSONObject(pageIndex) ?: continue
                    val index = page.optInt("index", -1)
                    val query = page.optString("query")
                    if (index !in queries.indices || query.isBlank()) continue
                    val items = page.optJSONArray("items").songValues().distinctBy(SongItem::id).take(MAX_ITEMS_PER_QUERY)
                    pages[index] = QueryPage(query, items, page.stringOrNull("continuation"))
                }
                val cache = PlaylistCache(
                    accountScope = account,
                    playlistId = playlistId,
                    title = value.optString("title"),
                    playlistSongIds = value.optJSONArray("playlistSongIds").stringValues(),
                    queries = queries,
                    pages = pages,
                    currentQueryIndex = value.optInt("currentQueryIndex").coerceIn(0, queries.lastIndex),
                    updatedAt = value.optLong("updatedAt"),
                )
                add(cacheKey(account, playlistId) to cache)
            }
        }
    }.getOrDefault(emptyList())

    private fun SongItem.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("thumbnail", thumbnail)
        .put("duration", duration)
        .put("explicit", explicit)
        .put("isPodcast", isPodcast)
        .put("artists", JSONArray().apply { artists.forEach { artist -> put(JSONObject().put("name", artist.name).put("id", artist.id)) } })
        .put("albumName", album?.name)
        .put("albumId", album?.id)

    private fun JSONArray?.songValues(): List<SongItem> = buildList {
        if (this@songValues == null) return@buildList
        for (index in 0 until this@songValues.length()) {
            val value = this@songValues.optJSONObject(index) ?: continue
            val id = value.optString("id").takeIf(String::isNotBlank) ?: continue
            val title = value.optString("title")
            if (title.isBlank()) continue
            val artistsJson = value.optJSONArray("artists")
            val artists = buildList {
                for (artistIndex in 0 until (artistsJson?.length() ?: 0)) {
                    val artist = artistsJson?.optJSONObject(artistIndex) ?: continue
                    val name = artist.optString("name").takeIf(String::isNotBlank) ?: continue
                    add(Artist(name, artist.stringOrNull("id")))
                }
            }
            add(
                SongItem(
                    id = id,
                    title = title,
                    artists = artists,
                    album = value.stringOrNull("albumName")?.let { Album(it, value.stringOrNull("albumId").orEmpty()) },
                    duration = value.optInt("duration").takeIf { it > 0 },
                    thumbnail = value.optString("thumbnail"),
                    explicit = value.optBoolean("explicit"),
                    endpoint = WatchEndpoint(videoId = id),
                    isPodcast = value.optBoolean("isPodcast"),
                ),
            )
        }
    }

    private fun JSONArray?.stringValues(): List<String> = buildList {
        if (this@stringValues == null) return@buildList
        for (index in 0 until this@stringValues.length()) {
            this@stringValues.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        opt(key).takeUnless { it == JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank)

    private companion object {
        const val PERSISTENT_KEY = "desktop-playlist-suggestions-v1"
        const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L
        const val MAX_PLAYLISTS_PER_ACCOUNT = 8
        const val MAX_PLAYLIST_CACHES = 48
        const val MAX_QUERIES = 12
        const val MAX_ITEMS_PER_QUERY = 30
        const val PAGE_SIZE = 10
        const val MAX_CONTINUATION_HOPS = 3
        const val MAX_EMPTY_PAGE_HOPS = 3
    }
}

private class ArchiveTuneDesktopPlaylistSuggestionsBackend(
    private val client: ArchiveTuneJvmClient,
) : DesktopPlaylistSuggestionsBackend {
    override suspend fun search(query: String): Result<SearchResult> =
        client.searchSuggestionSongs(query)

    override suspend fun continuation(token: String): Result<SearchResult> = client.searchContinuation(token)

    override suspend fun addSongs(playlistId: String, videoIds: List<String>): Result<Unit> =
        client.addSongsToPlaylist(playlistId, videoIds)
}

internal fun buildPlaylistSuggestionQueries(
    title: String,
    songs: List<SongItem>,
    epochMillis: Long,
): List<String> {
    val normalizedTitle = title.trim().replace(Regex("\\s+"), " ")
    val normalized = normalizeSuggestionText(normalizedTitle)
    val genres = GENRE_KEYWORDS.filter { keyword -> containsSignal(normalized, keyword) }
    val moods = MOOD_KEYWORDS.filter { keyword -> containsSignal(normalized, keyword) }
    val artistOrder = linkedMapOf<String, Pair<String, Int>>()
    songs.forEach { song ->
        song.artists.forEach { artist ->
            val key = normalizeSuggestionText(artist.name)
            if (key.isNotBlank()) {
                val current = artistOrder[key]
                artistOrder[key] = artist.name to ((current?.second ?: 0) + 1)
            }
        }
    }
    val artists = artistOrder.entries.sortedWith(compareByDescending<Map.Entry<String, Pair<String, Int>>> { it.value.second }.thenBy { songs.indexOfFirst { song -> song.artists.any { artist -> normalizeSuggestionText(artist.name) == it.key } } })
        .map { it.value.first }.take(3)
    val queries = linkedSetOf<String>()
    if (normalizedTitle.isNotBlank()) queries += normalizedTitle
    genres.take(2).forEach { queries += "$it music" }
    moods.take(2).forEach { queries += "$it music" }
    artists.forEach { queries += "songs like $it" }
    genres.take(1).forEach { genre -> artists.take(2).forEach { artist -> queries += "$genre music like $artist" } }
    val hour = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).hour
    queries += when (hour) {
        in 6..11 -> "morning music"
        in 12..17 -> "afternoon vibes"
        in 18..22 -> "evening music"
        else -> "late night music"
    }
    listOf("trending songs", "popular music", "new music", "top hits", "music ${Year.now().value}", "viral songs")
        .forEach { queries += it }
    return queries.toList()
}

internal fun filterPlaylistSuggestionItems(
    candidates: List<SongItem>,
    excludedIds: Set<String>,
    limit: Int = 10,
): List<SongItem> = candidates.asSequence()
    .filterNot(SongItem::isPodcast)
    .filterNot { it.id.isBlank() || it.id in excludedIds }
    .distinctBy(SongItem::id)
    .take(limit.coerceAtLeast(0))
    .toList()

private fun normalizeSuggestionText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim().replace(Regex("[\\s\\p{Z}]+"), " ")

private fun containsSignal(normalizedText: String, signal: String): Boolean =
    Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(signal)}([^\\p{L}\\p{N}]|$)").containsMatchIn(normalizedText)

private val GENRE_KEYWORDS = listOf("pop", "rock", "hip hop", "rap", "electronic", "dance", "jazz", "blues", "country", "folk", "classical", "metal", "indie", "alternative", "r&b", "soul", "reggae", "latin", "k-pop", "j-pop", "house", "techno", "ambient")
private val MOOD_KEYWORDS = listOf("chill", "upbeat", "energetic", "relaxing", "romantic", "sad", "happy", "motivational", "calm", "dreamy", "nostalgic", "epic")
