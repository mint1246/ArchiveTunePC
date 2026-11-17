/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Bounded, account-scoped, single-flight cache for loaded discovery/search/browse content.
 *
 * The cache is deliberately infrastructure-only. It does not fetch anything itself: a caller
 * supplies [accountScopeProvider] (the current account identity) and a [loader] per request. The
 * properties it preserves for callers are:
 *
 * - **Caching** — a successful value is retained for the current account and key, evicted in LRU
 *   order once [maxEntries] is exceeded. Repeated identical requests reuse the cached value.
 * - **Single-flight** — concurrent requests for the same account and key share one loader. Each
 *   waiter observes the same value or the same failure. When the last waiter is cancelled before
 *   the loader finishes, the loader is cancelled instead of continuing unobserved.
 * - **Cancellation** — cancellation is always rethrown, never converted into a cached failure. A
 *   cancelled request is never cached, and [close] cancels all in-flight loaders and drops state.
 * - **Account isolation** — every cache key includes the account scope returned by
 *   [accountScopeProvider] at request time, so content loaded under one account can never be
 *   returned under another. If the provider reports a different account before an in-flight
 *   loader finishes, that result is discarded and waiters receive a [CancellationException].
 *
 * Registration, lookup and invalidation are safe from any thread. Suspension points never run
 * while the internal lock is held.
 */
class DiscoveryContentCache<T : Any>(
    private val accountScopeProvider: () -> String,
    private val maxEntries: Int = 64,
) : AutoCloseable {
    init {
        require(maxEntries > 0) { "Discovery cache must hold at least one entry" }
    }

    private data class Composite(
        val accountKey: String,
        val key: String,
    )

    private class Flight<T>(
        val composite: Composite,
        val accountKey: String,
        val deferred: CompletableDeferred<T>,
        val job: Job,
        val waiters: AtomicInteger = AtomicInteger(0),
    )

    private val lock = Any()
    private val entries =
        object : LinkedHashMap<Composite, T>(maxEntries + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Composite, T>?): Boolean = size > maxEntries
        }
    private val inFlight = HashMap<Composite, Flight<T>>()
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closed = false

    /** Number of cached values currently retained across all accounts. */
    val size: Int
        get() = synchronized(lock) { entries.size }

    /** True when a value is cached for [key] under the current account. */
    fun contains(key: String): Boolean {
        val composite = Composite(accountScopeProvider(), key)
        return synchronized(lock) { entries.containsKey(composite) }
    }

    /** True when a loader for [key] is currently running under the current account. */
    fun isInFlight(key: String): Boolean {
        val composite = Composite(accountScopeProvider(), key)
        return synchronized(lock) { inFlight.containsKey(composite) }
    }

    /** Returns a cached value for [key] under the current account without starting a load. */
    fun peek(key: String): T? {
        val composite = Composite(accountScopeProvider(), key)
        return synchronized(lock) { entries[composite] }
    }

    /**
     * Returns the cached value for [key], or runs [loader] once and caches its result.
     * Concurrent callers with the same account and key coalesce onto a single loader.
     */
    suspend fun getOrLoad(
        key: String,
        loader: suspend () -> T,
    ): T {
        val account = accountScopeProvider()
        val composite = Composite(account, key)
        synchronized(lock) {
            check(!closed) { "DiscoveryContentCache is closed" }
            entries[composite]?.let { return it }
        }

        val flight: Flight<T>
        synchronized(lock) {
            check(!closed) { "DiscoveryContentCache is closed" }
            entries[composite]?.let { return it }
            flight =
                inFlight[composite] ?: run {
                    val deferred = CompletableDeferred<T>()
                    lateinit var created: Flight<T>
                    val job = loaderScope.launch(start = CoroutineStart.LAZY) { runLoad(created, loader) }
                    created = Flight(composite, account, deferred, job)
                    inFlight[composite] = created
                    created
                }
            flight.waiters.incrementAndGet()
            flight.job.start()
        }

        return try {
            flight.deferred.await()
        } finally {
            releaseWaiter(flight)
        }
    }

    private suspend fun runLoad(
        flight: Flight<T>,
        loader: suspend () -> T,
    ) {
        try {
            val value = loader()
            currentCoroutineContext().ensureActive()
            val accepted = synchronized(lock) {
                if (!closed && accountScopeProvider() == flight.accountKey && inFlight[flight.composite] === flight) {
                    inFlight.remove(flight.composite)
                    entries[flight.composite] = value
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                flight.deferred.cancel(CancellationException("Discovery request was invalidated"))
            } else {
                flight.deferred.complete(value)
            }
        } catch (cancellation: CancellationException) {
            synchronized(lock) { if (inFlight[flight.composite] === flight) inFlight.remove(flight.composite) }
            flight.deferred.cancel(cancellation)
        } catch (error: Throwable) {
            synchronized(lock) { if (inFlight[flight.composite] === flight) inFlight.remove(flight.composite) }
            flight.deferred.completeExceptionally(error)
        }
    }

    private fun releaseWaiter(flight: Flight<T>) {
        val cancel = synchronized(lock) {
            flight.waiters.decrementAndGet() == 0 && !flight.deferred.isCompleted &&
                inFlight.remove(flight.composite, flight)
        }
        if (cancel) {
            flight.deferred.cancel()
            flight.job.cancel()
        }
    }

    /** Drops the value and any in-flight loader for [key] under the current account. */
    fun invalidate(key: String) {
        val composite = Composite(accountScopeProvider(), key)
        synchronized(lock) {
            entries.remove(composite)
            inFlight.remove(composite)?.let { flight ->
                flight.deferred.cancel()
                flight.job.cancel()
            }
        }
    }

    /** Drops all cached values and in-flight loaders belonging to [accountKey]. */
    fun invalidateAccount(accountKey: String) {
        synchronized(lock) {
            val entryIterator = entries.keys.iterator()
            while (entryIterator.hasNext()) {
                if (entryIterator.next().accountKey == accountKey) entryIterator.remove()
            }
            val flights = inFlight.values.filter { it.accountKey == accountKey }
            flights.forEach { flight ->
                inFlight.remove(flight.composite)
                flight.deferred.cancel()
                flight.job.cancel()
            }
        }
    }

    /** Cancels all owned loaders and clears cached state. Idempotent. */
    override fun close() {
        val flights: Collection<Flight<T>>
        synchronized(lock) {
            if (closed) return
            closed = true
            flights = inFlight.values.toList()
            inFlight.clear()
            entries.clear()
        }
        flights.forEach { flight ->
            flight.deferred.cancel()
            flight.job.cancel()
        }
        loaderScope.cancel()
    }
}
