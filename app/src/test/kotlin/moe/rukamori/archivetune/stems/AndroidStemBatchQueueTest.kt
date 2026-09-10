/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import androidx.media3.common.MediaItem
import java.util.Collections
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

class AndroidStemBatchQueueTest {
    @Test
    fun queueDedupesRequests_skipsReady_andRunsOneGeneratorAtATime() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val generated = Collections.synchronizedList(mutableListOf<String>())
        var inFlight = 0
        var maxInFlight = 0
        val ready = setOf("cached")
        val queue =
            AndroidStemBatchQueue(
                scope = scope,
                isReady = { it.trackId in ready },
                generate = { request, progress ->
                    synchronized(generated) {
                        inFlight++
                        maxInFlight = maxOf(maxInFlight, inFlight)
                    }
                    progress(AndroidStemSeparationProgress(AndroidStemSeparationStage.SEPARATING, 0.5f, "working"))
                    delay(20)
                    generated += request.trackId
                    synchronized(generated) { inFlight-- }
                },
            )
        try {
            val accepted = queue.enqueue(listOf(request("a"), request("a"), request("cached"), request("b")))
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
    fun failedGeneration_isReportedWithoutPretendingItIsReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue =
            AndroidStemBatchQueue(
                scope = scope,
                isReady = { false },
                generate = { request, _ ->
                    if (request.trackId == "bad") error("provider failed")
                },
            )
        try {
            queue.enqueue(listOf(request("bad"), request("good")))
            val final =
                withTimeout(5_000) {
                    queue.state.first { state -> !state.active && state.completed + state.failed == 2 }
                }
            assertEquals(1, final.completed)
            assertEquals(1, final.failed)
            assertEquals("provider failed", final.lastError)
            assertTrue(final.pending == 0)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    private fun request(id: String) =
        AndroidStemBatchRequest(
            mediaItem = MediaItem.Builder().setMediaId(id).build(),
            trackId = id,
            durationMs = 180_000L,
            title = id,
        )
}
