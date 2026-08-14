package moe.rukamori.archivetune.backend

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.lyrics.JvmLyricsClient
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchStatus
import moe.rukamori.archivetune.lyrics.LyricsRequest
import javax.xml.parsers.DocumentBuilderFactory

/** Live, redaction-safe provider health check. It never prints lyric text or provider URLs. */
fun main() = runBlocking {
    val request = LyricsRequest(
        title = System.getenv("ARCHIVETUNE_LYRICS_TITLE") ?: "Blinding Lights",
        artist = System.getenv("ARCHIVETUNE_LYRICS_ARTIST") ?: "The Weeknd",
        album = System.getenv("ARCHIVETUNE_LYRICS_ALBUM") ?: "After Hours",
        durationSeconds = System.getenv("ARCHIVETUNE_LYRICS_DURATION")?.toIntOrNull() ?: 200,
        videoId = System.getenv("ARCHIVETUNE_LYRICS_VIDEO_ID") ?: "4NRXx6U8ABQ",
    )
    val providerIds = System.getenv("ARCHIVETUNE_LYRICS_PROVIDERS")
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.takeIf(List<String>::isNotEmpty)
    val results = JvmLyricsClient().searchLyrics(request, providerIds)
    results.forEach { result ->
        val format = result.document?.format?.name ?: "none"
        val text = result.document?.text.orEmpty()
        val structure = if (format == "TTML") {
            val root = runCatching {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream()).documentElement.nodeName
            }.getOrDefault("unparseable")
            " root=$root p=${Regex("<p(?:\\s|>)", RegexOption.IGNORE_CASE).findAll(text).count()}" +
                " p_begin=${Regex("<p[^>]*\\sbegin=", RegexOption.IGNORE_CASE).findAll(text).count()}" +
                " span_begin=${Regex("<span[^>]*\\sbegin=", RegexOption.IGNORE_CASE).findAll(text).count()}"
        } else ""
        println("lyrics_provider id=${result.providerId} status=${result.status.name} format=$format$structure")
    }
    val unhealthy = results.filter { it.status == LyricsProviderSearchStatus.FAILED || it.status == LyricsProviderSearchStatus.TIMED_OUT }
    check(unhealthy.isEmpty()) { "Unhealthy lyrics providers: ${unhealthy.joinToString { it.providerId }}" }
}
