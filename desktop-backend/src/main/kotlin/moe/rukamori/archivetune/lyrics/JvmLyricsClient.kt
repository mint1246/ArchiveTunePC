/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.betterlyrics.BetterLyrics
import moe.rukamori.archivetune.kugou.KuGou
import moe.rukamori.archivetune.lrclib.LrcLib
import moe.rukamori.archivetune.paxsenix.PaxsenixLyrics
import moe.rukamori.archivetune.simpmusic.SimpMusicLyrics
import moe.rukamori.archivetune.spicylyrics.SpicyLyrics
import moe.rukamori.archivetune.unison.Unison
import moe.rukamori.archivetune.youlyplus.YouLyPlus

/** Platform-neutral metadata accepted by every ArchiveTune lyrics provider. */
data class LyricsRequest(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int = -1,
    val videoId: String? = null,
) {
    internal fun normalized(): LyricsRequest =
        copy(
            title = title.trim(),
            artist = artist.trim(),
            album = album?.trim()?.takeIf(String::isNotEmpty),
            videoId = videoId?.trim()?.takeIf(String::isNotEmpty),
        ).also {
            require(it.title.isNotEmpty()) { "Song title is required" }
            require(it.artist.isNotEmpty()) { "Song artist is required" }
            require(it.durationSeconds == -1 || it.durationSeconds >= 0) {
                "Song duration must be -1 (unknown) or non-negative"
            }
        }
}

enum class LyricsFormat {
    PLAIN,
    LRC,
    TTML,
    QRC,
}

data class LyricsDocument(
    val providerId: String,
    val providerName: String,
    val text: String,
    val format: LyricsFormat,
) {
    val synchronized: Boolean
        get() = format != LyricsFormat.PLAIN
}

fun interface JvmLyricsProvider {
    suspend fun getLyrics(request: LyricsRequest): String
}

data class NamedLyricsProvider(
    val id: String,
    val name: String,
    val provider: JvmLyricsProvider,
)

enum class LyricsProviderSearchStatus {
    SEARCHING,
    FOUND,
    NO_MATCH,
    TIMED_OUT,
    FAILED,
}

/** One provider's redaction-safe search outcome. Lyrics text is present only in [document]. */
data class LyricsProviderSearchResult(
    val providerId: String,
    val providerName: String,
    val status: LyricsProviderSearchStatus,
    val document: LyricsDocument? = null,
    val failureMessage: String? = null,
)

/**
 * Desktop/JVM lyrics fallback client. Providers are attempted in order and failures are isolated;
 * cancellation is always propagated so closing a desktop player does not leave requests running.
 */
class JvmLyricsClient(
    val providers: List<NamedLyricsProvider> = BuiltInLyricsProviders.defaults,
    private val providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
    private val overallTimeoutMillis: Long = DEFAULT_OVERALL_TIMEOUT_MILLIS,
) {
    init {
        require(providers.isNotEmpty()) { "At least one lyrics provider is required" }
        require(providers.map { it.id }.distinct().size == providers.size) { "Lyrics provider IDs must be unique" }
        require(providerTimeoutMillis > 0) { "Lyrics provider timeout must be positive" }
        require(overallTimeoutMillis > 0) { "Lyrics overall timeout must be positive" }
    }

    suspend fun getLyrics(
        request: LyricsRequest,
        providerIds: List<String>? = null,
    ): Result<LyricsDocument> =
        try {
            withTimeout(overallTimeoutMillis) {
                val normalized = request.normalized()
                val selected = selectProviders(providerIds)
                val failures = mutableListOf<Throwable>()
                var receivedNoResult = false
                for (provider in selected) {
                    for (providerRequest in normalized.providerQueryVariants()) {
                        currentCoroutineContext().ensureActive()
                        val text =
                            try {
                                withTimeout(providerTimeoutMillis) {
                                    provider.provider.getLyrics(providerRequest).trim().takeIf(String::isNotEmpty)
                                }
                            } catch (timeout: TimeoutCancellationException) {
                                failures += LyricsProviderException(provider.id, LyricsProviderTimeoutException(provider.id))
                                null
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Exception) {
                                failures += LyricsProviderException(provider.id, failure)
                                null
                            }
                        currentCoroutineContext().ensureActive()
                        if (text != null) return@withTimeout Result.success(provider.toDocument(text))
                        receivedNoResult = true
                    }
                }
                Result.failure(LyricsUnavailableException(selected.map { it.id }, failures, receivedNoResult))
            }
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(LyricsRequestTimeoutException())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    suspend fun getAllLyrics(
        request: LyricsRequest,
        providerIds: List<String>? = null,
    ): List<LyricsDocument> =
        searchLyrics(request, providerIds)
            .mapNotNull(LyricsProviderSearchResult::document)
            .distinctBy { it.text.normalizedForDeduplication() }

    /** Searches selected providers concurrently and reports every provider's outcome. */
    suspend fun searchLyrics(
        request: LyricsRequest,
        providerIds: List<String>? = null,
    ): List<LyricsProviderSearchResult> {
        val variants = request.normalized().providerQueryVariants()
        val selected = selectProviders(providerIds)
        return supervisorScope {
            selected.map { provider ->
                async { searchProvider(provider, variants) }
            }.awaitAll()
        }
    }

    private suspend fun searchProvider(
        provider: NamedLyricsProvider,
        variants: List<LyricsRequest>,
    ): LyricsProviderSearchResult =
        try {
            withTimeout(providerTimeoutMillis) {
                for (variant in variants) {
                    currentCoroutineContext().ensureActive()
                    val text = provider.provider.getLyrics(variant).trim().takeIf(String::isNotEmpty)
                    if (text != null) {
                        return@withTimeout LyricsProviderSearchResult(
                            provider.id,
                            provider.name,
                            LyricsProviderSearchStatus.FOUND,
                            provider.toDocument(text),
                        )
                    }
                }
                LyricsProviderSearchResult(provider.id, provider.name, LyricsProviderSearchStatus.NO_MATCH)
            }
        } catch (_: TimeoutCancellationException) {
            LyricsProviderSearchResult(provider.id, provider.name, LyricsProviderSearchStatus.TIMED_OUT)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            LyricsProviderSearchResult(provider.id, provider.name, LyricsProviderSearchStatus.FAILED)
        }

    private fun selectProviders(providerIds: List<String>?): List<NamedLyricsProvider> {
        if (providerIds == null) return providers
        val providersById = providers.associateBy { it.id }
        return providerIds.distinct().map { id ->
            providersById[id] ?: throw IllegalArgumentException("Unknown lyrics provider: $id")
        }
    }
}

object BuiltInLyricsProviders {
    const val BETTER_LYRICS = "betterlyrics"
    const val BETTER_LYRICS_PORTATO = "betterlyrics-portato"
    const val YOULY_PLUS = "youlyplus"
    const val LRCLIB = "lrclib"
    const val KUGOU = "kugou"
    const val SIMPMUSIC = "simpmusic"
    const val UNISON = "unison"
    const val SPICY_LYRICS = "spicylyrics"
    const val PAXSENIX_APPLE_MUSIC = "paxsenix-apple-music"
    const val PAXSENIX_SPOTIFY = "paxsenix-spotify"
    const val PAXSENIX_MUSIXMATCH = "paxsenix-musixmatch"

    /** These endpoints now require the user-configured Paxsenix API key. */
    val paxsenixProviderIds: Set<String> = setOf(
        PAXSENIX_APPLE_MUSIC,
        PAXSENIX_SPOTIFY,
        PAXSENIX_MUSIXMATCH,
    )

    /** Drops retired provider IDs from saved selections without rejecting the whole preference. */
    fun normalizeProviderIds(providerIds: List<String>): List<String> {
        val available = defaults.mapTo(hashSetOf()) { it.id }
        return providerIds.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .filter(available::contains)
            .distinct()
            .toList()
    }

    /** Matches the Android fallback preference while remaining entirely plain JVM. */
    val defaults: List<NamedLyricsProvider> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        listOf(
            named(BETTER_LYRICS, "BetterLyrics") { request ->
                BetterLyrics.getLyrics(request.title, request.artist, request.album, request.durationSeconds).getOrThrow()
            },
            named(BETTER_LYRICS_PORTATO, "BetterLyrics Portato") { request ->
                BetterLyrics.getPortatoLyrics(request.title, request.artist, request.album, request.durationSeconds).getOrThrow()
            },
            named(YOULY_PLUS, "YouLyPlus") { request ->
                YouLyPlus.getLyrics(request.title, request.artist, request.album, request.durationSeconds).getOrThrow()
            },
            named(LRCLIB, "LRCLIB") { request ->
                LrcLib.getLyrics(request.title, request.artist, request.durationSeconds, request.album).getOrThrow()
            },
            named(KUGOU, "KuGou") { request ->
                KuGou.getLyrics(request.title, request.artist, request.durationSeconds).getOrThrow()
            },
            named(SIMPMUSIC, "SimpMusic") { request ->
                val videoId = request.videoId ?: throw IllegalArgumentException("SimpMusic requires a YouTube video ID")
                SimpMusicLyrics.getLyrics(videoId, request.durationSeconds).getOrThrow()
            },
            named(UNISON, "Unison") { request ->
                Unison.getLyrics(
                    videoId = request.videoId,
                    title = request.title,
                    artist = request.artist,
                    album = request.album,
                    durationSeconds = request.durationSeconds,
                ).getOrElse { failure ->
                    if (failure.message == "Lyrics unavailable") "" else throw failure
                }
            },
            named(SPICY_LYRICS, "SpicyLyrics") { request ->
                SpicyLyrics.getLyrics(request.title, request.artist, request.album, request.durationSeconds).getOrThrow()
            },
            named(PAXSENIX_APPLE_MUSIC, "Paxsenix: Apple Music") { request ->
                PaxsenixLyrics.getAppleMusicLyrics(request.title, request.artist, request.durationSeconds).getOrThrow()
            },
            named(PAXSENIX_SPOTIFY, "Paxsenix: Spotify") { request ->
                PaxsenixLyrics.getSpotifyLyrics(request.title, request.artist, request.durationSeconds).getOrThrow()
            },
            named(PAXSENIX_MUSIXMATCH, "Paxsenix: Musixmatch") { request ->
                PaxsenixLyrics.getMusixmatchLyrics(request.title, request.artist, request.durationSeconds).getOrThrow()
            },
        )
    }

    private fun named(
        id: String,
        name: String,
        getLyrics: suspend (LyricsRequest) -> String,
    ) = NamedLyricsProvider(id, name, JvmLyricsProvider(getLyrics))
}

class LyricsUnavailableException(
    val providerIds: List<String>,
    val failures: List<Throwable>,
    val receivedNoResult: Boolean,
) : IllegalStateException(
        if (failures.isEmpty()) {
            "No lyrics returned by: ${providerIds.joinToString()}"
        } else {
            "Lyrics providers failed: ${failures.mapNotNull { (it as? LyricsProviderException)?.providerId }.distinct().joinToString()}"
        },
) {
    val hasProviderFailures: Boolean
        get() = failures.isNotEmpty()

    init {
        failures.distinctBy { it::class to it.message }.take(MAX_REPORTED_FAILURES).forEach(::addSuppressed)
    }

    private companion object {
        const val MAX_REPORTED_FAILURES = 12
    }
}

/** Identifies a provider failure without exposing request metadata, URLs, or credentials. */
class LyricsProviderException(
    val providerId: String,
    cause: Throwable,
) : IllegalStateException("Lyrics provider '$providerId' failed", cause)

class LyricsProviderTimeoutException(val providerId: String) : IllegalStateException("Lyrics provider '$providerId' timed out")

class LyricsRequestTimeoutException : IllegalStateException("Lyrics lookup timed out")

private fun NamedLyricsProvider.toDocument(text: String): LyricsDocument =
    LyricsDocument(
        providerId = id,
        providerName = name,
        text = text,
        format = detectLyricsFormat(text),
    )

internal fun detectLyricsFormat(text: String): LyricsFormat =
    when {
        text.contains("<QrcInfos", ignoreCase = true) || text.contains("LyricContent=", ignoreCase = true) -> LyricsFormat.QRC
        text.trimStart().startsWith("<?xml", ignoreCase = true) ||
            text.contains("<tt", ignoreCase = true) -> LyricsFormat.TTML

        LRC_TIMESTAMP.containsMatchIn(text) -> LyricsFormat.LRC
        else -> LyricsFormat.PLAIN
    }

private fun String.normalizedForDeduplication(): String =
    lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString("\n")

private val LRC_TIMESTAMP = Regex("""(?m)^\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")

/**
 * Keeps the display title untouched while giving providers a conventional song-title query first.
 * The original title remains a fallback because some releases are intentionally distinguished by
 * their feature/remaster suffix.
 */
internal fun LyricsRequest.providerQueryVariants(): List<LyricsRequest> {
    val canonicalTitle =
        title
            .replace(FEATURE_SUFFIX, "")
            .replace(RELEASE_SUFFIX, "")
            .replace(DASH_RELEASE_SUFFIX, "")
            .replace(WHITESPACE, " ")
            .trim()
    val canonical = copy(title = canonicalTitle).takeIf { canonicalTitle.isNotEmpty() }
    return listOfNotNull(canonical, this).distinctBy { it.title }
}

private val FEATURE_SUFFIX = Regex("""\s*[\[(](?:feat(?:uring)?|ft)\.?\s+[^\])]+[\])]""", RegexOption.IGNORE_CASE)
private val RELEASE_SUFFIX = Regex("""\s*[\[(](?:official\s+(?:music\s+)?video|official\s+audio|lyrics?\s+video|music\s+video|visuali[sz]er|remaster(?:ed)?(?:\s+\d{4})?|hd|hq)\s*[\])]""", RegexOption.IGNORE_CASE)
private val DASH_RELEASE_SUFFIX = Regex("""\s+-\s+(?:official\s+(?:music\s+)?video|official\s+audio|lyrics?\s+video|music\s+video|visuali[sz]er|remaster(?:ed)?(?:\s+\d{4})?)\s*$""", RegexOption.IGNORE_CASE)
private val WHITESPACE = Regex("""\s+""")
private const val DEFAULT_PROVIDER_TIMEOUT_MILLIS = 8_000L
private const val DEFAULT_OVERALL_TIMEOUT_MILLIS = 30_000L
