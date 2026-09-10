/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import moe.rukamori.archivetune.db.MusicDatabase
import javax.inject.Inject

sealed interface AndroidStatisticsUiState {
    data object Loading : AndroidStatisticsUiState

    data class Content(
        val selectedRange: AndroidStatisticsRange,
        val overview: AndroidStatisticsOverview,
    ) : AndroidStatisticsUiState

    data class Error(
        val throwable: Throwable,
    ) : AndroidStatisticsUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AndroidStatisticsViewModel
    @Inject
    constructor(
        database: MusicDatabase,
    ) : ViewModel() {
        private val store = AndroidStatisticsStore(database)
        private val selectedRange = MutableStateFlow(AndroidStatisticsRange.DAYS_7)
        private val refreshNonce = MutableStateFlow(0L)

        val state: StateFlow<AndroidStatisticsUiState> =
            combine(selectedRange, refreshNonce) { range, _ -> range }
                .flatMapLatest { range ->
                    flow<AndroidStatisticsUiState> {
                        emit(AndroidStatisticsUiState.Loading)
                        val now = System.currentTimeMillis()
                        val epochRange = range.toEpochRange(now, store.zoneId)
                        val overview = store.overview(epochRange)
                        emit(AndroidStatisticsUiState.Content(range, overview))
                    }.flowOn(Dispatchers.IO)
                        .catch { throwable ->
                            if (throwable is CancellationException) throw throwable
                            emit(AndroidStatisticsUiState.Error(throwable))
                        }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000L),
                    initialValue = AndroidStatisticsUiState.Loading,
                )

        fun selectRange(range: AndroidStatisticsRange) {
            selectedRange.value = range
        }

        fun refresh() {
            refreshNonce.value = refreshNonce.value + 1L
        }
    }
