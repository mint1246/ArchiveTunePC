/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class ResolverProcessOutput(val exitCode: Int, val stdout: String, val stderr: String)

/** No shell, inherited stdin, persistent worker, or unbounded diagnostic buffer. */
internal suspend fun runResolverProcess(command: List<String>, temporaryDirectory: Path): ResolverProcessOutput =
    withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(command).apply {
                // QuickJS's temporary JS files and PyInstaller's unpack directory are request-local too.
                environment()["TMPDIR"] = temporaryDirectory.toString()
                environment()["TMP"] = temporaryDirectory.toString()
                environment()["TEMP"] = temporaryDirectory.toString()
                environment()["PYTHONIOENCODING"] = "utf-8"
            }.start()
        } catch (_: Exception) {
            throw YtDlpException(YtDlpFailure.TOOLCHAIN)
        }
        process.outputStream.close()
        val descendants = linkedMapOf<Long, ProcessHandle>()
        coroutineScope {
            val stdout = async { readProcessPipe(process.inputStream, 16 * 1024 * 1024) }
            val stderr = async { readProcessPipe(process.errorStream, 1024 * 1024) }
            try {
                while (process.isAlive) {
                    process.descendants().use { children -> children.forEach { descendants[it.pid()] = it } }
                    delay(25) // Cancellable even while the child or a pipe reader blocks.
                }
                ResolverProcessOutput(process.exitValue(), stdout.await(), stderr.await())
            } finally {
                // PyInstaller and QuickJS can be grandchildren. Snapshot before killing their parent.
                runCatching { process.descendants().use { children -> children.forEach { descendants[it.pid()] = it } } }
                descendants.values.toList().asReversed().forEach { child -> runCatching { child.destroyForcibly() } }
                if (process.isAlive) process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS) // Reap before removing cookies and temporary EJS files.
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
        }
    }

private suspend fun readProcessPipe(input: InputStream, limit: Int): String = try {
    input.readBounded(limit)
} catch (_: java.io.IOException) {
    // Closing pipes during cancellation must not turn queue cancellation into an extraction error.
    currentCoroutineContext().ensureActive()
    throw YtDlpException(YtDlpFailure.INVALID_OUTPUT)
}

private fun InputStream.readBounded(limit: Int): String = use { input ->
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val count = input.read(chunk)
        if (count < 0) break
        if (buffer.size() + count > limit) throw YtDlpException(YtDlpFailure.INVALID_OUTPUT)
        buffer.write(chunk, 0, count)
    }
    buffer.toString(Charsets.UTF_8)
}
