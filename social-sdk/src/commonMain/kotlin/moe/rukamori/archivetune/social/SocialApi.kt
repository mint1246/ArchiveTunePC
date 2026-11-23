/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

const val DEFAULT_SOCIAL_BASE_URL: String = "https://archivetune-social.archivetune.workers.dev"

class SocialApiException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

interface SocialApi {
    val baseUrl: String

    suspend fun exchangeAuthCode(code: String, nonce: String): StoredSocialSession
    suspend fun me(token: String): SocialUser
    suspend fun friends(token: String): List<SocialUser>
    suspend fun presence(token: String): Map<String, SocialPresence>
    suspend fun requests(token: String): Pair<List<SocialFriendRequest>, List<SocialFriendRequest>>
    suspend fun search(token: String, query: String): List<SocialUser>
    suspend fun sendFriendRequest(token: String, userId: String)
    suspend fun acceptFriendRequest(token: String, userId: String)
    suspend fun declineFriendRequest(token: String, userId: String)
    suspend fun removeFriend(token: String, userId: String)
    suspend fun block(token: String, userId: String)
    suspend fun sendPartyInvite(token: String, userId: String, partyUrl: String)
    suspend fun chatMessages(token: String, userId: String): List<SocialChatMessage>
    suspend fun sendChatMessage(token: String, userId: String, message: SocialOutgoingMessage): SocialChatMessage
    suspend fun uploadStatistics(token: String, snapshot: SocialStatisticsSnapshot)
    suspend fun friendStatistics(token: String, userId: String): SocialStatisticsSnapshot
    suspend fun logout(token: String)

    fun discordLoginUrl(port: Int, nonce: String): String
    fun liveUrl(deviceId: String): String
}
