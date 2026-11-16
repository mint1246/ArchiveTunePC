/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.cache.DesktopRequestPriority
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.PODCAST_LIBRARY_BROWSE_ID
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.PodcastPage
import org.json.JSONArray
import org.json.JSONObject

data class DesktopPodcastLibraryState(
    val podcasts: List<PodcastItem> = emptyList(),
    val episodes: List<EpisodeItem> = emptyList(),
    val remotelySavedPodcastIds: Set<String> = emptySet(),
    val locallySavedPodcastIds: Set<String> = emptySet(),
)

data class DesktopRemotePodcastLibrary(
    val podcasts: List<PodcastItem>?,
    val episodes: List<EpisodeItem>?,
)

interface DesktopPodcastBackend {
    suspend fun podcast(browseId: String): Result<PodcastPage>
    suspend fun continuation(token: String): Result<PodcastPage.Continuation>
    suspend fun search(query: String): Result<List<YTItem>>
    suspend fun savedLibrary(): Result<DesktopRemotePodcastLibrary>
    suspend fun setPodcastSaved(playlistId: String, saved: Boolean): Result<Unit>
    suspend fun setEpisodeSaved(episodeId: String, saved: Boolean): Result<Unit>
}

/** Cached podcast browse/search operations and a compact account-scoped saved podcast library. */
class DesktopPodcastRepository(
    private val backend: DesktopPodcastBackend,
    private val coordinator: DesktopResourceCoordinator,
    private val scope: CoroutineScope,
    private val accountScopeProvider: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class LibraryScopeToken(val account: String, val revision: Long)

    constructor(
        backend: ArchiveTuneJvmClient,
        coordinator: DesktopResourceCoordinator,
        scope: CoroutineScope,
        accountScopeProvider: () -> String,
        now: () -> Long = System::currentTimeMillis,
    ) : this(
        ArchiveTuneDesktopPodcastBackend(backend),
        coordinator,
        scope,
        accountScopeProvider,
        now,
    )

    private val _library = MutableStateFlow(DesktopPodcastLibraryState())
    val library: StateFlow<DesktopPodcastLibraryState> = _library.asStateFlow()
    @Volatile private var loadedAccountScope: String? = null
    private val persistMutex = Mutex()
    private val libraryLoadMutex = Mutex()
    private var scopeRevision = 0L
    private var libraryRevision = 0L

    init {
        scope.launchPodcastIo {
            val account = accountScopeProvider()
            ensureLibraryScope(account)
            refreshRemoteLibrary(account, DesktopRequestPriority.MAINTENANCE)
        }
    }

    suspend fun podcast(browseId: String): Result<PodcastPage> {
        val account = accountScopeProvider()
        ensureLibraryScope(account)
        val scopeToken = libraryScopeToken(account)
        val id = browseId.trim()
        if (id.isEmpty()) return Result.failure(IllegalArgumentException("Podcast ID is required"))
        val key = "$account:$id"
        val result = coordinator.singleFlight(
            key = "podcast:$key",
            priority = DesktopRequestPriority.FOREGROUND,
        ) { backend.podcast(id) }
        result.getOrNull()?.let { page ->
            scopeToken?.let { token -> updateLibrary(token) { state ->
                    val browseId = page.podcast.browseId
                    val isLocallySaved = browseId in state.locallySavedPodcastIds
                    val podcasts = when {
                        page.isSaved -> (state.podcasts.filterNot { it.browseId == browseId } + page.podcast).takeLast(MAX_SAVED_PODCASTS)
                        isLocallySaved -> (state.podcasts.filterNot { it.browseId == browseId } + page.podcast).takeLast(MAX_SAVED_PODCASTS)
                        else -> state.podcasts.filterNot { it.browseId == browseId }
                    }
                    state.copy(
                        podcasts = podcasts,
                        remotelySavedPodcastIds = if (page.isSaved) {
                            (state.remotelySavedPodcastIds + browseId).toList().takeLast(MAX_SAVED_PODCASTS).toSet()
                        } else {
                            state.remotelySavedPodcastIds - browseId
                        },
                    )
                } }
        }
        return result
    }

    suspend fun continuation(token: String): Result<PodcastPage.Continuation> {
        val account = accountScopeProvider()
        ensureLibraryScope(account)
        val normalized = token.trim()
        if (normalized.isEmpty()) return Result.failure(IllegalArgumentException("Podcast continuation is required"))
        return coordinator.singleFlight(
            key = "podcast:$account:continuation:${normalized.sha256Hex()}",
            priority = DesktopRequestPriority.FOREGROUND,
        ) { backend.continuation(normalized) }
    }

    suspend fun search(query: String): Result<List<YTItem>> {
        val account = accountScopeProvider()
        ensureLibraryScope(account)
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return Result.success(emptyList())
        val cacheKey = "podcast-search:$account:${normalized.lowercase(java.util.Locale.ROOT)}"
        coordinator.memoryValue<List<YTItem>>(cacheKey)?.let { (items, storedAt) ->
            if (now() - storedAt < SEARCH_TTL_MS) return Result.success(items)
        }
        return coordinator.singleFlight(
            key = cacheKey,
            priority = DesktopRequestPriority.FOREGROUND,
        ) {
            withContext(Dispatchers.IO) {
                backend.search(normalized).map { items -> items.distinctBy { item -> item::class to item.id } }
            }
        }.onSuccess { items -> coordinator.putMemory(cacheKey, items, now()) }
    }

    suspend fun setPodcastSaved(podcast: PodcastItem, saved: Boolean): Result<Unit> {
        val account = accountScopeProvider()
        ensureLibraryScope(account)
        val scopeToken = libraryScopeToken(account)
        val wasRemoteSaved = scopeToken?.let { token ->
            synchronized(this) {
                if (isCurrentScopeLocked(token)) podcast.browseId in _library.value.remotelySavedPodcastIds else false
            }
        } ?: false
        if (podcast.playlistId?.isNotBlank() == true && (saved || wasRemoteSaved)) {
            val remote = backend.setPodcastSaved(requireNotNull(podcast.playlistId), saved)
            if (remote.isFailure) return remote
        }
        scopeToken?.let { token -> updateLibrary(token) { current ->
            val podcasts = if (saved) (current.podcasts.filterNot { it.browseId == podcast.browseId } + podcast).takeLast(MAX_SAVED_PODCASTS) else current.podcasts.filterNot { it.browseId == podcast.browseId }
            current.copy(
                podcasts = podcasts,
                remotelySavedPodcastIds = if (saved && podcast.playlistId?.isNotBlank() == true) {
                    (current.remotelySavedPodcastIds + podcast.browseId).toList().takeLast(MAX_SAVED_PODCASTS).toSet()
                } else {
                    current.remotelySavedPodcastIds - podcast.browseId
                },
                locallySavedPodcastIds = if (saved && podcast.playlistId.isNullOrBlank()) {
                    (current.locallySavedPodcastIds + podcast.browseId).toList().takeLast(MAX_SAVED_PODCASTS).toSet()
                } else {
                    current.locallySavedPodcastIds - podcast.browseId
                },
            )
        } }
        return Result.success(Unit)
    }

    suspend fun setEpisodeSaved(episode: EpisodeItem, saved: Boolean): Result<Unit> {
        val account = accountScopeProvider()
        ensureLibraryScope(account)
        val scopeToken = libraryScopeToken(account)
        val remote = backend.setEpisodeSaved(episode.id, saved)
        if (remote.isFailure) return remote
        scopeToken?.let { token -> updateLibrary(token) { current ->
            val episodes = if (saved) (current.episodes.filterNot { it.id == episode.id } + episode).takeLast(MAX_SAVED_EPISODES) else current.episodes.filterNot { it.id == episode.id }
            current.copy(episodes = episodes)
        } }
        return remote
    }

    fun isPodcastSaved(browseId: String): Boolean = _library.value.podcasts.any { it.browseId == browseId }
    fun isEpisodeSaved(episodeId: String): Boolean = _library.value.episodes.any { it.id == episodeId }

    suspend fun onAccountScopeChanged() {
        synchronized(this) {
            loadedAccountScope = null
            scopeRevision++
            libraryRevision++
            _library.value = DesktopPodcastLibraryState()
        }
        val account = accountScopeProvider()
        ensureLibraryScope(account)
        scope.launchPodcastIo { refreshRemoteLibrary(account, DesktopRequestPriority.MAINTENANCE) }
    }

    suspend fun refreshLibrary(): Result<Unit> {
        val account = accountScopeProvider()
        ensureLibraryScope(account)
        return refreshRemoteLibrary(account, DesktopRequestPriority.FOREGROUND)
    }

    private suspend fun refreshRemoteLibrary(account: String, priority: DesktopRequestPriority): Result<Unit> {
        val token = libraryScopeToken(account) ?: return Result.success(Unit)
        val remote = coordinator.singleFlight(
            key = "podcast-library-sync:$account",
            priority = priority,
        ) { backend.savedLibrary() }.getOrElse { return Result.failure(it) }
        updateLibrary(token) { current ->
            val podcasts = remote.podcasts?.let { remotePodcasts ->
                val remoteIds = remotePodcasts.mapTo(HashSet()) { it.browseId }
                val localOnly = current.podcasts.filter { it.browseId in current.locallySavedPodcastIds && it.browseId !in remoteIds }
                (remotePodcasts.distinctBy(PodcastItem::browseId) + localOnly).takeLast(MAX_SAVED_PODCASTS)
            } ?: current.podcasts
            val remoteIds = remote.podcasts?.mapTo(HashSet()) { it.browseId } ?: current.remotelySavedPodcastIds
            val localIds = current.locallySavedPodcastIds.intersect(podcasts.map(PodcastItem::browseId).toSet())
            current.copy(
                podcasts = podcasts,
                episodes = remote.episodes?.distinctBy(EpisodeItem::id)?.takeLast(MAX_SAVED_EPISODES) ?: current.episodes,
                remotelySavedPodcastIds = remoteIds.intersect(podcasts.map(PodcastItem::browseId).toSet()),
                locallySavedPodcastIds = localIds,
            )
        }
        return Result.success(Unit)
    }

    private suspend fun ensureLibraryScope(account: String, force: Boolean = false) {
        libraryLoadMutex.withLock load@{
            val (capturedScopeRevision, capturedLibraryRevision) = synchronized(this) {
                if (accountScopeProvider() != account) return@load
                if (!force && loadedAccountScope == account) return@load
                if (loadedAccountScope != account) {
                    _library.value = DesktopPodcastLibraryState()
                    libraryRevision++
                }
                scopeRevision++
                scopeRevision to libraryRevision
            }
            val persisted = coordinator.readPersistent(libraryKey(account))
            val decoded = persisted?.payload?.let(::decodeLibrary) ?: DesktopPodcastLibraryState()
            synchronized(this) {
                if (accountScopeProvider() != account || scopeRevision != capturedScopeRevision || libraryRevision != capturedLibraryRevision) return@load
                loadedAccountScope = account
                _library.value = decoded
            }
        }
    }

    private fun libraryScopeToken(account: String): LibraryScopeToken? = synchronized(this) {
        if (loadedAccountScope != account || accountScopeProvider() != account) null
        else LibraryScopeToken(account, scopeRevision)
    }

    private fun isCurrentScopeLocked(token: LibraryScopeToken): Boolean =
        loadedAccountScope == token.account && scopeRevision == token.revision && accountScopeProvider() == token.account

    private suspend fun updateLibrary(
        token: LibraryScopeToken,
        update: (DesktopPodcastLibraryState) -> DesktopPodcastLibraryState,
    ) {
        val changed = synchronized(this) {
            if (!isCurrentScopeLocked(token)) return
            val next = update(_library.value)
            val didChange = next != _library.value
            if (didChange) libraryRevision++
            _library.value = next
            didChange
        }
        if (!changed) return
        persistMutex.withLock {
            val snapshot = synchronized(this) {
                if (isCurrentScopeLocked(token)) _library.value else null
            } ?: return@withLock
            coordinator.writePersistent(libraryKey(token.account), encodeLibrary(snapshot), now())
        }
    }

    private fun libraryKey(account: String) = "podcast-library:$account"

    private fun encodeLibrary(state: DesktopPodcastLibraryState): String = JSONObject().apply {
        put("podcasts", JSONArray().apply {
            state.podcasts.takeLast(MAX_SAVED_PODCASTS).forEach { podcast ->
                put(JSONObject().apply {
                    put("browseId", podcast.browseId)
                    putNullable("playlistId", podcast.playlistId)
                    put("title", podcast.title)
                    putNullable("author", podcast.author?.name)
                    putNullable("authorId", podcast.author?.id)
                    putNullable("thumbnail", podcast.thumbnail)
                    putNullable("thumbnailWidth", podcast.thumbnailWidth)
                    putNullable("thumbnailHeight", podcast.thumbnailHeight)
                })
            }
        })
        put("remotelySavedPodcastIds", JSONArray(state.remotelySavedPodcastIds.toList().takeLast(MAX_SAVED_PODCASTS)))
        put("locallySavedPodcastIds", JSONArray(state.locallySavedPodcastIds.toList().takeLast(MAX_SAVED_PODCASTS)))
        put("episodes", JSONArray().apply {
            state.episodes.takeLast(MAX_SAVED_EPISODES).forEach { episode ->
                put(JSONObject().apply {
                    put("id", episode.id)
                    putNullable("browseId", episode.browseId)
                    put("title", episode.title)
                    putNullable("podcastName", episode.podcast?.name)
                    putNullable("podcastId", episode.podcast?.id)
                    putNullable("description", episode.description)
                    putNullable("dateText", episode.dateText)
                    putNullable("durationText", episode.durationText)
                    putNullable("duration", episode.duration)
                    put("thumbnail", episode.thumbnail)
                    putNullable("thumbnailWidth", episode.thumbnailWidth)
                    putNullable("thumbnailHeight", episode.thumbnailHeight)
                    put("endpoint", JSONObject().apply {
                        putNullable("videoId", episode.endpoint.videoId)
                        putNullable("playlistId", episode.endpoint.playlistId)
                        putNullable("playlistSetVideoId", episode.endpoint.playlistSetVideoId)
                        putNullable("params", episode.endpoint.params)
                        putNullable("index", episode.endpoint.index)
                        episode.endpoint.watchEndpointMusicSupportedConfigs?.let { supported ->
                            put("musicVideoType", supported.watchEndpointMusicConfig.musicVideoType)
                        }
                    })
                })
            }
        })
    }.toString()

    private fun decodeLibrary(raw: String): DesktopPodcastLibraryState = runCatching {
        val json = JSONObject(raw)
        val podcasts = json.optJSONArray("podcasts").jsonObjects().mapNotNull { value ->
            val browseId = value.optString("browseId").takeIf(String::isNotBlank) ?: return@mapNotNull null
            PodcastItem(
                browseId = browseId,
                playlistId = value.optNullableString("playlistId"),
                title = value.optString("title").ifBlank { "Podcast" },
                author = value.optNullableString("author")?.let { Artist(it, value.optNullableString("authorId")) },
                thumbnail = value.optNullableString("thumbnail"),
                thumbnailWidth = value.optNullableInt("thumbnailWidth"),
                thumbnailHeight = value.optNullableInt("thumbnailHeight"),
            )
        }.takeLast(MAX_SAVED_PODCASTS)
        val remoteIds = buildList {
            val array = json.optJSONArray("remotelySavedPodcastIds")
            if (array != null) for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }.takeLast(MAX_SAVED_PODCASTS).toSet().intersect(podcasts.map(PodcastItem::browseId).toSet())
        val localIds = json.optJSONArray("locallySavedPodcastIds")?.let { array ->
            buildList {
                for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }?.takeLast(MAX_SAVED_PODCASTS)?.toSet()
            ?: podcasts.filter { it.playlistId.isNullOrBlank() }.mapTo(HashSet(), PodcastItem::browseId)
        val episodes = json.optJSONArray("episodes").jsonObjects().mapNotNull { value ->
            val id = value.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            EpisodeItem(
                id = id,
                browseId = value.optNullableString("browseId"),
                title = value.optString("title").ifBlank { "Episode" },
                podcast = value.optNullableString("podcastName")?.let { Artist(it, value.optNullableString("podcastId")) },
                description = value.optNullableString("description"),
                dateText = value.optNullableString("dateText"),
                durationText = value.optNullableString("durationText"),
                duration = value.optNullableInt("duration"),
                thumbnail = value.optString("thumbnail").takeIf(String::isNotBlank) ?: "",
                endpoint = value.optJSONObject("endpoint").toWatchEndpoint(id),
                thumbnailWidth = value.optNullableInt("thumbnailWidth"),
                thumbnailHeight = value.optNullableInt("thumbnailHeight"),
            )
        }.takeLast(MAX_SAVED_EPISODES)
        DesktopPodcastLibraryState(podcasts, episodes, remoteIds, localIds)
    }.getOrDefault(DesktopPodcastLibraryState())

    private companion object {
        const val SEARCH_TTL_MS = 10 * 60 * 1000L
        const val MAX_SAVED_PODCASTS = 100
        const val MAX_SAVED_EPISODES = 250
    }
}

private class ArchiveTuneDesktopPodcastBackend(
    private val client: ArchiveTuneJvmClient,
) : DesktopPodcastBackend {
    override suspend fun podcast(browseId: String): Result<PodcastPage> = client.podcast(browseId)
    override suspend fun continuation(token: String): Result<PodcastPage.Continuation> = client.podcastContinuation(token)
    override suspend fun search(query: String): Result<List<YTItem>> =
        client.search(query, YouTube.SearchFilter.FILTER_PODCAST).map { it.items }
    override suspend fun savedLibrary(): Result<DesktopRemotePodcastLibrary> {
        val podcasts = client.library(PODCAST_LIBRARY_BROWSE_ID).map { page ->
            page.items.filterIsInstance<PodcastItem>().distinctBy(PodcastItem::browseId)
        }
        val episodes = client.librarySongs().map { page ->
            page.items.filterIsInstance<SongItem>().filter(SongItem::isPodcast).distinctBy(SongItem::id).map { song ->
                EpisodeItem(
                    id = song.id,
                    browseId = null,
                    title = song.title,
                    podcast = song.artists.firstOrNull(),
                    description = null,
                    dateText = null,
                    durationText = song.duration?.let(::podcastDurationText),
                    duration = song.duration,
                    thumbnail = song.thumbnail,
                    endpoint = song.endpoint ?: WatchEndpoint(videoId = song.id),
                    thumbnailWidth = song.thumbnailWidth,
                    thumbnailHeight = song.thumbnailHeight,
                )
            }
        }
        if (podcasts.isFailure && episodes.isFailure) {
            return Result.failure(podcasts.exceptionOrNull() ?: episodes.exceptionOrNull() ?: IllegalStateException("Podcast library sync failed"))
        }
        return Result.success(DesktopRemotePodcastLibrary(podcasts.getOrNull(), episodes.getOrNull()))
    }
    override suspend fun setPodcastSaved(playlistId: String, saved: Boolean): Result<Unit> = client.setPodcastSaved(playlistId, saved)
    override suspend fun setEpisodeSaved(episodeId: String, saved: Boolean): Result<Unit> = client.setSongLiked(episodeId, saved)
}

private fun podcastDurationText(durationSeconds: Int): String {
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    val seconds = durationSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun JSONArray?.jsonObjects(): List<JSONObject> = buildList {
    if (this@jsonObjects == null) return@buildList
    for (index in 0 until length()) optJSONObject(index)?.let(::add)
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

private fun JSONObject.optNullableString(key: String): String? = opt(key)
    ?.takeUnless { it == JSONObject.NULL }
    ?.toString()
    ?.takeIf(String::isNotBlank)

private fun JSONObject.optNullableInt(key: String): Int? = opt(key)
    ?.takeUnless { it == JSONObject.NULL }
    ?.let { runCatching { (it as Number).toInt() }.getOrNull() }
    ?.takeIf { it > 0 }

private fun JSONObject?.toWatchEndpoint(fallbackVideoId: String): WatchEndpoint {
    if (this == null) return WatchEndpoint(videoId = fallbackVideoId)
    val musicVideoType = optNullableString("musicVideoType")
    return WatchEndpoint(
        videoId = optNullableString("videoId") ?: fallbackVideoId,
        playlistId = optNullableString("playlistId"),
        playlistSetVideoId = optNullableString("playlistSetVideoId"),
        params = optNullableString("params"),
        index = opt("index")?.takeUnless { it == JSONObject.NULL }?.let { runCatching { (it as Number).toInt() }.getOrNull() },
        watchEndpointMusicSupportedConfigs = musicVideoType?.let {
            WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig(it),
            )
        },
    )
}

private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun CoroutineScope.launchPodcastIo(block: suspend () -> Unit) {
    launch(Dispatchers.IO) { block() }
}

