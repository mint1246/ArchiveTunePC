package moe.rukamori.archivetune.desktop.library

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopLibraryTest {
    @Test fun `scan includes supported files and ignores others`() = runTest {
        val root = Files.createTempDirectory("archivetune-library")
        Files.writeString(root.resolve("Artist - Track.mp3"), "audio")
        Files.writeString(root.resolve("notes.txt"), "not audio")
        val library = DesktopLibrary(root.resolve("state.properties"))
        library.setFolders(listOf(root))
        val tracks = library.rescan()
        assertEquals(1, tracks.size); assertEquals("Artist - Track", tracks.single().title)
    }

    @Test fun `folder selection persists`() {
        val root = Files.createTempDirectory("archivetune-library")
        val state = root.resolve("config/state.properties")
        DesktopLibrary(state).setFolders(listOf(root))
        assertEquals(listOf(root.toAbsolutePath().normalize()), DesktopLibrary(state).folders)
    }

    @Test fun `injected folder store owns folder persistence`() {
        val root = Files.createTempDirectory("archivetune-library")
        var saved = emptyList<java.nio.file.Path>()
        val store = object : DesktopLibraryFolderStore {
            override fun load() = saved
            override fun save(folders: List<java.nio.file.Path>) { saved = folders }
        }

        DesktopLibrary(root.resolve("unused.properties"), folderStore = store).setFolders(listOf(root))

        assertEquals(listOf(root.toAbsolutePath().normalize()), saved)
        assertTrue(Files.notExists(root.resolve("unused.properties")))
    }

    @Test fun `rescan keeps identity for edits and removes deleted files`() = runTest {
        val root = Files.createTempDirectory("archivetune-library")
        val song = root.resolve("Track.flac")
        Files.writeString(song, "first")
        val library = DesktopLibrary(root.resolve("state.properties"))
        library.setFolders(listOf(root))
        val initial = library.rescan().single()
        Files.writeString(song, "updated and larger")
        val changed = library.rescan().single()
        assertEquals(initial.id, changed.id); assertTrue(changed.sizeBytes > initial.sizeBytes)
        Files.delete(song)
        assertTrue(library.rescan().isEmpty())
    }

    @Test fun `scanner maps supplied metadata and falls back for malformed tags`() = runTest {
        val root = Files.createTempDirectory("archivetune-library")
        val tagged = root.resolve("tagged.ogg"); val broken = root.resolve("broken.mp3")
        Files.writeString(tagged, "audio"); Files.writeString(broken, "bad")
        val reader = AudioMetadataReader { file -> if (file == tagged.toRealPath()) LocalMetadata("Song", "Artist", "Album", "Album Artist", 2, 1, 123_000, byteArrayOf(1)) else error("bad tag") }
        val library = DesktopLibrary(root.resolve("state.properties"), metadataReader = reader)
        library.setFolders(listOf(root))
        val tracks = library.rescan()
        val song = tracks.single { it.title == "Song" }
        assertEquals("Artist", song.artist); assertEquals("Album", song.album); assertEquals(2, song.trackNumber)
        assertEquals(123_000, song.durationMs); assertEquals(1, song.artwork?.single())
        assertEquals("broken", tracks.single { it.path == broken.toRealPath() }.title)
    }

    @Test fun `persisted index is immediately reusable and unchanged files skip tag parsing`() = runTest {
        val root = Files.createTempDirectory("archivetune-library")
        val state = root.resolve("state.properties")
        val song = root.resolve("cached.flac")
        Files.writeString(song, "audio")
        var metadataReads = 0
        val reader = AudioMetadataReader {
            metadataReads++
            LocalMetadata("Cached Song", "Cached Artist", "Cached Album", durationMs = 42_000, artwork = byteArrayOf(1, 2, 3))
        }

        val first = DesktopLibrary(state, metadataReader = reader)
        first.setFolders(listOf(root))
        assertEquals("Cached Song", first.rescan().single().title)
        assertEquals(1, metadataReads)

        val restored = DesktopLibrary(state, metadataReader = reader)
        assertEquals("Cached Song", restored.tracks.single().title)
        assertEquals(null, restored.tracks.single().artwork)

        restored.rescan()
        assertEquals(1, metadataReads)
    }
}
