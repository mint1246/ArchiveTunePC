package moe.rukamori.archivetune.playback.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LatestLoadJobOwnerTest {
    @Test
    fun `rapid A to B cancels A and only B applies`() = runTest {
        val aStarted = CompletableDeferred<Unit>()
        val aCancelled = CompletableDeferred<Unit>()
        val harness = LoadHarness(this) { request ->
            if (request.id == "A") {
                aStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    aCancelled.complete(Unit)
                }
            }
            "resolved-${request.id}"
        }

        harness.load("A")
        runCurrent()
        assertTrue(aStarted.isCompleted)

        harness.load("B")
        advanceUntilIdle()

        assertTrue(aCancelled.isCompleted)
        assertEquals(listOf("resolved-B"), harness.applied)
        assertTrue(harness.playbackErrors.isEmpty())
        harness.close()
    }

    @Test
    fun `resolver ignoring cancellation can finish A late but cannot overwrite B`() = runTest {
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val harness = LoadHarness(this) { request ->
            if (request.id == "A") {
                aStarted.complete(Unit)
                withContext(NonCancellable) { releaseA.await() }
            }
            "resolved-${request.id}"
        }

        harness.load("A")
        runCurrent()
        harness.load("B")
        runCurrent()
        assertEquals(listOf("resolved-B"), harness.applied)

        releaseA.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("resolved-B"), harness.applied)
        assertTrue(harness.playbackErrors.isEmpty())
        harness.close()
    }

    @Test
    fun `same current recovery replacement supersedes original load`() = runTest {
        val originalStarted = CompletableDeferred<Unit>()
        val originalCancelled = CompletableDeferred<Unit>()
        var attempts = 0
        val harness = LoadHarness(this) { request ->
            attempts++
            if (attempts == 1) {
                originalStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    originalCancelled.complete(Unit)
                }
            }
            "resolved-${request.id}-attempt-$attempts"
        }

        harness.load("same-item")
        runCurrent()
        assertTrue(originalStarted.isCompleted)

        // Mirrors prepareReloadLocked: the queue item stays the same, but recovery owns a new generation.
        harness.load("same-item")
        advanceUntilIdle()

        assertTrue(originalCancelled.isCompleted)
        assertEquals(listOf("resolved-same-item-attempt-2"), harness.applied)
        assertEquals(2, attempts)
        assertTrue(harness.playbackErrors.isEmpty())
        harness.close()
    }

    @Test
    fun `close cancels the in flight load`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val harness = LoadHarness(this) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        harness.load("A")
        runCurrent()
        assertTrue(started.isCompleted)
        harness.close()
        advanceUntilIdle()

        assertTrue(cancelled.isCompleted)
        assertTrue(harness.applied.isEmpty())
        assertTrue(harness.playbackErrors.isEmpty())
    }

    @Test
    fun `stale submit cannot cancel the current generation`() = runTest {
        val releaseB = CompletableDeferred<Unit>()
        val bCancelled = CompletableDeferred<Unit>()
        val harness = LoadHarness(this) { request ->
            if (request.id == "B") {
                try {
                    releaseB.await()
                } catch (cancelled: CancellationException) {
                    bCancelled.complete(Unit)
                    throw cancelled
                }
            }
            "resolved-${request.id}"
        }

        harness.load("A")
        val staleA = harness.lastRequest
        harness.load("B")
        runCurrent()
        harness.submitStale(staleA)
        runCurrent()

        assertFalse(bCancelled.isCompleted)
        releaseB.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("resolved-B"), harness.applied)
        harness.close()
    }

    private data class Request(val generation: Long, val id: String)

    /** Minimal mirror of the controller's generation guard around the production job owner. */
    private class LoadHarness(
        scope: TestScope,
        private val resolver: suspend (Request) -> String,
    ) : AutoCloseable {
        private var generation = 0L
        lateinit var lastRequest: Request
            private set
        val applied = mutableListOf<String>()
        val playbackErrors = mutableListOf<String>()
        private val owner = LatestLoadJobOwner(
            scope = scope,
            accept = { request: Request -> request.generation == generation },
            execute = execute@ { request ->
                val resolved = try {
                    resolver(request)
                } catch (cancelled: CancellationException) {
                    // Same rule as VlcjPlaybackController.executeLoad: cancellation is never a playback failure.
                    throw cancelled
                } catch (error: Throwable) {
                    if (request.generation == generation) playbackErrors += error.message.orEmpty()
                    return@execute
                }
                // Same post-suspension guard as executeLoad/isCurrentLocked.
                if (request.generation == generation) applied += resolved
            },
        )

        fun load(id: String) {
            generation++
            owner.cancelCurrent()
            lastRequest = Request(generation, id)
            owner.submit(lastRequest)
        }

        fun submitStale(request: Request) {
            owner.submit(request)
        }

        override fun close() = owner.close()
    }
}
