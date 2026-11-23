package moe.rukamori.archivetune.shared

/** LRCLIB candidate metadata; duration is in seconds. */
data class LyricsVersion(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Double?,
    val isSynced: Boolean,
    val provider: LyricsProvider = LyricsProvider.LRCLIB,
    val wordTimed: Boolean = false,
    val preview: String = "",
    val lineCount: Int = 0,
    val characterCount: Int = 0,
)

enum class LyricsProvider(val label: String) { YOULY_PLUS("YouLyPlus"), LRCLIB("LrcLib") }
enum class LyricsSource { DEFAULT, LRCLIB_VERSION, YOULY_PLUS }
