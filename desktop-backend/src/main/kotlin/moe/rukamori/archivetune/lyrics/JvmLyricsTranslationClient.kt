/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

enum class LyricsTranslationProvider {
    GOOGLE,
    OPENAI_COMPATIBLE,
}

/** Configuration contains credentials only in memory; callers must keep apiKey in a secure store. */
data class LyricsTranslationConfig(
    val provider: LyricsTranslationProvider = LyricsTranslationProvider.GOOGLE,
    val targetLanguage: String = "en",
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val model: String = "gpt-4o-mini",
    val apiKey: String? = null,
    val customPrompt: String = "",
)

/**
 * Translates visible lyric lines while leaving the source document and all timestamps untouched.
 * The client uses the JDK HTTP stack and the backend's existing JSON library, so it adds no
 * reflection-heavy runtime dependencies to the Native Image target.
 */
class JvmLyricsTranslationClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(45),
) {
    suspend fun translate(
        lines: List<String>,
        config: LyricsTranslationConfig,
    ): List<String> {
        if (lines.isEmpty()) return emptyList()
        val language = config.targetLanguage.trim().also {
            require(LANGUAGE_CODE.matches(it)) { "Enter a valid target language code" }
        }
        val normalizedLines = lines.map(String::trim)
        require(normalizedLines.all(String::isNotBlank)) { "Lyrics contain an empty line" }

        val output = ArrayList<String>(normalizedLines.size)
        for (batch in normalizedLines.chunkedWithinBudget()) {
            val translated = when (config.provider) {
                LyricsTranslationProvider.GOOGLE -> translateWithGoogle(batch, language)
                LyricsTranslationProvider.OPENAI_COMPATIBLE -> translateWithOpenAiCompatible(batch, config, language)
            }
            require(translated.size == batch.size) { "Translation service changed the lyric line count" }
            require(translated.all(String::isNotBlank)) { "Translation service returned an empty lyric line" }
            output += translated
        }
        return output
    }

    private suspend fun translateWithGoogle(lines: List<String>, targetLanguage: String): List<String> {
        val separator = uniqueSeparator(lines)
        val query = lines.joinToString(separator)
        val body = formBody(
            "client" to "at",
            "dt" to "t",
            "ie" to "UTF-8",
            "oe" to "UTF-8",
            "sl" to "auto",
            "tl" to targetLanguage,
            "q" to query,
        )
        val response = send(
            HttpRequest.newBuilder(URI.create(GOOGLE_TRANSLATE_ENDPOINT))
                .timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("User-Agent", "ArchiveTune Desktop")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
        )
        val segments = JSONArray(response).optJSONArray(0)
            ?: throw LyricsTranslationException("Translation service returned malformed data")
        val translatedText = buildString {
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONArray(index) ?: continue
                append(segment.optString(0))
            }
        }
        return splitBatch(translatedText, separator, lines.size)
    }

    private suspend fun translateWithOpenAiCompatible(
        lines: List<String>,
        config: LyricsTranslationConfig,
        targetLanguage: String,
    ): List<String> {
        val apiKey = config.apiKey?.trim().orEmpty()
        require(apiKey.isNotEmpty()) { "Configure an API key before using the AI translation provider" }
        val model = config.model.trim()
        require(model.isNotEmpty()) { "Configure a model before using the AI translation provider" }
        val endpoint = config.endpoint.trim()
        val uri = URI.create(endpoint)
        require(uri.scheme.equals("https", ignoreCase = true) || isLoopbackHttp(uri)) {
            "The AI endpoint must use HTTPS (HTTP is allowed for a local endpoint)"
        }
        require(uri.host != null) { "Configure a valid AI chat-completions endpoint" }

        val systemPrompt = buildString {
            append("You are an expert song lyrics translator.\n")
            append("Translate each input string into ")
            append(targetLanguage)
            append(" with natural, accurate lyric phrasing. Preserve meaning, tone, profanity level, names, repeated hooks, and line-level intent.\n")
            append("Return only a JSON array of strings with exactly ")
            append(lines.size)
            append(" items in the same order. Do not add timestamps, IDs, markdown, explanations, or extra lines.")
            config.customPrompt.trim().takeIf(String::isNotEmpty)?.let {
                append("\n\n")
                append(it)
            }
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", JSONArray(lines).toString()))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.15)
            .put("max_tokens", 8192)
            .toString()
        val response = send(
            HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build(),
        )
        val content = JSONObject(response)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf(String::isNotBlank)
            ?: throw LyricsTranslationException("AI provider returned no translated lines")
        val jsonArrayText = extractJsonArray(content)
        val result = JSONArray(jsonArrayText)
        require(result.length() == lines.size) { "AI response changed the lyric line count" }
        return List(result.length()) { index -> result.getString(index).trim() }
    }

    private suspend fun send(request: HttpRequest): String {
        val response = try {
            runInterruptible(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw LyricsTranslationException("Translation service could not be reached", failure)
        }
        if (response.statusCode() !in 200..299) {
            throw LyricsTranslationException("Translation service returned HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    private fun List<String>.chunkedWithinBudget(): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var batch = mutableListOf<String>()
        var chars = 0
        forEach { line ->
            val addition = line.length + if (batch.isEmpty()) 0 else SEPARATOR_LENGTH
            if (batch.isNotEmpty() && (batch.size >= MAX_LINES_PER_BATCH || chars + addition > MAX_CHARS_PER_BATCH)) {
                result += batch
                batch = mutableListOf()
                chars = 0
            }
            if (batch.isNotEmpty()) chars += SEPARATOR_LENGTH
            batch += line
            chars += line.length
        }
        if (batch.isNotEmpty()) result += batch
        return result
    }

    private fun isLoopbackHttp(uri: URI): Boolean =
        uri.scheme.equals("http", ignoreCase = true) &&
            (uri.host.equals("localhost", ignoreCase = true) || uri.host == "127.0.0.1" || uri.host == "::1")

    private fun uniqueSeparator(lines: List<String>): String {
        var separator = "<<<ARCHIVETUNE-${java.util.UUID.randomUUID()}-LYRIC-LINE>>>"
        while (lines.any { it.contains(separator) }) separator = "<<<ARCHIVETUNE-${java.util.UUID.randomUUID()}-LYRIC-LINE>>>"
        return separator
    }

    private fun splitBatch(translated: String, separator: String, expectedCount: Int): List<String> {
        val split = translated.split(separator)
        require(split.size == expectedCount) { "Translation service changed the lyric line boundaries" }
        return split.map(String::trim)
    }

    private fun formBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun extractJsonArray(content: String): String {
        val trimmed = content.trim()
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        require(start >= 0 && end > start) { "AI provider returned a malformed JSON array" }
        return trimmed.substring(start, end + 1)
    }

    companion object {
        const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        private const val GOOGLE_TRANSLATE_ENDPOINT = "https://translate.googleapis.com/translate_a/single"
        private const val MAX_LINES_PER_BATCH = 50
        private const val MAX_CHARS_PER_BATCH = 4_000
        private const val SEPARATOR_LENGTH = 46
        private val LANGUAGE_CODE = Regex("^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*$")
    }
}

class LyricsTranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)
