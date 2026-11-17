/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryContentCacheTest {
    @Test
    fun `invalidated old loader cannot overwrite or remove replacement`() =
        runTest {
            val cache = DiscoveryContentCache<String>({ "account" })
            val started = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val releaseNew = CompletableDeferred<String>()
            val old = async {
                runCatching {
                    cache.getOrLoad("key") {
                        started.complete(Unit)
                        withContext(NonCancellable) { releaseOld.await() }
                        "stale"
                    }
                }
            }
            started.await()
            cache.invalidate("key")
            val replacement = async { cache.getOrLoad("key") { releaseNew.await() } }
            while (!cache.isInFlight("key")) yield()
            releaseOld.complete(Unit)
            old.await()
            assertTrue(cache.isInFlight("key"))
            releaseNew.complete("fresh")
            assertEquals("fresh", replacement.await())
            assertEquals("fresh", cache.peek("key"))
            cache.close()
        }

    @Test
    fun `reuses cached value for the same account and key`() =
        runTest {
            var loads = 0
            val cache = DiscoveryContentCache<String>({ "account-a" })
            val first =
                cache.getOrLoad("k") {
                    loads++
                    "value"
                }
            val second =
                cache.getOrLoad("k") {
                    loads++
                    "other"
                }
            assertEquals("value", first)
            assertEquals("value", second)
            assertEquals(1, loads)
            assertEquals(1, cache.size)
            cache.close()
        }

    @Test
    fun `coalesces concurrent loads for the same account and key`() =
        runTest {
            val cache = DiscoveryContentCache<String>({ "account-a" })
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<String>()
            var loads = 0

            suspend fun load(): String {
                loads++
                started.complete(Unit)
                return release.await()
            }

            val first =
                async {
                    cache.getOrLoad("k") { load() }
                }
            started.await()
            val second =
                async {
                    cache.getOrLoad("k") { load() }
                }
            yield()
            assertEquals(1, loads)
            assertTrue(cache.isInFlight("k"))

            release.complete("shared")
            assertEquals("shared", first.await())
            assertEquals("shared", second.await())
            assertEquals(1, loads)
            assertFalse(cache.isInFlight("k"))
            cache.close()
        }

    @Test
    fun `does not share cached values between accounts`() =
        runTest {
            val account = AtomicReference("account-a")
            val cache = DiscoveryContentCache<String>({ account.get() })

            assertEquals("a-value", cache.getOrLoad("k") { "a-value" })
            account.set("account-b")
            assertEquals("b-value", cache.getOrLoad("k") { "b-value" })
            assertEquals("b-value", cache.getOrLoad("k") { "must-not-run" })
            assertEquals(2, cache.size)
            cache.close()
        }

    @Test
    fun `discards an in-flight result when the account changes`() =
        runTest {
            val account = AtomicReference("account-a")
            val cache = DiscoveryContentCache<String>({ account.get() })
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<String>()

            val result =
                async {
                    cache.getOrLoad("k") {
                        started.complete(Unit)
                        release.await()
                    }
                }
            started.await()
            account.set("account-b")
            release.complete("stale")

            assertFailsWith<CancellationException> { result.await() }
            account.set("account-a")
            assertFalse(cache.contains("k"))
            account.set("account-b")
            assertFalse(cache.contains("k"))
            cache.close()
        }

    @Test
    fun `propagates loader failures without caching them`() =
        runTest {
            val cache = DiscoveryContentCache<String>({ "account-a" })
            assertFailsWith<IllegalStateException> {
                cache.getOrLoad("k") { throw IllegalStateException("boom") }
            }
            assertEquals(0, cache.size)
            assertEquals("ok", cache.getOrLoad("k") { "ok" })
            cache.close()
        }

    @Test
    fun `close cancels all in-flight loaders and rejects new work`() =
        runTest {
            val cache = DiscoveryContentCache<String>({ "account-a" })
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<String>()
            val result =
                async {
                    cache.getOrLoad("k") {
                        started.complete(Unit)
                        release.await()
                    }
                }
            started.await()

            cache.close()
            assertFailsWith<CancellationException> { result.await() }
            assertFailsWith<IllegalStateException> { cache.getOrLoad("other") { "value" } }
        }

    @Test
    fun `evicts the least recently used entry`() =
        runTest {
            val cache = DiscoveryContentCache<String>({ "account-a" }, maxEntries = 2)
            cache.getOrLoad("k1") { "1" }
            cache.getOrLoad("k2") { "2" }
            assertEquals("1", cache.peek("k1"))
            cache.getOrLoad("k3") { "3" }

            assertEquals(2, cache.size)
            assertTrue(cache.contains("k1"))
            assertFalse(cache.contains("k2"))
            assertTrue(cache.contains("k3"))
            cache.close()
        }

    @Test
    fun `invalidating an account leaves other accounts intact`() =
        runTest {
            val account = AtomicReference("account-a")
            val cache = DiscoveryContentCache<String>({ account.get() })
            cache.getOrLoad("k") { "a-value" }
            account.set("account-b")
            cache.getOrLoad("k") { "b-value" }

            cache.invalidateAccount("account-a")
            account.set("account-a")
            assertFalse(cache.contains("k"))
            account.set("account-b")
            assertTrue(cache.contains("k"))
            cache.close()
        }

    @Test
    fun `cancelling the last waiter cancels its loader`() =
        runTest {
            val cache = DiscoveryContentCache<String>({ "account-a" })
            val started = CompletableDeferred<Unit>()
            val loaderCancelled = CompletableDeferred<Unit>()
            val job =
                launch {
                    cache.getOrLoad("k") {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            loaderCancelled.complete(Unit)
                        }
                    }
                }
            started.await()
            assertTrue(cache.isInFlight("k"))

            job.cancel()
            loaderCancelled.await()
            assertFalse(cache.isInFlight("k"))
            assertFalse(cache.contains("k"))
            cache.close()
        }
}
