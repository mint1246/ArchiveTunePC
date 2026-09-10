package moe.rukamori.archivetune.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSocialNotificationsTest {
    private val own = SocialUser("self", "discord-self", "me", "Me", null)
    private val friend = SocialUser("friend-1", "discord-friend", "friend", "Alex", null)
    private val signedIn = AndroidSocialState(account = SocialAccountState.SignedIn(own), friends = listOf(friend))

    @Test
    fun `incoming message routes directly to sender chat`() {
        val message = SocialChatMessage(
            id = "m1",
            senderId = friend.id,
            receiverId = own.id,
            kind = SocialChatMessageKind.TEXT,
            text = "hello there",
            createdAt = 1L,
        )
        val spec = socialNotificationSpec(SocialRealtimeEvent.ChatMessage(message), signedIn)!!
        assertEquals("Alex", spec.title)
        assertEquals("hello there", spec.text)
        assertEquals("social/chat/friend-1", spec.route)
    }

    @Test
    fun `echoed own message does not notify`() {
        val message = SocialChatMessage(
            id = "m2",
            senderId = own.id,
            receiverId = friend.id,
            kind = SocialChatMessageKind.TEXT,
            text = "sent",
            createdAt = 2L,
        )
        assertNull(socialNotificationSpec(SocialRealtimeEvent.ChatMessage(message), signedIn))
    }

    @Test
    fun `party and friend events route to friends surface`() {
        val invite = SocialPartyInvite(friend, "https://room.trycloudflare.com/party?token=abcdef012345&sid=s", 3L)
        val party = socialNotificationSpec(SocialRealtimeEvent.PartyInvite(invite), signedIn)!!
        val request = socialNotificationSpec(SocialRealtimeEvent.FriendRequest(friend), signedIn)!!
        assertEquals("social/friends", party.route)
        assertEquals("social/friends", request.route)
        assertTrue(party.text.contains("Alex"))
    }
}
