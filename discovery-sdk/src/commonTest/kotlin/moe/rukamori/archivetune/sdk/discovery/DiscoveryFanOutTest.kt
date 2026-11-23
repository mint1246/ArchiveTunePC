/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiscoveryFanOutTest {
    @Test
    fun flattens_results_in_input_order() =
        runTest {
            val fanOut = DiscoveryFanOut(batchSize = 3)
            val result =
                fanOut.flatMapBatched(listOf(1, 2, 3, 4, 5)) { value ->
                    listOf("$value-a", "$value-b")
                }
            assertEquals(
                listOf("1-a", "1-b", "2-a", "2-b", "3-a", "3-b", "4-a", "4-b", "5-a", "5-b"),
                result,
            )
        }

    @Test
    fun returns_an_empty_list_for_empty_input_without_loading() =
        runTest {
            var loads = 0
            val result =
                DiscoveryFanOut().flatMapBatched(emptyList<Int>()) { value ->
                    loads++
                    listOf(value)
                }
            assertTrue(result.isEmpty())
            assertEquals(0, loads)
        }

    @Test
    fun propagates_cancellation_from_a_child_load() =
        runTest {
            val fanOut = DiscoveryFanOut()
            assertFailsWith<CancellationException> {
                fanOut.flatMapBatched(listOf(1, 2, 3)) { value ->
                    if (value == 2) throw CancellationException("cancelled") else listOf(value)
                }
            }
        }

    @Test
    fun best_effort_mode_swallows_ordinary_failures_but_not_cancellation() =
        runTest {
            val fanOut = DiscoveryFanOut()
            val result =
                fanOut.flatMapBatchedBestEffort(listOf(1, 2, 3)) { value ->
                    when (value) {
                        2 -> throw IllegalStateException("failed")
                        else -> listOf(value)
                    }
                }
            assertEquals(listOf(1, 3), result)

            assertFailsWith<CancellationException> {
                fanOut.flatMapBatchedBestEffort(listOf(1, 2)) { value ->
                    if (value == 2) throw CancellationException("cancelled") else listOf(value)
                }
            }
        }

    @Test
    fun limits_in_flight_work_to_the_configured_batch_size() =
        runTest {
            val fanOut = DiscoveryFanOut(batchSize = 2)
            val release = CompletableDeferred<Unit>()
            var started = 0
            var finished = 0

            val job =
                async {
                    fanOut.flatMapBatched(listOf(1, 2, 3, 4, 5)) { value ->
                        started++
                        release.await()
                        finished++
                        listOf(value)
                    }
                }
            // Let the first batch start; it must not exceed two concurrent loads.
            while (started < 2) yield()
            assertEquals(2, started)
            assertEquals(0, finished)

            release.complete(Unit)
            assertEquals(listOf(1, 2, 3, 4, 5), job.await())
            assertEquals(5, started)
        }
}
