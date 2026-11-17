package moe.rukamori.archivetune.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSocialManagerTest {
    @Test
    fun restoreLoadsFriendsAndRealtimeMessagesAreDeduplicated() = runBlocking {
        val own = SocialUser("self", "d-self", "self", "Me", null)
        val friend = SocialUser("friend", "d-friend", "friend", "Friend", null)
        val api = FakeSocialApi(own, friend)
        val store = FakeSessionStore(StoredSocialSession("ats_test", 10_000L))
        val realtime = FakeRealtime()
        val manager = newManager(api, store, realtime)

        manager.restore()

        assertEquals(own, (manager.state.value.account as SocialAccountState.SignedIn).user)
        assertEquals(listOf(friend), manager.state.value.friends)
        assertTrue(realtime.connected)

        val incoming = """
            {"type":"message","message":{
              "id":9,"senderId":"friend","recipientId":"self","kind":"text",
              "payload":{"text":"hey"},"createdAt":99
            }}
        """.trimIndent()
        realtime.emit(incoming)
        realtime.emit(incoming)

        assertEquals(listOf("9"), manager.state.value.chats[friend.id]?.map(SocialChatMessage::id))
        manager.close()
    }

    @Test
    fun sentMessageAndRealtimeEchoRemainOneChronologicalMessage() = runBlocking {
        val own = SocialUser("self", "d-self", "self", null, null)
        val friend = SocialUser("friend", "d-friend", "friend", null, null)
        val api = FakeSocialApi(own, friend)
        val store = FakeSessionStore(StoredSocialSession("ats_test", 10_000L))
        val realtime = FakeRealtime()
        val manager = newManager(api, store, realtime)
        manager.restore()

        val sent = manager.sendText(friend.id, " hello ").getOrThrow()
        assertEquals("hello", sent.text)
        assertEquals(1, manager.state.value.chats[friend.id]?.size)

        realtime.emit(
            JSONObject()
                .put("type", "message")
                .put("message", api.lastSentJson)
                .toString(),
        )
        assertEquals(1, manager.state.value.chats[friend.id]?.size)
        assertEquals(sent.id, manager.state.value.chats[friend.id]?.single()?.id)
        manager.close()
    }

    @Test
    fun partyInvitesOnlySendCanonicalCloudflareQuickTunnelUrls() = runBlocking {
        val own = SocialUser("self", "d-self", "self", "Me", null)
        val friend = SocialUser("friend", "d-friend", "friend", "Friend", null)
        val api = FakeSocialApi(own, friend)
        val store = FakeSessionStore(StoredSocialSession("ats_test", 10_000L))
        val manager = newManager(api, store, FakeRealtime())
        manager.restore()

        val invite =
            "https://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef&sid=session"
        assertTrue(manager.sendPartyInvite(friend.id, invite).isSuccess)
        assertEquals("https://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef", api.lastPartyInviteUrl)

        assertTrue(manager.sendPartyInvite(friend.id, "archivetune://together?code=legacy").isFailure)
        assertEquals("https://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef", api.lastPartyInviteUrl)
        manager.close()
    }

    private fun newManager(
        api: FakeSocialApi,
        store: FakeSessionStore,
        realtime: FakeRealtime,
    ): AndroidSocialManager = AndroidSocialManager(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        api = api,
        sessionStore = store,
        login = { error("Login is not used by this test") },
        realtime = realtime,
        nowMillis = { 1_000L },
        deviceId = "test-device",
    )

    private class FakeSessionStore(
        var stored: StoredSocialSession?,
    ) : SocialSessionStore {
        override suspend fun read(): StoredSocialSession? = stored
        override suspend fun write(session: StoredSocialSession) {
            stored = session
        }
        override suspend fun clear() {
            stored = null
        }
    }

    private class FakeRealtime : SocialRealtimeTransport {
        var connected = false
        private var onMessage: ((String) -> Unit)? = null

        override suspend fun connect(
            token: String,
            deviceId: String,
            onConnected: suspend () -> Unit,
            onMessage: (String) -> Unit,
        ) {
            connected = true
            this.onMessage = onMessage
            onConnected()
            awaitCancellation()
        }

        override suspend fun publishPresence(presence: SocialPresence) = Unit

        fun emit(value: String) {
            onMessage?.invoke(value)
        }

        override fun disconnect() {
            connected = false
        }

        override fun close() {
            disconnect()
        }
    }

    private class FakeSocialApi(
        private val own: SocialUser,
        private val friend: SocialUser,
    ) : SocialApi {
        override val baseUrl: String = "https://example.invalid"
        var lastSentJson: JSONObject? = null
        var lastPartyInviteUrl: String? = null
        private var nextMessageId = 1

        override suspend fun exchangeAuthCode(code: String, nonce: String) = StoredSocialSession("ats_test", 10_000L)
        override suspend fun me(token: String) = own
        override suspend fun friends(token: String) = listOf(friend)
        override suspend fun presence(token: String) = mapOf(friend.id to SocialPresence(online = true))
        override suspend fun requests(token: String) = emptyList<SocialFriendRequest>() to emptyList<SocialFriendRequest>()
        override suspend fun search(token: String, query: String) = listOf(friend)
        override suspend fun sendFriendRequest(token: String, userId: String) = Unit
        override suspend fun acceptFriendRequest(token: String, userId: String) = Unit
        override suspend fun declineFriendRequest(token: String, userId: String) = Unit
        override suspend fun removeFriend(token: String, userId: String) = Unit
        override suspend fun block(token: String, userId: String) = Unit
        override suspend fun sendPartyInvite(token: String, userId: String, partyUrl: String) {
            lastPartyInviteUrl = partyUrl
        }
        override suspend fun chatMessages(token: String, userId: String) = emptyList<SocialChatMessage>()
        override suspend fun uploadStatistics(token: String, snapshot: SocialStatisticsSnapshot) = Unit
        override suspend fun friendStatistics(token: String, userId: String) = SocialStatisticsSnapshot(emptyMap())

        override suspend fun sendChatMessage(
            token: String,
            userId: String,
            message: SocialOutgoingMessage,
        ): SocialChatMessage {
            val (kind, payload) = message.toWirePayload()
            val json = JSONObject()
                .put("id", nextMessageId++)
                .put("senderId", own.id)
                .put("recipientId", userId)
                .put("kind", kind)
                .put("payload", payload)
                .put("createdAt", 100L)
            lastSentJson = json
            return parseSocialChatMessage(json)
        }

        override suspend fun logout(token: String) = Unit
        override fun discordLoginUrl(port: Int, nonce: String) = "$baseUrl/login?port=$port&nonce=$nonce"
        override fun liveUrl(deviceId: String) = "wss://example.invalid/live?device=$deviceId"
    }
}
