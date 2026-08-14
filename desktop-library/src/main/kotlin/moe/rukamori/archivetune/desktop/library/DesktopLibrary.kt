package moe.rukamori.archivetune.desktop.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    val artwork: ByteArray? = null,
)

data class LocalMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long? = null,
    val artwork: ByteArray? = null,
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
        val artwork = tag.firstArtwork?.binaryData?.takeIf { it.size <= MAX_ARTWORK_BYTES }
        return LocalMetadata(
            title = tag.getFirst(FieldKey.TITLE).trim().ifBlank { null },
            artist = tag.getFirst(FieldKey.ARTIST).trim().ifBlank { null },
            album = tag.getFirst(FieldKey.ALBUM).trim().ifBlank { null },
            albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST).trim().ifBlank { null },
            trackNumber = tag.getFirst(FieldKey.TRACK).toPositiveIntOrNull(),
            discNumber = tag.getFirst(FieldKey.DISC_NO).toPositiveIntOrNull(),
            durationMs = audio.audioHeader.trackLength.toLong().takeIf { it > 0 }?.times(1_000),
            artwork = artwork,
        )
    }

    private fun String.toPositiveIntOrNull() = substringBefore('/').trim().toIntOrNull()?.takeIf { it > 0 }
    private const val MAX_ARTWORK_BYTES = 5 * 1024 * 1024
}

/** Filesystem-backed local library. The index is rebuilt transactionally on each explicit scan. */
class DesktopLibrary(
    private val storageFile: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val metadataReader: AudioMetadataReader = JaudiotaggerMetadataReader,
    private val folderStore: DesktopLibraryFolderStore = PropertiesDesktopLibraryFolderStore(storageFile),
) {
    private var indexed = emptyList<LocalTrack>()
    var folders: List<Path> = loadFolders(); private set
    val tracks: List<LocalTrack> get() = indexed

    fun setFolders(value: List<Path>) {
        folders = value.map { it.toAbsolutePath().normalize() }.distinct()
        folderStore.save(folders)
    }

    suspend fun rescan(): List<LocalTrack> = withContext(io) {
        val discovered = linkedMapOf<String, LocalTrack>()
        folders.filter(Files::isDirectory).forEach { root ->
            Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() && it.extension.lowercase() in AUDIO_EXTENSIONS }.forEach { file ->
                    runCatching { readTrack(file) }.getOrNull()?.let { discovered[it.id] = it }
                }
            }
        }
        indexed = discovered.values.sortedWith(compareBy<LocalTrack> { it.artist }.thenBy { it.title })
        indexed
    }

    private fun readTrack(file: Path): LocalTrack {
        val normalized = file.toRealPath()
        val modified = Files.getLastModifiedTime(normalized).toMillis()
        val size = Files.size(normalized)
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
            artwork = metadata?.artwork,
        )
    }

    private fun stableId(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(path.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun loadFolders(): List<Path> = folderStore.load().map { it.toAbsolutePath().normalize() }.distinct()

    private companion object { val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "aiff") }
}
