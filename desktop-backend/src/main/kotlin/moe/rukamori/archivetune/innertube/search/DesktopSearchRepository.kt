/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.innertube.search

import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.cache.DesktopRequestPriority
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.cache.jsonObjects
import moe.rukamori.archivetune.innertube.cache.ytItemFromJson
import moe.rukamori.archivetune.innertube.cache.ytItemToJson
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.SearchSummaryPage
import org.json.JSONArray
import org.json.JSONObject

data class DesktopSearchState(
    val accountScope: String = "",
    val query: String = "",
    val normalizedQuery: String = "",
    val items: List<YTItem> = emptyList(),
    val history: List<String> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val updatedAtEpochMillis: Long? = null,
)

private data class SearchSnapshot(
    val query: String,
    val items: List<YTItem>,
    val storedAtEpochMillis: Long,
)

/** Application-scoped search sessions with bounded memory and persistent recent results/history. */
class DesktopSearchRepository(
    private val searchSummary: suspend (String) -> Result<SearchSummaryPage>,
    private val coordinator: DesktopResourceCoordinator,
    private val scope: CoroutineScope,
    private val accountScopeProvider: () -> String,
    private val maxMemoryQueries: Int = 12,
    private val maxHistoryEntries: Int = 30,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val sessionLock = Any()
    private val sessions = object : LinkedHashMap<String, SearchSnapshot>(maxMemoryQueries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchSnapshot>?): Boolean =
            size > maxMemoryQueries
    }
    private val requestGeneration = AtomicLong()
    private var refreshJob: Job? = null
    private val _state = MutableStateFlow(DesktopSearchState())
    val state: StateFlow<DesktopSearchState> = _state.asStateFlow()

    init {
        require(maxMemoryQueries > 0) { "Search memory cache must contain at least one query" }
        require(maxHistoryEntries > 0) { "Search history must contain at least one query" }
        scope.launch(Dispatchers.IO) { ensureScope(accountScopeProvider()) }
    }

    suspend fun activate(rawQuery: String, forceRefresh: Boolean = false) {
        val query = rawQuery.trim().replace(Regex("\\s+"), " ")
        val normalized = normalize(query)
        val accountScope = accountScopeProvider()
        ensureScope(accountScope)
        refreshJob?.cancel()
        val generation = requestGeneration.incrementAndGet()
        if (normalized.isBlank()) {
            _state.value = _state.value.copy(
                accountScope = accountScope,
                query = "",
                normalizedQuery = "",
                items = emptyList(),
                isInitialLoading = false,
                isRefreshing = false,
                error = null,
                updatedAtEpochMillis = null,
            )
            return
        }

        val cached = loadCached(accountScope, normalized)
        if (cached != null) {
            _state.value = _state.value.copy(
                accountScope = accountScope,
                query = query,
                normalizedQuery = normalized,
                items = cached.items,
                isInitialLoading = false,
                isRefreshing = false,
                error = null,
                updatedAtEpochMillis = cached.storedAtEpochMillis,
            )
            recordHistory(accountScope, query)
            if (!forceRefresh && !isStale(cached.storedAtEpochMillis)) return
        } else {
            _state.value = _state.value.copy(
                accountScope = accountScope,
                query = query,
                normalizedQuery = normalized,
                items = emptyList(),
                isInitialLoading = true,
                isRefreshing = false,
                error = null,
                updatedAtEpochMillis = null,
            )
        }

        refreshJob = scope.launch(Dispatchers.IO) {
            refresh(query, normalized, accountScope, generation, cached != null)
        }
    }

    suspend fun onAccountScopeChanged() {
        refreshJob?.cancel()
        requestGeneration.incrementAndGet()
        synchronized(sessionLock) { sessions.clear() }
        ensureScope(accountScopeProvider(), force = true)
    }

    fun removeHistory(query: String) {
        val accountScope = accountScopeProvider()
        val normalized = normalize(query)
        val updated = _state.value.history.filterNot { normalize(it) == normalized }
        _state.value = _state.value.copy(history = updated)
        scope.launch(Dispatchers.IO) { persistHistory(accountScope, updated) }
    }

    fun clearHistory() {
        val accountScope = accountScopeProvider()
        _state.value = _state.value.copy(history = emptyList())
        scope.launch(Dispatchers.IO) { coordinator.deletePersistent(historyKey(accountScope)) }
    }

    private suspend fun refresh(
        query: String,
        normalized: String,
        accountScope: String,
        generation: Long,
        hadCachedContent: Boolean,
    ) {
        if (generation != requestGeneration.get()) return
        if (hadCachedContent) _state.value = _state.value.copy(isRefreshing = true, error = null)
        val result = coordinator.singleFlight(
            key = "search:$accountScope:$normalized",
            priority = if (hadCachedContent) DesktopRequestPriority.REFRESH else DesktopRequestPriority.FOREGROUND,
        ) {
            withContext(Dispatchers.IO) { searchSummary(query) }
        }
        if (generation != requestGeneration.get() || normalize(_state.value.query) != normalized) return

        result.onSuccess { page ->
            val items = page.summaries.asSequence()
                .flatMap { it.items.asSequence() }
                .distinctBy { it::class to it.id }
                .toList()
            val storedAt = now()
            val snapshot = SearchSnapshot(query, items, storedAt)
            synchronized(sessionLock) { sessions[normalized] = snapshot }
            coordinator.putMemory(cacheKey(accountScope, normalized), snapshot, storedAt)
            _state.value = _state.value.copy(
                query = query,
                normalizedQuery = normalized,
                items = items,
                isInitialLoading = false,
                isRefreshing = false,
                error = null,
                updatedAtEpochMillis = storedAt,
            )
            recordHistory(accountScope, query)
            scope.launch(Dispatchers.IO) {
                coordinator.writePersistent(cacheKey(accountScope, normalized), encodeSnapshot(snapshot), storedAt)
            }
        }.onFailure { failure ->
            _state.value = _state.value.copy(
                isInitialLoading = false,
                isRefreshing = false,
                error = failure.message ?: "Search failed. Check your connection and try again.",
            )
        }
    }

    private suspend fun loadCached(accountScope: String, normalized: String): SearchSnapshot? {
        synchronized(sessionLock) { sessions[normalized] }?.let { return it }
        coordinator.memoryValue<SearchSnapshot>(cacheKey(accountScope, normalized))?.first?.let { snapshot ->
            synchronized(sessionLock) { sessions[normalized] = snapshot }
            return snapshot
        }
        val persisted = coordinator.readPersistent(cacheKey(accountScope, normalized)) ?: return null
        val decoded = decodeSnapshot(persisted.payload, persisted.storedAtEpochMillis) ?: return null
        synchronized(sessionLock) { sessions[normalized] = decoded }
        coordinator.putMemory(cacheKey(accountScope, normalized), decoded, persisted.storedAtEpochMillis)
        return decoded
    }

    private suspend fun ensureScope(accountScope: String, force: Boolean = false) {
        if (!force && _state.value.accountScope == accountScope) return
        val history = readHistory(accountScope)
        _state.value = DesktopSearchState(accountScope = accountScope, history = history)
    }

    private fun recordHistory(accountScope: String, query: String) {
        val normalized = normalize(query)
        if (normalized.isBlank()) return
        val updated = buildList {
            add(query)
            _state.value.history.forEach { existing ->
                if (normalize(existing) != normalized && size < maxHistoryEntries) add(existing)
            }
        }.take(maxHistoryEntries)
        if (updated == _state.value.history) return
        _state.value = _state.value.copy(history = updated)
        scope.launch(Dispatchers.IO) { persistHistory(accountScope, updated) }
    }

    private suspend fun readHistory(accountScope: String): List<String> {
        val persisted = coordinator.readPersistent(historyKey(accountScope)) ?: return emptyList()
        return runCatching {
            val array = JSONArray(persisted.payload)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }.distinctBy(::normalize).take(maxHistoryEntries)
        }.getOrDefault(emptyList())
    }

    private suspend fun persistHistory(accountScope: String, history: List<String>) {
        coordinator.writePersistent(historyKey(accountScope), JSONArray(history).toString(), now())
    }

    private fun isStale(storedAt: Long): Boolean = now() - storedAt >= SEARCH_TTL_MS
    private fun normalize(query: String): String = query.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    private fun cacheKey(accountScope: String, normalized: String) = "search-cache:$accountScope:$normalized"
    private fun historyKey(accountScope: String) = "search-history:$accountScope"

    internal fun memorySessionCount(): Int = synchronized(sessionLock) { sessions.size }

    private companion object {
        const val SEARCH_TTL_MS = 10L * 60L * 1000L
    }
}

private fun encodeSnapshot(snapshot: SearchSnapshot): String =
    JSONObject()
        .put("query", snapshot.query)
        .put("items", JSONArray().apply { snapshot.items.forEach { ytItemToJson(it)?.let(::put) } })
        .toString()

private fun decodeSnapshot(raw: String, storedAt: Long): SearchSnapshot? = runCatching {
    val json = JSONObject(raw)
    val query = json.optString("query").trim().takeIf(String::isNotBlank) ?: return null
    val items = json.optJSONArray("items").jsonObjects().mapNotNull(::ytItemFromJson)
    SearchSnapshot(query, items, storedAt)
}.getOrNull()
