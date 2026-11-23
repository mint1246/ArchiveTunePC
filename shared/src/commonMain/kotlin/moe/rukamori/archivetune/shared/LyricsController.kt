package moe.rukamori.archivetune.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.sdk.lifecycle.SdkLifecycle

data class LyricsState(
    val trackId: String? = null,
    val loading: Boolean = false,
    val lines: List<LyricsLine> = emptyList(),
    val error: LyricsError? = null,
    val source: LyricsSource = LyricsSource.DEFAULT,
    val selectedVersion: LyricsVersion? = null,
    val versions: List<LyricsVersion> = emptyList(),
    val versionsLoading: Boolean = false,
    val versionLoading: Boolean = false,
    val versionError: LyricsError? = null,
) {
    fun activeLine(positionMs: Long): Int? = LyricsTimeline.activeLine(lines, positionMs)
    fun activeLines(positionMs: Long): IntRange? = LyricsTimeline.activeLines(lines, positionMs)
}

/**
 * Playback-independent, application-owned selection and load state backed by `sdk-lifecycle`
 * and `lyrics-sdk`. Switching tracks cancels the previous request and generation-guards even
 * against a repository that ignores cancellation. Call from the UI/main scope.
 */
class LyricsController(
    private val repository: LyricsRepository,
    scope: CoroutineScope,
) : AutoCloseable {
    private val lifecycle = SdkLifecycle.forScope(scope)
    private val workerScope = lifecycle.scope()
    private val mutableState = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = mutableState.asStateFlow()

    private var selectionJob: Job? = null
    private var selectionGeneration = 0L
    private var currentTrack: MusicTrack? = null
    private var searchJob: Job? = null
    private var versionJob: Job? = null
    private var searchGeneration = 0L
    private var versionGeneration = 0L
    private var pendingVersion: LyricsVersion? = null
    private val selections = linkedMapOf<MusicTrack, Pair<LyricsVersion, List<LyricsLine>>>()

    init {
        lifecycle.register(AutoCloseable { repository.close() })
    }

    fun select(track: MusicTrack?, refresh: Boolean = false) {
        if (lifecycle.isClosed) return
        currentTrack = track
        searchJob?.cancel()
        versionJob?.cancel()
        ++searchGeneration
        ++versionGeneration
        val generation = ++selectionGeneration
        selectionJob?.cancel()
        mutableState.value = if (track == null) LyricsState()
        else LyricsState(trackId = track.id, loading = true)
        if (track == null) return
        if (!refresh) {
            val saved = selections.remove(track)
            if (saved != null) {
                selections[track] = saved
                mutableState.value = LyricsState(
                    trackId = track.id,
                    lines = saved.second,
                    source = saved.first.source(),
                    selectedVersion = saved.first,
                )
                return
            }
        }

        selectionJob = workerScope.launch {
            try {
                if (refresh) repository.invalidate(track)
                val lines = repository.fetch(track)
                if (lifecycle.isClosed || generation != selectionGeneration) return@launch
                mutableState.value = LyricsState(
                    trackId = track.id,
                    lines = lines,
                    source = if (lines.any { it.words.isNotEmpty() }) LyricsSource.YOULY_PLUS else LyricsSource.DEFAULT,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LyricsException) {
                if (!lifecycle.isClosed && generation == selectionGeneration) {
                    mutableState.value = LyricsState(trackId = track.id, error = failure.reason)
                }
            } catch (_: Exception) {
                if (!lifecycle.isClosed && generation == selectionGeneration) {
                    mutableState.value = LyricsState(trackId = track.id, error = LyricsError.NETWORK)
                }
            }
        }
    }

    /** Search the current track; repeated calls while searching share the in-flight request. */
    fun searchVersions(track: MusicTrack? = currentTrack) {
        if (lifecycle.isClosed || track == null || track != currentTrack || searchJob?.isActive == true) return
        searchVersionsFor(track, track, null)
    }

    fun searchVersions(title: String, artist: String, album: String?) {
        val track = currentTrack ?: return
        if (lifecycle.isClosed || title.isBlank() || artist.isBlank()) return
        searchJob?.cancel()
        searchVersionsFor(track, track.copy(title = title.trim(), artist = artist.trim()), album?.trim())
    }

    private fun searchVersionsFor(track: MusicTrack, query: MusicTrack, album: String?) {
        val generation = ++searchGeneration
        mutableState.value = mutableState.value.copy(versionsLoading = true, versionError = null)
        searchJob = workerScope.launch {
            try {
                val versions = repository.searchVersions(query, album)
                if (!lifecycle.isClosed && generation == searchGeneration && currentTrack == track) {
                    mutableState.value = mutableState.value.copy(versions = versions, versionsLoading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!lifecycle.isClosed && generation == searchGeneration && currentTrack == track) {
                    mutableState.value = mutableState.value.copy(
                        versionsLoading = false,
                        versionError = (e as? LyricsException)?.reason ?: LyricsError.NETWORK,
                    )
                }
            }
        }
    }

    /** Explicit selection is authoritative; previous lyrics remain visible until it succeeds. */
    fun selectVersion(version: LyricsVersion) {
        val track = currentTrack ?: return
        if (lifecycle.isClosed) return
        if (versionJob?.isActive == true && pendingVersion == version) return
        versionJob?.cancel()
        selectionJob?.cancel()
        ++selectionGeneration
        val generation = ++versionGeneration
        pendingVersion = version
        mutableState.value = mutableState.value.copy(loading = false, versionLoading = true, versionError = null)
        versionJob = workerScope.launch {
            try {
                val lines = repository.fetchVersion(version)
                if (lifecycle.isClosed || generation != versionGeneration || currentTrack != track) return@launch
                selections.remove(track)
                selections[track] = version to lines
                while (selections.size > 24) selections.remove(selections.keys.first())
                mutableState.value = mutableState.value.copy(
                    lines = lines,
                    error = null,
                    versionLoading = false,
                    source = version.source(),
                    selectedVersion = version,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!lifecycle.isClosed && generation == versionGeneration && currentTrack == track) {
                    mutableState.value = mutableState.value.copy(
                        versionLoading = false,
                        versionError = (e as? LyricsException)?.reason ?: LyricsError.NETWORK,
                    )
                }
            }
        }
    }

    private fun LyricsVersion.source() =
        if (provider == LyricsProvider.YOULY_PLUS) LyricsSource.YOULY_PLUS else LyricsSource.LRCLIB_VERSION

    override fun close() {
        if (lifecycle.isClosed) return
        ++selectionGeneration
        lifecycle.close()
        mutableState.value = LyricsState()
    }
}
