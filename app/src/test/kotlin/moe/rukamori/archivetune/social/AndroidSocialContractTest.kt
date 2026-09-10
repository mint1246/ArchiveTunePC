package moe.rukamori.archivetune.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSocialContractTest {
    @Test
    fun loginCallbackRequiresMatchingNonceAndDecodesCode() {
        val callback = parseSocialLoginCallbackTarget(
            "/archivetune-auth?nonce=abc123&code=hello%2Bworld%2Fok",
            "abc123",
        )

        assertEquals("hello+world/ok", callback.code)
        assertNull(callback.error)
        assertThrows(IllegalArgumentException::class.java) {
            parseSocialLoginCallbackTarget(
                "/archivetune-auth?nonce=wrong&code=hello",
                "abc123",
            )
        }
    }

    @Test
    fun chatMessageParserMatchesCloudPayloadShapes() {
        val message = parseSocialChatMessage(
            JSONObject(
                """
                {
                  "id": 42,
                  "senderId": "usr_a",
                  "recipientId": "usr_b",
                  "kind": "queue",
                  "payload": {
                    "title": "Road trip",
                    "currentIndex": 1,
                    "items": [
                      {"id":"one","title":"One","artists":[{"name":"A"}],"artworkUri":"https://example.com/1.jpg"},
                      {"id":"two","title":"Two","artists":[{"name":"B"}]}
                    ]
                  },
                  "createdAt": 1234
                }
                """.trimIndent(),
            ),
        )

        assertEquals("42", message.id)
        assertEquals(SocialChatMessageKind.QUEUE, message.kind)
        assertEquals("Road trip", message.queue?.title)
        assertEquals(1, message.queue?.currentIndex)
        assertEquals(listOf("one", "two"), message.queue?.songs?.map(SocialSharedSong::id))
        assertEquals("A", message.queue?.songs?.first()?.artist)
    }

    @Test
    fun queuePayloadIsBoundedToCloudLimitAndIndexIsValid() {
        val songs = (0 until 75).map { index -> SocialSharedSong("id-$index", "Song $index") }
        val (_, payload) = SocialOutgoingMessage.Queue(
            SocialSharedQueue(title = "lots", songs = songs, currentIndex = 74),
        ).toWirePayload()

        assertEquals(50, payload.getJSONArray("items").length())
        assertEquals(49, payload.getInt("currentIndex"))
    }

    @Test
    fun presenceUpdateUsesArchiveTuneCloudSchema() {
        val json = socialPresenceUpdateJson(
            SocialPresence(
                online = true,
                songId = "abc",
                title = "Title",
                artist = "Artist",
                artworkUrl = "https://example.com/a.jpg",
                partyJoinable = true,
            ),
        )

        assertEquals("presence.update", json.getString("type"))
        val presence = json.getJSONObject("presence")
        assertEquals("abc", presence.getString("songId"))
        assertEquals("Title", presence.getString("title"))
        assertEquals("Artist", presence.getString("artist"))
        assertTrue(presence.getBoolean("partyJoinable"))
        assertFalse(presence.has("online"))
    }

    @Test
    fun friendStatisticsParserKeepsDesktopRangeKeysAndMedia() {
        val snapshot = parseSocialStatisticsSnapshot(
            JSONObject(
                """
                {"updatedAt":99,"ranges":{"DAYS_7":{"summary":{"totalListeningMillis":123000,"totalPlays":4,"uniqueSongs":2},"topSongs":[{"id":"song","title":"Song","subtitle":"Artist","listeningMillis":90000,"playCount":3}],"topArtists":[],"topAlbums":[]}}}
                """.trimIndent(),
            ),
        )

        val range = snapshot.ranges.getValue("DAYS_7")
        assertEquals(123000L, range.summary.totalListeningMillis)
        assertEquals(4L, range.summary.totalPlays)
        assertEquals("song", range.topSongs.single().id)
        assertEquals("Artist", range.topSongs.single().subtitle)
    }
}
