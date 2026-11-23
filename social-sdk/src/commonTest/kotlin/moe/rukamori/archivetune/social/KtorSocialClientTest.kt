/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class KtorSocialClientTest {
    @Test
    fun exchangesCodeFetchesProfileAndFriendsAndParsesCallbacks() = runTest {
        val engine = MockEngine { request ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when (request.url.encodedPath) {
                "/v1/auth/exchange" -> respond("""{"token":"tok-123","expiresAt":9999999999}""", HttpStatusCode.OK, jsonHeaders)
                "/v1/me" -> respond("""{"user":{"id":"u1","discordId":"d1","username":"mint","displayName":"Mint"}}""", HttpStatusCode.OK, jsonHeaders)
                "/v1/friends" -> respond("""{"friends":[{"id":"u2","discordId":"d2","username":"friend","name":"Best Friend"}]}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"error":"Not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val client = HttpClient(engine)
        try {
            val api = KtorSocialClient(client, baseUrl = "https://social.example.test")
            val session = api.exchangeAuthCode("code-abc", "nonce-1")
            assertEquals("tok-123", session.token)
            val me = api.me(session.token)
            assertEquals("Mint", me.name)
            val friends = api.friends(session.token)
            assertEquals(1, friends.size)
            assertEquals("Best Friend", friends.single().name)
            assertFailsWith<SocialApiException> { api.logout(session.token) }
        } finally {
            client.close()
        }

        val code = parseLocalhostDiscordCallbackCode(
            targetUrl = "http://127.0.0.1:49152/archivetune-auth?code=abc123&nonce=n1",
            expectedNonce = "n1",
        )
        assertEquals("abc123", code)
        assertFailsWith<IllegalArgumentException> {
            parseLocalhostDiscordCallbackCode("http://127.0.0.1:49152/archivetune-auth?code=abc123&nonce=wrong", "n1")
        }
    }
}
