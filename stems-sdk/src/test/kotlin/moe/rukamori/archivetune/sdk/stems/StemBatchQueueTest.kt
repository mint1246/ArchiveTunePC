/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.stems

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StemBatchQueueTest {
    private data class Req(val id: String, val title: String?)

    @Test
    fun queueDedupesRequests_skipsReady_andRunsOneGeneratorAtATime() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val generated = Collections.synchronizedList(mutableListOf<String>())
        var inFlight = 0
        var maxInFlight = 0
        val ready = setOf("cached")
        val queue =
            StemBatchQueue<Req>(
                scope = scope,
                trackIdOf = Req::id,
                titleOf = Req::title,
                isReady = { it.id in ready },
                generate = { request, progress ->
                    synchronized(generated) {
                        inFlight++
                        maxInFlight = maxOf(maxInFlight, inFlight)
                    }
                    progress(StemBatchProgress(0.5f, "working"))
                    delay(20)
                    generated += request.id
                    synchronized(generated) { inFlight-- }
                },
            )
        try {
            val accepted = queue.enqueue(listOf(Req("a", "A"), Req("a", "A"), Req("cached", "C"), Req("b", "B")))
            assertEquals(3, accepted)

            val final =
                withTimeout(5_000) {
                    queue.state.first { state -> !state.active && state.completed == 2 }
                }
            assertEquals(listOf("a", "b"), generated.toList())
            assertEquals(1, maxInFlight)
            assertEquals(0, final.failed)
            assertFalse(final.active)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun failedGeneration_andCancel_updateStateCleanly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val startedFirst = CompletableDeferred<Unit>()
        val queue =
            StemBatchQueue<Req>(
                scope = scope,
                trackIdOf = Req::id,
                titleOf = Req::title,
                isReady = { false },
                generate = { request, _ ->
                    if (request.id == "bad") error("provider failed")
                    if (request.id == "hang") {
                        startedFirst.complete(Unit)
                        delay(10_000)
                    }
                },
            )
        try {
            queue.enqueue(listOf(Req("bad", "Bad"), Req("good", "Good")))
            val final =
                withTimeout(5_000) {
                    queue.state.first { state -> !state.active && state.completed + state.failed == 2 }
                }
            assertEquals(1, final.completed)
            assertEquals(1, final.failed)
            assertEquals("provider failed", final.lastError)
            assertTrue(final.pending == 0)

            queue.enqueue(listOf(Req("hang", "Hang"), Req("queued", "Queued")))
            withTimeout(5_000) { startedFirst.await() }
            queue.cancel()
            assertFalse(queue.state.value.active)
            assertEquals(0, queue.state.value.pending)
        } finally {
            queue.close()
            scope.cancel()
        }
    }
}
