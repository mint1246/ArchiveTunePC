/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.innertube.cache

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class DesktopRequestPriority {
    FOREGROUND,
    REFRESH,
    PREFETCH,
    MAINTENANCE,
}

data class DesktopRequestMetrics(
    val started: Long,
    val coalesced: Long,
    val memoryHits: Long,
    val diskHits: Long,
    val misses: Long,
)

data class DesktopPersistentCacheValue(
    val payload: String,
    val storedAtEpochMillis: Long,
)

/** Small, explicitly bounded application cache plus single-flight request coordinator. */
class DesktopResourceCoordinator(
    private val cacheDirectory: Path,
    private val maxMemoryEntries: Int = 32,
    maxBackgroundRequests: Int = 2,
    private val clock: Clock = Clock.systemUTC(),
    private val diagnostic: (String) -> Unit = {},
) {
    private data class MemoryEntry(val value: Any, val storedAtEpochMillis: Long)

    private val memoryLock = Any()
    private val memory = object : LinkedHashMap<String, MemoryEntry>(maxMemoryEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemoryEntry>?): Boolean =
            size > maxMemoryEntries
    }
    private val flightLock = Any()
    private val inFlight = HashMap<String, CompletableDeferred<Result<Any>>>()
    private val backgroundPermits = Semaphore(maxBackgroundRequests.coerceAtLeast(1))
    private val started = AtomicLong()
    private val coalesced = AtomicLong()
    private val memoryHits = AtomicLong()
    private val diskHits = AtomicLong()
    private val misses = AtomicLong()

    init {
        require(maxMemoryEntries > 0) { "Desktop memory cache must have at least one entry" }
        Files.createDirectories(cacheDirectory)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> memoryValue(key: String): Pair<T, Long>? = synchronized(memoryLock) {
        val entry = memory[key]
        if (entry == null) {
            misses.incrementAndGet()
            null
        } else {
            memoryHits.incrementAndGet()
            (entry.value as? T)?.let { it to entry.storedAtEpochMillis }
        }
    }

    fun <T : Any> putMemory(key: String, value: T, storedAtEpochMillis: Long = clock.millis()) {
        synchronized(memoryLock) { memory[key] = MemoryEntry(value, storedAtEpochMillis) }
    }

    fun invalidateMemory(prefix: String) {
        synchronized(memoryLock) { memory.keys.removeIf { it.startsWith(prefix) } }
    }

    suspend fun readPersistent(key: String): DesktopPersistentCacheValue? = withContext(Dispatchers.IO) {
        val file = cacheFile(key)
        if (!Files.isRegularFile(file)) return@withContext null
        runCatching {
            val json = JSONObject(Files.readString(file))
            DesktopPersistentCacheValue(
                payload = json.getString("payload"),
                storedAtEpochMillis = json.getLong("storedAt"),
            )
        }.onSuccess { diskHits.incrementAndGet() }
            .onFailure {
                runCatching { Files.deleteIfExists(file) }
                diagnostic("content_cache event=corrupt key=${safeDiagnosticKey(key)}")
            }
            .getOrNull()
    }

    suspend fun writePersistent(
        key: String,
        payload: String,
        storedAtEpochMillis: Long = clock.millis(),
    ) = withContext(Dispatchers.IO) {
        Files.createDirectories(cacheDirectory)
        val target = cacheFile(key)
        val temporary = Files.createTempFile(cacheDirectory, ".content-cache.", ".tmp")
        try {
            Files.writeString(
                temporary,
                JSONObject().put("storedAt", storedAtEpochMillis).put("payload", payload).toString(),
            )
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    suspend fun deletePersistent(key: String) = withContext(Dispatchers.IO) {
        Files.deleteIfExists(cacheFile(key))
    }

    /** Coalesces simultaneous requests for the exact same account-scoped resource key. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> singleFlight(
        key: String,
        priority: DesktopRequestPriority,
        block: suspend () -> Result<T>,
    ): Result<T> {
        var owner = false
        val deferred = synchronized(flightLock) {
            inFlight[key]?.also {
                coalesced.incrementAndGet()
                diagnostic("request event=coalesced priority=${priority.name.lowercase()} key=${safeDiagnosticKey(key)}")
            } ?: CompletableDeferred<Result<Any>>().also {
                inFlight[key] = it
                owner = true
                started.incrementAndGet()
            }
        }
        if (!owner) return deferred.await() as Result<T>

        try {
            val result =
                if (priority == DesktopRequestPriority.FOREGROUND) {
                    block()
                } else {
                    backgroundPermits.withPermit { block() }
                }
            deferred.complete(result as Result<Any>)
            diagnostic(
                "request event=completed priority=${priority.name.lowercase()} result=${if (result.isSuccess) "success" else "failure"} key=${safeDiagnosticKey(key)}",
            )
            return result
        } catch (cancellation: CancellationException) {
            deferred.completeExceptionally(cancellation)
            throw cancellation
        } catch (failure: Throwable) {
            val result = Result.failure<T>(failure)
            deferred.complete(result as Result<Any>)
            return result
        } finally {
            synchronized(flightLock) { inFlight.remove(key, deferred) }
        }
    }

    fun metrics(): DesktopRequestMetrics = DesktopRequestMetrics(
        started = started.get(),
        coalesced = coalesced.get(),
        memoryHits = memoryHits.get(),
        diskHits = diskHits.get(),
        misses = misses.get(),
    )

    private fun cacheFile(key: String): Path = cacheDirectory.resolve("${sha256(key)}.json")

    private fun safeDiagnosticKey(key: String): String = key.substringBefore(':').take(48)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}