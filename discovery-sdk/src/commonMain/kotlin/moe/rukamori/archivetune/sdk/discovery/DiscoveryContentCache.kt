/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Bounded, account-scoped, single-flight cache for loaded discovery/search/browse content.
 */
class DiscoveryContentCache<T : Any>(
    private val accountScopeProvider: () -> String,
    private val maxEntries: Int = 64,
    loaderContext: CoroutineContext = discoveryIoDispatcher,
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
        val waiters: DiscoveryAtomicInt = DiscoveryAtomicInt(0),
    )

    private val lock = DiscoveryLock()
    private val entries = LinkedHashMap<Composite, T>()
    private val inFlight = HashMap<Composite, Flight<T>>()
    private val loaderScope = CoroutineScope(loaderContext.minusKey(Job) + SupervisorJob())

    @Volatile
    private var closed = false

    val size: Int
        get() = lock.withLock { entries.size }

    fun contains(key: String): Boolean {
        val composite = Composite(accountScopeProvider(), key)
        return lock.withLock { entries.containsKey(composite) }
    }

    fun isInFlight(key: String): Boolean {
        val composite = Composite(accountScopeProvider(), key)
        return lock.withLock { inFlight.containsKey(composite) }
    }

    fun peek(key: String): T? {
        val composite = Composite(accountScopeProvider(), key)
        return lock.withLock { getLruLocked(composite) }
    }

    suspend fun getOrLoad(
        key: String,
        loader: suspend () -> T,
    ): T {
        val account = accountScopeProvider()
        val composite = Composite(account, key)
        val immediateHit = lock.withLock {
            check(!closed) { "DiscoveryContentCache is closed" }
            getLruLocked(composite)
        }
        if (immediateHit != null) return immediateHit

        var cachedHit: T? = null
        val flight: Flight<T>? = lock.withLock {
            check(!closed) { "DiscoveryContentCache is closed" }
            val hit = getLruLocked(composite)
            if (hit != null) {
                cachedHit = hit
                null
            } else {
                val current = inFlight[composite] ?: run {
                    val deferred = CompletableDeferred<T>()
                    lateinit var created: Flight<T>
                    val job = loaderScope.launch(start = CoroutineStart.LAZY) { runLoad(created, loader) }
                    created = Flight(composite, account, deferred, job)
                    inFlight[composite] = created
                    created
                }
                current.waiters.incrementAndGet()
                current.job.start()
                current
            }
        }
        cachedHit?.let { return it }
        val activeFlight = flight!!

        return try {
            activeFlight.deferred.await()
        } finally {
            releaseWaiter(activeFlight)
        }
    }

    private suspend fun runLoad(
        flight: Flight<T>,
        loader: suspend () -> T,
    ) {
        try {
            val value = loader()
            currentCoroutineContext().ensureActive()
            val accepted = lock.withLock {
                if (!closed && accountScopeProvider() == flight.accountKey && inFlight[flight.composite] === flight) {
                    inFlight.remove(flight.composite)
                    putLruLocked(flight.composite, value)
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
            lock.withLock { if (inFlight[flight.composite] === flight) inFlight.remove(flight.composite) }
            flight.deferred.cancel(cancellation)
        } catch (error: Throwable) {
            lock.withLock { if (inFlight[flight.composite] === flight) inFlight.remove(flight.composite) }
            flight.deferred.completeExceptionally(error)
        }
    }

    private fun releaseWaiter(flight: Flight<T>) {
        val cancel = lock.withLock {
            if (flight.waiters.decrementAndGet() == 0 && !flight.deferred.isCompleted && inFlight[flight.composite] === flight) {
                inFlight.remove(flight.composite)
                true
            } else {
                false
            }
        }
        if (cancel) {
            flight.deferred.cancel()
            flight.job.cancel()
        }
    }

    fun invalidate(key: String) {
        val composite = Composite(accountScopeProvider(), key)
        val flightToCancel = lock.withLock {
            entries.remove(composite)
            inFlight.remove(composite)
        }
        flightToCancel?.let { flight ->
            flight.deferred.cancel()
            flight.job.cancel()
        }
    }

    fun invalidateAccount(accountKey: String) {
        val flightsToCancel = lock.withLock {
            val keysToRemove = entries.keys.filter { it.accountKey == accountKey }
            keysToRemove.forEach { entries.remove(it) }
            val flights = inFlight.values.filter { it.accountKey == accountKey }
            flights.forEach { flight -> inFlight.remove(flight.composite) }
            flights
        }
        flightsToCancel.forEach { flight ->
            flight.deferred.cancel()
            flight.job.cancel()
        }
    }

    override fun close() {
        val flights: Collection<Flight<T>> = lock.withLock {
            if (closed) {
                emptyList()
            } else {
                closed = true
                val current = inFlight.values.toList()
                inFlight.clear()
                entries.clear()
                current
            }
        }
        flights.forEach { flight ->
            flight.deferred.cancel()
            flight.job.cancel()
        }
        loaderScope.cancel()
    }

    private fun getLruLocked(composite: Composite): T? {
        val existing = entries.remove(composite) ?: return null
        entries[composite] = existing
        return existing
    }

    private fun putLruLocked(composite: Composite, value: T) {
        entries.remove(composite)
        entries[composite] = value
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }
}
