/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.sponsorblock

import kotlinx.coroutines.suspendCancellableCoroutine
import moe.rukamori.archivetune.sponsorblock.core.RawSponsorBlockSegment
import moe.rukamori.archivetune.sponsorblock.core.normalizeSponsorBlockSegments
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DEFAULT_DESKTOP_SPONSOR_BLOCK_API_URL = "https://sponsor.ajay.app"

enum class DesktopSponsorBlockCategory(
    val apiValue: String,
    val displayName: String,
) {
    MUSIC_OFF_TOPIC("music_offtopic", "Non-music and off-topic portions"),
    SPONSOR("sponsor", "Sponsors"),
    INTRO("intro", "Intros"),
    OUTRO("outro", "Outros"),
    SELF_PROMOTION("selfpromo", "Self promotions"),
    PREVIEW("preview", "Previews"),
    FILLER("filler", "Fillers"),
    INTERACTION("interaction", "Subscription reminders"),
    HOOK("hook", "Hooks and greetings"),
    ;

    companion object {
        fun fromApiValue(value: String): DesktopSponsorBlockCategory? = entries.firstOrNull { it.apiValue == value }
    }
}

data class DesktopSponsorBlockSegment(
    val startMs: Long,
    val endMs: Long,
)

fun interface DesktopSponsorBlockSegmentProvider {
    suspend fun fetchSegments(
        videoId: String,
        categories: Set<String>,
        apiUrl: String,
    ): List<DesktopSponsorBlockSegment>
}

/** SponsorBlock client with cancellable HTTP requests and a small process-local LRU. */
class DesktopSponsorBlockRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build(),
) : DesktopSponsorBlockSegmentProvider {
    private val cache = object : LinkedHashMap<RequestKey, List<DesktopSponsorBlockSegment>>(CACHE_LIMIT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RequestKey, List<DesktopSponsorBlockSegment>>): Boolean =
            size > CACHE_LIMIT
    }

    override suspend fun fetchSegments(
        videoId: String,
        categories: Set<String>,
        apiUrl: String,
    ): List<DesktopSponsorBlockSegment> {
        if (!YOUTUBE_VIDEO_ID.matches(videoId) || categories.isEmpty()) return emptyList()
        val normalizedUrl = normalizeDesktopSponsorBlockApiUrl(apiUrl) ?: DEFAULT_DESKTOP_SPONSOR_BLOCK_API_URL
        val selectedCategories = DesktopSponsorBlockCategory.entries
            .map(DesktopSponsorBlockCategory::apiValue)
            .filter(categories::contains)
        if (selectedCategories.isEmpty()) return emptyList()
        val key = RequestKey(normalizedUrl, videoId, selectedCategories)
        synchronized(cache) { cache[key] }?.let { return it }

        val base = normalizedUrl.toHttpUrlOrNull() ?: return emptyList()
        val hashPrefix = videoId.sha256Prefix()
        val url = base.newBuilder()
            .addPathSegments("api/skipSegments/$hashPrefix")
            .addQueryParameter("categories", JSONArray(selectedCategories).toString())
            .addQueryParameter("actionTypes", SKIP_ACTION_TYPES)
            .addQueryParameter("service", "YouTube")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val response = client.newCall(request).awaitResponse()
        val segments = response.use { httpResponse ->
            when {
                httpResponse.code == 404 -> emptyList()
                !httpResponse.isSuccessful -> error("SponsorBlock returned HTTP ${httpResponse.code}")
                else -> {
                    val videos = JSONArray(httpResponse.body?.string().orEmpty())
                    val video = (0 until videos.length())
                        .map(videos::getJSONObject)
                        .firstOrNull { it.optString("videoID") == videoId }
                    parseSegments(video?.optJSONArray("segments"), selectedCategories.toSet())
                }
            }
        }
        synchronized(cache) { cache[key] = segments }
        return segments
    }

    internal fun parseSegments(
        rawSegments: JSONArray?,
        selectedCategories: Set<String>,
    ): List<DesktopSponsorBlockSegment> {
        val segments = buildList {
            for (index in 0 until (rawSegments?.length() ?: 0)) {
                val raw = rawSegments?.optJSONObject(index) ?: continue
                val range = raw.optJSONArray("segment") ?: continue
                if (range.length() < 2) continue
                add(
                    RawSponsorBlockSegment(
                        startSeconds = range.optDouble(0, Double.NaN),
                        endSeconds = range.optDouble(1, Double.NaN),
                        category = raw.optString("category"),
                        actionType = raw.optString("actionType"),
                    ),
                )
            }
        }
        return normalizeSponsorBlockSegments(segments, selectedCategories)
            .map { DesktopSponsorBlockSegment(it.startMs, it.endMs) }
    }

    private data class RequestKey(val apiUrl: String, val videoId: String, val categories: List<String>)

    private companion object {
        val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        const val CACHE_LIMIT = 64
        const val HASH_PREFIX_LENGTH = 4
        const val SKIP_ACTION_TYPES = "[\"skip\"]"
        const val USER_AGENT = "ArchiveTune Desktop"
        const val HEX_DIGITS = "0123456789abcdef"
    }
}

fun normalizeDesktopSponsorBlockApiUrl(value: String): String? {
    val candidate = value.trim().ifEmpty { DEFAULT_DESKTOP_SPONSOR_BLOCK_API_URL }
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
    if (candidate.length > MAX_API_URL_LENGTH) return null
    return candidate.trimEnd('/')
}

private fun String.sha256Prefix(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return buildString(HASH_PREFIX_LENGTH) {
        for (index in 0 until HASH_PREFIX_LENGTH / 2) {
            val value = digest[index].toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }
}

private const val HASH_PREFIX_LENGTH = 4
private const val HEX_DIGITS = "0123456789abcdef"
private const val MAX_API_URL_LENGTH = 2_048

private suspend fun Call.awaitResponse(): okhttp3.Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: java.io.IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: okhttp3.Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
