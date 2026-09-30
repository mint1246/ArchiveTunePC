package moe.rukamori.archivetune.lyrics

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class JvmLyricsTranslationClientTest {
    @Test
    fun `openai-compatible translation preserves line count and sends configured language and model`() = runBlocking {
        var payload: JSONObject? = null
        var authorization: String? = null
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/v1/chat/completions") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization")
                payload = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                val body = JSONObject()
                    .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "[\"Bonjour\",\"Monde\"]"))))
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        try {
            val translated = JvmLyricsTranslationClient().translate(
                lines = listOf("Hello", "World"),
                config = LyricsTranslationConfig(
                    provider = LyricsTranslationProvider.OPENAI_COMPATIBLE,
                    targetLanguage = "fr",
                    endpoint = "http://127.0.0.1:${server.address.port}/v1/chat/completions",
                    model = "test-model",
                    apiKey = "secret-test-key",
                ),
            )

            assertEquals(listOf("Bonjour", "Monde"), translated)
            assertEquals("Bearer secret-test-key", authorization)
            assertEquals("test-model", payload?.optString("model"))
            val prompt = payload?.optJSONArray("messages")?.optJSONObject(0)?.optString("content").orEmpty()
            assertTrue(prompt.contains("French") || prompt.contains("fr"))
            val source = payload?.optJSONArray("messages")?.optJSONObject(1)?.optString("content")
            assertEquals("[\"Hello\",\"World\"]", source)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `translation cache persists by track language provider model and endpoint without secrets`() {
        val directory = Files.createTempDirectory("lyrics-translation-cache")
        val file = directory.resolve("translations.json")
        try {
            val key = cacheKey(track = "track-a", language = "fr", model = "model-a")
            JvmLyricsTranslationCache(file).put(key, listOf("Bonjour"))
            val reloaded = JvmLyricsTranslationCache(file)

            assertEquals(listOf("Bonjour"), reloaded.get(key))
            assertEquals(null, reloaded.get(cacheKey(track = "track-b", language = "fr", model = "model-a")))
            assertEquals(null, reloaded.get(cacheKey(track = "track-a", language = "de", model = "model-a")))
            assertEquals(null, reloaded.get(cacheKey(track = "track-a", language = "fr", model = "model-b")))
            val persisted = Files.readString(file)
            assertFalse(persisted.contains("track-a"))
            assertFalse(persisted.contains("source lyrics"))
            assertFalse(persisted.contains("secret-test-key"))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `translation cache remains bounded`() {
        val directory = Files.createTempDirectory("lyrics-translation-cache")
        try {
            val cache = JvmLyricsTranslationCache(directory.resolve("translations.json"), maxEntries = 2)
            cache.put(cacheKey(track = "one"), listOf("one"))
            cache.put(cacheKey(track = "two"), listOf("two"))
            cache.put(cacheKey(track = "three"), listOf("three"))

            assertEquals(2, cache.size())
            assertEquals(null, cache.get(cacheKey(track = "one")))
            assertEquals(listOf("two"), cache.get(cacheKey(track = "two")))
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun cacheKey(
        track: String,
        language: String = "en",
        model: String = "gpt-4o-mini",
    ) = LyricsTranslationCacheKey(
        trackId = track,
        sourceText = "source lyrics",
        targetLanguage = language,
        provider = LyricsTranslationProvider.OPENAI_COMPATIBLE,
        endpoint = "https://example.com/v1/chat/completions",
        model = model,
        customPrompt = "",
    )
}
