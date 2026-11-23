/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SocialSessionCoordinatorTest {
    private class FakeApi : SocialApi {
        override val baseUrl = "https://social.example.test"
        var loggedOutToken: String? = null

        override suspend fun exchangeAuthCode(code: String, nonce: String) = StoredSocialSession("token-$code", Long.MAX_VALUE)
        override suspend fun me(token: String) = SocialUser("u1", "d1", "mint", "Mint", null)
        override suspend fun friends(token: String) = listOf(SocialUser("u2", "d2", "pal", "Pal", null))
        override suspend fun presence(token: String) = emptyMap<String, SocialPresence>()
        override suspend fun requests(token: String) = emptyList<SocialFriendRequest>() to emptyList<SocialFriendRequest>()
        override suspend fun search(token: String, query: String) = emptyList<SocialUser>()
        override suspend fun sendFriendRequest(token: String, userId: String) = Unit
        override suspend fun acceptFriendRequest(token: String, userId: String) = Unit
        override suspend fun declineFriendRequest(token: String, userId: String) = Unit
        override suspend fun removeFriend(token: String, userId: String) = Unit
        override suspend fun block(token: String, userId: String) = Unit
        override suspend fun sendPartyInvite(token: String, userId: String, partyUrl: String) = Unit
        override suspend fun chatMessages(token: String, userId: String) = emptyList<SocialChatMessage>()
        override suspend fun sendChatMessage(token: String, userId: String, message: SocialOutgoingMessage) =
            throw UnsupportedOperationException()
        override suspend fun uploadStatistics(token: String, snapshot: SocialStatisticsSnapshot) = Unit
        override suspend fun friendStatistics(token: String, userId: String) = SocialStatisticsSnapshot(emptyMap())
        override suspend fun logout(token: String) { loggedOutToken = token }
        override fun discordLoginUrl(port: Int, nonce: String) = "$baseUrl/auth/discord/start?port=$port&nonce=$nonce"
        override fun liveUrl(deviceId: String) = "wss://social.example.test/v1/live?device=$deviceId"
    }

    @Test
    fun signInRefreshAndSignOutManageStateAndValidateQuickTunnelUrls() = runTest {
        val api = FakeApi()
        val coordinator = SocialSessionCoordinator(
            api = api,
            scope = this,
            authorize = { _, _ -> "auth-code" },
            nonceProvider = { "nonce-xyz" },
        )

        coordinator.signIn()
        advanceUntilIdle()
        val signedIn = coordinator.state.value.account as? SocialAccountState.SignedIn
        assertEquals("Mint", signedIn?.user?.name)
        assertEquals(1, coordinator.state.value.friends.size)

        coordinator.signOut()
        advanceUntilIdle()
        assertEquals(SocialAccountState.SignedOut, coordinator.state.value.account)
        assertEquals("token-auth-code", api.loggedOutToken)
        assertTrue(coordinator.state.value.friends.isEmpty())
        coordinator.close()

        assertEquals(
            "https://demo-room.trycloudflare.com/party?token=abcdefghijk123",
            canonicalizeQuickTunnelInviteUrl("wss://demo-room.trycloudflare.com/party?token=abcdefghijk123"),
        )
        assertNull(canonicalizeQuickTunnelInviteUrl("https://api.trycloudflare.com/party?token=abcdefghijk123"))
        assertNull(canonicalizeQuickTunnelInviteUrl("https://evil.example.com/party?token=abcdefghijk123"))
    }
}
