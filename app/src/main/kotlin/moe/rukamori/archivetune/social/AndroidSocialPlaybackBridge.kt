/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import moe.rukamori.archivetune.models.MediaMetadata
import java.net.URI
import java.util.Locale

internal fun MediaMetadata.toSocialSharedSongOrNull(): SocialSharedSong? {
    if (!id.isShareableSocialMediaId()) return null
    return SocialSharedSong(
        id = id,
        title = title,
        artist = artists.joinToString { it.name }.takeIf(String::isNotBlank),
        artworkUrl = thumbnailUrl,
    )
}

internal fun SocialSharedSong.toPlaybackMediaMetadata(): MediaMetadata =
    MediaMetadata(
        id = id,
        title = title,
        artists = artist
            ?.takeIf(String::isNotBlank)
            ?.let { listOf(MediaMetadata.Artist(id = null, name = it)) }
            .orEmpty(),
        duration = -1,
        thumbnailUrl = artworkUrl,
    )

internal fun buildSocialSharedQueue(
    title: String?,
    metadata: List<MediaMetadata?>,
    currentIndex: Int,
): SocialSharedQueue? {
    val shareable = metadata.mapIndexedNotNull { sourceIndex, item ->
        item?.toSocialSharedSongOrNull()?.let { sourceIndex to it }
    }.take(50)
    if (shareable.isEmpty()) return null

    val sharedCurrentIndex = shareable.indexOfFirst { it.first == currentIndex }
        .takeIf { it >= 0 }
        ?: 0
    return SocialSharedQueue(
        title = title,
        songs = shareable.map { it.second },
        currentIndex = sharedCurrentIndex,
    )
}

internal fun socialPresenceForPlayback(
    metadata: MediaMetadata?,
    isPlaying: Boolean,
): SocialPresence {
    val song = if (isPlaying) metadata?.toSocialSharedSongOrNull() else null
    return if (song == null) {
        SocialPresence()
    } else {
        SocialPresence(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            artworkUrl = song.artworkUrl,
        )
    }
}

private fun String.isShareableSocialMediaId(): Boolean {
    val scheme = runCatching { URI(this).scheme?.lowercase(Locale.US) }.getOrNull()
    return scheme !in setOf("content", "file", "android.resource")
}
