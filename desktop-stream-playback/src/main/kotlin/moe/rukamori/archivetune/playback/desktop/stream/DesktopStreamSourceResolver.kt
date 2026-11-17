package moe.rukamori.archivetune.playback.desktop.stream

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.DesktopSessionProvider
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.ResolvedAudioStream
import moe.rukamori.archivetune.innertube.StreamHttpRecoveryAction
import moe.rukamori.archivetune.innertube.StreamResolutionFailureKind
import moe.rukamori.archivetune.innertube.classifyStreamResolutionFailure
import moe.rukamori.archivetune.innertube.utils.sha1
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.desktop.DesktopConcretePlaybackSourceResolver

/** Narrow backend collaborator required by [DesktopStreamSourceResolver]. */
interface DesktopStreamBackend {
    suspend fun resolveAudioStreamWithMetadataFallback(
        videoId: String,
        title: String,
        artistNames: List<String>,
        albumTitle: String? = null,
        durationHintMs: Long? = null,
    ): Result<ResolvedAudioStream>

    suspend fun prepareStreamHttpRecovery(
        videoId: String,
        clientKey: String?,
        httpStatusCode: Int,
        authFingerprint: String,
        failedUrlHadPoToken: Boolean,
    ): StreamHttpRecoveryAction
}

/** Adapts [ArchiveTuneJvmClient] to [DesktopStreamBackend]. */
class JvmClientDesktopStreamBackend(
    private val client: ArchiveTuneJvmClient,
) : DesktopStreamBackend {
    override suspend fun resolveAudioStreamWithMetadataFallback(
        videoId: String,
        title: String,
        artistNames: List<String>,
        albumTitle: String?,
        durationHintMs: Long?,
    ): Result<ResolvedAudioStream> =
        client.resolveAudioStreamWithMetadataFallback(
            videoId = videoId,
            title = title,
            artistNames = artistNames,
            albumTitle = albumTitle,
            durationHintMs = durationHintMs,
        )

    override suspend fun prepareStreamHttpRecovery(
        videoId: String,
        clientKey: String?,
        httpStatusCode: Int,
        authFingerprint: String,
        failedUrlHadPoToken: Boolean,
    ): StreamHttpRecoveryAction =
        client.prepareStreamHttpRecovery(
            videoId = videoId,
            clientKey = clientKey,
            httpStatusCode = httpStatusCode,
            authFingerprint = authFingerprint,
            failedUrlHadPoToken = failedUrlHadPoToken,
        )
}

/** Sanitized diagnostic sink for stream resolution and HTTP recovery events. */
interface DesktopStreamDiagnostics {
    fun info(message: String) {}
    fun warning(message: String) {}

    companion object {
        val NO_OP: DesktopStreamDiagnostics = object : DesktopStreamDiagnostics {}
    }
}

class DesktopStreamResolutionException(
    val classification: StreamResolutionFailureKind,
    cause: Throwable,
) : IllegalStateException(classification.userMessage, cause)

/**
 * Reusable desktop [PlaybackSourceResolver] boundary owning local-first completed-download
 * preference, auth-scoped in-memory signed-URL caching, and HTTP stream failure recovery.
 *
 * Signed URLs and auth material live only in memory and are never persisted to disk.
 */
class DesktopStreamSourceResolver(
    private val backend: DesktopStreamBackend,
    private val sessionProvider: DesktopSessionProvider,
    completedDownloadLookup: ((String) -> Path?)? = null,
    private val diagnostics: DesktopStreamDiagnostics = DesktopStreamDiagnostics.NO_OP,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : PlaybackSourceResolver, AutoCloseable {
    constructor(
        backend: ArchiveTuneJvmClient,
        sessionProvider: DesktopSessionProvider,
        completedDownloadLookup: ((String) -> Path?)? = null,
        diagnostics: DesktopStreamDiagnostics = DesktopStreamDiagnostics.NO_OP,
        clockMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        backend = JvmClientDesktopStreamBackend(backend),
        sessionProvider = sessionProvider,
        completedDownloadLookup = completedDownloadLookup,
        diagnostics = diagnostics,
        clockMillis = clockMillis,
    )

    @Volatile
    private var completedDownloadLookup: ((String) -> Path?)? = completedDownloadLookup

    /** Signed sources live only in memory and are discarded before their server expiry. */
    private val resolvedSourceCache = ConcurrentHashMap<String, CachedResolvedSource>()

    fun attachCompletedDownloadLookup(lookup: ((String) -> Path?)?) {
        completedDownloadLookup = lookup
    }

    override suspend fun resolve(item: PlaybackItem): Result<ResolvedPlaybackSource> =
        resolve(item, cacheResolvedRemote = { true }, reportFailure = true)

    suspend fun resolve(
        item: PlaybackItem,
        cacheResolvedRemote: () -> Boolean = { true },
        reportFailure: Boolean = true,
    ): Result<ResolvedPlaybackSource> = when (val source = item.source) {
        is PlaybackSource.Deferred -> {
            // Capture the account scope before network resolution. If the account changes while
            // the request is in flight, the resulting signed URL must never be stamped as belonging
            // to the new account when it is inserted into the in-memory cache.
            val requestSessionFingerprint = sessionProvider.currentAuthState().streamCacheSessionFingerprint()
            resolveDeferredPreferCompletedDownload(
                item = item,
                completedDownloadLookup = completedDownloadLookup,
            ) {
                cachedSourceFor(source.key)?.let { Result.success(it) }
                    ?: backend.resolveAudioStreamWithMetadataFallback(
                        videoId = source.key,
                        title = item.metadata.title,
                        artistNames = item.metadata.artists.map { it.name },
                        albumTitle = item.metadata.album?.title,
                        durationHintMs = item.metadata.durationHintMs,
                    )
                        .fold(
                            onSuccess = {
                                diagnostics.info(
                                    "stream_resolution event=resolved videoId=${source.key} resolvedVideoId=${it.videoId} client=${it.clientKey} " +
                                        "itag=${it.format.itag} mime=${it.mimeType.substringBefore(';')} bitrate=${it.bitrate} " +
                                        "hasPot=${it.url.hasQueryParameter("pot")} hasN=${it.url.hasQueryParameter("n")}",
                                )
                                val resolved = ResolvedPlaybackSource(
                                    uri = it.url,
                                    mimeType = it.mimeType,
                                    headers = it.requestHeaders,
                                    expiresAtEpochMs = it.expiresAtEpochMillis,
                                    loudnessDb = it.audioConfig?.loudnessDb,
                                    perceptualLoudnessDb = it.audioConfig?.perceptualLoudnessDb,
                                )
                                if (cacheResolvedRemote() && isSameStreamCacheSession(
                                        requestSessionFingerprint,
                                        sessionProvider.currentAuthState().streamCacheSessionFingerprint(),
                                    )
                                ) {
                                    cacheSource(
                                        key = source.key,
                                        source = resolved,
                                        resolvedVideoId = it.videoId,
                                        streamClientKey = it.clientKey,
                                        streamAuthFingerprint = it.authFingerprint,
                                        sessionFingerprint = requestSessionFingerprint,
                                    )
                                }
                                Result.success(resolved)
                            },
                            onFailure = { failure ->
                                val classification = classifyStreamResolutionFailure(failure)
                                if (reportFailure) {
                                    diagnostics.warning("stream_resolution event=player_handoff result=${classification.name}")
                                }
                                Result.failure(DesktopStreamResolutionException(classification, failure))
                            },
                        )
            }
        }
        is PlaybackSource.Remote, is PlaybackSource.LocalFile -> DesktopConcretePlaybackSourceResolver.resolve(item)
    }

    suspend fun recoverFromStreamHttpFailure(
        item: PlaybackItem,
        failedSource: ResolvedPlaybackSource,
        httpStatus: Int,
    ): Boolean {
        if (httpStatus !in RETRYABLE_STREAM_RESPONSE_CODES) return false
        val deferred = item.source as? PlaybackSource.Deferred ?: return false
        val key = deferred.key
        val cached = resolvedSourceCache[key]
        val matchingCachedSource = cached?.takeIf { it.source.uri == failedSource.uri }
        val resolvedVideoId = matchingCachedSource?.resolvedVideoId ?: key

        // Android drops every cached URL for this media ID before resolving the retry. Desktop has
        // only this auth-scoped in-memory source cache, so remove it unconditionally as well.
        resolvedSourceCache.remove(key)

        val nearExpiry =
            failedSource.expiresAtEpochMs?.let {
                it <= clockMillis() + STREAM_CACHE_EXPIRY_LEAD_MILLIS
            } == true
        val recoveryAction =
            if (!nearExpiry && matchingCachedSource?.streamClientKey?.isNotBlank() == true) {
                backend.prepareStreamHttpRecovery(
                    videoId = resolvedVideoId,
                    clientKey = matchingCachedSource.streamClientKey,
                    httpStatusCode = httpStatus,
                    authFingerprint = matchingCachedSource.streamAuthFingerprint
                        ?: sessionProvider.currentAuthState().fingerprint,
                    failedUrlHadPoToken = failedSource.uri.hasQueryParameter("pot"),
                )
            } else {
                StreamHttpRecoveryAction.RETRY_ONLY
            }

        diagnostics.info(
            "stream_resolution event=media_http_failure videoId=$key http=$httpStatus " +
                "expired=$nearExpiry recovery=${recoveryAction.name} action=retry",
        )
        // Match Android: even an expired/unknown-profile URL gets one fresh resolve; the failed
        // client is only backoff-marked when the concrete profile is known and the URL was current.
        return true
    }

    override fun close() {
        resolvedSourceCache.clear()
    }

    private fun cachedSourceFor(key: String): ResolvedPlaybackSource? {
        val cached = resolvedSourceCache[key] ?: return null
        if (cached.sessionFingerprint != sessionProvider.currentAuthState().streamCacheSessionFingerprint()) {
            resolvedSourceCache.remove(key, cached)
            return null
        }
        val expiresAt = cached.source.expiresAtEpochMs ?: run {
            resolvedSourceCache.remove(key, cached)
            return null
        }
        if (expiresAt <= clockMillis() + STREAM_CACHE_EXPIRY_LEAD_MILLIS) {
            resolvedSourceCache.remove(key, cached)
            return null
        }
        return cached.source
    }

    private fun cacheSource(
        key: String,
        source: ResolvedPlaybackSource,
        resolvedVideoId: String,
        streamClientKey: String,
        streamAuthFingerprint: String,
        sessionFingerprint: String,
    ) {
        source.expiresAtEpochMs ?: return
        // Defend against a session change between the caller's check and this cache write.
        if (!isSameStreamCacheSession(sessionFingerprint, sessionProvider.currentAuthState().streamCacheSessionFingerprint())) return
        if (resolvedSourceCache.size >= MAX_RESOLVED_SOURCE_CACHE_ENTRIES) {
            resolvedSourceCache.entries.removeIf { (_, value) ->
                value.source.expiresAtEpochMs?.let { it <= clockMillis() + STREAM_CACHE_EXPIRY_LEAD_MILLIS } != false
            }
        }
        if (resolvedSourceCache.size >= MAX_RESOLVED_SOURCE_CACHE_ENTRIES) resolvedSourceCache.clear()
        resolvedSourceCache[key] = CachedResolvedSource(
            source = source,
            // Per-video PoTokens rotate every time another song is prepared. They must not evict a
            // still-valid signed source for the previous song; only stable account/session context
            // determines whether a cached URL belongs to the current login.
            sessionFingerprint = sessionFingerprint,
            resolvedVideoId = resolvedVideoId,
            streamClientKey = streamClientKey,
            streamAuthFingerprint = streamAuthFingerprint,
        )
    }

    private fun String.hasQueryParameter(name: String): Boolean =
        contains("?$name=") || contains("&$name=")

    private data class CachedResolvedSource(
        val source: ResolvedPlaybackSource,
        val sessionFingerprint: String?,
        val resolvedVideoId: String,
        val streamClientKey: String,
        val streamAuthFingerprint: String?,
    )

    companion object {
        const val MAX_RESOLVED_SOURCE_CACHE_ENTRIES = 128
        const val STREAM_CACHE_EXPIRY_LEAD_MILLIS = 60_000L
        val RETRYABLE_STREAM_RESPONSE_CODES = setOf(403, 404, 410, 416)
    }
}

/**
 * Resolves a completed download through the normal LocalFile path before touching any signed-stream
 * fallback. The lookup already filters stale journal entries, but validation here also covers a file
 * disappearing between journal lookup and playback without turning a recoverable cache miss into a
 * player error.
 */
suspend fun resolveDeferredPreferCompletedDownload(
    item: PlaybackItem,
    completedDownloadLookup: ((String) -> Path?)?,
    fallback: suspend () -> Result<ResolvedPlaybackSource>,
): Result<ResolvedPlaybackSource> {
    val deferred = item.source as? PlaybackSource.Deferred ?: return fallback()
    val completedPath = runCatching { completedDownloadLookup?.invoke(deferred.key) }.getOrNull()
    if (completedPath != null) {
        val localResult = DesktopConcretePlaybackSourceResolver.resolve(
            item.copy(source = PlaybackSource.LocalFile(completedPath.toString())),
        )
        if (localResult.isSuccess) return localResult
    }
    return fallback()
}

fun isSameStreamCacheSession(requestSessionFingerprint: String, currentSessionFingerprint: String): Boolean =
    requestSessionFingerprint == currentSessionFingerprint

internal fun PlaybackAuthState.streamCacheSessionFingerprint(): String =
    sha1(
        listOf(
            cookie.orEmpty(),
            visitorData.orEmpty(),
            dataSyncId.orEmpty(),
        ).joinToString(separator = "\u0000"),
    )
