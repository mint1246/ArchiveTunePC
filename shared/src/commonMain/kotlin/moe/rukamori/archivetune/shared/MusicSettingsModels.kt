/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

enum class MusicPlayerDesignStyle(val label: String, val subtitle: String) {
    V4("ArchiveTune V4", "Default expressive layout with large artwork"),
    V1_CLASSIC("Classic", "Clean traditional player layout"),
    V2_MODERN("Modern", "Centered metadata and compact transport"),
    V3_MINIMAL("Minimal", "Focused artwork with streamlined controls"),
    V5_CINEMATIC("Cinematic", "Full-bleed visual emphasis"),
}

enum class MusicPlayerBackgroundStyle(val label: String) {
    DEFAULT("Dynamic artwork"),
    BLUR("Blurred artwork"),
    GRADIENT("Color gradient"),
    SOLID("Solid theme"),
}

enum class MusicMiniPlayerBackgroundStyle(val label: String) {
    THEME("Theme surface"),
    ARTWORK("Blurred artwork"),
    GRADIENT("Artwork gradient"),
}

enum class MusicLyricsBackgroundStyle(val label: String) {
    DEFAULT("Dynamic backdrop"),
    BLUR("Blurred artwork"),
    SOLID("Solid surface"),
}

enum class MusicLyricsAlignment(val label: String) {
    LEFT("Left"),
    CENTER("Center"),
    RIGHT("Right"),
}

enum class MusicGridItemSize(val label: String) {
    SMALL("Small"),
    BIG("Big"),
}

enum class MusicQuickPicksMode(val label: String) {
    CAROUSEL("Hero carousel"),
    GRID("Compact rows"),
    LAST_LISTEN("Last listened"),
    DONT_SHOW("Don't show"),
}

enum class MusicDefaultTab(val routeName: String, val label: String) {
    HOME("Home", "Home"),
    SEARCH("Search", "Search"),
    LIBRARY("Library", "Library"),
    FRIENDS("Friends", "Friends"),
}

data class MusicSpeedDialPin(
    val type: String, // "song", "album", "artist", "playlist"
    val id: String,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
    val params: String? = null,
)

data class MusicDownloadedTrack(
    val track: MusicTrack,
    val filePath: String,
    val sizeBytes: Long = 0L,
    val downloadedAtMs: Long = 0L,
)

data class MusicPlaylistTag(
    val id: String,
    val name: String,
    val playlistIds: List<String> = emptyList(),
)

data class MusicSettings(
    val theme: MusicThemeChoice = MusicThemeChoice.SYSTEM,
    // Appearance
    val pureBlack: Boolean = false,
    val dynamicTheme: Boolean = true,
    val playerDesignStyle: MusicPlayerDesignStyle = MusicPlayerDesignStyle.V4,
    val playerBackgroundStyle: MusicPlayerBackgroundStyle = MusicPlayerBackgroundStyle.DEFAULT,
    val miniPlayerBackgroundStyle: MusicMiniPlayerBackgroundStyle = MusicMiniPlayerBackgroundStyle.THEME,
    val lyricsBackgroundStyle: MusicLyricsBackgroundStyle = MusicLyricsBackgroundStyle.DEFAULT,
    val sliderStyle: MusicSliderStyle = MusicSliderStyle.STANDARD,
    val thumbnailCornerRadius: Float = 16f,
    val cropThumbnailToSquare: Boolean = false,
    val showPlayerVolumeBar: Boolean = false,
    val hidePlayerThumbnail: Boolean = false,
    val defaultOpenTab: MusicDefaultTab = MusicDefaultTab.HOME,
    val gridItemSize: MusicGridItemSize = MusicGridItemSize.SMALL,
    val showHomeCategoryChips: Boolean = true,
    val quickPicksMode: MusicQuickPicksMode = MusicQuickPicksMode.CAROUSEL,
    val disableBlur: Boolean = false,
    val disableAnimations: Boolean = false,
    val swipeThumbnail: Boolean = true,
    val swipeSensitivity: Float = 0.73f,
    val swipeToSong: Boolean = true,
    // Playback & Audio
    val audioQuality: MusicAudioQuality = MusicAudioQuality.AUTO,
    val playbackSpeed: Float = 1f,
    val playbackPitch: Float = 0f,
    val persistentQueue: Boolean = true,
    val permanentShuffle: Boolean = false,
    val autoSkipNextOnError: Boolean = false,
    val seekExtraSeconds: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val crossfadeDurationSec: Float = 5f,
    val crossfadeGapless: Boolean = true,
    val audioNormalization: Boolean = true,
    val skipSilence: Boolean = false,
    val autoDownloadOnLike: Boolean = false,
    val keepScreenAwake: Boolean = false,
    val sponsorBlockEnabled: Boolean = false,
    // Lyrics
    val lyricsClick: Boolean = true,
    val lyricsScroll: Boolean = true,
    val lyricsTextSize: Float = 26f,
    val lyricsLineSpacing: Float = 1.3f,
    val lyricsLineBlur: Boolean = true,
    val lyricsAlignment: MusicLyricsAlignment = MusicLyricsAlignment.CENTER,
    val lyricsOffsetMs: Long = 0L,
    val enableLrclib: Boolean = true,
    val enableYouLyPlus: Boolean = true,
    // Content
    val contentLanguage: String = "system",
    val contentCountry: String = "system",
    val hideExplicit: Boolean = false,
    val hideVideo: Boolean = false,
    val topLength: Int = 50,
    // Behavior & Privacy
    val pauseListenHistory: Boolean = false,
    val pauseSearchHistory: Boolean = false,
    val enableHapticFeedback: Boolean = true,
    val countPartyListening: Boolean = true,
    // Storage
    val maxImageCacheSizeMb: Int = 512,
    val maxSongCacheSizeMb: Int = 1024,
)
