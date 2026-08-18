/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.absolute

/** One persisted listen, mirroring the Android `event` table's song + timestamp + playTime. */
data class DesktopPlayEvent(
    val song: SongItem,
    val playedAtEpochMillis: Long,
    val playTimeMs: Long,
)

/** Account-scoped data the local Home sections are derived from. */
data class DesktopHomeSnapshot(
    val likedSongs: List<SongItem> = emptyList(),
    val librarySongs: List<SongItem> = emptyList(),
    val likedAlbums: List<AlbumItem> = emptyList(),
    val subscribedArtists: List<ArtistItem> = emptyList(),
    val playHistory: List<DesktopPlayEvent> = emptyList(),
    val speedDial: List<YTItem> = emptyList(),
)

/** Atomic, JSON-backed persistence for desktop Home personalization data. */
class DesktopHomeStore(private val file: Path) {
    private val normalized = file.absolute().normalize()
    private val lock = ReentrantLock()
    private val _snapshot = MutableStateFlow(load())

    val snapshot: StateFlow<DesktopHomeSnapshot> = _snapshot.asStateFlow()

    fun replaceLikedSongs(songs: List<SongItem>) = update { it.copy(likedSongs = songs) }
    fun replaceLibrarySongs(songs: List<SongItem>) = update { it.copy(librarySongs = songs) }
    fun replaceLikedAlbums(albums: List<AlbumItem>) = update { it.copy(likedAlbums = albums) }
    fun replaceSubscribedArtists(artists: List<ArtistItem>) = update { it.copy(subscribedArtists = artists) }

    fun recordPlay(event: DesktopPlayEvent) = update { current ->
        val merged =
            (current.playHistory.filterNot { it.song.id == event.song.id && it.playedAtEpochMillis == event.playedAtEpochMillis } + event)
                .sortedByDescending { it.playedAtEpochMillis }
                .take(MAX_PLAY_EVENTS)
        current.copy(playHistory = merged)
    }

    fun toggleSpeedDial(item: YTItem) = update { current ->
        val pinned = current.speedDial.any { it.id == item.id && it::class == item::class }
        current.copy(
            speedDial =
                if (pinned) {
                    current.speedDial.filterNot { it.id == item.id && it::class == item::class }
                } else {
                    (current.speedDial + item).take(MAX_SPEED_DIAL)
                },
        )
    }

    fun isSpeedDialPinned(item: YTItem): Boolean =
        _snapshot.value.speedDial.any { it.id == item.id && it::class == item::class }

    fun clearAccountData() = update {
        it.copy(
            likedSongs = emptyList(),
            librarySongs = emptyList(),
            likedAlbums = emptyList(),
            subscribedArtists = emptyList(),
        )
    }

    private fun update(transform: (DesktopHomeSnapshot) -> DesktopHomeSnapshot) = lock.withLock {
        val next = transform(_snapshot.value)
        _snapshot.value = next
        write(next)
    }

    private fun load(): DesktopHomeSnapshot = lock.withLock {
        if (!Files.isRegularFile(normalized)) return@withLock DesktopHomeSnapshot()
        runCatching { read(normalized) }.getOrDefault(DesktopHomeSnapshot())
    }

    private fun write(snapshot: DesktopHomeSnapshot) {
        val parent = checkNotNull(normalized.parent) { "Home store file must have a parent directory" }
        Files.createDirectories(parent)
        val json =
            JSONObject()
                .put("likedSongs", JSONArray().apply { snapshot.likedSongs.forEach { put(songToJson(it)) } })
                .put("librarySongs", JSONArray().apply { snapshot.librarySongs.forEach { put(songToJson(it)) } })
                .put("likedAlbums", JSONArray().apply { snapshot.likedAlbums.forEach { put(albumToJson(it)) } })
                .put("subscribedArtists", JSONArray().apply { snapshot.subscribedArtists.forEach { put(artistToJson(it)) } })
                .put("speedDial", JSONArray().apply { snapshot.speedDial.forEach { put(itemToJson(it)) } })
                .put(
                    "playHistory",
                    JSONArray().apply {
                        snapshot.playHistory.forEach { event ->
                            put(
                                JSONObject()
                                    .put("song", songToJson(event.song))
                                    .put("playedAt", event.playedAtEpochMillis)
                                    .put("playTimeMs", event.playTimeMs),
                            )
                        }
                    },
                )
        val temporary = Files.createTempFile(parent, ".${normalized.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, json.toString())
            try {
                Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun read(file: Path): DesktopHomeSnapshot {
        val json = JSONObject(Files.readString(file))
        fun songs(key: String): List<SongItem> =
            json.optJSONArray(key).orEmptyObjects().mapNotNull(::songFromJson)
        return DesktopHomeSnapshot(
            likedSongs = songs("likedSongs"),
            librarySongs = songs("librarySongs"),
            likedAlbums = json.optJSONArray("likedAlbums").orEmptyObjects().mapNotNull(::albumFromJson),
            subscribedArtists = json.optJSONArray("subscribedArtists").orEmptyObjects().mapNotNull(::artistFromJson),
            speedDial = json.optJSONArray("speedDial").orEmptyObjects().mapNotNull(::itemFromJson),
            playHistory =
                json.optJSONArray("playHistory").orEmptyObjects().mapNotNull { event ->
                    val song = event.optJSONObject("song")?.let(::songFromJson) ?: return@mapNotNull null
                    DesktopPlayEvent(
                        song = song,
                        playedAtEpochMillis = event.optLong("playedAt", 0L),
                        playTimeMs = event.optLong("playTimeMs", 0L),
                    )
                },
        )
    }

    private companion object {
        const val MAX_PLAY_EVENTS = 2_000
        const val MAX_SPEED_DIAL = 24
    }
}

private fun JSONArray.orEmptyObjects(): List<JSONObject> =
    buildList(length()) { for (i in 0 until length()) optJSONObject(i)?.let(::add) }

private fun songToJson(song: SongItem): JSONObject =
    JSONObject()
        .put("id", song.id)
        .put("title", song.title)
        .put("artists", JSONArray().apply { song.artists.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        .put("album", song.album?.let { JSONObject().put("id", it.id).put("name", it.name) } ?: JSONObject.NULL)
        .put("duration", song.duration ?: JSONObject.NULL)
        .put("thumbnail", song.thumbnail)
        .put("explicit", song.explicit)

private fun songFromJson(json: JSONObject): SongItem? {
    val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
    val title = json.optString("title").takeIf(String::isNotBlank) ?: return null
    return SongItem(
        id = id,
        title = title,
        artists = json.optJSONArray("artists").orEmptyObjects().map {
            Artist(id = it.optString("id").takeIf(String::isNotBlank), name = it.optString("name"))
        },
        album = json.optJSONObject("album")?.takeUnless { it === JSONObject.NULL }?.let {
            Album(name = it.optString("name"), id = it.optString("id"))
        },
        duration = json.opt("duration")?.takeUnless { it === JSONObject.NULL }?.let { json.optInt("duration", -1).takeIf { v -> v >= 0 } },
        thumbnail = json.optString("thumbnail"),
        explicit = json.optBoolean("explicit"),
    )
}

private fun albumToJson(album: AlbumItem): JSONObject =
    JSONObject()
        .put("browseId", album.browseId)
        .put("playlistId", album.playlistId)
        .put("title", album.title)
        .put("artists", JSONArray().apply { album.artists.orEmpty().forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        .put("year", album.year ?: JSONObject.NULL)
        .put("thumbnail", album.thumbnail)
        .put("explicit", album.explicit)

private fun albumFromJson(json: JSONObject): AlbumItem? {
    val browseId = json.optString("browseId").takeIf(String::isNotBlank) ?: return null
    val title = json.optString("title").takeIf(String::isNotBlank) ?: return null
    return AlbumItem(
        browseId = browseId,
        playlistId = json.optString("playlistId"),
        title = title,
        artists = json.optJSONArray("artists").orEmptyObjects().map {
            Artist(id = it.optString("id").takeIf(String::isNotBlank), name = it.optString("name"))
        },
        year = json.opt("year")?.takeUnless { it === JSONObject.NULL }?.let { json.optInt("year", -1).takeIf { v -> v >= 0 } },
        thumbnail = json.optString("thumbnail"),
        explicit = json.optBoolean("explicit"),
    )
}

private fun artistToJson(artist: ArtistItem): JSONObject =
    JSONObject()
        .put("id", artist.id)
        .put("title", artist.title)
        .put("thumbnail", artist.thumbnail ?: JSONObject.NULL)
        .put("channelId", artist.channelId ?: JSONObject.NULL)

private fun artistFromJson(json: JSONObject): ArtistItem? {
    val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
    val title = json.optString("title").takeIf(String::isNotBlank) ?: return null
    return ArtistItem(
        id = id,
        title = title,
        thumbnail = json.optString("thumbnail").takeIf(String::isNotBlank),
        channelId = json.optString("channelId").takeIf(String::isNotBlank),
        shuffleEndpoint = null,
        radioEndpoint = null,
    )
}

private fun itemToJson(item: YTItem): JSONObject =
    JSONObject()
        .put("type", item::class.simpleName)
        .put(
            "value",
            when (item) {
                is SongItem -> songToJson(item)
                is AlbumItem -> albumToJson(item)
                is ArtistItem -> artistToJson(item)
                is PlaylistItem -> playlistToJson(item)
            },
        )

private fun itemFromJson(json: JSONObject): YTItem? =
    json.optJSONObject("value")?.let { value ->
        when (json.optString("type")) {
            "SongItem" -> songFromJson(value)
            "AlbumItem" -> albumFromJson(value)
            "ArtistItem" -> artistFromJson(value)
            "PlaylistItem" -> playlistFromJson(value)
            else -> null
        }
    }

private fun playlistToJson(playlist: PlaylistItem): JSONObject =
    JSONObject()
        .put("id", playlist.id)
        .put("title", playlist.title)
        .put("author", playlist.author?.let { JSONObject().put("id", it.id).put("name", it.name) } ?: JSONObject.NULL)
        .put("songCountText", playlist.songCountText ?: JSONObject.NULL)
        .put("thumbnail", playlist.thumbnail ?: JSONObject.NULL)

private fun playlistFromJson(json: JSONObject): PlaylistItem? {
    val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
    val title = json.optString("title").takeIf(String::isNotBlank) ?: return null
    return PlaylistItem(
        id = id,
        title = title,
        author = json.optJSONObject("author")?.takeUnless { it === JSONObject.NULL }?.let {
            Artist(id = it.optString("id").takeIf(String::isNotBlank), name = it.optString("name"))
        },
        songCountText = json.optString("songCountText").takeIf(String::isNotBlank),
        thumbnail = json.optString("thumbnail").takeIf(String::isNotBlank),
        playEndpoint = null,
        shuffleEndpoint = null,
        radioEndpoint = null,
    )
}
