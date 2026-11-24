/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

data class MusicStorageUsage(
    val downloadsBytes: Long = 0L,
    val songCacheBytes: Long = 0L,
    val imageCacheBytes: Long = 0L,
)

/**
 * Optional native platform hooks (iOS UIKit/Foundation/Keychain/WebKit).
 * Defaults are safe no-ops so JVM unit tests run deterministically without OS dependencies.
 */
interface MusicPlatformBridge {
    val canShare: Boolean get() = false
    val canDownload: Boolean get() = false
    val canImportLocalFiles: Boolean get() = false
    val canSignInYouTube: Boolean get() = false
    val canBackupRestore: Boolean get() = false

    fun shareText(text: String) {}
    fun copyToClipboard(text: String) {}
    fun openUrl(url: String) {}
    fun setKeepScreenAwake(enabled: Boolean) {}

    suspend fun downloadAudio(
        trackId: String,
        source: ResolvedAudio,
        onProgress: (Float) -> Unit = {},
    ): Result<Pair<String, Long>> = Result.failure(UnsupportedOperationException("Downloads unavailable"))

    fun deleteLocalFile(fileUrl: String) {}
    fun fileExists(fileUrl: String): Boolean = false

    suspend fun importLocalAudioFiles(): List<MusicTrack> = emptyList()
    suspend fun importBackupJson(): String? = null

    suspend fun signInYouTube(): Result<MusicYouTubeAuth> =
        Result.failure(UnsupportedOperationException("YouTube sign-in unavailable"))

    fun loadYouTubeAuth(): MusicYouTubeAuth? = null
    fun saveYouTubeAuth(auth: MusicYouTubeAuth?) {}

    fun computeStorageUsage(): MusicStorageUsage = MusicStorageUsage()
    fun clearImageCache() {}
    fun clearAudioCache() {}
}

object NoOpPlatformBridge : MusicPlatformBridge
