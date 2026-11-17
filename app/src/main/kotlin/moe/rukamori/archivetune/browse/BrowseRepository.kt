/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.browse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.PODCAST_LIBRARY_BROWSE_ID
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.sdk.discovery.DiscoveryContentCache
import moe.rukamori.archivetune.sdk.discovery.DiscoveryKeys
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class BrowsePage(
    val title: String?,
    val items: List<YTItem>,
    val continuation: String?,
)

@Singleton
class BrowseRepository
    @Inject
    constructor() {
        private val pageCache =
            DiscoveryContentCache<BrowsePage>(
                accountScopeProvider = {
                    val accountScope = YouTube.cookie?.takeIf(String::isNotBlank)?.let(::sha256Hex) ?: "signed-out"
                    "$accountScope|${YouTube.locale}"
                },
            )

        suspend fun load(
            browseId: String,
            forceRefresh: Boolean = false,
        ): Result<BrowsePage> =
            withContext(Dispatchers.IO) {
                val key = DiscoveryKeys.browse(browseId)
                if (forceRefresh) {
                    pageCache.invalidate(key)
                }
                try {
                    Result.success(
                        pageCache.getOrLoad(key) {
                            if (browseId == PODCAST_LIBRARY_BROWSE_ID) {
                                YouTube.library(browseId).map { page ->
                                    BrowsePage(
                                        title = page.title,
                                        items = page.items,
                                        continuation = page.continuation,
                                    )
                                }
                            } else {
                                YouTube.browse(browseId, params = null).map { page ->
                                    BrowsePage(
                                        title = page.title,
                                        items = page.items.flatMap { section -> section.items },
                                        continuation = null,
                                    )
                                }
                            }.getOrThrow()
                        },
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    Result.failure(throwable)
                }
            }

        suspend fun loadContinuation(continuation: String): Result<BrowsePage> =
            withContext(Dispatchers.IO) {
                val result =
                    YouTube.libraryContinuation(continuation).map { page ->
                        BrowsePage(
                            title = null,
                            items = page.items,
                            continuation = page.continuation,
                        )
                    }
                result.rethrowCancellation()
                result
            }
    }

private fun Result<*>.rethrowCancellation() {
    val failure = exceptionOrNull()
    if (failure is CancellationException) throw failure
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
