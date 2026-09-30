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
import moe.rukamori.archivetune.innertube.cache.ytItemToJson
import moe.rukamori.archivetune.innertube.cache.ytItemFromJson
import moe.rukamori.archivetune.innertube.cache.watchEndpointToCacheJson
import moe.rukamori.archivetune.innertube.cache.watchEndpointFromCacheJson
import moe.rukamori.archivetune.innertube.cache.optionalCacheString
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.HomePage
import org.json.JSONArray
import org.json.JSONObject

data class DesktopRemoteHomeState(
    val accountScope: String = "",
    val homePage: HomePage? = null,
    val remoteQuickPicks: HomePage.Section? = null,
    val playlists: List<PlaylistItem> = emptyList(),
    val selectedChip: HomePage.Chip? = null,
    val previousHomePage: HomePage? = null,
    val previousQuickPicks: HomePage.Section? = null,
    val feedbackHiddenItems: Set<String> = emptySet(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val updatedAtEpochMillis: Long? = null,
)

private data class PersistedRemoteHome(
    val homePage: HomePage,
    val quickPicks: HomePage.Section?,
)

/** Long-lived Home state. Compose only renders this state; it no longer owns remote Home requests. */
class DesktopHomeRepository(
    private val backend: ArchiveTuneJvmClient,
    private val coordinator: DesktopResourceCoordinator,
    private val libraryRepository: DesktopLibraryRepository,
    private val scope: CoroutineScope,
    private val accountScopeProvider: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(DesktopRemoteHomeState())
    val state: StateFlow<DesktopRemoteHomeState> = _state.asStateFlow()

    init {
        scope.launch {
            libraryRepository.state.collect { libraryState ->
                if (libraryState.accountScope == _state.value.accountScope) {
                    _state.value = _state.value.copy(playlists = libraryState.playlists)
                }
            }
        }
    }

    suspend fun activate(forceRefresh: Boolean = false) {
        val accountScope = accountScopeProvider()
        ensureScope(accountScope)
        scope.launch(Dispatchers.IO) { libraryRepository.activate(forceRefresh = false) }
        val current = _state.value
        if (forceRefresh || current.homePage == null || isStale(current.updatedAtEpochMillis)) {
            if (current.homePage == null) refresh(DesktopRequestPriority.FOREGROUND)
            else scope.launch(Dispatchers.IO) { refresh(DesktopRequestPriority.REFRESH) }
        }
    }

    suspend fun onAccountScopeChanged() {
        ensureScope(accountScopeProvider())
    }

    suspend fun refresh(priority: DesktopRequestPriority = DesktopRequestPriority.REFRESH): Result<HomePage> {
        val accountScope = accountScopeProvider()
        ensureScope(accountScope)
        val hadContent = _state.value.homePage != null
        _state.value = _state.value.copy(
            isInitialLoading = !hadContent,
            isRefreshing = hadContent,
            error = null,
        )
        val result = coordinator.singleFlight("home:$accountScope", priority) {
            withContext(Dispatchers.IO) { backend.home() }
        }
        result.onSuccess { rawPage ->
            val (homePage, quickPicks) = rawPage.extractDesktopQuickPicks()
            val updatedAt = now()
            _state.value = _state.value.copy(
                accountScope = accountScope,
                homePage = homePage,
                remoteQuickPicks = quickPicks,
                selectedChip = null,
                previousHomePage = null,
                previousQuickPicks = null,
                isInitialLoading = false,
                isRefreshing = false,
                error = null,
                updatedAtEpochMillis = updatedAt,
            )
            val persisted = PersistedRemoteHome(homePage, quickPicks)
            coordinator.putMemory(cacheKey(accountScope), persisted, updatedAt)
            scope.launch(Dispatchers.IO) {
                coordinator.writePersistent(cacheKey(accountScope), encodeRemoteHome(persisted), updatedAt)
            }
        }.onFailure { failure ->
            _state.value = _state.value.copy(
                isInitialLoading = false,
                isRefreshing = false,
                error = failure.message ?: "Could not load the home feed.",
            )
        }
        return result
    }

    fun selectChip(chip: HomePage.Chip) {
        val current = _state.value
        if (chip == current.selectedChip && current.previousHomePage != null) {
            _state.value = current.copy(
                homePage = current.previousHomePage,
                remoteQuickPicks = current.previousQuickPicks,
                selectedChip = null,
                previousHomePage = null,
                previousQuickPicks = null,
                isRefreshing = false,
                error = null,
            )
            return
        }
        val previousPage = if (current.selectedChip == null) current.homePage else current.previousHomePage
        val previousQuickPicks = if (current.selectedChip == null) current.remoteQuickPicks else current.previousQuickPicks
        val accountScope = accountScopeProvider()
        scope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isRefreshing = true, error = null)
            coordinator.singleFlight("home-chip:$accountScope:${chip.endpoint?.params.orEmpty()}", DesktopRequestPriority.FOREGROUND) {
                backend.home(params = chip.endpoint?.params)
            }.onSuccess { rawPage ->
                val (page, quickPicks) = rawPage.copy(chips = previousPage?.chips).extractDesktopQuickPicks()
                _state.value = _state.value.copy(
                    homePage = page,
                    remoteQuickPicks = quickPicks,
                    selectedChip = chip,
                    previousHomePage = previousPage,
                    previousQuickPicks = previousQuickPicks,
                    isRefreshing = false,
                    error = null,
                )
            }.onFailure { failure ->
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    error = failure.message ?: "Could not load this Home category.",
                )
            }
        }
    }

    fun loadMore() {
        val continuation = _state.value.homePage?.continuation ?: return
        if (_state.value.isLoadingMore) return
        val accountScope = accountScopeProvider()
        scope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoadingMore = true)
            coordinator.singleFlight("home-continuation:$accountScope:$continuation", DesktopRequestPriority.FOREGROUND) {
                backend.home(continuation = continuation)
            }.onSuccess { next ->
                val current = _state.value.homePage ?: return@onSuccess
                val merged = current.copy(
                    sections = current.sections + next.sections,
                    continuation = next.continuation,
                )
                val (page, quickPicks) = merged.extractDesktopQuickPicks()
                _state.value = _state.value.copy(
                    homePage = page,
                    remoteQuickPicks = quickPicks ?: _state.value.remoteQuickPicks,
                )
            }
            _state.value = _state.value.copy(isLoadingMore = false)
        }
    }

    fun hideRemoteItem(item: YTItem) {
        _state.value = _state.value.copy(feedbackHiddenItems = _state.value.feedbackHiddenItems + item.homeItemKey())
    }

    private suspend fun ensureScope(accountScope: String) {
        if (_state.value.accountScope == accountScope) return
        val playlists = libraryRepository.state.value.takeIf { it.accountScope == accountScope }?.playlists.orEmpty()
        val key = cacheKey(accountScope)
        val memory = coordinator.memoryValue<PersistedRemoteHome>(key)
        if (memory != null) {
            _state.value = DesktopRemoteHomeState(
                accountScope = accountScope,
                homePage = memory.first.homePage,
                remoteQuickPicks = memory.first.quickPicks,
                playlists = playlists,
                updatedAtEpochMillis = memory.second,
            )
            return
        }
        val persisted = coordinator.readPersistent(key)
        val decoded = persisted?.payload?.let(::decodeRemoteHome)
        if (decoded != null) coordinator.putMemory(key, decoded, persisted.storedAtEpochMillis)
        _state.value = DesktopRemoteHomeState(
            accountScope = accountScope,
            homePage = decoded?.homePage,
            remoteQuickPicks = decoded?.quickPicks,
            playlists = playlists,
            updatedAtEpochMillis = persisted?.storedAtEpochMillis,
        )
    }

    private fun isStale(updatedAt: Long?): Boolean = updatedAt == null || now() - updatedAt >= HOME_TTL_MS
    private fun cacheKey(scope: String) = "home-cache:$scope"

    private companion object {
        const val HOME_TTL_MS = 3L * 60L * 1000L
    }
}

private fun HomePage.extractDesktopQuickPicks(): Pair<HomePage, HomePage.Section?> {
    val quickPicksIndex = sections.indexOfFirst { it.title.contains("quick pick", ignoreCase = true) }
    if (quickPicksIndex < 0) return this to null
    val quickPicks = sections[quickPicksIndex]
    return copy(sections = sections.toMutableList().also { it.removeAt(quickPicksIndex) }) to quickPicks
}

private fun YTItem.homeItemKey() = "${this::class.simpleName}:$id"

private fun encodeRemoteHome(snapshot: PersistedRemoteHome): String =
    JSONObject()
        .put("homePage", homePageToJson(snapshot.homePage))
        .put("quickPicks", snapshot.quickPicks?.let(::sectionToJson) ?: JSONObject.NULL)
        .toString()

private fun decodeRemoteHome(raw: String): PersistedRemoteHome? = runCatching {
    val json = JSONObject(raw)
    PersistedRemoteHome(
        homePage = homePageFromJson(json.getJSONObject("homePage")) ?: return@runCatching null,
        quickPicks = json.optJSONObject("quickPicks")?.let(::sectionFromJson),
    )
}.getOrNull()

private fun homePageToJson(page: HomePage): JSONObject =
    JSONObject()
        .put("continuation", page.continuation ?: JSONObject.NULL)
        .put("chips", JSONArray().apply { page.chips.orEmpty().forEach { put(chipToJson(it)) } })
        .put("sections", JSONArray().apply { page.sections.forEach { put(sectionToJson(it)) } })

private fun homePageFromJson(json: JSONObject): HomePage? =
    HomePage(
        chips = json.optJSONArray("chips").objects().mapNotNull(::chipFromJson).takeIf(List<HomePage.Chip>::isNotEmpty),
        sections = json.optJSONArray("sections").objects().mapNotNull(::sectionFromJson),
        continuation = json.optString("continuation").takeIf(String::isNotBlank),
    )

private fun chipToJson(chip: HomePage.Chip): JSONObject =
    JSONObject()
        .put("title", chip.title)
        .put("endpoint", chip.endpoint?.let(::browseEndpointToJson) ?: JSONObject.NULL)
        .put("deselect", chip.deselectEndPoint?.let(::browseEndpointToJson) ?: JSONObject.NULL)

private fun chipFromJson(json: JSONObject): HomePage.Chip? {
    val title = json.optString("title").takeIf(String::isNotBlank) ?: return null
    return HomePage.Chip(
        title = title,
        endpoint = json.optJSONObject("endpoint")?.let(::browseEndpointFromJson),
        deselectEndPoint = json.optJSONObject("deselect")?.let(::browseEndpointFromJson),
    )
}

private fun browseEndpointToJson(endpoint: BrowseEndpoint): JSONObject =
    JSONObject().put("browseId", endpoint.browseId).put("params", endpoint.params ?: JSONObject.NULL)

private fun browseEndpointFromJson(json: JSONObject): BrowseEndpoint? =
    json.optString("browseId").takeIf(String::isNotBlank)?.let { BrowseEndpoint(it, json.optString("params").takeIf(String::isNotBlank)) }

internal fun sectionToJson(section: HomePage.Section): JSONObject =
    JSONObject()
        .put("title", section.title)
        .put("label", section.label ?: JSONObject.NULL)
        .put("thumbnail", section.thumbnail ?: JSONObject.NULL)
        .put("endpoint", section.endpoint?.let(::browseEndpointToJson) ?: JSONObject.NULL)
        .put("items", JSONArray().apply { section.items.forEach { itemToJson(it)?.let(::put) } })
        .put("featuredCards", JSONArray().apply {
            section.featuredCards.forEach { card ->
                put(JSONObject()
                    .put("id", card.id).put("title", card.title)
                    .put("subtitle", card.subtitle ?: JSONObject.NULL)
                    .put("thumbnail", card.thumbnail ?: JSONObject.NULL)
                    .put("endpoint", card.endpoint?.let(::browseEndpointToJson) ?: JSONObject.NULL)
                    .put("playEndpoint", card.playEndpoint?.let(::watchEndpointToCacheJson) ?: JSONObject.NULL)
                    .put("shuffleEndpoint", card.shuffleEndpoint?.let(::watchEndpointToCacheJson) ?: JSONObject.NULL)
                    .put("radioEndpoint", card.radioEndpoint?.let(::watchEndpointToCacheJson) ?: JSONObject.NULL)
                    .put("itemIds", JSONArray(card.itemIds)))
            }
        })

internal fun sectionFromJson(json: JSONObject): HomePage.Section? {
    val title = json.optString("title").takeIf(String::isNotBlank) ?: return null
    val items = json.optJSONArray("items").objects().mapNotNull(::itemFromJson)
    val cards = json.optJSONArray("featuredCards").objects().mapNotNull { card ->
        val id = card.optionalCacheString("id") ?: return@mapNotNull null
        val cardTitle = card.optionalCacheString("title") ?: return@mapNotNull null
        val ids = card.optJSONArray("itemIds")
        HomePage.Section.FeaturedCard(
            id = id, title = cardTitle,
            subtitle = card.optionalCacheString("subtitle"), thumbnail = card.optionalCacheString("thumbnail"),
            endpoint = card.optJSONObject("endpoint")?.let(::browseEndpointFromJson),
            playEndpoint = card.optJSONObject("playEndpoint")?.let(::watchEndpointFromCacheJson),
            shuffleEndpoint = card.optJSONObject("shuffleEndpoint")?.let(::watchEndpointFromCacheJson),
            radioEndpoint = card.optJSONObject("radioEndpoint")?.let(::watchEndpointFromCacheJson),
            itemIds = if (ids == null) emptyList() else (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotBlank) },
        )
    }
    if (items.isEmpty() && cards.isEmpty()) return null
    return HomePage.Section(
        title = title,
        label = json.optString("label").takeIf(String::isNotBlank),
        thumbnail = json.optString("thumbnail").takeIf(String::isNotBlank),
        endpoint = json.optJSONObject("endpoint")?.let(::browseEndpointFromJson),
        items = items,
        featuredCards = cards,
    )
}

private fun itemToJson(item: YTItem): JSONObject = ytItemToJson(item)

private fun itemFromJson(json: JSONObject): YTItem? = ytItemFromJson(json)

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else buildList(length()) { for (index in 0 until length()) optJSONObject(index)?.let(::add) }
