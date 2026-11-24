/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ParityCoreTests {
    @Test
    fun sha1MatchesRfc3174TestVectorsAndBuildsSapisidHash() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", KmpSha1.hex(""))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", KmpSha1.hex("abc"))
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            KmpSha1.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        val header = KmpSha1.sapisidHash(
            sapisid = "test_sapisid_value",
            origin = "https://music.youtube.com",
            timestampSeconds = 1_700_000_000L,
        )
        val expectedDigest = KmpSha1.hex("1700000000 test_sapisid_value https://music.youtube.com")
        assertEquals("SAPISIDHASH 1700000000_$expectedDigest", header)
    }

    @Test
    fun audibleStatisticsCountsOnlyMonotonicAudibleTimeAndEnforcesPlayThreshold() {
        val clock = TestTimeSource()
        val session = AudibleStatisticsSession(clock)
        assertEquals(30_000L, session.playCountThresholdMs(180_000L))
        assertEquals(10_000L, session.playCountThresholdMs(20_000L))
        assertEquals(4_000L, session.playCountThresholdMs(8_000L))
        assertEquals(30_000L, session.playCountThresholdMs(null))

        val shortTrack = MusicTrack(id = "short1", title = "Intro", artist = "Artist", durationMs = 20_000L)
        // Start audible playback
        assertNull(session.onSample(shortTrack, isAudiblePlaying = true, eligible = true))

        // 5 seconds audible -> listening accumulates, not yet a play (threshold = 10s)
        clock += 5.seconds
        val first = session.onSample(shortTrack, isAudiblePlaying = true, eligible = true)
        assertNotNull(first)
        assertEquals(5_000L, first.listeningMs)
        assertFalse(first.newlyCountedPlay)

        // Pause for 60 seconds -> paused time must NOT count
        val pauseFlush = session.onSample(shortTrack, isAudiblePlaying = false, eligible = true)
        assertNull(pauseFlush)
        clock += 60.seconds
        assertNull(session.onSample(shortTrack, isAudiblePlaying = false, eligible = true))

        // Resume and play 6 more seconds -> crosses 10s threshold -> counted play!
        assertNull(session.onSample(shortTrack, isAudiblePlaying = true, eligible = true))
        clock += 6.seconds
        val second = session.onSample(shortTrack, isAudiblePlaying = true, eligible = true)
        assertNotNull(second)
        assertEquals(6_000L, second.listeningMs)
        assertTrue(second.newlyCountedPlay)

        // Further playback in same play session does not double-count play
        clock += 5.seconds
        val third = session.onSample(shortTrack, isAudiblePlaying = true, eligible = true)
        assertNotNull(third)
        assertEquals(5_000L, third.listeningMs)
        assertFalse(third.newlyCountedPlay)

        // Disabled eligibility (pauseListenHistory) stops counting immediately
        clock += 2.seconds
        val eligibleStop = session.onSample(shortTrack, isAudiblePlaying = true, eligible = false)
        assertNotNull(eligibleStop)
        clock += 10.seconds
        assertNull(session.onSample(shortTrack, isAudiblePlaying = true, eligible = false))
    }

    @Test
    fun statisticsQueryFiltersRangesAndRanksTopItems() {
        val now = 1_700_000_000_000L
        val trackA = MusicTrackStat(trackId = "a", title = "Song A", artist = "Artist 1", albumId = "alb1", albumName = "Album 1", totalListeningMs = 90_000L, playCount = 2)
        val trackB = MusicTrackStat(trackId = "b", title = "Song B", artist = "Artist 2", totalListeningMs = 20_000L, playCount = 1)
        val store = MusicStatisticsStoreData(
            tracks = mapOf("a" to trackA, "b" to trackB),
            events = listOf(
                MusicStatisticsEvent("a", now - 2 * 86_400_000L, 45_000L, countedPlay = true, dayOfWeek = 1, hourOfDay = 14),
                MusicStatisticsEvent("a", now - 40 * 86_400_000L, 45_000L, countedPlay = true, dayOfWeek = 2, hourOfDay = 20),
                MusicStatisticsEvent("b", now - 1 * 86_400_000L, 20_000L, countedPlay = true, dayOfWeek = 3, hourOfDay = 9),
            ),
        )
        val week = MusicStatisticsQuery.overview(store, MusicStatisticsRange.DAYS_7, now, topLimit = 10)
        assertEquals(65_000L, week.totalListeningMs)
        assertEquals(2, week.totalPlays)
        assertEquals("a", week.topSongs.first().id)

        val all = MusicStatisticsQuery.overview(store, MusicStatisticsRange.ALL_TIME, now, topLimit = 10)
        assertEquals(110_000L, all.totalListeningMs)
        assertEquals(3, all.totalPlays)
        assertEquals(2, all.uniqueSongs)
        assertEquals(2, all.uniqueArtists)
    }

    @Test
    fun libraryPersistsExtendedSettingsCollectionsAndBackupRoundTrip() = runTest {
        var persisted: String? = null
        val store = object : MusicStore {
            override suspend fun read(): String? = persisted
            override suspend fun write(json: String) { persisted = json }
        }
        val lib = MusicLibrary(store, this)
        lib.awaitLoaded()
        val track = MusicTrack(
            id = "v123",
            title = "Midnight",
            artist = "Band",
            artistId = "UC123",
            albumId = "MPREb_123",
            albumName = "Night Album",
            explicit = true,
            durationMs = 210_000L,
        )
        lib.toggleFavorite(track)
        lib.updateSettings {
            it.copy(
                pureBlack = true,
                sliderStyle = MusicSliderStyle.WAVY,
                audioQuality = MusicAudioQuality.HIGH,
                lyricsTextSize = 30f,
                lyricsOffsetMs = -400L,
                topLength = 25,
                hideExplicit = true,
            )
        }
        lib.toggleSpeedDialPin(MusicSpeedDialPin("song", track.id, track.title, track.artist))
        lib.toggleSavedBrowse(MusicCatalogItem.Browse("MPREb_123", "Night Album", "Band", kind = MusicBrowseKind.ALBUM))
        lib.recordDownload(MusicDownloadedTrack(track, "file:///tmp/v123.m4a", 3_500_000L, 1_000L))
        val plId = lib.createPlaylist("Roadtrip")
        assertNotNull(plId)
        lib.addToPlaylist(plId, track)
        val tagId = lib.createPlaylistTag("Chill")
        assertNotNull(tagId)
        lib.togglePlaylistTag(tagId, plId)
        lib.markNewsRead("news-42")
        lib.flush()

        val backup = lib.exportBackupJson()
        lib.close()

        // Restore into a fresh library instance from persisted JSON
        val restored = MusicLibrary(store, this)
        restored.awaitLoaded()
        val s = restored.state.value
        assertEquals(1, s.favorites.size)
        assertEquals("UC123", s.favorites.first().artistId)
        assertEquals("MPREb_123", s.favorites.first().albumId)
        assertTrue(s.favorites.first().explicit)
        assertTrue(s.settings.pureBlack)
        assertEquals(MusicSliderStyle.WAVY, s.settings.sliderStyle)
        assertEquals(MusicAudioQuality.HIGH, s.settings.audioQuality)
        assertEquals(30f, s.settings.lyricsTextSize)
        assertEquals(-400L, s.settings.lyricsOffsetMs)
        assertEquals(25, s.settings.topLength)
        assertEquals(1, s.speedDialPins.size)
        assertEquals(1, s.savedAlbums.size)
        assertEquals(1, s.downloads.size)
        assertEquals(1, s.playlistTags.size)
        assertTrue("news-42" in s.readNewsIds)

        // Import backup into a blank library
        var backupSaved: String? = null
        val blankStore = object : MusicStore {
            override suspend fun read(): String? = backupSaved
            override suspend fun write(json: String) { backupSaved = json }
        }
        val imported = MusicLibrary(blankStore, this)
        imported.awaitLoaded()
        assertTrue(imported.importBackupJson(backup))
        assertEquals(1, imported.state.value.favorites.size)
        assertEquals("Roadtrip", imported.state.value.playlists.first().name)
        restored.close()
        imported.close()
    }

    @Test
    fun libraryBackwardCompatibleWithMinimalV1Json() = runTest {
        val v1Json = """
            {
              "version": 1,
              "favorites": [{"id":"abc","title":"Legacy Song","artist":"Legacy Artist"}],
              "settings": {"theme": "DARK"}
            }
        """.trimIndent()
        val store = object : MusicStore {
            override suspend fun read(): String = v1Json
            override suspend fun write(json: String) {}
        }
        val lib = MusicLibrary(store, this)
        lib.awaitLoaded()
        assertEquals(MusicThemeChoice.DARK, lib.state.value.settings.theme)
        assertEquals("Legacy Song", lib.state.value.favorites.single().title)
        assertEquals(MusicSliderStyle.STANDARD, lib.state.value.settings.sliderStyle)
        lib.close()
    }
}
