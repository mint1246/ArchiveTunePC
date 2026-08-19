/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.cache.DesktopRequestPriority
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.pages.ChartsPage
import moe.rukamori.archivetune.innertube.pages.ExplorePage

data class DesktopExploreState(
    val accountScope: String = "",
    val explorePage: ExplorePage? = null,
    val chartsPage: ChartsPage? = null,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val updatedAtEpochMillis: Long? = null,
) {
    val hasContent: Boolean get() = explorePage != null || chartsPage != null
}

/** Application-scoped Explore state; independent resources refresh concurrently behind cached UI. */
internal class DesktopExploreRepository(
    private val backend: ArchiveTuneJvmClient,
    private val coordinator: DesktopResourceCoordinator,
    private val scope: CoroutineScope,
    private val accountScopeProvider: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val generation = AtomicLong()
    private val _state = MutableStateFlow(DesktopExploreState())
    val state: StateFlow<DesktopExploreState> = _state.asStateFlow()

    suspend fun activate(forceRefresh: Boolean = false) {
        val accountScope = accountScopeProvider()
        if (_state.value.accountScope != accountScope) {
            generation.incrementAndGet()
            _state.value = DesktopExploreState(accountScope = accountScope)
        }

        val current = _state.value
        val fresh = current.hasContent && current.updatedAtEpochMillis?.let { now() - it < EXPLORE_TTL_MS } == true
        if (!forceRefresh && fresh && current.explorePage != null && current.chartsPage != null) return

        val requestGeneration = generation.incrementAndGet()
        val priority = if (current.hasContent) DesktopRequestPriority.REFRESH else DesktopRequestPriority.FOREGROUND
        if (current.hasContent) {
            _state.value = current.copy(isRefreshing = true, isInitialLoading = false, error = null)
        } else {
            _state.value = current.copy(isInitialLoading = true, isRefreshing = false, error = null)
        }

        val (exploreResult, chartsResult) = coroutineScope {
            val explore = async(Dispatchers.IO) {
                coordinator.singleFlight("explore:$accountScope", priority) { backend.explore() }
            }
            val charts = async(Dispatchers.IO) {
                coordinator.singleFlight("charts:$accountScope", priority) { backend.charts() }
            }
            explore.await() to charts.await()
        }

        if (requestGeneration != generation.get() || accountScope != accountScopeProvider()) return
        val previous = _state.value
        val explorePage = exploreResult.getOrNull() ?: previous.explorePage
        val chartsPage = chartsResult.getOrNull() ?: previous.chartsPage
        val error = listOfNotNull(
            exploreResult.exceptionOrNull()?.message?.let { "Explore: $it" },
            chartsResult.exceptionOrNull()?.message?.let { "Charts: $it" },
        ).joinToString(" · ").ifBlank { null }
        _state.value = previous.copy(
            explorePage = explorePage,
            chartsPage = chartsPage,
            isInitialLoading = false,
            isRefreshing = false,
            error = error,
            updatedAtEpochMillis = if (exploreResult.isSuccess || chartsResult.isSuccess) now() else previous.updatedAtEpochMillis,
        )
    }

    fun onAccountScopeChanged() {
        generation.incrementAndGet()
        _state.value = DesktopExploreState(accountScope = accountScopeProvider())
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) { activate(forceRefresh = true) }
    }

    private companion object {
        const val EXPLORE_TTL_MS = 10L * 60L * 1000L
    }
}
