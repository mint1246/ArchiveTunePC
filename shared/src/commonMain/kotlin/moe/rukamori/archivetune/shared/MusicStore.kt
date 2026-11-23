package moe.rukamori.archivetune.shared

/**
 * Platform storage for one small, versioned JSON snapshot.
 *
 * Implementations must finish writing the complete value before returning from [write].
 * No account credentials, playback sources, cookies or request headers belong here.
 */
interface MusicStore {
    suspend fun read(): String?
    suspend fun write(json: String)
}
