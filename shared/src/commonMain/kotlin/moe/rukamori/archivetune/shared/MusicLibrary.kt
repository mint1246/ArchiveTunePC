package moe.rukamori.archivetune.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class MusicThemeChoice { SYSTEM, LIGHT, DARK }

data class MusicPlaylist(val id: String, val name: String, val tracks: List<MusicTrack> = emptyList())

/** Restored queue/index are metadata only; restoring this state never starts playback. */
interface MusicLibraryState {
    val favorites: List<MusicTrack>
    val searchHistory: List<String> get() = emptyList()
    val history: List<MusicTrack>
    val playlists: List<MusicPlaylist>
    val queue: List<MusicTrack>
    val queueIndex: Int
    val settings: MusicSettings
    val speedDialPins: List<MusicSpeedDialPin> get() = emptyList()
    val savedAlbums: List<MusicCatalogItem.Browse> get() = emptyList()
    val savedArtists: List<MusicCatalogItem.Browse> get() = emptyList()
    val savedPlaylists: List<MusicCatalogItem.Browse> get() = emptyList()
    val savedPodcasts: List<MusicCatalogItem.Browse> get() = emptyList()
    val downloads: List<MusicDownloadedTrack> get() = emptyList()
    val localTracks: List<MusicTrack> get() = emptyList()
    val cachedTracks: List<MusicTrack> get() = emptyList()
    val playlistTags: List<MusicPlaylistTag> get() = emptyList()
    val readNewsIds: Set<String> get() = emptySet()
    val statistics: MusicStatisticsStoreData get() = MusicStatisticsStoreData()
    val youtubeAccount: MusicYouTubeAccount? get() = null
    val isLoaded: Boolean
    /** True when the most recent storage read/write failed. Explicit [MusicLibrary.flush] can retry. */
    val persistenceError: Boolean
}

private data class LibrarySnapshot(
    override val favorites: List<MusicTrack> = emptyList(),
    override val history: List<MusicTrack> = emptyList(),
    override val searchHistory: List<String> = emptyList(),
    val pendingSearchEdits: List<(List<String>) -> List<String>> = emptyList(),
    override val playlists: List<MusicPlaylist> = emptyList(),
    val pendingPlaylistEdits: List<(List<MusicPlaylist>) -> List<MusicPlaylist>> = emptyList(),
    override val queue: List<MusicTrack> = emptyList(),
    override val queueIndex: Int = -1,
    override val settings: MusicSettings = MusicSettings(),
    override val speedDialPins: List<MusicSpeedDialPin> = emptyList(),
    override val savedAlbums: List<MusicCatalogItem.Browse> = emptyList(),
    override val savedArtists: List<MusicCatalogItem.Browse> = emptyList(),
    override val savedPlaylists: List<MusicCatalogItem.Browse> = emptyList(),
    override val savedPodcasts: List<MusicCatalogItem.Browse> = emptyList(),
    override val downloads: List<MusicDownloadedTrack> = emptyList(),
    override val localTracks: List<MusicTrack> = emptyList(),
    override val cachedTracks: List<MusicTrack> = emptyList(),
    override val playlistTags: List<MusicPlaylistTag> = emptyList(),
    override val readNewsIds: Set<String> = emptySet(),
    override val statistics: MusicStatisticsStoreData = MusicStatisticsStoreData(),
    override val youtubeAccount: MusicYouTubeAccount? = null,
    override val isLoaded: Boolean = false,
    override val persistenceError: Boolean = false,
    // Retain user intent while disk read is pending. Replay against the restored favorites,
    // rather than incorrectly treating an initially empty list as the persisted truth.
    val pendingFavoriteToggles: List<MusicTrack> = emptyList(),
    val queueEditedBeforeLoad: Boolean = false,
    val settingsEditedBeforeLoad: Boolean = false,
    val revision: Long = 0,
) : MusicLibraryState

/**
 * Application-owned, portable library state. Changes appear synchronously in [state].
 * A single asynchronous writer coalesces edits; playback position/slider ticks are never saved.
 *
 * The IO job is independent of the supplied scope's Job, so callers can flush during app
 * shutdown even if the UI scope has been cancelled. Always call suspending [close] on shutdown:
 * it retries any pending write, propagates storage failures, and can be retried on failure.
 */
class MusicLibrary(
    private val store: MusicStore,
    scope: CoroutineScope,
) {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + serviceJob)
    private val mutableState = MutableStateFlow(LibrarySnapshot())
    val state: StateFlow<MusicLibraryState> = mutableState.asStateFlow()

    private val writeSignals = Channel<Unit>(Channel.CONFLATED)
    private val writeMutex = Mutex()
    private val closeMutex = Mutex()
    private val closing = MutableStateFlow(false)
    private var disposed = false
    // Protected by writeMutex. Revision 0 is the unmodified, already-read snapshot.
    private var persistedRevision = 0L

    private val loading = serviceScope.launch {
        var readFailed = false
        val restored = try {
            store.read()?.let(MusicLibraryJson::decode) ?: RestoredLibrary()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readFailed = true
            RestoredLibrary()
        }
        mutableState.update { current ->
            if (current.isLoaded) current else current.copy(
                favorites = current.pendingFavoriteToggles.fold(restored.favorites, ::toggle),
                history = (current.history + restored.history).distinctBy { it.id }.take(200),
                searchHistory = current.pendingSearchEdits.fold(restored.searchHistory) { list, edit -> edit(list) },
                pendingSearchEdits = emptyList(),
                playlists = current.pendingPlaylistEdits.fold(restored.playlists) { list, edit -> edit(list) },
                pendingPlaylistEdits = emptyList(),
                queue = if (current.queueEditedBeforeLoad) current.queue else restored.queue,
                queueIndex = if (current.queueEditedBeforeLoad) current.queueIndex else restored.queueIndex,
                settings = if (current.settingsEditedBeforeLoad) current.settings else restored.settings,
                speedDialPins = (current.speedDialPins + restored.speedDialPins).distinctBy { it.type to it.id }.take(30),
                savedAlbums = (current.savedAlbums + restored.savedAlbums).distinctBy { it.id }.take(200),
                savedArtists = (current.savedArtists + restored.savedArtists).distinctBy { it.id }.take(200),
                savedPlaylists = (current.savedPlaylists + restored.savedPlaylists).distinctBy { it.id }.take(200),
                savedPodcasts = (current.savedPodcasts + restored.savedPodcasts).distinctBy { it.id }.take(200),
                downloads = (current.downloads + restored.downloads).distinctBy { it.track.id }.take(500),
                localTracks = (current.localTracks + restored.localTracks).distinctBy { it.id }.take(500),
                cachedTracks = (current.cachedTracks + restored.cachedTracks).distinctBy { it.id }.take(200),
                playlistTags = (current.playlistTags + restored.playlistTags).distinctBy { it.id }.take(50),
                readNewsIds = current.readNewsIds + restored.readNewsIds,
                statistics = if (current.statistics.tracks.isEmpty() && current.statistics.events.isEmpty()) restored.statistics else current.statistics,
                youtubeAccount = current.youtubeAccount ?: restored.youtubeAccount,
                isLoaded = true,
                persistenceError = readFailed,
                pendingFavoriteToggles = emptyList(),
                queueEditedBeforeLoad = false,
                settingsEditedBeforeLoad = false,
            )
        }
    }

    private val writer = serviceScope.launch {
        for (ignored in writeSignals) {
            delay(150) // consolidate rapid user actions into one snapshot
            try {
                flush()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep dirty revision and retry on the next change, explicit flush, or close.
                mutableState.update { it.copy(persistenceError = true) }
            }
        }
    }

    /** This is only needed when the caller must distinguish initial defaults from restored state. */
    suspend fun awaitLoaded() {
        loading.join()
    }

    /** Recent audible starts are history metadata, never wall-clock listening statistics. */
    fun recordPlay(track: MusicTrack) {
        if (closing.value || mutableState.value.settings.pauseListenHistory) return
        val safe = MusicLibraryJson.cleanTrack(track) ?: return
        if (state.value.history.firstOrNull()?.id == safe.id) return
        mutableState.update { previous ->
            previous.copy(
                history = (listOf(safe) + previous.history).distinctBy { it.id }.take(200),
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun removeFromHistory(trackId: String) {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(
                history = previous.history.filterNot { it.id == trackId },
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun clearHistory() {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(history = emptyList(), revision = previous.revision + 1)
        }
        writeSignals.trySend(Unit)
    }

    fun recordSearch(query: String) {
        if (mutableState.value.settings.pauseSearchHistory) return
        val clean = query.trim().take(200).takeIf { it.isNotEmpty() } ?: return
        editSearchHistory { (listOf(clean) + it).distinct().take(100) }
    }

    fun removeSearch(query: String) { editSearchHistory { it.filterNot { value -> value == query } } }

    fun clearSearchHistory() { editSearchHistory { emptyList() } }

    private fun editSearchHistory(edit: (List<String>) -> List<String>) {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(
                searchHistory = edit(previous.searchHistory),
                pendingSearchEdits = if (previous.isLoaded) emptyList() else previous.pendingSearchEdits + edit,
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    /** Unique favorites, ordered by addition. Toggling off removes by track ID. */
    fun toggleFavorite(track: MusicTrack) {
        if (closing.value) return
        val safe = MusicLibraryJson.cleanTrack(track) ?: return
        mutableState.update { previous ->
            previous.copy(
                favorites = toggle(previous.favorites, safe),
                pendingFavoriteToggles = if (previous.isLoaded) emptyList() else
                    (previous.pendingFavoriteToggles + safe).takeLast(MAX_PENDING_TOGGLES),
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    private fun editPlaylists(edit: (List<MusicPlaylist>) -> List<MusicPlaylist>) {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(
                playlists = edit(previous.playlists),
                pendingPlaylistEdits = if (previous.isLoaded) emptyList() else previous.pendingPlaylistEdits + edit,
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun createPlaylist(name: String): String? {
        val clean = name.trim().take(256).takeIf { it.isNotEmpty() } ?: return null
        val id = "local-" + kotlin.random.Random.nextLong().toULong().toString(16) + "-" + kotlin.random.Random.nextLong().toULong().toString(16)
        editPlaylists { (it + MusicPlaylist(id, clean)).takeLast(200) }
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        val clean = name.trim().take(256).takeIf { it.isNotEmpty() } ?: return
        editPlaylists { list -> list.map { if (it.id == id) it.copy(name = clean) else it } }
    }

    fun deletePlaylist(id: String) = editPlaylists { list -> list.filterNot { it.id == id } }

    fun addToPlaylist(id: String, track: MusicTrack) {
        val safe = MusicLibraryJson.cleanTrack(track) ?: return
        editPlaylists { list ->
            if (list.sumOf { it.tracks.size } >= 2000) list
            else list.map { if (it.id == id) it.copy(tracks = it.tracks + safe) else it }
        }
    }

    /** Index, rather than track ID, identifies an occurrence when duplicates exist. */
    fun removeFromPlaylist(id: String, index: Int) = editPlaylists { list ->
        list.map { playlist -> if (playlist.id == id) playlist.copy(tracks = playlist.tracks.filterIndexed { i, _ -> i != index }) else playlist }
    }

    fun reorderPlaylistTrack(id: String, fromIndex: Int, toIndex: Int) = editPlaylists { list ->
        list.map { playlist ->
            if (playlist.id != id || fromIndex == toIndex ||
                fromIndex !in playlist.tracks.indices || toIndex !in playlist.tracks.indices
            ) playlist else {
                val mutable = playlist.tracks.toMutableList()
                val moved = mutable.removeAt(fromIndex)
                mutable.add(toIndex, moved)
                playlist.copy(tracks = mutable)
            }
        }
    }

    fun toggleSpeedDialPin(pin: MusicSpeedDialPin) {
        if (closing.value || pin.id.isBlank()) return
        mutableState.update { previous ->
            val exists = previous.speedDialPins.any { it.type == pin.type && it.id == pin.id }
            val updated = if (exists) {
                previous.speedDialPins.filterNot { it.type == pin.type && it.id == pin.id }
            } else {
                (listOf(pin) + previous.speedDialPins).take(30)
            }
            previous.copy(speedDialPins = updated, revision = previous.revision + 1)
        }
        writeSignals.trySend(Unit)
    }

    fun toggleSavedBrowse(item: MusicCatalogItem.Browse) {
        if (closing.value || item.id.isBlank()) return
        val clean = MusicLibraryJson.cleanBrowse(item) ?: return
        mutableState.update { previous ->
            fun toggleList(list: List<MusicCatalogItem.Browse>): List<MusicCatalogItem.Browse> =
                if (list.any { it.id == clean.id }) list.filterNot { it.id == clean.id }
                else (listOf(clean) + list).take(200)
            when (clean.kind) {
                MusicBrowseKind.ALBUM -> previous.copy(savedAlbums = toggleList(previous.savedAlbums), revision = previous.revision + 1)
                MusicBrowseKind.ARTIST -> previous.copy(savedArtists = toggleList(previous.savedArtists), revision = previous.revision + 1)
                MusicBrowseKind.PLAYLIST -> previous.copy(savedPlaylists = toggleList(previous.savedPlaylists), revision = previous.revision + 1)
                MusicBrowseKind.OTHER -> previous.copy(savedPodcasts = toggleList(previous.savedPodcasts), revision = previous.revision + 1)
            }
        }
        writeSignals.trySend(Unit)
    }

    fun recordDownload(downloaded: MusicDownloadedTrack) {
        if (closing.value) return
        val cleanTrack = MusicLibraryJson.cleanTrack(downloaded.track) ?: return
        val entry = downloaded.copy(track = cleanTrack)
        mutableState.update { previous ->
            previous.copy(
                downloads = (listOf(entry) + previous.downloads.filterNot { it.track.id == cleanTrack.id }).take(500),
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun removeDownload(trackId: String) {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(
                downloads = previous.downloads.filterNot { it.track.id == trackId },
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun clearDownloads() {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(downloads = emptyList(), revision = previous.revision + 1)
        }
        writeSignals.trySend(Unit)
    }

    fun addLocalTrack(track: MusicTrack) {
        if (closing.value) return
        val clean = MusicLibraryJson.cleanTrack(track) ?: return
        mutableState.update { previous ->
            previous.copy(
                localTracks = (listOf(clean) + previous.localTracks.filterNot { it.id == clean.id }).take(500),
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun removeLocalTrack(trackId: String) {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(
                localTracks = previous.localTracks.filterNot { it.id == trackId },
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun recordCachedTrack(track: MusicTrack) {
        if (closing.value) return
        val clean = MusicLibraryJson.cleanTrack(track) ?: return
        mutableState.update { previous ->
            previous.copy(
                cachedTracks = (listOf(clean) + previous.cachedTracks.filterNot { it.id == clean.id }).take(200),
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun clearCachedTracks() {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(cachedTracks = emptyList(), revision = previous.revision + 1)
        }
        writeSignals.trySend(Unit)
    }

    fun createPlaylistTag(name: String): String? {
        if (closing.value) return null
        val clean = name.trim().take(64).takeIf { it.isNotEmpty() } ?: return null
        val id = "tag-" + kotlin.random.Random.nextLong().toULong().toString(16)
        mutableState.update { previous ->
            previous.copy(
                playlistTags = (previous.playlistTags + MusicPlaylistTag(id, clean)).take(50),
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
        return id
    }

    fun deletePlaylistTag(tagId: String) {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(
                playlistTags = previous.playlistTags.filterNot { it.id == tagId },
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun togglePlaylistTag(tagId: String, playlistId: String) {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(
                playlistTags = previous.playlistTags.map { tag ->
                    if (tag.id != tagId) tag else {
                        val updated = if (playlistId in tag.playlistIds) tag.playlistIds - playlistId else tag.playlistIds + playlistId
                        tag.copy(playlistIds = updated)
                    }
                },
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun markNewsRead(newsId: String) {
        if (closing.value || newsId.isBlank() || newsId in mutableState.value.readNewsIds) return
        mutableState.update { previous ->
            previous.copy(
                readNewsIds = (previous.readNewsIds + newsId).takeLastSet(200),
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    internal fun recordAudibleDelta(delta: AudibleStatisticsSession.Delta, parts: LocalDateTimeParts) {
        if (closing.value || delta.listeningMs <= 0L) return
        val clean = MusicLibraryJson.cleanTrack(delta.track) ?: return
        mutableState.update { previous ->
            val oldStat = previous.statistics.tracks[clean.id]
            val updatedStat = MusicTrackStat(
                trackId = clean.id,
                title = clean.title,
                artist = clean.artist,
                artistId = clean.artistId ?: oldStat?.artistId,
                albumId = clean.albumId ?: oldStat?.albumId,
                albumName = clean.albumName ?: oldStat?.albumName,
                artworkUrl = clean.artworkUrl ?: oldStat?.artworkUrl,
                durationMs = clean.durationMs ?: oldStat?.durationMs,
                totalListeningMs = (oldStat?.totalListeningMs ?: 0L) + delta.listeningMs,
                playCount = (oldStat?.playCount ?: 0) + if (delta.newlyCountedPlay) 1 else 0,
                lastPlayedEpochMs = parts.epochMs,
            )
            // Merge adjacent event for same track within the same hour to keep compact storage!
            val events = previous.statistics.events.toMutableList()
            val last = events.lastOrNull()
            if (last != null &&
                last.trackId == clean.id &&
                !delta.newlyCountedPlay &&
                last.dayOfWeek == parts.dayOfWeek &&
                last.hourOfDay == parts.hourOfDay &&
                (parts.epochMs - last.epochMs) in 0L..3_600_000L
            ) {
                events[events.lastIndex] = last.copy(
                    listeningMs = last.listeningMs + delta.listeningMs,
                    epochMs = parts.epochMs,
                )
            } else {
                events += MusicStatisticsEvent(
                    trackId = clean.id,
                    epochMs = parts.epochMs,
                    listeningMs = delta.listeningMs,
                    countedPlay = delta.newlyCountedPlay,
                    dayOfWeek = parts.dayOfWeek,
                    hourOfDay = parts.hourOfDay,
                )
            }
            val trimmedEvents = if (events.size > 4_000) events.takeLast(4_000) else events
            previous.copy(
                statistics = MusicStatisticsStoreData(
                    tracks = previous.statistics.tracks + (clean.id to updatedStat),
                    events = trimmedEvents,
                ),
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun resetStatistics() {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(statistics = MusicStatisticsStoreData(), revision = previous.revision + 1)
        }
        writeSignals.trySend(Unit)
    }

    fun setYouTubeAccount(account: MusicYouTubeAccount?) {
        if (closing.value) return
        mutableState.update { previous ->
            previous.copy(youtubeAccount = account, revision = previous.revision + 1)
        }
        writeSignals.trySend(Unit)
    }

    /** Queue duplicates and the exact selected list index are preserved; no source resolution. */
    fun saveQueue(tracks: List<MusicTrack>, index: Int) {
        if (closing.value) return
        val candidates = tracks.take(MAX_QUEUE).map(MusicLibraryJson::cleanTrack)
        val savedIndex = if (index in candidates.indices && candidates[index] != null)
            candidates.take(index).count { it != null } else -1
        val safe = candidates.filterNotNull()
        mutableState.update { previous ->
            if (previous.isLoaded && previous.queue == safe && previous.queueIndex == savedIndex) previous
            else previous.copy(
                queue = safe,
                queueIndex = savedIndex,
                queueEditedBeforeLoad = !previous.isLoaded,
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun setTheme(theme: MusicThemeChoice) = updateSettings { it.copy(theme = theme) }

    fun updateSettings(transform: (MusicSettings) -> MusicSettings) {
        if (closing.value) return
        mutableState.update { previous ->
            val updated = transform(previous.settings)
            if (previous.isLoaded && previous.settings == updated) previous
            else previous.copy(
                settings = updated,
                settingsEditedBeforeLoad = !previous.isLoaded,
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
    }

    fun exportBackupJson(): String = MusicLibraryJson.encode(mutableState.value)

    fun importBackupJson(raw: String): Boolean {
        if (closing.value) return false
        val decoded = MusicLibraryJson.decodeValidated(raw) ?: return false
        mutableState.update { previous ->
            previous.copy(
                favorites = decoded.favorites,
                history = decoded.history,
                searchHistory = decoded.searchHistory,
                playlists = decoded.playlists,
                queue = decoded.queue,
                queueIndex = decoded.queueIndex,
                settings = decoded.settings,
                speedDialPins = decoded.speedDialPins,
                savedAlbums = decoded.savedAlbums,
                savedArtists = decoded.savedArtists,
                savedPlaylists = decoded.savedPlaylists,
                savedPodcasts = decoded.savedPodcasts,
                playlistTags = decoded.playlistTags,
                statistics = decoded.statistics,
                isLoaded = true,
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
        return true
    }

    /**
     * Suspend until the latest observed revision is stored. A concurrent newer edit is also
     * persisted if it becomes visible before the final revision check. Throws on IO failure;
     * failed writes remain dirty for retry.
     */
    suspend fun flush() {
        loading.join()
        writeMutex.lock()
        try {
            while (true) {
                val snapshot = mutableState.value
                if (!snapshot.isLoaded || snapshot.revision <= persistedRevision) return
                try {
                    store.write(MusicLibraryJson.encode(snapshot))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    mutableState.update { it.copy(persistenceError = true) }
                    throw failure
                }
                persistedRevision = snapshot.revision
                mutableState.update { it.copy(persistenceError = false) }
            }
        } finally {
            writeMutex.unlock()
        }
    }

    /**
     * Prevent new edits, cancel the delayed writer and durably flush the final state.
     * Suspends instead of silently dropping a pending snapshot. Safe to retry if storage fails.
     */
    suspend fun close() = withContext(NonCancellable) {
        closeMutex.lock()
        try {
            if (disposed) return@withContext
            closing.value = true
            writeSignals.close()
            writer.cancelAndJoin()
            flush()
            disposed = true
            serviceScope.cancel()
        } finally {
            closeMutex.unlock()
        }
    }
}

private fun Set<String>.takeLastSet(limit: Int): Set<String> =
    if (size <= limit) this else toList().takeLast(limit).toSet()

private const val MAX_FAVORITES = 400
private const val MAX_QUEUE = 200
private const val MAX_PENDING_TOGGLES = 2_048

private fun toggle(favorites: List<MusicTrack>, track: MusicTrack): List<MusicTrack> {
    val existing = favorites.indexOfFirst { it.id == track.id }
    return if (existing >= 0) favorites.filterIndexed { index, _ -> index != existing }
    else (favorites + track).takeLast(MAX_FAVORITES)
}

private data class RestoredLibrary(
    val playlists: List<MusicPlaylist> = emptyList(),
    val favorites: List<MusicTrack> = emptyList(),
    val queue: List<MusicTrack> = emptyList(),
    val queueIndex: Int = -1,
    val settings: MusicSettings = MusicSettings(),
    val history: List<MusicTrack> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val speedDialPins: List<MusicSpeedDialPin> = emptyList(),
    val savedAlbums: List<MusicCatalogItem.Browse> = emptyList(),
    val savedArtists: List<MusicCatalogItem.Browse> = emptyList(),
    val savedPlaylists: List<MusicCatalogItem.Browse> = emptyList(),
    val savedPodcasts: List<MusicCatalogItem.Browse> = emptyList(),
    val downloads: List<MusicDownloadedTrack> = emptyList(),
    val localTracks: List<MusicTrack> = emptyList(),
    val cachedTracks: List<MusicTrack> = emptyList(),
    val playlistTags: List<MusicPlaylistTag> = emptyList(),
    val readNewsIds: Set<String> = emptySet(),
    val statistics: MusicStatisticsStoreData = MusicStatisticsStoreData(),
    val youtubeAccount: MusicYouTubeAccount? = null,
)

/** Small explicit schema, no Kotlin serialization plugin or JVM-only dependencies. */
private object MusicLibraryJson {
    private const val VERSION = 1
    private const val MAX_JSON_LENGTH = 16_000_000
    private const val MAX_ID_LENGTH = 128
    private const val MAX_TEXT_LENGTH = 256
    private const val MAX_ARTWORK_LENGTH = 1_024
    private const val MAX_DURATION_MS = 24L * 60 * 60 * 1000

    fun cleanTrack(track: MusicTrack): MusicTrack? {
        val id = track.id
        if (id.isBlank() || id.length > MAX_ID_LENGTH || id.any { it.code < 32 }) return null
        return MusicTrack(
            id = id,
            title = track.title.take(MAX_TEXT_LENGTH).ifBlank { id },
            artist = track.artist.take(MAX_TEXT_LENGTH),
            artworkUrl = cleanArtwork(track.artworkUrl),
            durationMs = track.durationMs?.takeIf { it in 0..MAX_DURATION_MS },
            artistId = track.artistId?.take(MAX_ID_LENGTH)?.takeIf(String::isNotBlank),
            albumId = track.albumId?.take(MAX_ID_LENGTH)?.takeIf(String::isNotBlank),
            albumName = track.albumName?.take(MAX_TEXT_LENGTH)?.takeIf(String::isNotBlank),
            explicit = track.explicit,
            localFilePath = track.localFilePath?.take(MAX_ARTWORK_LENGTH)?.takeIf { it.startsWith("file:///") && !it.contains("..") },
        )
    }

    fun cleanBrowse(item: MusicCatalogItem.Browse): MusicCatalogItem.Browse? {
        if (item.id.isBlank() || item.id.length > 512) return null
        return MusicCatalogItem.Browse(
            id = item.id,
            title = item.title.take(MAX_TEXT_LENGTH).ifBlank { item.id },
            subtitle = item.subtitle?.take(MAX_TEXT_LENGTH),
            artworkUrl = cleanArtwork(item.artworkUrl),
            kind = item.kind,
            params = item.params?.take(1024),
        )
    }

    private fun cleanArtwork(value: String?): String? {
        val url = value ?: return null
        if (url.length > MAX_ARTWORK_LENGTH || !url.startsWith("https://") ||
            '?' in url || '#' in url
        ) return null
        // No playback hosts, expiring/signed query strings, arbitrary third-party URLs or userinfo.
        val authority = url.removePrefix("https://").substringBefore('/')
        val host = authority.lowercase()
        if (host !in setOf("i.ytimg.com", "yt3.ggpht.com", "lh3.googleusercontent.com",
                "yt3.googleusercontent.com")) return null
        return url
    }

    fun decode(raw: String): RestoredLibrary = decodeValidated(raw) ?: RestoredLibrary()

    fun decodeValidated(raw: String): RestoredLibrary? {
        if (raw.length > MAX_JSON_LENGTH) return null
        return try {
            val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
            val version = root.int("version") ?: 0
            if (version !in 0..VERSION) return null

            val seen = mutableSetOf<String>()
            val favorites = (root["favorites"] as? JsonArray).orEmpty()
                .mapNotNull(::readTrack)
                .filter { seen.add(it.id) }
                .take(MAX_FAVORITES)
            val queueNode = root["queue"]
            val queueObject = queueNode as? JsonObject
            val queueTracks = (queueObject?.get("tracks") as? JsonArray)
                ?: (queueNode as? JsonArray)
                ?: (root["queueTracks"] as? JsonArray)
            val candidates = queueTracks.orEmpty().take(MAX_QUEUE).map(::readTrack)
            val restoredIndex = queueObject?.int("index") ?: root.int("queueIndex") ?: -1
            val index = if (restoredIndex in candidates.indices && candidates[restoredIndex] != null)
                candidates.take(restoredIndex).count { it != null } else -1
            val queue = candidates.filterNotNull()
            val settingsObj = root["settings"] as? JsonObject
            val settings = readSettings(settingsObj, root.string("theme"))
            val playlistIds = mutableSetOf<String>()
            var remainingTracks = 2000
            val playlists = (root["playlists"] as? JsonArray).orEmpty().take(200).mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.string("id")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return@mapNotNull null
                val name = obj.string("name")?.trim()?.take(256)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (!playlistIds.add(id)) return@mapNotNull null
                val tracks = (obj["tracks"] as? JsonArray).orEmpty().take(remainingTracks).mapNotNull(::readTrack)
                remainingTracks -= tracks.size
                MusicPlaylist(id, name, tracks)
            }
            val speedDial = (root["speedDialPins"] as? JsonArray).orEmpty().take(30).mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val type = obj.string("type")?.takeIf(String::isNotBlank) ?: "song"
                MusicSpeedDialPin(
                    type = type,
                    id = id,
                    title = obj.string("title").orEmpty().ifBlank { id }.take(MAX_TEXT_LENGTH),
                    subtitle = obj.string("subtitle").orEmpty().take(MAX_TEXT_LENGTH),
                    artworkUrl = cleanArtwork(obj.string("artworkUrl")),
                    params = obj.string("params")?.take(1024),
                )
            }
            val downloads = (root["downloads"] as? JsonArray).orEmpty().take(500).mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val track = obj["track"]?.let(::readTrack) ?: return@mapNotNull null
                val path = obj.string("filePath")?.takeIf { it.isNotBlank() && !it.contains("..") } ?: return@mapNotNull null
                MusicDownloadedTrack(
                    track = track,
                    filePath = path,
                    sizeBytes = obj.long("sizeBytes") ?: 0L,
                    downloadedAtMs = obj.long("downloadedAtMs") ?: 0L,
                )
            }
            val tags = (root["playlistTags"] as? JsonArray).orEmpty().take(50).mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = obj.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val ids = (obj["playlistIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                MusicPlaylistTag(id, name, ids)
            }
            val readNews = (root["readNewsIds"] as? JsonArray).orEmpty().take(200)
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.toSet()
            val stats = readStatistics(root["statistics"] as? JsonObject)
            val ytAccount = (root["youtubeAccount"] as? JsonObject)?.let { obj ->
                val name = obj.string("name")?.takeIf(String::isNotBlank) ?: return@let null
                MusicYouTubeAccount(
                    name = name.take(MAX_TEXT_LENGTH),
                    email = obj.string("email").orEmpty().take(MAX_TEXT_LENGTH),
                    handle = obj.string("handle").orEmpty().take(MAX_TEXT_LENGTH),
                    avatarUrl = cleanArtwork(obj.string("avatarUrl")),
                )
            }
            RestoredLibrary(
                playlists = playlists,
                favorites = favorites,
                queue = queue,
                queueIndex = index,
                settings = settings,
                history = (root["history"] as? JsonArray).orEmpty().mapNotNull(::readTrack).distinctBy { it.id }.take(200),
                searchHistory = (root["searchHistory"] as? JsonArray).orEmpty().take(100)
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.take(200)?.takeIf(String::isNotEmpty) }.distinct(),
                speedDialPins = speedDial,
                savedAlbums = readBrowseList(root["savedAlbums"]),
                savedArtists = readBrowseList(root["savedArtists"]),
                savedPlaylists = readBrowseList(root["savedPlaylists"]),
                savedPodcasts = readBrowseList(root["savedPodcasts"]),
                downloads = downloads,
                localTracks = (root["localTracks"] as? JsonArray).orEmpty().mapNotNull(::readTrack).distinctBy { it.id }.take(500),
                cachedTracks = (root["cachedTracks"] as? JsonArray).orEmpty().mapNotNull(::readTrack).distinctBy { it.id }.take(200),
                playlistTags = tags,
                readNewsIds = readNews,
                statistics = stats,
                youtubeAccount = ytAccount,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun encode(snapshot: MusicLibraryState): String {
        val json = buildJsonObject {
            put("version", VERSION)
            put("favorites", JsonArray(snapshot.favorites.take(MAX_FAVORITES).map(::writeTrack)))
            put("history", JsonArray(snapshot.history.take(200).map(::writeTrack)))
            put("searchHistory", JsonArray(snapshot.searchHistory.take(100).map(::JsonPrimitive)))
            put("playlists", JsonArray(snapshot.playlists.take(200).map { playlist ->
                buildJsonObject {
                    put("id", playlist.id)
                    put("name", playlist.name)
                    put("tracks", JsonArray(playlist.tracks.take(2000).map(::writeTrack)))
                }
            }))
            put("queue", buildJsonObject {
                put("tracks", JsonArray(snapshot.queue.take(MAX_QUEUE).map(::writeTrack)))
                put("index", snapshot.queueIndex)
            })
            put("settings", writeSettings(snapshot.settings))
            if (snapshot.speedDialPins.isNotEmpty()) {
                put("speedDialPins", JsonArray(snapshot.speedDialPins.take(30).map { pin ->
                    buildJsonObject {
                        put("type", pin.type)
                        put("id", pin.id)
                        put("title", pin.title)
                        if (pin.subtitle.isNotEmpty()) put("subtitle", pin.subtitle)
                        pin.artworkUrl?.let { put("artworkUrl", it) }
                        pin.params?.let { put("params", it) }
                    }
                }))
            }
            if (snapshot.savedAlbums.isNotEmpty()) put("savedAlbums", writeBrowseList(snapshot.savedAlbums))
            if (snapshot.savedArtists.isNotEmpty()) put("savedArtists", writeBrowseList(snapshot.savedArtists))
            if (snapshot.savedPlaylists.isNotEmpty()) put("savedPlaylists", writeBrowseList(snapshot.savedPlaylists))
            if (snapshot.savedPodcasts.isNotEmpty()) put("savedPodcasts", writeBrowseList(snapshot.savedPodcasts))
            if (snapshot.downloads.isNotEmpty()) {
                put("downloads", JsonArray(snapshot.downloads.take(500).map { dl ->
                    buildJsonObject {
                        put("track", writeTrack(dl.track))
                        put("filePath", dl.filePath)
                        put("sizeBytes", dl.sizeBytes)
                        put("downloadedAtMs", dl.downloadedAtMs)
                    }
                }))
            }
            if (snapshot.localTracks.isNotEmpty()) put("localTracks", JsonArray(snapshot.localTracks.take(500).map(::writeTrack)))
            if (snapshot.cachedTracks.isNotEmpty()) put("cachedTracks", JsonArray(snapshot.cachedTracks.take(200).map(::writeTrack)))
            if (snapshot.playlistTags.isNotEmpty()) {
                put("playlistTags", JsonArray(snapshot.playlistTags.take(50).map { tag ->
                    buildJsonObject {
                        put("id", tag.id)
                        put("name", tag.name)
                        put("playlistIds", JsonArray(tag.playlistIds.map(::JsonPrimitive)))
                    }
                }))
            }
            if (snapshot.readNewsIds.isNotEmpty()) {
                put("readNewsIds", JsonArray(snapshot.readNewsIds.take(200).map(::JsonPrimitive)))
            }
            if (snapshot.statistics.tracks.isNotEmpty() || snapshot.statistics.events.isNotEmpty()) {
                put("statistics", writeStatistics(snapshot.statistics))
            }
            snapshot.youtubeAccount?.let { acc ->
                put("youtubeAccount", buildJsonObject {
                    put("name", acc.name)
                    if (acc.email.isNotEmpty()) put("email", acc.email)
                    if (acc.handle.isNotEmpty()) put("handle", acc.handle)
                    acc.avatarUrl?.let { put("avatarUrl", it) }
                })
            }
        }
        return json.toString()
    }

    private inline fun <reified E : Enum<E>> JsonObject?.enum(key: String, default: E): E {
        val raw = this?.string(key) ?: return default
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
    }

    private fun readSettings(obj: JsonObject?, rootTheme: String?): MusicSettings {
        val d = MusicSettings()
        val themeRaw = obj?.string("theme") ?: rootTheme
        val theme = MusicThemeChoice.entries.firstOrNull { it.name.equals(themeRaw, ignoreCase = true) } ?: d.theme
        if (obj == null) return d.copy(theme = theme)
        return MusicSettings(
            theme = theme,
            pureBlack = obj.bool("pureBlack") ?: d.pureBlack,
            dynamicTheme = obj.bool("dynamicTheme") ?: d.dynamicTheme,
            playerDesignStyle = obj.enum("playerDesignStyle", d.playerDesignStyle),
            playerBackgroundStyle = obj.enum("playerBackgroundStyle", d.playerBackgroundStyle),
            miniPlayerBackgroundStyle = obj.enum("miniPlayerBackgroundStyle", d.miniPlayerBackgroundStyle),
            lyricsBackgroundStyle = obj.enum("lyricsBackgroundStyle", d.lyricsBackgroundStyle),
            sliderStyle = obj.enum("sliderStyle", d.sliderStyle),
            thumbnailCornerRadius = (obj.float("thumbnailCornerRadius") ?: d.thumbnailCornerRadius).coerceIn(0f, 36f),
            cropThumbnailToSquare = obj.bool("cropThumbnailToSquare") ?: d.cropThumbnailToSquare,
            showPlayerVolumeBar = obj.bool("showPlayerVolumeBar") ?: d.showPlayerVolumeBar,
            hidePlayerThumbnail = obj.bool("hidePlayerThumbnail") ?: d.hidePlayerThumbnail,
            defaultOpenTab = obj.enum("defaultOpenTab", d.defaultOpenTab),
            gridItemSize = obj.enum("gridItemSize", d.gridItemSize),
            showHomeCategoryChips = obj.bool("showHomeCategoryChips") ?: d.showHomeCategoryChips,
            quickPicksMode = obj.enum("quickPicksMode", d.quickPicksMode),
            disableBlur = obj.bool("disableBlur") ?: d.disableBlur,
            disableAnimations = obj.bool("disableAnimations") ?: d.disableAnimations,
            swipeThumbnail = obj.bool("swipeThumbnail") ?: d.swipeThumbnail,
            swipeSensitivity = (obj.float("swipeSensitivity") ?: d.swipeSensitivity).coerceIn(0.1f, 1f),
            swipeToSong = obj.bool("swipeToSong") ?: d.swipeToSong,
            audioQuality = obj.enum("audioQuality", d.audioQuality),
            playbackSpeed = (obj.float("playbackSpeed") ?: d.playbackSpeed).coerceIn(0.25f, 3f),
            playbackPitch = (obj.float("playbackPitch") ?: d.playbackPitch).coerceIn(-12f, 12f),
            persistentQueue = obj.bool("persistentQueue") ?: d.persistentQueue,
            permanentShuffle = obj.bool("permanentShuffle") ?: d.permanentShuffle,
            autoSkipNextOnError = obj.bool("autoSkipNextOnError") ?: d.autoSkipNextOnError,
            seekExtraSeconds = obj.bool("seekExtraSeconds") ?: d.seekExtraSeconds,
            crossfadeEnabled = obj.bool("crossfadeEnabled") ?: d.crossfadeEnabled,
            crossfadeDurationSec = (obj.float("crossfadeDurationSec") ?: d.crossfadeDurationSec).coerceIn(1f, 12f),
            crossfadeGapless = obj.bool("crossfadeGapless") ?: d.crossfadeGapless,
            audioNormalization = obj.bool("audioNormalization") ?: d.audioNormalization,
            skipSilence = obj.bool("skipSilence") ?: d.skipSilence,
            autoDownloadOnLike = obj.bool("autoDownloadOnLike") ?: d.autoDownloadOnLike,
            keepScreenAwake = obj.bool("keepScreenAwake") ?: d.keepScreenAwake,
            sponsorBlockEnabled = obj.bool("sponsorBlockEnabled") ?: d.sponsorBlockEnabled,
            lyricsClick = obj.bool("lyricsClick") ?: d.lyricsClick,
            lyricsScroll = obj.bool("lyricsScroll") ?: d.lyricsScroll,
            lyricsTextSize = (obj.float("lyricsTextSize") ?: d.lyricsTextSize).coerceIn(16f, 36f),
            lyricsLineSpacing = (obj.float("lyricsLineSpacing") ?: d.lyricsLineSpacing).coerceIn(1f, 2f),
            lyricsLineBlur = obj.bool("lyricsLineBlur") ?: d.lyricsLineBlur,
            lyricsAlignment = obj.enum("lyricsAlignment", d.lyricsAlignment),
            lyricsOffsetMs = (obj.long("lyricsOffsetMs") ?: d.lyricsOffsetMs).coerceIn(-5000L, 5000L),
            enableLrclib = obj.bool("enableLrclib") ?: d.enableLrclib,
            enableYouLyPlus = obj.bool("enableYouLyPlus") ?: d.enableYouLyPlus,
            contentLanguage = obj.string("contentLanguage") ?: d.contentLanguage,
            contentCountry = obj.string("contentCountry") ?: d.contentCountry,
            hideExplicit = obj.bool("hideExplicit") ?: d.hideExplicit,
            hideVideo = obj.bool("hideVideo") ?: d.hideVideo,
            topLength = (obj.int("topLength") ?: d.topLength).coerceIn(10, 200),
            pauseListenHistory = obj.bool("pauseListenHistory") ?: d.pauseListenHistory,
            pauseSearchHistory = obj.bool("pauseSearchHistory") ?: d.pauseSearchHistory,
            enableHapticFeedback = obj.bool("enableHapticFeedback") ?: d.enableHapticFeedback,
            countPartyListening = obj.bool("countPartyListening") ?: d.countPartyListening,
            maxImageCacheSizeMb = obj.int("maxImageCacheSizeMb") ?: d.maxImageCacheSizeMb,
            maxSongCacheSizeMb = obj.int("maxSongCacheSizeMb") ?: d.maxSongCacheSizeMb,
        )
    }

    private fun writeSettings(s: MusicSettings): JsonElement = buildJsonObject {
        put("theme", s.theme.name.lowercase())
        put("pureBlack", s.pureBlack)
        put("dynamicTheme", s.dynamicTheme)
        put("playerDesignStyle", s.playerDesignStyle.name)
        put("playerBackgroundStyle", s.playerBackgroundStyle.name)
        put("miniPlayerBackgroundStyle", s.miniPlayerBackgroundStyle.name)
        put("lyricsBackgroundStyle", s.lyricsBackgroundStyle.name)
        put("sliderStyle", s.sliderStyle.name)
        put("thumbnailCornerRadius", s.thumbnailCornerRadius)
        put("cropThumbnailToSquare", s.cropThumbnailToSquare)
        put("showPlayerVolumeBar", s.showPlayerVolumeBar)
        put("hidePlayerThumbnail", s.hidePlayerThumbnail)
        put("defaultOpenTab", s.defaultOpenTab.name)
        put("gridItemSize", s.gridItemSize.name)
        put("showHomeCategoryChips", s.showHomeCategoryChips)
        put("quickPicksMode", s.quickPicksMode.name)
        put("disableBlur", s.disableBlur)
        put("disableAnimations", s.disableAnimations)
        put("swipeThumbnail", s.swipeThumbnail)
        put("swipeSensitivity", s.swipeSensitivity)
        put("swipeToSong", s.swipeToSong)
        put("audioQuality", s.audioQuality.name)
        put("playbackSpeed", s.playbackSpeed)
        put("playbackPitch", s.playbackPitch)
        put("persistentQueue", s.persistentQueue)
        put("permanentShuffle", s.permanentShuffle)
        put("autoSkipNextOnError", s.autoSkipNextOnError)
        put("seekExtraSeconds", s.seekExtraSeconds)
        put("crossfadeEnabled", s.crossfadeEnabled)
        put("crossfadeDurationSec", s.crossfadeDurationSec)
        put("crossfadeGapless", s.crossfadeGapless)
        put("audioNormalization", s.audioNormalization)
        put("skipSilence", s.skipSilence)
        put("autoDownloadOnLike", s.autoDownloadOnLike)
        put("keepScreenAwake", s.keepScreenAwake)
        put("sponsorBlockEnabled", s.sponsorBlockEnabled)
        put("lyricsClick", s.lyricsClick)
        put("lyricsScroll", s.lyricsScroll)
        put("lyricsTextSize", s.lyricsTextSize)
        put("lyricsLineSpacing", s.lyricsLineSpacing)
        put("lyricsLineBlur", s.lyricsLineBlur)
        put("lyricsAlignment", s.lyricsAlignment.name)
        put("lyricsOffsetMs", s.lyricsOffsetMs)
        put("enableLrclib", s.enableLrclib)
        put("enableYouLyPlus", s.enableYouLyPlus)
        put("contentLanguage", s.contentLanguage)
        put("contentCountry", s.contentCountry)
        put("hideExplicit", s.hideExplicit)
        put("hideVideo", s.hideVideo)
        put("topLength", s.topLength)
        put("pauseListenHistory", s.pauseListenHistory)
        put("pauseSearchHistory", s.pauseSearchHistory)
        put("enableHapticFeedback", s.enableHapticFeedback)
        put("countPartyListening", s.countPartyListening)
        put("maxImageCacheSizeMb", s.maxImageCacheSizeMb)
        put("maxSongCacheSizeMb", s.maxSongCacheSizeMb)
    }

    private fun readBrowseList(el: JsonElement?): List<MusicCatalogItem.Browse> =
        (el as? JsonArray).orEmpty().take(200).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: return@mapNotNull null
            val kind = obj.enum("kind", MusicBrowseKind.OTHER)
            cleanBrowse(
                MusicCatalogItem.Browse(
                    id = id,
                    title = obj.string("title") ?: id,
                    subtitle = obj.string("subtitle"),
                    artworkUrl = obj.string("artworkUrl"),
                    kind = kind,
                    params = obj.string("params"),
                ),
            )
        }

    private fun writeBrowseList(items: List<MusicCatalogItem.Browse>): JsonElement =
        JsonArray(items.take(200).map { item ->
            buildJsonObject {
                put("id", item.id)
                put("title", item.title)
                item.subtitle?.let { put("subtitle", it) }
                item.artworkUrl?.let { put("artworkUrl", it) }
                put("kind", item.kind.name)
                item.params?.let { put("params", it) }
            }
        })

    private fun readStatistics(obj: JsonObject?): MusicStatisticsStoreData {
        if (obj == null) return MusicStatisticsStoreData()
        val tracks = linkedMapOf<String, MusicTrackStat>()
        (obj["tracks"] as? JsonArray).orEmpty().take(2_000).forEach { el ->
            val t = el as? JsonObject ?: return@forEach
            val id = t.string("id")?.takeIf(String::isNotBlank) ?: return@forEach
            tracks[id] = MusicTrackStat(
                trackId = id,
                title = t.string("title").orEmpty().ifBlank { id },
                artist = t.string("artist").orEmpty(),
                artistId = t.string("artistId"),
                albumId = t.string("albumId"),
                albumName = t.string("albumName"),
                artworkUrl = cleanArtwork(t.string("artworkUrl")),
                durationMs = t.long("durationMs"),
                totalListeningMs = (t.long("totalListeningMs") ?: 0L).coerceAtLeast(0L),
                playCount = (t.int("playCount") ?: 0).coerceAtLeast(0),
                lastPlayedEpochMs = t.long("lastPlayedEpochMs") ?: 0L,
            )
        }
        val events = (obj["events"] as? JsonArray).orEmpty().take(4_000).mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val id = e.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            MusicStatisticsEvent(
                trackId = id,
                epochMs = e.long("epochMs") ?: return@mapNotNull null,
                listeningMs = (e.long("listeningMs") ?: 0L).coerceAtLeast(0L),
                countedPlay = e.bool("countedPlay") ?: false,
                dayOfWeek = (e.int("dayOfWeek") ?: 0).coerceIn(0, 6),
                hourOfDay = (e.int("hourOfDay") ?: 0).coerceIn(0, 23),
            )
        }
        return MusicStatisticsStoreData(tracks, events)
    }

    private fun writeStatistics(stats: MusicStatisticsStoreData): JsonElement = buildJsonObject {
        put("tracks", JsonArray(stats.tracks.values.take(2_000).map { t ->
            buildJsonObject {
                put("id", t.trackId)
                put("title", t.title)
                put("artist", t.artist)
                t.artistId?.let { put("artistId", it) }
                t.albumId?.let { put("albumId", it) }
                t.albumName?.let { put("albumName", it) }
                t.artworkUrl?.let { put("artworkUrl", it) }
                t.durationMs?.let { put("durationMs", it) }
                put("totalListeningMs", t.totalListeningMs)
                put("playCount", t.playCount)
                put("lastPlayedEpochMs", t.lastPlayedEpochMs)
            }
        }))
        put("events", JsonArray(stats.events.takeLast(4_000).map { e ->
            buildJsonObject {
                put("id", e.trackId)
                put("epochMs", e.epochMs)
                put("listeningMs", e.listeningMs)
                if (e.countedPlay) put("countedPlay", true)
                put("dayOfWeek", e.dayOfWeek)
                put("hourOfDay", e.hourOfDay)
            }
        }))
    }

    private fun writeTrack(track: MusicTrack): JsonElement = buildJsonObject {
        put("id", track.id)
        put("title", track.title)
        put("artist", track.artist)
        track.artworkUrl?.let { put("artworkUrl", it) }
        track.durationMs?.let { put("durationMs", it) }
        track.artistId?.let { put("artistId", it) }
        track.albumId?.let { put("albumId", it) }
        track.albumName?.let { put("albumName", it) }
        if (track.explicit) put("explicit", true)
        track.localFilePath?.let { put("localFilePath", it) }
    }

    private fun readTrack(element: JsonElement): MusicTrack? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id") ?: return null
        return cleanTrack(
            MusicTrack(
                id = id,
                title = obj.string("title") ?: id,
                artist = obj.string("artist").orEmpty(),
                artworkUrl = obj.string("artworkUrl"),
                durationMs = obj.long("durationMs"),
                artistId = obj.string("artistId"),
                albumId = obj.string("albumId"),
                albumName = obj.string("albumName"),
                explicit = obj.bool("explicit") ?: false,
                localFilePath = obj.string("localFilePath"),
            ),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.float(key: String): Float? =
        (get(key) as? JsonPrimitive)?.floatOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull
}
