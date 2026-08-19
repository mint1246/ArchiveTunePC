/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.innertube.cache

import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.AlbumReleaseType
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import org.json.JSONArray
import org.json.JSONObject

/** Compact render/navigation representation used by persistent desktop content caches. */
internal fun ytItemToJson(item: YTItem): JSONObject? = when (item) {
    is SongItem -> JSONObject()
        .put("type", "song")
        .put("id", item.id)
        .put("title", item.title)
        .put("artists", artistsToJson(item.artists))
        .put("albumName", item.album?.name ?: JSONObject.NULL)
        .put("albumId", item.album?.id ?: JSONObject.NULL)
        .put("duration", item.duration ?: JSONObject.NULL)
        .put("thumbnail", item.thumbnail)
        .put("thumbnailWidth", item.thumbnailWidth ?: JSONObject.NULL)
        .put("thumbnailHeight", item.thumbnailHeight ?: JSONObject.NULL)
        .put("explicit", item.explicit)
        .put("feedbackToken", item.feedbackToken ?: JSONObject.NULL)
    is AlbumItem -> JSONObject()
        .put("type", "album")
        .put("browseId", item.browseId)
        .put("playlistId", item.playlistId)
        .put("title", item.title)
        .put("artists", artistsToJson(item.artists.orEmpty()))
        .put("year", item.year ?: JSONObject.NULL)
        .put("thumbnail", item.thumbnail)
        .put("thumbnailWidth", item.thumbnailWidth ?: JSONObject.NULL)
        .put("thumbnailHeight", item.thumbnailHeight ?: JSONObject.NULL)
        .put("explicit", item.explicit)
        .put("releaseType", item.releaseType.name)
        .put("feedbackToken", item.feedbackToken ?: JSONObject.NULL)
    is ArtistItem -> JSONObject()
        .put("type", "artist")
        .put("id", item.id)
        .put("title", item.title)
        .put("thumbnail", item.thumbnail ?: JSONObject.NULL)
        .put("channelId", item.channelId ?: JSONObject.NULL)
        .put("thumbnailWidth", item.thumbnailWidth ?: JSONObject.NULL)
        .put("thumbnailHeight", item.thumbnailHeight ?: JSONObject.NULL)
        .put("feedbackToken", item.feedbackToken ?: JSONObject.NULL)
    is PlaylistItem -> JSONObject()
        .put("type", "playlist")
        .put("id", item.id)
        .put("title", item.title)
        .put("authorName", item.author?.name ?: JSONObject.NULL)
        .put("authorId", item.author?.id ?: JSONObject.NULL)
        .put("songCountText", item.songCountText ?: JSONObject.NULL)
        .put("thumbnail", item.thumbnail ?: JSONObject.NULL)
        .put("thumbnailWidth", item.thumbnailWidth ?: JSONObject.NULL)
        .put("thumbnailHeight", item.thumbnailHeight ?: JSONObject.NULL)
        .put("editable", item.isEditable)
        .put("description", item.description ?: JSONObject.NULL)
        .put("feedbackToken", item.feedbackToken ?: JSONObject.NULL)
}

internal fun ytItemFromJson(json: JSONObject): YTItem? = when (json.optString("type")) {
    "song" -> {
        val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
        val title = json.optString("title").takeIf(String::isNotBlank) ?: return null
        SongItem(
            id = id,
            title = title,
            artists = json.optJSONArray("artists").jsonObjects().mapNotNull(::artistFromJson),
            album = json.optString("albumId").takeIf(String::isNotBlank)?.let { albumId ->
                Album(json.optString("albumName"), albumId)
            },
            duration = json.optInt("duration", -1).takeIf { it >= 0 },
            thumbnail = json.optString("thumbnail"),
            explicit = json.optBoolean("explicit"),
            thumbnailWidth = json.optInt("thumbnailWidth", -1).takeIf { it >= 0 },
            thumbnailHeight = json.optInt("thumbnailHeight", -1).takeIf { it >= 0 },
            feedbackToken = json.optString("feedbackToken").takeIf(String::isNotBlank),
        )
    }
    "album" -> {
        val browseId = json.optString("browseId").takeIf(String::isNotBlank) ?: return null
        val title = json.optString("title").takeIf(String::isNotBlank) ?: return null
        AlbumItem(
            browseId = browseId,
            playlistId = json.optString("playlistId"),
            title = title,
            artists = json.optJSONArray("artists").jsonObjects().mapNotNull(::artistFromJson),
            year = json.optInt("year", -1).takeIf { it >= 0 },
            thumbnail = json.optString("thumbnail"),
            explicit = json.optBoolean("explicit"),
            releaseType = runCatching { AlbumReleaseType.valueOf(json.optString("releaseType")) }.getOrDefault(AlbumReleaseType.ALBUM),
            thumbnailWidth = json.optInt("thumbnailWidth", -1).takeIf { it >= 0 },
            thumbnailHeight = json.optInt("thumbnailHeight", -1).takeIf { it >= 0 },
            feedbackToken = json.optString("feedbackToken").takeIf(String::isNotBlank),
        )
    }
    "artist" -> {
        val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
        val title = json.optString("title").takeIf(String::isNotBlank) ?: return null
        ArtistItem(
            id = id,
            title = title,
            thumbnail = json.optString("thumbnail").takeIf(String::isNotBlank),
            channelId = json.optString("channelId").takeIf(String::isNotBlank),
            shuffleEndpoint = null,
            radioEndpoint = null,
            thumbnailWidth = json.optInt("thumbnailWidth", -1).takeIf { it >= 0 },
            thumbnailHeight = json.optInt("thumbnailHeight", -1).takeIf { it >= 0 },
            feedbackToken = json.optString("feedbackToken").takeIf(String::isNotBlank),
        )
    }
    "playlist" -> {
        val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
        val title = json.optString("title").takeIf(String::isNotBlank) ?: return null
        PlaylistItem(
            id = id,
            title = title,
            author = json.optString("authorName").takeIf(String::isNotBlank)?.let { Artist(it, json.optString("authorId").takeIf(String::isNotBlank)) },
            songCountText = json.optString("songCountText").takeIf(String::isNotBlank),
            thumbnail = json.optString("thumbnail").takeIf(String::isNotBlank),
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
            isEditable = json.optBoolean("editable"),
            description = json.optString("description").takeIf(String::isNotBlank),
            thumbnailWidth = json.optInt("thumbnailWidth", -1).takeIf { it >= 0 },
            thumbnailHeight = json.optInt("thumbnailHeight", -1).takeIf { it >= 0 },
            feedbackToken = json.optString("feedbackToken").takeIf(String::isNotBlank),
        )
    }
    else -> null
}

internal fun JSONArray?.jsonObjects(): List<JSONObject> =
    if (this == null) emptyList() else buildList(length()) {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }

private fun artistsToJson(artists: List<Artist>): JSONArray = JSONArray().apply {
    artists.forEach { put(JSONObject().put("name", it.name).put("id", it.id ?: JSONObject.NULL)) }
}

private fun artistFromJson(json: JSONObject): Artist? =
    json.optString("name").takeIf(String::isNotBlank)?.let { Artist(it, json.optString("id").takeIf(String::isNotBlank)) }
