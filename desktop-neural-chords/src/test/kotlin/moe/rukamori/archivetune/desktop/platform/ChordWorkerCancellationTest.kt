package moe.rukamori.archivetune.desktop.platform

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChordWorkerCancellationTest {
    @Test
    fun `cancellation terminates a worker even while its output reader is blocked`() = runBlocking {
        val command = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 60")
        } else {
            listOf("sh", "-c", "exec sleep 60")
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                val monitor = CoroutineScope(currentCoroutineContext()).stopProcessOnCancellation(process)
                try {
                    ready.complete(Unit)
                    process.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    monitor.cancel()
                }
            }
            ready.await()
            assertTrue(process.isAlive)
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertFalse(process.isAlive)
        } finally {
            process.destroyForcibly()
        }
    }
}
