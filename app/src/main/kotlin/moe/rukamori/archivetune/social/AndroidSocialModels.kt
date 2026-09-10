/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

data class SocialUser(
    val id: String,
    val discordId: String,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
) {
    val name: String get() = displayName?.takeIf(String::isNotBlank) ?: username
}

data class SocialFriendRequest(
    val user: SocialUser,
    val createdAt: Long,
)

data class SocialPresence(
    val online: Boolean = false,
    val songId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val artworkUrl: String? = null,
    val partyJoinable: Boolean = false,
)

data class SocialPartyInvite(
    val sender: SocialUser,
    val partyUrl: String,
    val sentAt: Long,
)

enum class SocialChatMessageKind {
    TEXT,
    SONG,
    QUEUE,
    PLAYLIST,
}

data class SocialSharedSong(
    val id: String,
    val title: String,
    val artist: String? = null,
    val artworkUrl: String? = null,
)

data class SocialSharedQueue(
    val title: String? = null,
    val songs: List<SocialSharedSong>,
    val currentIndex: Int = 0,
)

data class SocialSharedPlaylist(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
)

data class SocialChatMessage(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val kind: SocialChatMessageKind,
    val text: String? = null,
    val song: SocialSharedSong? = null,
    val queue: SocialSharedQueue? = null,
    val playlist: SocialSharedPlaylist? = null,
    val createdAt: Long,
)

data class SocialStatisticsSummary(
    val totalListeningMillis: Long = 0L,
    val totalPlays: Long = 0L,
    val uniqueSongs: Int = 0,
    val uniqueArtists: Int = 0,
    val uniqueAlbums: Int = 0,
)

data class SocialStatisticsMedia(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val listeningMillis: Long = 0L,
    val playCount: Long = 0L,
)

data class SocialStatisticsBucket(
    val label: String,
    val listeningMillis: Long,
)

data class SocialStatisticsRangeSnapshot(
    val summary: SocialStatisticsSummary = SocialStatisticsSummary(),
    val favoriteArtist: SocialStatisticsMedia? = null,
    val favoriteSong: SocialStatisticsMedia? = null,
    val dailyListening: List<SocialStatisticsBucket> = emptyList(),
    val listeningByDayOfWeek: List<SocialStatisticsBucket> = emptyList(),
    val listeningByHour: List<SocialStatisticsBucket> = emptyList(),
    val topSongs: List<SocialStatisticsMedia> = emptyList(),
    val topArtists: List<SocialStatisticsMedia> = emptyList(),
    val topAlbums: List<SocialStatisticsMedia> = emptyList(),
)

data class SocialStatisticsSnapshot(
    val ranges: Map<String, SocialStatisticsRangeSnapshot>,
    val updatedAt: Long = 0L,
)

sealed interface SocialOutgoingMessage {
    data class Text(val text: String) : SocialOutgoingMessage
    data class Song(val song: SocialSharedSong) : SocialOutgoingMessage
    data class Queue(val queue: SocialSharedQueue) : SocialOutgoingMessage
    data class Playlist(val playlist: SocialSharedPlaylist) : SocialOutgoingMessage
}

sealed interface SocialRealtimeEvent {
    data class FriendRequest(val user: SocialUser) : SocialRealtimeEvent
    data class FriendAccepted(val user: SocialUser) : SocialRealtimeEvent
    data class PartyInvite(val invite: SocialPartyInvite) : SocialRealtimeEvent
    data class ChatMessage(val message: SocialChatMessage) : SocialRealtimeEvent
}

sealed interface SocialAccountState {
    data object SignedOut : SocialAccountState
    data object SigningIn : SocialAccountState
    data class SignedIn(val user: SocialUser) : SocialAccountState
}

data class AndroidSocialState(
    val account: SocialAccountState = SocialAccountState.SignedOut,
    val friends: List<SocialUser> = emptyList(),
    val incomingRequests: List<SocialFriendRequest> = emptyList(),
    val outgoingRequests: List<SocialFriendRequest> = emptyList(),
    val presence: Map<String, SocialPresence> = emptyMap(),
    val partyInvites: List<SocialPartyInvite> = emptyList(),
    val chats: Map<String, List<SocialChatMessage>> = emptyMap(),
    val loadingChats: Set<String> = emptySet(),
    val friendStatistics: Map<String, SocialStatisticsSnapshot> = emptyMap(),
    val loadingFriendStatistics: Set<String> = emptySet(),
    val refreshing: Boolean = false,
    val error: String? = null,
)

data class StoredSocialSession(
    val token: String,
    val expiresAt: Long,
)
