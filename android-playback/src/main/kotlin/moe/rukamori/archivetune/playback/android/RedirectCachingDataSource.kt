/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import moe.rukamori.archivetune.playback.api.PlaybackRedirectCache

/**
 * Media3 [DataSource] decorator backed by `:playback-core`'s [PlaybackRedirectCache].
 *
 * Reuses validated same-family HTTPS redirect targets (such as CDN edge redirects on
 * `*.googlevideo.com`) across subsequent range opens, seeks, cache fragment boundaries, and
 * crossfade starts. If a cached redirect ever fails to open, it is evicted immediately and the
 * original [DataSpec.uri] is retried transparently.
 */
@UnstableApi
internal class RedirectCachingDataSource(
    private val upstream: DataSource,
    private val redirectCache: PlaybackRedirectCache,
    private val parseUri: (String) -> Uri = Uri::parse,
) : DataSource {
    private var activeOriginalUrl: String? = null
    private var usedCachedRedirect: Boolean = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val originalUrl = dataSpec.uri.toString()
        val outcome =
            openWithRedirectCache(
                originalUrl = originalUrl,
                redirectCache = redirectCache,
                openUrl = { targetUrl ->
                    val targetSpec =
                        if (targetUrl == originalUrl) {
                            dataSpec
                        } else {
                            dataSpec.withUri(parseUri(targetUrl))
                        }
                    val length = upstream.open(targetSpec)
                    length to upstream.uri?.toString()
                },
                closeBeforeFallback = { runCatching { upstream.close() } },
            )
        activeOriginalUrl = originalUrl
        usedCachedRedirect = outcome.usedCachedRedirect
        return outcome.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        try {
            upstream.read(buffer, offset, length)
        } catch (error: IOException) {
            if (usedCachedRedirect) {
                activeOriginalUrl?.let(redirectCache::invalidate)
            }
            throw error
        }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        activeOriginalUrl = null
        usedCachedRedirect = false
        upstream.close()
    }
}

internal data class RedirectOpenOutcome(
    val length: Long,
    val usedCachedRedirect: Boolean,
)

internal fun openWithRedirectCache(
    originalUrl: String,
    redirectCache: PlaybackRedirectCache,
    openUrl: (targetUrl: String) -> Pair<Long, String?>,
    closeBeforeFallback: () -> Unit = {},
): RedirectOpenOutcome {
    val cachedUrl = redirectCache.resolve(originalUrl)
    var usedCached = cachedUrl != originalUrl
    val (length, finalUrl) =
        if (usedCached) {
            try {
                openUrl(cachedUrl)
            } catch (_: IOException) {
                closeBeforeFallback()
                redirectCache.invalidate(originalUrl)
                usedCached = false
                openUrl(originalUrl)
            }
        } else {
            openUrl(originalUrl)
        }
    if (finalUrl != null && finalUrl != originalUrl) {
        redirectCache.record(originalUrl, finalUrl)
    }
    return RedirectOpenOutcome(length = length, usedCachedRedirect = usedCached)
}
