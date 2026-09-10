/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

internal data class SocialNotificationSpec(
    val idKey: String,
    val title: String,
    val text: String,
    val route: String,
)

/** Maps realtime social events to the same user-visible moments the desktop notification center surfaces. */
internal fun socialNotificationSpec(
    event: SocialRealtimeEvent,
    state: AndroidSocialState,
): SocialNotificationSpec? =
    when (event) {
        is SocialRealtimeEvent.FriendRequest ->
            SocialNotificationSpec(
                idKey = "request:${event.user.id}",
                title = "Friend request",
                text = "${event.user.name} sent you a friend request",
                route = SOCIAL_FRIENDS_ROUTE,
            )

        is SocialRealtimeEvent.FriendAccepted ->
            SocialNotificationSpec(
                idKey = "accepted:${event.user.id}",
                title = "Friend added",
                text = "${event.user.name} accepted your friend request",
                route = SOCIAL_FRIENDS_ROUTE,
            )

        is SocialRealtimeEvent.PartyInvite ->
            SocialNotificationSpec(
                idKey = "party:${event.invite.sender.id}:${event.invite.sentAt}",
                title = "Play Together invite",
                text = "${event.invite.sender.name} invited you to a Play Together session",
                route = SOCIAL_FRIENDS_ROUTE,
            )

        is SocialRealtimeEvent.ChatMessage -> {
            val ownId = (state.account as? SocialAccountState.SignedIn)?.user?.id
            if (ownId != null && event.message.senderId == ownId) return null
            val sender =
                state.friends.firstOrNull { it.id == event.message.senderId }
                    ?: state.incomingRequests.firstOrNull { it.user.id == event.message.senderId }?.user
                    ?: state.outgoingRequests.firstOrNull { it.user.id == event.message.senderId }?.user
            val name = sender?.name ?: "Friend"
            val preview =
                when (event.message.kind) {
                    SocialChatMessageKind.TEXT -> event.message.text?.trim().orEmpty().ifBlank { "Sent you a message" }
                    SocialChatMessageKind.SONG -> "Shared a song: ${event.message.song?.title.orEmpty().ifBlank { "song" }}"
                    SocialChatMessageKind.QUEUE -> "Shared a queue${event.message.queue?.title?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
                    SocialChatMessageKind.PLAYLIST -> "Shared a playlist: ${event.message.playlist?.title.orEmpty().ifBlank { "playlist" }}"
                }
            SocialNotificationSpec(
                idKey = "message:${event.message.id}",
                title = name,
                text = preview.take(MAX_NOTIFICATION_PREVIEW_CHARS),
                route = "social/chat/${event.message.senderId}",
            )
        }
    }

private const val SOCIAL_FRIENDS_ROUTE = "social/friends"
private const val MAX_NOTIFICATION_PREVIEW_CHARS = 180
