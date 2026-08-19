package moe.rukamori.archivetune.innertube.cache

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopResourceCoordinatorTest {
    @Test
    fun `simultaneous identical requests are coalesced`() = runBlocking {
        val coordinator = DesktopResourceCoordinator(Files.createTempDirectory("archivetune-content-cache"))
        val calls = AtomicInteger()

        val results = coroutineScope {
            val first = async {
                coordinator.singleFlight("home:account-a", DesktopRequestPriority.REFRESH) {
                    calls.incrementAndGet()
                    delay(80)
                    Result.success("home")
                }
            }
            delay(10)
            val second = async {
                coordinator.singleFlight("home:account-a", DesktopRequestPriority.REFRESH) {
                    calls.incrementAndGet()
                    Result.success("should-not-run")
                }
            }
            listOf(first.await().getOrThrow(), second.await().getOrThrow())
        }

        assertEquals(listOf("home", "home"), results)
        assertEquals(1, calls.get())
        assertEquals(1, coordinator.metrics().coalesced)
    }

    @Test
    fun `memory cache is explicitly bounded`() {
        val coordinator = DesktopResourceCoordinator(
            cacheDirectory = Files.createTempDirectory("archivetune-content-cache"),
            maxMemoryEntries = 2,
        )

        coordinator.putMemory("one", "1")
        coordinator.putMemory("two", "2")
        assertEquals("1", coordinator.memoryValue<String>("one")?.first)
        coordinator.putMemory("three", "3")

        assertTrue(coordinator.memoryValue<String>("two") == null)
        assertEquals("1", coordinator.memoryValue<String>("one")?.first)
        assertEquals("3", coordinator.memoryValue<String>("three")?.first)
    }
}