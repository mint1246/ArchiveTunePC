package moe.rukamori.archivetune.desktop.lyrics

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.lyrics.JvmLyricsClient
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchStatus
import moe.rukamori.archivetune.lyrics.LyricsRequest

/** Opt-in live diagnostic. It prints structure only; lyric text and request URLs are never emitted. */
fun main() = runBlocking {
    val request = LyricsRequest(
        title = System.getenv("ARCHIVETUNE_LYRICS_TITLE") ?: "Lost In The World (feat. Bon Iver)",
        artist = System.getenv("ARCHIVETUNE_LYRICS_ARTIST") ?: "Kanye West",
        album = System.getenv("ARCHIVETUNE_LYRICS_ALBUM")?.takeIf(String::isNotBlank),
        durationSeconds = System.getenv("ARCHIVETUNE_LYRICS_DURATION")?.toIntOrNull() ?: -1,
        videoId = System.getenv("ARCHIVETUNE_LYRICS_VIDEO_ID")?.takeIf(String::isNotBlank),
    )
    val client = JvmLyricsClient()
    val selectedProviders = System.getenv("ARCHIVETUNE_LYRICS_PROVIDERS")
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.takeIf(List<String>::isNotEmpty)

    client.searchLyrics(request, selectedProviders).forEach { result ->
        val document = result.document
        val timeline = document?.let(LyricsTimelineParser::parse)
        println(
            listOf(
                "provider=${result.providerId}",
                "status=${result.status}",
                "format=${document?.format ?: "none"}",
                "lines=${timeline?.lines?.size ?: 0}",
                "renderable=${result.status == LyricsProviderSearchStatus.FOUND && !timeline?.lines.isNullOrEmpty()}",
            ).joinToString(" "),
        )
    }
}
