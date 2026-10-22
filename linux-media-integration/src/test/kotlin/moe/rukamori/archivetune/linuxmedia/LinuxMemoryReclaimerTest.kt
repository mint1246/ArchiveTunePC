package moe.rukamori.archivetune.linuxmedia

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class LinuxMemoryReclaimerTest {
    @Test fun `real allocator returns freed pages while preserving live allocations`() {
        assumeTrue(System.getenv("ARCHIVETUNE_MEMORY_SMOKE") == "1", "Opt-in native allocator smoke")
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val libc = NativeLibrary.getInstance("c")
        assumeTrue(runCatching { libc.getFunction("malloc_trim") }.isSuccess, "Requires glibc")
        val malloc = libc.getFunction("malloc")
        val free = libc.getFunction("free")
        val live = mutableListOf<Pointer>()
        fun rssKb() = Files.readAllLines(Path.of("/proc/self/status"))
            .first { it.startsWith("VmRSS:") }.trim().split(Regex("\\s+"))[1].toLong()
        try {
            repeat(4096) {
                val pointer = requireNotNull(malloc.invokePointer(arrayOf<Any>(32_768L)))
                live.add(pointer)
                pointer.setMemory(0, 32_768L, 0x5a.toByte())
            }
            // Leave live blocks between freed ones, so automatic top-of-heap trimming cannot
            // release the holes. malloc_trim should release those pages without touching survivors.
            val holes = live.filterIndexed { index, _ -> index % 2 == 0 }
            holes.forEach { free.invokeVoid(arrayOf<Any>(it)) }
            live.removeAll(holes.toSet())
            val before = rssKb()
            assertTrue(LinuxMemoryReclaimer().trimCurrentProcess())
            val after = rssKb()
            live.forEach { assertEquals(0x5a.toByte(), it.getByte(0)) }
            println("Allocator smoke: RSS before_kib=$before after_kib=$after live_blocks=${live.size}")
            assertTrue(before - after > 16_384L, "Expected freed allocator pages to return to Linux")
        } finally { live.forEach { free.invokeVoid(arrayOf<Any>(it)) } }
    }

    @Test fun `other operating systems never call the Linux allocator`() {
        val reclaimer = LinuxMemoryReclaimer("Windows 11", { error("unexpected allocator call") }, { 0 })
        assertFalse(reclaimer.trimCurrentProcess())
    }

    @Test fun `overlapping lifecycle requests are coalesced and resume after cooldown`() {
        var calls = 0
        var now = 0L
        val reclaimer = LinuxMemoryReclaimer("Linux", { calls++; true }, { now })
        assertTrue(reclaimer.trimCurrentProcess())
        val executor = Executors.newFixedThreadPool(4)
        try {
            assertTrue(executor.invokeAll(List(12) { Callable { reclaimer.trimCurrentProcess() } }).none { it.get() })
        } finally { executor.shutdownNow() }
        now = 29_999_999_999L
        assertFalse(reclaimer.trimCurrentProcess())
        now++
        assertTrue(reclaimer.trimCurrentProcess())
        assertEquals(2, calls)
    }

    @Test fun `unavailable allocator does not crash or retry in a tight loop`() {
        var calls = 0
        val reclaimer = LinuxMemoryReclaimer("Linux", { calls++; error("unsupported allocator") }, { 0 })
        assertFalse(reclaimer.trimCurrentProcess())
        assertFalse(reclaimer.trimCurrentProcess())
        assertEquals(1, calls)
    }
}
