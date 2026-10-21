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
        val linux = System.getProperty("os.name").startsWith("Linux", true)
        val setsid = if (linux) listOf("/usr/bin/setsid", "/bin/setsid").firstOrNull { java.io.File(it).canExecute() } else null
        val kill = if (setsid != null) listOf("/usr/bin/kill", "/bin/kill").firstOrNull { java.io.File(it).canExecute() } else null
        val grouped = setsid != null && kill != null
        val process = try {
            ProcessBuilder(if (grouped) listOf(setsid!!, "--") + command else command).apply {
                // Keep Bun's project/.env discovery and all runtime unpacking inside this private request.
                directory(temporaryDirectory.toFile())
                environment()["TMPDIR"] = temporaryDirectory.toString()
                environment()["TMP"] = temporaryDirectory.toString()
                environment()["TEMP"] = temporaryDirectory.toString()
                environment()["PYTHONIOENCODING"] = "utf-8"
                // Runtime flags must come from yt-dlp; inherited --cwd/preload options defeat isolation.
                environment().remove("BUN_OPTIONS")
                environment()["DO_NOT_TRACK"] = "1"
            }.start()
        } catch (_: Exception) {
            throw YtDlpException(YtDlpFailure.TOOLCHAIN)
        }
        process.outputStream.close()
        val descendants = linkedMapOf<Long, ProcessHandle>()
        var childrenStopped = false
        fun terminateDescendants() {
            if (childrenStopped) return
            childrenStopped = true
            runCatching { process.descendants().use { children -> children.forEach { descendants[it.pid()] = it } } }
            if (grouped) runCatching {
                // Session/process-group ownership survives a parent exiting before discovery.
                ProcessBuilder(kill!!, "-KILL", "--", "-${process.pid()}")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor(1, TimeUnit.SECONDS)
            }
            descendants.values.toList().asReversed().forEach { child -> runCatching { child.destroyForcibly() } }
        }
        coroutineScope {
            val stdout = async { readProcessPipe(process.inputStream, 16 * 1024 * 1024) }
            val stderr = async { readProcessPipe(process.errorStream, 1024 * 1024) }
            try {
                while (process.isAlive) {
                    process.descendants().use { children -> children.forEach { descendants[it.pid()] = it } }
                    delay(25) // Cancellable even while the child or a pipe reader blocks.
                }
                // A crashed/exited parent must not leave a child holding our stdout pipe open.
                terminateDescendants()
                ResolverProcessOutput(process.exitValue(), stdout.await(), stderr.await())
            } finally {
                // PyInstaller and Bun can be grandchildren. Snapshot before killing their parent.
                terminateDescendants()
                if (process.isAlive) process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS) // Reap before removing cookies and temporary EJS files.
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
                descendants.values.forEach { child ->
                    val remaining = deadline - System.nanoTime()
                    if (child.isAlive && remaining > 0) runCatching { child.onExit().get(remaining, TimeUnit.NANOSECONDS) }
                }
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
    throw YtDlpException(YtDlpFailure.NETWORK)
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
