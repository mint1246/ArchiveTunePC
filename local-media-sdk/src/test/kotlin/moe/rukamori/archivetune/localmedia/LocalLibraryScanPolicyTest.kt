/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmedia

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalLibraryScanPolicyTest {

    @Test
    fun `folder entry is trimmed backslash normalized slash collapsed and unrooted`() {
        assertEquals("Music/Albums", LocalLibraryScanPolicy.normalizeFolderEntry("  Music\\\\Albums\\  "))
        assertEquals("Music/Albums", LocalLibraryScanPolicy.normalizeFolderEntry("/Music//Albums/"))
        assertEquals("", LocalLibraryScanPolicy.normalizeFolderEntry("   "))
    }

    @Test
    fun `folder entries are case insensitively deduplicated keeping first display case and order`() {
        val result = LocalLibraryScanPolicy.deduplicateFolderEntries(
            listOf("  Music\\  ", "MUSIC", "Podcasts", "podcasts", "", "  /Books/  "),
        )
        assertEquals(linkedSetOf("Music", "Podcasts", "Books"), result)
    }

    @Test
    fun `relative path wins over absolute legacy data path`() {
        assertEquals(
            "music/albums",
            LocalLibraryScanPolicy.resolveNormalizedFolderPath(
                "Music\\Albums",
                "/storage/emulated/0/Other/song.mp3",
            ),
        )
    }

    @Test
    fun `absolute path falls back to parent folder and missing paths are null`() {
        assertEquals(
            "storage/emulated/0/music",
            LocalLibraryScanPolicy.resolveNormalizedFolderPath(null, "/storage/emulated/0/Music/song.mp3"),
        )
        assertNull(LocalLibraryScanPolicy.resolveNormalizedFolderPath(null, null))
        assertNull(LocalLibraryScanPolicy.resolveNormalizedFolderPath(null, "song.mp3"))
    }

    @Test
    fun `included folders match exact descendants ancestors and nested segments`() {
        val folders = setOf("music", "podcasts")
        assertTrue(LocalLibraryScanPolicy.matchesFolderEntry("music", folders))
        assertTrue(LocalLibraryScanPolicy.matchesFolderEntry("music/albums", folders))
        assertTrue(LocalLibraryScanPolicy.matchesFolderEntry("root/music", folders))
        assertTrue(LocalLibraryScanPolicy.matchesFolderEntry("root/music/albums", folders))
        assertFalse(LocalLibraryScanPolicy.matchesFolderEntry("musical", folders))
        assertFalse(LocalLibraryScanPolicy.matchesFolderEntry(null, folders))
        assertFalse(LocalLibraryScanPolicy.matchesFolderEntry("", folders))
    }

    @Test
    fun `empty filter lists keep and exclude nothing`() {
        assertTrue(LocalLibraryScanPolicy.shouldIncludeFolder("anywhere", emptySet()))
        assertFalse(LocalLibraryScanPolicy.shouldExcludeFolder("anywhere", emptySet()))
        assertTrue(LocalLibraryScanPolicy.shouldIncludeFolder("music/albums", setOf("music")))
        assertTrue(LocalLibraryScanPolicy.shouldExcludeFolder("music/albums", setOf("music")))
    }

    @Test
    fun `config sanitization clamps duration and reuses folder normalization`() {
        val config = LocalSongScanConfig(
            minimumDurationSeconds = -5,
            includedFolders = setOf(" Music ", "MUSIC", "Podcasts\\"),
            excludedFolders = setOf("//Temp//"),
        )
        assertEquals(0, config.sanitizedMinimumDurationSeconds)
        assertEquals(linkedSetOf("Music", "Podcasts"), config.sanitizedIncludedFolders)
        assertEquals(setOf("Temp"), config.sanitizedExcludedFolders)
        assertEquals("Music", LocalSongScanConfig.normalizeFolderEntry("\\Music\\"))
        assertEquals(setOf("A", "B"), LocalSongScanConfig.deduplicateFolderEntries(listOf("A", "a", "B")))
    }

    @Test
    fun `clean tag trims and treats unknown placeholder values as absent`() {
        assertEquals("Artist", LocalLibraryScanPolicy.cleanTag("  Artist  "))
        assertNull(LocalLibraryScanPolicy.cleanTag("<unknown>"))
        assertNull(LocalLibraryScanPolicy.cleanTag("UNKNOWN ARTIST"))
        assertNull(LocalLibraryScanPolicy.cleanTag("   "))
        assertNull(LocalLibraryScanPolicy.cleanTag(null))
    }

    @Test
    fun `split artists supports semicolon and nul separators and deduplicates by identity`() {
        assertEquals(
            listOf("AC/DC", "Ａ ｇｏｇｏ"),
            LocalLibraryScanPolicy.splitArtistNames("AC/DC; Ａ ｇｏｇｏ; ac/dc"),
        )
        assertEquals(emptyList(), LocalLibraryScanPolicy.splitArtistNames("<unknown>\u0000unknown"))
    }

    @Test
    fun `album artist falls back to track artists only when no album artist survives cleaning`() {
        val artists = listOf("A", "B")
        assertEquals(listOf("Album", "Artist"), LocalLibraryScanPolicy.albumArtistNames(artists, "Album;Artist"))
        assertEquals(artists, LocalLibraryScanPolicy.albumArtistNames(artists, "<unknown>"))
        assertEquals(artists, LocalLibraryScanPolicy.albumArtistNames(artists, null))
    }

    @Test
    fun `album key uses normalized title and sorted normalized album artists`() {
        val first = LocalLibraryScanPolicy.albumKey(" Album  A ", listOf("B", "A"), null)
        val second = LocalLibraryScanPolicy.albumKey("ALBUM a", listOf("A", "B"), null)
        assertEquals(first, second)
        assertEquals("album a|a\u0000b", first)

        val withAlbumArtist = LocalLibraryScanPolicy.albumKey("Album A", listOf("B"), "Z;A")
        assertEquals("album a|a\u0000z", withAlbumArtist)
    }

    @Test
    fun `same name compares normalized identity`() {
        assertTrue(LocalLibraryScanPolicy.sameName("  Ｂｅｙｏｎｃé!  ", "beyoncé!"))
        assertFalse(LocalLibraryScanPolicy.sameName("Artist A", "Artist B"))
    }

    @Test
    fun `stable hash is deterministic and prefix ready for local ids`() {
        val hash = LocalLibraryScanPolicy.stableHash("Album A|artist")
        assertEquals(32, hash.length)
        assertFalse(hash.contains("-"))
        assertEquals(hash, LocalLibraryScanPolicy.stableHash("Album A|artist"))

        val id = "LOCAL_ALBUM_${LocalLibraryScanPolicy.stableHash("Album A|artist")}"
        assertTrue(id.startsWith("LOCAL_ALBUM_"))
    }

    @Test
    fun `unchanged requires both prior records known date matching and size matching`() {
        val modified = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        assertTrue(LocalLibraryScanPolicy.isTrackUnchanged(modified, 100L, modified, 100L))
        assertFalse(LocalLibraryScanPolicy.isTrackUnchanged(modified, 100L, modified, 101L))
        assertFalse(LocalLibraryScanPolicy.isTrackUnchanged(modified, 100L, null, 100L))
        assertFalse(LocalLibraryScanPolicy.isTrackUnchanged(null, 100L, modified, 100L))
        assertFalse(LocalLibraryScanPolicy.isTrackUnchanged(modified, 100L, modified, null))
    }

    @Test
    fun `scan summary defaults metadata lookup failure to false`() {
        val summary = LocalSongScanSummary(scannedSongs = 3, removedSongs = 1)
        assertEquals(3, summary.scannedSongs)
        assertEquals(1, summary.removedSongs)
        assertFalse(summary.metadataLookupFailed)
    }
}
