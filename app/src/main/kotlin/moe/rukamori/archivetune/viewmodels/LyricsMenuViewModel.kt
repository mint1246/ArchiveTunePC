/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.common.collect.ImmutableList
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ai.AiLyricsTranslator
import moe.rukamori.archivetune.ai.AiServiceConfig
import moe.rukamori.archivetune.constants.AiApiKeyKey
import moe.rukamori.archivetune.constants.AiApiValidationStatus
import moe.rukamori.archivetune.constants.AiApiValidationStatusKey
import moe.rukamori.archivetune.constants.AiCustomEndpointKey
import moe.rukamori.archivetune.constants.AiCustomModelKey
import moe.rukamori.archivetune.constants.AiCustomPromptKey
import moe.rukamori.archivetune.constants.AiProvider
import moe.rukamori.archivetune.constants.AiProviderKey
import moe.rukamori.archivetune.constants.AiSelectedModelKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.extensions.toEnum
import moe.rukamori.archivetune.lyrics.LyricsHelper
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchStatus as HelperLyricsProviderSearchStatus
import moe.rukamori.archivetune.lyrics.LyricsResult
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.lyrics.LyricsUtils.displayLyricsText
import moe.rukamori.archivetune.lyrics.LyricsUtils.isLineSyncedLrc
import moe.rukamori.archivetune.lyrics.LyricsUtils.isTtml
import moe.rukamori.archivetune.lyrics.translation.TranslateLyricsResult
import moe.rukamori.archivetune.lyrics.translation.TranslateLyricsUseCase
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.utils.NetworkConnectivityObserver
import moe.rukamori.archivetune.utils.dataStore
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

sealed interface LyricsSearchScreenState {
    data object Loading : LyricsSearchScreenState

    @Immutable
    data class Success(
        val providers: ImmutableList<LyricsSearchProviderUiModel>,
        val results: ImmutableList<LyricsSearchResultUiModel>,
        val isSearching: Boolean,
    ) : LyricsSearchScreenState

    data object Empty : LyricsSearchScreenState

    @Immutable
    data class Error(
        @StringRes val messageResId: Int,
    ) : LyricsSearchScreenState
}

enum class LyricsSearchProviderStatus {
    SEARCHING,
    FOUND,
    NO_MATCH,
    TIMED_OUT,
    FAILED,
}

@Immutable
data class LyricsSearchProviderUiModel(
    val id: String,
    val providerName: String,
    val status: LyricsSearchProviderStatus,
    val results: ImmutableList<LyricsSearchResultUiModel> = ImmutableList.of(),
)

@Immutable
data class LyricsSearchResultUiModel(
    val id: String,
    val providerName: String,
    val lyrics: String,
    val preview: String,
    val lineCount: Int,
    val characterCount: Int,
    val isLineSynced: Boolean,
    val isWordSynced: Boolean,
)

internal fun LyricsSearchScreenState.Success.withProviderOutcome(
    providerIndex: Int,
    status: LyricsSearchProviderStatus,
    results: ImmutableList<LyricsSearchResultUiModel>,
): LyricsSearchScreenState.Success {
    if (providerIndex !in providers.indices) return this

    val updatedProviders = providers.toMutableList()
    updatedProviders[providerIndex] =
        updatedProviders[providerIndex].copy(
            status = status,
            results = results,
        )
    return copy(
        providers = ImmutableList.copyOf(updatedProviders),
        results = ImmutableList.copyOf(updatedProviders.flatMap(LyricsSearchProviderUiModel::results)),
    )
}

@Immutable
sealed interface LyricsTranslationError {
    data object Failed : LyricsTranslationError

    data class UnsupportedLanguage(
        val languageName: String,
    ) : LyricsTranslationError
}

@Immutable
sealed interface LyricsTranslationScreenState {
    data object Loading : LyricsTranslationScreenState

    data object Success : LyricsTranslationScreenState

    data object Empty : LyricsTranslationScreenState

    data class Error(
        val error: LyricsTranslationError,
    ) : LyricsTranslationScreenState
}

@HiltViewModel
class LyricsMenuViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val lyricsHelper: LyricsHelper,
        val database: MusicDatabase,
        private val networkConnectivity: NetworkConnectivityObserver,
        private val translateLyricsUseCase: TranslateLyricsUseCase,
    ) : ViewModel() {
        private var job: Job? = null
        private var translationJob: Job? = null
        private var aiTranslationJob: Job? = null
        private val searchGeneration = AtomicLong(0L)
        private val _lyricsSearchState = MutableStateFlow<LyricsSearchScreenState>(LyricsSearchScreenState.Empty)
        val lyricsSearchState: StateFlow<LyricsSearchScreenState> = _lyricsSearchState.asStateFlow()
        private val _isRefetching = MutableStateFlow(false)
        val isRefetching: StateFlow<Boolean> = _isRefetching.asStateFlow()
        private val _refetchCompletionEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val refetchCompletionEvents: SharedFlow<Unit> = _refetchCompletionEvents.asSharedFlow()
        val isAiTranslating = MutableStateFlow(false)

        private val _lyricsTranslationState =
            MutableStateFlow<LyricsTranslationScreenState>(LyricsTranslationScreenState.Empty)
        val lyricsTranslationState: StateFlow<LyricsTranslationScreenState> = _lyricsTranslationState.asStateFlow()

        private val _aiTranslationEvents = MutableSharedFlow<String>()
        val aiTranslationEvents: SharedFlow<String> = _aiTranslationEvents.asSharedFlow()

        private val _isNetworkAvailable = MutableStateFlow(false)
        val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

        init {
            viewModelScope.launch {
                networkConnectivity.networkStatus.collect { isConnected ->
                    _isNetworkAvailable.value = isConnected
                }
            }

            _isNetworkAvailable.value =
                try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (e: Exception) {
                    true
                }
        }

        fun search(
            mediaId: String,
            title: String,
            artist: String,
            album: String?,
            duration: Int,
        ) {
            val generation = searchGeneration.incrementAndGet()
            job?.cancel()
            _lyricsSearchState.value = LyricsSearchScreenState.Loading
            job =
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        if (!_isNetworkAvailable.value) {
                            if (generation == searchGeneration.get()) {
                                _lyricsSearchState.value = LyricsSearchScreenState.Error(R.string.error_no_internet)
                            }
                            return@launch
                        }

                        lyricsHelper.searchAllLyrics(
                            mediaId = mediaId,
                            songTitle = title,
                            songArtists = artist.trim(),
                            songAlbum = album?.trim()?.takeIf(String::isNotEmpty),
                            duration = duration,
                        ) { update ->
                            if (generation != searchGeneration.get()) return@searchAllLyrics
                            val resultModels =
                                ImmutableList.copyOf(
                                    update.results.mapIndexedNotNull { index, result ->
                                        result.toUiModel(index).takeIf { it.preview.isNotBlank() }
                                    },
                                )
                            val status =
                                when (update.status) {
                                    HelperLyricsProviderSearchStatus.SEARCHING -> LyricsSearchProviderStatus.SEARCHING
                                    HelperLyricsProviderSearchStatus.FOUND ->
                                        if (resultModels.isEmpty()) {
                                            LyricsSearchProviderStatus.NO_MATCH
                                        } else {
                                            LyricsSearchProviderStatus.FOUND
                                        }

                                    HelperLyricsProviderSearchStatus.NO_MATCH -> LyricsSearchProviderStatus.NO_MATCH
                                    HelperLyricsProviderSearchStatus.TIMED_OUT -> LyricsSearchProviderStatus.TIMED_OUT
                                    HelperLyricsProviderSearchStatus.FAILED -> LyricsSearchProviderStatus.FAILED
                                }
                            publishProviderSearchUpdate(
                                generation = generation,
                                providerIndex = update.providerIndex,
                                providerName = update.providerName,
                                status = status,
                                results = resultModels,
                            )
                        }
                        if (generation != searchGeneration.get()) return@launch
                        _lyricsSearchState.update { current ->
                            when (current) {
                                LyricsSearchScreenState.Loading -> LyricsSearchScreenState.Empty
                                is LyricsSearchScreenState.Success -> current.copy(isSearching = false)
                                LyricsSearchScreenState.Empty,
                                is LyricsSearchScreenState.Error,
                                -> current
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (generation == searchGeneration.get()) {
                            _lyricsSearchState.value = LyricsSearchScreenState.Error(R.string.error_unknown)
                        }
                    }
                }
        }

        private fun publishProviderSearchUpdate(
            generation: Long,
            providerIndex: Int,
            providerName: String,
            status: LyricsSearchProviderStatus,
            results: ImmutableList<LyricsSearchResultUiModel>,
        ) {
            _lyricsSearchState.update { current ->
                if (generation != searchGeneration.get()) {
                    return@update current
                }
                when (current) {
                    LyricsSearchScreenState.Loading -> {
                        LyricsSearchScreenState.Success(
                            providers =
                                ImmutableList.of(
                                    LyricsSearchProviderUiModel(
                                        id = "$providerIndex:$providerName",
                                        providerName = providerName,
                                        status = status,
                                        results = results,
                                    ),
                                ),
                            results = results,
                            isSearching = true,
                        )
                    }

                    is LyricsSearchScreenState.Success -> {
                        if (providerIndex < current.providers.size) {
                            current.withProviderOutcome(
                                providerIndex = providerIndex,
                                status = status,
                                results = results,
                            )
                        } else {
                            val providers =
                                current.providers.toMutableList().apply {
                                    add(
                                        LyricsSearchProviderUiModel(
                                            id = "$providerIndex:$providerName",
                                            providerName = providerName,
                                            status = status,
                                            results = results,
                                        ),
                                    )
                                }
                            current.copy(
                                providers = ImmutableList.copyOf(providers),
                                results = ImmutableList.copyOf(providers.flatMap(LyricsSearchProviderUiModel::results)),
                            )
                        }
                    }

                    LyricsSearchScreenState.Empty,
                    is LyricsSearchScreenState.Error,
                    -> current
                }
            }
        }

        fun cancelSearch() {
            searchGeneration.incrementAndGet()
            job?.cancel()
            job = null
        }

        fun resetSearchState() {
            cancelSearch()
            _lyricsSearchState.value = LyricsSearchScreenState.Empty
        }

        fun refetchLyrics(mediaMetadata: MediaMetadata) {
            if (!_isRefetching.compareAndSet(expect = false, update = true)) return

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val lyrics = lyricsHelper.getLyrics(mediaMetadata, forceRefresh = true)
                    database.withTransaction {
                        replaceLyrics(
                            id = mediaMetadata.id,
                            lyrics = lyrics,
                            source = LyricsEntity.Source.REMOTE.value,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    _isRefetching.value = false
                    _refetchCompletionEvents.tryEmit(Unit)
                }
            }
        }

        fun updateLyrics(
            mediaMetadata: MediaMetadata,
            lyrics: String,
            source: LyricsEntity.Source = LyricsEntity.Source.USER_EDIT,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                val lyricsToSave =
                    when (source) {
                        LyricsEntity.Source.REMOTE,
                        LyricsEntity.Source.EMBEDDED,
                        LyricsEntity.Source.USER_SELECTION,
                        -> LyricsUtils.lyricsOrNotFound(lyrics)

                        LyricsEntity.Source.USER_EDIT,
                        -> lyrics

                        LyricsEntity.Source.TRANSLATION,
                        LyricsEntity.Source.AI_TRANSLATION ->
                            usableTranslatedLyrics(lyrics) ?: return@launch
                    }
                database.query {
                    replaceLyrics(
                        id = mediaMetadata.id,
                        lyrics = lyricsToSave,
                        source = source.value,
                    )
                }
            }
        }

        fun translateLyrics(
            mediaMetadata: MediaMetadata,
            lyrics: String,
            targetLanguage: String,
            targetLanguageName: String,
        ) {
            if (translationJob?.isActive == true) return
            if (lyrics.isBlank()) {
                _lyricsTranslationState.value =
                    LyricsTranslationScreenState.Error(LyricsTranslationError.Failed)
                return
            }

            _lyricsTranslationState.value = LyricsTranslationScreenState.Loading
            translationJob =
                viewModelScope.launch {
                    val runningJob = coroutineContext[Job]
                    try {
                        _lyricsTranslationState.value =
                            when (
                                translateLyricsUseCase(
                                    mediaId = mediaMetadata.id,
                                    lyrics = lyrics,
                                    targetLanguage = targetLanguage,
                                )
                            ) {
                                TranslateLyricsResult.Success -> LyricsTranslationScreenState.Success
                                TranslateLyricsResult.Empty ->
                                    LyricsTranslationScreenState.Error(LyricsTranslationError.Failed)
                                TranslateLyricsResult.UnsupportedLanguage ->
                                    LyricsTranslationScreenState.Error(
                                        LyricsTranslationError.UnsupportedLanguage(targetLanguageName),
                                    )
                            }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        _lyricsTranslationState.value =
                            LyricsTranslationScreenState.Error(LyricsTranslationError.Failed)
                    } finally {
                        if (translationJob === runningJob) {
                            translationJob = null
                        }
                    }
                }
        }

        fun cancelLyricsTranslation() {
            translationJob?.cancel()
            translationJob = null
            _lyricsTranslationState.value = LyricsTranslationScreenState.Empty
        }

        fun clearLyricsTranslationResult() {
            if (_lyricsTranslationState.value !is LyricsTranslationScreenState.Loading) {
                _lyricsTranslationState.value = LyricsTranslationScreenState.Empty
            }
        }

        fun translateLyricsWithAi(
            mediaMetadata: MediaMetadata,
            lyrics: String,
            targetLanguage: String,
        ) {
            if (isAiTranslating.value || lyrics.isBlank()) return
            aiTranslationJob =
                viewModelScope.launch(Dispatchers.IO) {
                    isAiTranslating.value = true
                    try {
                        val prefs = context.dataStore.data.first()
                        val translatedLyrics =
                            AiLyricsTranslator().translate(
                                config =
                                    AiServiceConfig(
                                        provider = prefs[AiProviderKey].toEnum(AiProvider.NONE),
                                        apiKey = prefs[AiApiKeyKey].orEmpty(),
                                        customEndpoint = prefs[AiCustomEndpointKey].orEmpty(),
                                        model =
                                            if (prefs[AiProviderKey].toEnum(AiProvider.NONE) == AiProvider.CUSTOM) {
                                                prefs[AiCustomModelKey].orEmpty()
                                            } else {
                                                prefs[AiSelectedModelKey].orEmpty()
                                            },
                                    ),
                                lyrics = lyrics,
                                targetLanguage = targetLanguage.ifBlank { "ENGLISH" },
                                customPrompt = prefs[AiCustomPromptKey].orEmpty(),
                            )
                        val usableLyrics = usableTranslatedLyrics(translatedLyrics)
                        if (usableLyrics == null) {
                            _aiTranslationEvents.emit(context.getString(R.string.translation_failed))
                            return@launch
                        }
                        database.query {
                            replaceLyrics(
                                id = mediaMetadata.id,
                                lyrics = usableLyrics,
                                source = LyricsEntity.Source.AI_TRANSLATION.value,
                            )
                        }
                        val msg = context.getString(R.string.translation_success)
                        _aiTranslationEvents.emit(msg)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val msg = context.getString(R.string.translation_failed) + ": " + (e.localizedMessage ?: e.toString())
                        _aiTranslationEvents.emit(msg)
                    } finally {
                        isAiTranslating.value = false
                        aiTranslationJob = null
                    }
                }
        }

        private fun usableTranslatedLyrics(lyrics: String): String? =
            LyricsUtils
                .normalizeLyricsText(lyrics)
                .takeIf(LyricsUtils::hasMeaningfulLyricsContent)

        fun cancelAiTranslation() {
            aiTranslationJob?.cancel()
            aiTranslationJob = null
            isAiTranslating.value = false
        }

        private fun LyricsResult.toUiModel(index: Int): LyricsSearchResultUiModel {
            val preview = displayLyricsText(lyrics)
            val lineCount = preview.lineSequence().count { it.isNotBlank() }
            val isTtmlLyrics = isTtml(lyrics)
            val ttmlEntries =
                if (isTtmlLyrics) {
                    runCatching { LyricsUtils.parseTtml(lyrics) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            val isWordSynced = LyricsUtils.hasWordSyncedLyrics(lyrics)

            return LyricsSearchResultUiModel(
                id = "${providerName}_${lyrics.hashCode()}_$index",
                providerName = providerName,
                lyrics = lyrics,
                preview = preview,
                lineCount = lineCount,
                characterCount = preview.length,
                isLineSynced =
                    when {
                        isWordSynced -> false
                        isTtmlLyrics -> ttmlEntries.isNotEmpty()
                        else -> isLineSyncedLrc(lyrics)
                    },
                isWordSynced = isWordSynced,
            )
        }

    }
