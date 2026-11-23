/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SdkLifecycleTest {
    @Test
    fun `parent cancellation reaches child scope`() {
        val parent = SupervisorJob()
        val lifecycle = SdkLifecycle(parent + Dispatchers.Default)
        val child = lifecycle.scope()

        parent.cancel()

        assertTrue(child.coroutineContext[Job]!!.isCancelled)
        lifecycle.close()
    }

    @Test
    fun `scope cancellation precedes hooks and components`() {
        val lifecycle = SdkLifecycle(Dispatchers.Default)
        val scope = lifecycle.scope()
        lifecycle.register(AutoCloseable { assertTrue(scope.coroutineContext[Job]!!.isCancelled) })
        lifecycle.onClosing("check") { assertTrue(scope.coroutineContext[Job]!!.isCancelled) }

        lifecycle.close()
    }

    private class Recorder(
        private val name: String,
        private val events: MutableList<String>,
        private val failure: Throwable? = null,
    ) : Closeable {
        val closeCount = AtomicInteger()

        override fun close() {
            closeCount.incrementAndGet()
            events.add(name)
            failure?.let { throw it }
        }
    }

    @Test
    fun `runs hooks before components and closes in reverse registration order`() {
        val events = mutableListOf<String>()
        val lifecycle = SdkLifecycle(SupervisorJob())
        val first = Recorder("first", events)
        val second = Recorder("second", events)

        lifecycle.register(first)
        lifecycle.onClosing("hook-a") { events.add("hook-a") }
        lifecycle.register(second)
        lifecycle.onClosing("hook-b") { events.add("hook-b") }

        lifecycle.close()

        assertEquals(listOf("hook-b", "hook-a", "second", "first"), events)
        assertFalse(lifecycle.isClosed.not())
    }

    @Test
    fun `close is idempotent and never closes a component twice`() {
        val events = mutableListOf<String>()
        val lifecycle = SdkLifecycle(SupervisorJob())
        val component = Recorder("only", events)
        lifecycle.register(component)

        lifecycle.close()
        lifecycle.close()
        lifecycle.close()

        assertEquals(1, component.closeCount.get())
        assertEquals(listOf("only"), events)
    }

    @Test
    fun `cancels child scopes on close and rejects scope creation afterwards`() = runBlocking {
        val lifecycle = SdkLifecycle(Dispatchers.Default)
        val scope = lifecycle.scope()
        val started = CountDownLatch(1)
        val job: Job =
            scope.launch {
                started.countDown()
                delay(60_000L)
            }
        started.await()

        lifecycle.close()

        job.join()
        assertTrue(job.isCancelled)
        assertFailsWith<IllegalStateException> { lifecycle.scope() }
        Unit
    }

    @Test
    fun `a failing child does not cancel a sibling scope before close`() = runBlocking {
        val lifecycle = SdkLifecycle(Dispatchers.Default)
        val failingScope = lifecycle.scope()
        val siblingScope = lifecycle.scope()
        val siblingCompleted = CountDownLatch(1)

        failingScope.launch { error("component failure") }.join()
        siblingScope.launch { siblingCompleted.countDown() }

        assertTrue(siblingCompleted.await(1, TimeUnit.SECONDS))
        lifecycle.close()
    }

    @Test
    fun `continues shutdown after failures and suppresses later failures`() {
        val lifecycle = SdkLifecycle(SupervisorJob())
        val firstFailure = IllegalStateException("first")
        val secondFailure = IllegalArgumentException("second")
        val events = mutableListOf<String>()
        val first = Recorder("first", events, firstFailure)
        val second = Recorder("second", events, secondFailure)
        val third = Recorder("third", events)
        lifecycle.register(first)
        lifecycle.register(second)
        lifecycle.register(third)

        val thrown = assertFailsWith<IllegalArgumentException> { lifecycle.close() }

        assertSame(secondFailure, thrown)
        assertEquals(listOf(firstFailure), thrown.suppressed.toList())
        assertEquals(listOf("third", "second", "first"), events)
        assertEquals(1, third.closeCount.get())
    }

    @Test
    fun `ignores cancellation exceptions from component cleanup`() {
        val lifecycle = SdkLifecycle(SupervisorJob())
        lifecycle.register(Recorder("cancelled", mutableListOf(), CancellationException("shutting down")))

        lifecycle.close()
    }

    @Test
    fun `registration after close closes the component immediately`() {
        val events = mutableListOf<String>()
        val lifecycle = SdkLifecycle(SupervisorJob())
        lifecycle.close()
        val late = Recorder("late", events)

        assertSame(late, lifecycle.register(late))

        assertEquals(1, late.closeCount.get())
        assertEquals(listOf("late"), events)
        assertTrue(lifecycle.isClosed)
    }

    @Test
    fun `duplicate hook names keep the first callback`() {
        val lifecycle = SdkLifecycle(SupervisorJob())
        val calls = AtomicInteger()
        lifecycle.onClosing("same") { calls.incrementAndGet() }
        lifecycle.onClosing("same") { calls.incrementAndGet() }

        lifecycle.close()

        assertEquals(1, calls.get())
    }

    @Test
    fun `unregister transfers ownership away from the lifecycle`() {
        val lifecycle = SdkLifecycle(SupervisorJob())
        val component = Recorder("owned-elsewhere", mutableListOf())
        lifecycle.register(component)

        assertTrue(lifecycle.unregister(component))
        assertFalse(lifecycle.unregister(component))
        lifecycle.close()

        assertEquals(0, component.closeCount.get())
    }

    @Test
    fun `concurrent registration and close close each component exactly once`() {
        val lifecycle = SdkLifecycle(SupervisorJob())
        val registered =
            Collections.synchronizedList(
                mutableListOf<Recorder>(),
            )
        val start = CountDownLatch(1)
        val threads =
            (1..8).map { index ->
                Thread {
                    start.await()
                    repeat(50) { attempt ->
                        val recorder = Recorder("t$index-$attempt", mutableListOf())
                        registered.add(recorder)
                        lifecycle.register(recorder)
                    }
                }.also { it.start() }
            }
        start.countDown()
        lifecycle.close()
        threads.forEach { it.join(10_000L) }

        assertEquals(400, registered.size)
        registered.forEach { assertEquals(1, it.closeCount.get(), "closed exactly once: ${it.closeCount.get()}") }
        assertTrue(lifecycle.isClosed)
    }

    @Test
    fun `blanks are rejected for hook names`() {
        val lifecycle = SdkLifecycle(SupervisorJob())
        assertFailsWith<IllegalArgumentException> { lifecycle.onClosing(" ") { } }
        lifecycle.close()
    }

    @Test
    fun `forScope leaves the parent scope active after close`() = runBlocking {
        val parent = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val lifecycle = SdkLifecycle.forScope(parent)
        val child = lifecycle.scope()

        lifecycle.close()

        assertTrue(child.coroutineContext[Job]!!.isCancelled)
        assertFalse(parent.coroutineContext[Job]!!.isCancelled)
        parent.cancel()
    }

    @Test
    fun `lifecycle can be constructed with a plain empty context`() {
        val lifecycle = SdkLifecycle(CoroutineScope(Dispatchers.Default).coroutineContext)
        val component = Recorder("plain", mutableListOf())
        lifecycle.register(component)
        lifecycle.close()
        assertEquals(1, component.closeCount.get())
    }
}
