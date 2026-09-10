/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.social

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import moe.rukamori.archivetune.constants.PlaylistSortType
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.social.AndroidSocialManager
import moe.rukamori.archivetune.social.SocialSharedQueue
import moe.rukamori.archivetune.social.SocialSharedPlaylist
import moe.rukamori.archivetune.social.SocialSharedSong
import moe.rukamori.archivetune.social.buildSocialSharedQueue
import moe.rukamori.archivetune.social.toPlaybackMediaMetadata
import moe.rukamori.archivetune.social.toSocialSharedSongOrNull

const val SocialFriendsRoute = "social/friends"
private const val SocialFriendIdArgument = "friendId"
const val SocialChatRoute = "social/chat/{$SocialFriendIdArgument}"
const val SocialFriendStatisticsRoute = "social/statistics/{$SocialFriendIdArgument}"

fun socialChatRoute(friendId: String): String = "social/chat/${Uri.encode(friendId)}"
fun socialFriendStatisticsRoute(friendId: String): String = "social/statistics/${Uri.encode(friendId)}"

fun NavGraphBuilder.androidSocialNavigation(
    navController: NavHostController,
    social: AndroidSocialManager,
    playerConnection: () -> PlayerConnection?,
    database: MusicDatabase,
) {
    composable(SocialFriendsRoute) {
        SocialFriendsDestination(
            navController = navController,
            social = social,
            playerConnection = playerConnection(),
            database = database,
        )
    }
    composable(
        route = SocialChatRoute,
        arguments = listOf(navArgument(SocialFriendIdArgument) { type = NavType.StringType }),
    ) { backStackEntry ->
        val friendId = backStackEntry.arguments?.getString(SocialFriendIdArgument).orEmpty()
        SocialChatDestination(
            navController = navController,
            social = social,
            friendId = friendId,
            playerConnection = playerConnection(),
            database = database,
        )
    }
    composable(
        route = SocialFriendStatisticsRoute,
        arguments = listOf(navArgument(SocialFriendIdArgument) { type = NavType.StringType }),
    ) { backStackEntry ->
        val friendId = backStackEntry.arguments?.getString(SocialFriendIdArgument).orEmpty()
        ArchiveTuneFriendStatisticsRoute(
            social = social,
            friendId = friendId,
            onBack = navController::navigateUp,
            onPlaySong = { media ->
                playerConnection()?.playSocialSong(
                    SocialSharedSong(
                        id = media.id,
                        title = media.title,
                        artist = media.subtitle,
                        artworkUrl = media.artworkUrl,
                    ),
                )
            },
            onOpenArtist = { media ->
                media.id.takeIf(String::isNotBlank)?.let { navController.navigate("artist/${Uri.encode(it)}") }
            },
            onOpenAlbum = { media ->
                media.id.takeIf(String::isNotBlank)?.let { navController.navigate("album/${Uri.encode(it)}") }
            },
        )
    }
}

@Composable
private fun SocialFriendsDestination(
    navController: NavHostController,
    social: AndroidSocialManager,
    playerConnection: PlayerConnection?,
    database: MusicDatabase,
) {
    val share = rememberSocialShareState(playerConnection, database)
    ArchiveTuneFriendsScreen(
        social = social,
        currentSong = share.song,
        currentQueue = share.queue,
        playlists = share.playlists,
        onOpenChat = { navController.navigate(socialChatRoute(it.id)) },
        onOpenFriendStatistics = { navController.navigate(socialFriendStatisticsRoute(it.id)) },
        onPlaySharedSong = { playerConnection?.playSocialSong(it) },
        onPlaySharedQueue = { playerConnection?.playSocialQueue(it) },
        onOpenSharedPlaylist = { playlist ->
            playlist.id.takeIf(String::isNotBlank)?.let { id ->
                navController.navigate("online_playlist/${Uri.encode(id)}")
            }
        },
    )
}

@Composable
private fun SocialChatDestination(
    navController: NavHostController,
    social: AndroidSocialManager,
    friendId: String,
    playerConnection: PlayerConnection?,
    database: MusicDatabase,
) {
    val share = rememberSocialShareState(playerConnection, database)
    ArchiveTuneChatRoute(
        social = social,
        friendId = friendId,
        currentSong = share.song,
        currentQueue = share.queue,
        playlists = share.playlists,
        onBack = navController::navigateUp,
        onPlaySharedSong = { playerConnection?.playSocialSong(it) },
        onPlaySharedQueue = { playerConnection?.playSocialQueue(it) },
        onOpenSharedPlaylist = { playlist ->
            playlist.id.takeIf(String::isNotBlank)?.let { id ->
                navController.navigate("online_playlist/${Uri.encode(id)}")
            }
        },
    )
}

private data class SocialShareState(
    val song: SocialSharedSong?,
    val queue: SocialSharedQueue?,
    val playlists: List<SocialSharedPlaylist>,
)

@Composable
private fun rememberSocialShareState(
    playerConnection: PlayerConnection?,
    database: MusicDatabase,
): SocialShareState {
    val playlists by database
        .playlists(PlaylistSortType.CREATE_DATE, descending = true)
        .collectAsState(initial = emptyList())
    val sharedPlaylists = remember(playlists) {
        playlists.mapNotNull { playlist ->
            val browseId = playlist.playlist.browseId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            SocialSharedPlaylist(
                id = browseId,
                title = playlist.playlist.name,
                subtitle = playlist.songCount.takeIf { it > 0 }?.let { "$it songs" },
                artworkUrl = playlist.playlist.thumbnailUrl ?: playlist.thumbnails.firstOrNull(),
            )
        }.take(30)
    }
    if (playerConnection == null) return SocialShareState(null, null, sharedPlaylists)
    val metadata by playerConnection.mediaMetadata.collectAsState()
    val queueWindows by playerConnection.queueWindows.collectAsState()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsState()
    val queueTitle by playerConnection.queueTitle.collectAsState()

    return remember(metadata, queueWindows, currentWindowIndex, queueTitle, sharedPlaylists) {
        SocialShareState(
            song = metadata?.toSocialSharedSongOrNull(),
            queue = buildSocialSharedQueue(
                title = queueTitle,
                metadata = queueWindows.map { it.mediaItem.metadata },
                currentIndex = currentWindowIndex,
            ),
            playlists = sharedPlaylists,
        )
    }
}

private fun PlayerConnection.playSocialSong(song: SocialSharedSong) {
    playQueue(
        ListQueue(
            title = "Shared song",
            items = listOf(song.toPlaybackMediaMetadata().toMediaItem()),
        ),
    )
}

private fun PlayerConnection.playSocialQueue(queue: SocialSharedQueue) {
    if (queue.songs.isEmpty()) return
    playQueue(
        ListQueue(
            title = queue.title,
            items = queue.songs.map { it.toPlaybackMediaMetadata().toMediaItem() },
            startIndex = queue.currentIndex.coerceIn(queue.songs.indices),
        ),
    )
}
