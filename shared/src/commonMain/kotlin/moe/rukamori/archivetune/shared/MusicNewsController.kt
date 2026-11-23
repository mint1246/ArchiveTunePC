package moe.rukamori.archivetune.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class MusicNewsItem(val id: String, val title: String, val description: String, val author: String, val image: String?)
data class MusicNewsState(val items: List<MusicNewsItem> = emptyList(), val loading: Boolean = false, val loaded: Boolean = false, val error: String? = null, val article: String? = null)
class MusicNewsController(private val http: HttpClient, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(MusicNewsState())
    val state: StateFlow<MusicNewsState> = mutable.asStateFlow()
    private var request: Job? = null
    private val base = "https://raw.githubusercontent.com/koiverse/ArchiveTuneNewsRepository/main/"
    fun load(force: Boolean = false) {
        if (request?.isActive == true || (!force && state.value.loaded)) return
        mutable.update { it.copy(loading = true, error = null) }
        request = scope.launch {
            try {
                val response = http.get(base + "metadata.json")
                check(response.status.value in 200..299)
                val array = Json.parseToJsonElement(response.bodyAsText()) as? JsonArray ?: error("Invalid news")
                val items = array.take(100).mapNotNull { node -> (node as? JsonObject)?.let { obj ->
                    fun text(key: String) = (obj[key] as? JsonPrimitive)?.content.orEmpty()
                    val image = when (val value = obj["ImageURL"]) {
                        is JsonPrimitive -> value.content
                        is JsonArray -> (value.firstOrNull() as? JsonPrimitive)?.content
                        else -> null
                    }
                    MusicNewsItem(text("id"), text("Title"), text("Description"), text("Author"), image)
                } }
                mutable.value = MusicNewsState(items = items, loaded = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, error = "News could not connect. Try again.") } }
        }
    }
    fun open(id: String) {
        if (id.isBlank() || id.length > 200 || '/' in id || '\\' in id || ".." in id) return
        request?.cancel()
        mutable.update { it.copy(loading = true, article = null, error = null) }
        request = scope.launch {
            try {
                val response = http.get(base + "content/" + id)
                check(response.status.value in 200..299)
                mutable.update { it.copy(loading = false, article = response.bodyAsText().take(200_000)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, error = "This article could not connect. Try again.") } }
        }
    }
    fun closeArticle() { request?.cancel(); mutable.update { it.copy(article = null, loading = false) } }
}
