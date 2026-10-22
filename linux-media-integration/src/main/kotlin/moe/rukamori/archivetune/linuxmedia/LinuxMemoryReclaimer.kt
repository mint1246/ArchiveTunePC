package moe.rukamori.archivetune.linuxmedia

import com.sun.jna.Native
import com.sun.jna.NativeLibrary

/** Returns already-free glibc allocator pages to Linux; never discards live application pages. */
class LinuxMemoryReclaimer internal constructor(
    private val osName: String,
    private val trim: () -> Boolean,
    private val nanoTime: () -> Long,
) {
    constructor() : this(System.getProperty("os.name", ""), LinuxAllocator::trim, System::nanoTime)

    private var previousAttempt: Long? = null

    val isSupported: Boolean
        get() = osName.startsWith("Linux", ignoreCase = true) && LinuxAllocator.isAvailable()

    /** Calls may overlap at UI lifecycle boundaries; at most one attempt runs per 30 seconds. */
    @Synchronized
    fun trimCurrentProcess(): Boolean {
        if (!osName.startsWith("Linux", ignoreCase = true)) return false
        val now = nanoTime()
        val previous = previousAttempt
        if (previous != null && now - previous < MIN_INTERVAL_NANOS) return false
        previousAttempt = now
        return runCatching(trim).getOrDefault(false)
    }

    private companion object {
        const val MIN_INTERVAL_NANOS = 30_000_000_000L
    }
}

private object LinuxAllocator {
    // Resolve once. Non-glibc systems can simply lack this optional allocator operation.
    private val function by lazy {
        runCatching { NativeLibrary.getInstance("c").getFunction("malloc_trim") }.getOrNull()
    }

    fun isAvailable(): Boolean = function != null

    fun trim(): Boolean {
        val mallocTrim = function ?: return false
        // size_t is pointer-sized; using primitives avoids NativeMapped reflection in Native Image.
        val padding: Any = if (Native.SIZE_T_SIZE == 8) 0L else 0
        return mallocTrim.invokeInt(arrayOf(padding)) != 0
    }
}
