package moe.rukamori.archivetune.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSocialContractTest {
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

    @Test
    fun statisticsUploadJsonMatchesCloudRequiredShape() {
        val range =
            SocialStatisticsRangeSnapshot(
                summary = SocialStatisticsSummary(123_000L, 4L, 2, 1, 1),
                favoriteSong = SocialStatisticsMedia("song", "Song", "Artist", null, 90_000L, 3L),
                dailyListening = listOf(SocialStatisticsBucket("10 Sep", 123_000L)),
                listeningByDayOfWeek = listOf(SocialStatisticsBucket("Thu", 123_000L)),
                listeningByHour = listOf(SocialStatisticsBucket("18:00", 123_000L)),
                topSongs = listOf(SocialStatisticsMedia("song", "Song", "Artist", null, 90_000L, 3L)),
            )
        val snapshot = SocialStatisticsSnapshot(AndroidStatisticsRangeKeys.associateWith { range }, updatedAt = 99L)
        val json = socialStatisticsSnapshotJson(snapshot)

        assertEquals(99L, json.getLong("updatedAt"))
        val ranges = json.getJSONObject("ranges")
        assertEquals(AndroidStatisticsRangeKeys.toSet(), ranges.keys().asSequence().toSet())
        val sevenDays = ranges.getJSONObject("DAYS_7")
        assertEquals(123_000L, sevenDays.getJSONObject("summary").getLong("totalListeningMillis"))
        assertEquals("10 Sep", sevenDays.getJSONArray("dailyListening").getJSONObject(0).getString("label"))
        assertEquals(1, sevenDays.getJSONArray("listeningByDayOfWeek").length())
        assertEquals(1, sevenDays.getJSONArray("listeningByHour").length())
    }

    private companion object {
        val AndroidStatisticsRangeKeys = listOf("DAYS_7", "MONTH_1", "MONTHS_3", "MONTHS_6", "YEAR_1", "ALL_TIME")
    }
}
