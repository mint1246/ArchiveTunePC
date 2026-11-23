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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class MusicThemeChoice { SYSTEM, LIGHT, DARK }

data class MusicSettings(val theme: MusicThemeChoice = MusicThemeChoice.SYSTEM)

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
    override val isLoaded: Boolean = false,
    override val persistenceError: Boolean = false,
    // Retain user intent while disk read is pending. Replay against the restored favorites,
    // rather than incorrectly treating an initially empty list as the persisted truth.
    val pendingFavoriteToggles: List<MusicTrack> = emptyList(),
    val queueEditedBeforeLoad: Boolean = false,
    val themeEditedBeforeLoad: Boolean = false,
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
                settings = if (current.themeEditedBeforeLoad) current.settings else restored.settings,
                isLoaded = true,
                persistenceError = readFailed,
                pendingFavoriteToggles = emptyList(),
                queueEditedBeforeLoad = false,
                themeEditedBeforeLoad = false,
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
        if (closing.value) return
        val safe = MusicLibraryJson.cleanTrack(track) ?: return
        if (state.value.history.firstOrNull()?.id == safe.id) return
        mutableState.update { previous -> previous.copy(
            history = (listOf(safe) + previous.history).distinctBy { it.id }.take(200),
            revision = previous.revision + 1,
        ) }
        writeSignals.trySend(Unit)
    }

    fun recordSearch(query: String) {
        val clean = query.trim().take(200).takeIf { it.isNotEmpty() } ?: return
        editSearchHistory { (listOf(clean) + it).distinct().take(100) }
    }

    fun removeSearch(query: String) { editSearchHistory { it.filterNot { value -> value == query } } }

    private fun editSearchHistory(edit: (List<String>) -> List<String>) {
        if (closing.value) return
        mutableState.update { previous -> previous.copy(
            searchHistory = edit(previous.searchHistory),
            pendingSearchEdits = if (previous.isLoaded) emptyList() else previous.pendingSearchEdits + edit,
            revision = previous.revision + 1,
        ) }
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
        mutableState.update { previous -> previous.copy(
            playlists = edit(previous.playlists),
            pendingPlaylistEdits = if (previous.isLoaded) emptyList() else previous.pendingPlaylistEdits + edit,
            revision = previous.revision + 1,
        ) }
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

    fun setTheme(theme: MusicThemeChoice) {
        if (closing.value) return
        mutableState.update { previous ->
            if (previous.isLoaded && previous.settings.theme == theme) previous
            else previous.copy(
                settings = MusicSettings(theme),
                themeEditedBeforeLoad = !previous.isLoaded,
                revision = previous.revision + 1,
            )
        }
        writeSignals.trySend(Unit)
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

    fun decode(raw: String): RestoredLibrary {
        if (raw.length > MAX_JSON_LENGTH) return RestoredLibrary()
        return try {
            val root = Json.parseToJsonElement(raw) as? JsonObject ?: return RestoredLibrary()
            val version = root.int("version") ?: 0
            if (version !in 0..VERSION) return RestoredLibrary()

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
            val settings = root["settings"] as? JsonObject
            val choice = settings?.string("theme") ?: root.string("theme")
            val theme = MusicThemeChoice.entries.firstOrNull {
                it.name.equals(choice, ignoreCase = true)
            } ?: MusicThemeChoice.SYSTEM
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
            RestoredLibrary(playlists, favorites, queue, index, MusicSettings(theme),
                history = (root["history"] as? JsonArray).orEmpty().mapNotNull(::readTrack).distinctBy { it.id }.take(200),
                searchHistory = (root["searchHistory"] as? JsonArray).orEmpty().take(100)
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.take(200)?.takeIf(String::isNotEmpty) }.distinct())
        } catch (_: Exception) {
            RestoredLibrary()
        }
    }

    fun encode(snapshot: MusicLibraryState): String {
        val json = buildJsonObject {
            put("version", VERSION)
            put("favorites", JsonArray(snapshot.favorites.take(MAX_FAVORITES).map(::writeTrack)))
            put("history", JsonArray(snapshot.history.take(200).map(::writeTrack)))
            put("searchHistory", JsonArray(snapshot.searchHistory.take(100).map(::JsonPrimitive)))
            put("playlists", JsonArray(snapshot.playlists.take(200).map { playlist -> buildJsonObject {
                put("id", playlist.id)
                put("name", playlist.name)
                put("tracks", JsonArray(playlist.tracks.take(2000).map(::writeTrack)))
            } }))
            put("queue", buildJsonObject {
                put("tracks", JsonArray(snapshot.queue.take(MAX_QUEUE).map(::writeTrack)))
                put("index", snapshot.queueIndex)
            })
            put("settings", buildJsonObject {
                put("theme", snapshot.settings.theme.name.lowercase())
            })
        }
        return json.toString()
    }

    private fun writeTrack(track: MusicTrack): JsonElement = buildJsonObject {
        put("id", track.id)
        put("title", track.title)
        put("artist", track.artist)
        track.artworkUrl?.let { put("artworkUrl", it) }
        track.durationMs?.let { put("durationMs", it) }
    }

    private fun readTrack(element: JsonElement): MusicTrack? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id") ?: return null
        return cleanTrack(MusicTrack(
            id = id,
            title = obj.string("title") ?: id,
            artist = obj.string("artist").orEmpty(),
            artworkUrl = obj.string("artworkUrl"),
            durationMs = (obj["durationMs"] as? JsonPrimitive)?.longOrNull,
        ))
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull
}
