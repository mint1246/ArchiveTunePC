package moe.rukamori.archivetune.desktop.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

data class LocalTrack(
    val id: String,
    val path: Path,
    val modifiedMillis: Long,
    val sizeBytes: Long,
    val title: String,
    val artist: String = "Unknown artist",
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long? = null,
)

data class LocalMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long? = null,
)

fun interface AudioMetadataReader { fun read(file: Path): LocalMetadata? }

/** Persistence boundary for the user-selected library roots. */
interface DesktopLibraryFolderStore {
    fun load(): List<Path>

    fun save(folders: List<Path>)
}

/** Legacy properties implementation retained for embedders that do not provide a shared settings store. */
class PropertiesDesktopLibraryFolderStore(
    private val storageFile: Path,
) : DesktopLibraryFolderStore {
    override fun load(): List<Path> = runCatching {
        if (!Files.exists(storageFile)) return@runCatching emptyList()
        Properties().also { Files.newInputStream(storageFile).use(it::load) }[FOLDERS_KEY]?.toString()
            ?.split('|')?.mapNotNull { it.takeIf(String::isNotBlank)?.let(Path::of) }.orEmpty()
    }.getOrDefault(emptyList())

    override fun save(folders: List<Path>) {
        Files.createDirectories(storageFile.parent)
        Properties().also { it[FOLDERS_KEY] = folders.joinToString("|") }.also { properties ->
            Files.newOutputStream(storageFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use {
                properties.store(it, "ArchiveTune desktop library")
            }
        }
    }

    private companion object {
        const val FOLDERS_KEY = "folders"
    }
}

object JaudiotaggerMetadataReader : AudioMetadataReader {
    override fun read(file: Path): LocalMetadata? {
        val audio = AudioFileIO.read(file.toFile())
        val tag = audio.tagOrCreateAndSetDefault
        return LocalMetadata(
            title = tag.getFirst(FieldKey.TITLE).trim().ifBlank { null },
            artist = tag.getFirst(FieldKey.ARTIST).trim().ifBlank { null },
            album = tag.getFirst(FieldKey.ALBUM).trim().ifBlank { null },
            albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST).trim().ifBlank { null },
            trackNumber = tag.getFirst(FieldKey.TRACK).toPositiveIntOrNull(),
            discNumber = tag.getFirst(FieldKey.DISC_NO).toPositiveIntOrNull(),
            durationMs = audio.audioHeader.trackLength.toLong().takeIf { it > 0 }?.times(1_000),
        )
    }

    private fun String.toPositiveIntOrNull() = substringBefore('/').trim().toIntOrNull()?.takeIf { it > 0 }
}

/** Filesystem-backed local library. The index is rebuilt transactionally on each explicit scan. */
class DesktopLibrary(
    private val storageFile: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val metadataReader: AudioMetadataReader = JaudiotaggerMetadataReader,
    private val folderStore: DesktopLibraryFolderStore = PropertiesDesktopLibraryFolderStore(storageFile),
) {
    private var indexed = loadIndex()
    private val mutableTracks = MutableStateFlow(indexed)
    var folders: List<Path> = loadFolders(); private set
    val tracks: List<LocalTrack> get() = indexed
    val tracksFlow: StateFlow<List<LocalTrack>> = mutableTracks.asStateFlow()

    fun setFolders(value: List<Path>) {
        folders = value.map { it.toAbsolutePath().normalize() }.distinct()
        folderStore.save(folders)
    }

    suspend fun rescan(): List<LocalTrack> = withContext(io) {
        val discovered = linkedMapOf<String, LocalTrack>()
        val previousByPath = indexed.associateBy { it.path.toAbsolutePath().normalize() }
        folders.filter(Files::isDirectory).forEach { root ->
            Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() && it.extension.lowercase() in AUDIO_EXTENSIONS }.forEach { file ->
                    runCatching {
                        val normalized = file.toRealPath()
                        val modified = Files.getLastModifiedTime(normalized).toMillis()
                        val size = Files.size(normalized)
                        previousByPath[normalized]
                            ?.takeIf { it.modifiedMillis == modified && it.sizeBytes == size }
                            ?: readTrack(normalized, modified, size)
                    }.getOrNull()?.let { discovered[it.id] = it }
                }
            }
        }
        indexed = discovered.values.sortedWith(compareBy<LocalTrack> { it.artist }.thenBy { it.title })
        mutableTracks.value = indexed
        persistIndex(indexed)
        indexed
    }

    private fun readTrack(normalized: Path, modified: Long, size: Long): LocalTrack {
        val metadata = runCatching { metadataReader.read(normalized) }.getOrNull()
        return LocalTrack(
            id = stableId(normalized), path = normalized, modifiedMillis = modified, sizeBytes = size,
            title = metadata?.title ?: normalized.name.substringBeforeLast('.').ifBlank { normalized.name },
            artist = metadata?.artist ?: "Unknown artist",
            album = metadata?.album,
            albumArtist = metadata?.albumArtist,
            trackNumber = metadata?.trackNumber,
            discNumber = metadata?.discNumber,
            durationMs = metadata?.durationMs,
        )
    }

    private fun loadIndex(): List<LocalTrack> = runCatching {
        if (!Files.isRegularFile(storageFile)) return@runCatching emptyList()
        val properties = Properties().also { Files.newInputStream(storageFile).use(it::load) }
        val count = properties.getProperty(INDEX_COUNT_KEY)?.toIntOrNull() ?: return@runCatching emptyList()
        buildList(count) {
            repeat(count) { index ->
                val prefix = "track.$index"
                val path = properties.getProperty("$prefix.path")?.takeIf(String::isNotBlank)?.let(Path::of) ?: return@repeat
                if (!Files.isRegularFile(path)) return@repeat
                val id = properties.getProperty("$prefix.id")?.takeIf(String::isNotBlank) ?: return@repeat
                val title = properties.getProperty("$prefix.title")?.takeIf(String::isNotBlank) ?: return@repeat
                add(
                    LocalTrack(
                        id = id,
                        path = path.toAbsolutePath().normalize(),
                        modifiedMillis = properties.getProperty("$prefix.modified")?.toLongOrNull() ?: 0L,
                        sizeBytes = properties.getProperty("$prefix.size")?.toLongOrNull() ?: 0L,
                        title = title,
                        artist = properties.getProperty("$prefix.artist").orEmpty().ifBlank { "Unknown artist" },
                        album = properties.getProperty("$prefix.album").orEmpty().ifBlank { null },
                        albumArtist = properties.getProperty("$prefix.albumArtist").orEmpty().ifBlank { null },
                        trackNumber = properties.getProperty("$prefix.trackNumber")?.toIntOrNull(),
                        discNumber = properties.getProperty("$prefix.discNumber")?.toIntOrNull(),
                        durationMs = properties.getProperty("$prefix.durationMs")?.toLongOrNull(),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun persistIndex(tracks: List<LocalTrack>) {
        Files.createDirectories(storageFile.parent)
        val properties =
            if (Files.isRegularFile(storageFile)) {
                runCatching { Properties().also { Files.newInputStream(storageFile).use(it::load) } }.getOrDefault(Properties())
            } else {
                Properties()
            }
        properties.keys.toList().map { it.toString() }.filter { it.startsWith("track.") || it == INDEX_COUNT_KEY }.forEach(properties::remove)
        properties[INDEX_COUNT_KEY] = tracks.size.toString()
        tracks.forEachIndexed { index, track ->
            val prefix = "track.$index"
            properties["$prefix.id"] = track.id
            properties["$prefix.path"] = track.path.toString()
            properties["$prefix.modified"] = track.modifiedMillis.toString()
            properties["$prefix.size"] = track.sizeBytes.toString()
            properties["$prefix.title"] = track.title
            properties["$prefix.artist"] = track.artist
            properties["$prefix.album"] = track.album.orEmpty()
            properties["$prefix.albumArtist"] = track.albumArtist.orEmpty()
            properties["$prefix.trackNumber"] = track.trackNumber?.toString().orEmpty()
            properties["$prefix.discNumber"] = track.discNumber?.toString().orEmpty()
            properties["$prefix.durationMs"] = track.durationMs?.toString().orEmpty()
        }
        val temporary = Files.createTempFile(storageFile.parent, ".library-index.", ".tmp")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, "ArchiveTune desktop local library index") }
            try {
                Files.move(temporary, storageFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, storageFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun stableId(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(path.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun loadFolders(): List<Path> = folderStore.load().map { it.toAbsolutePath().normalize() }.distinct()

    private companion object {
        const val INDEX_COUNT_KEY = "index.trackCount"
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "aiff")
    }
}
