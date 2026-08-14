/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class MpvProcessHost(
    private val configuration: MpvConfiguration,
    private val onMessage: (JSONObject) -> Unit,
    private val onExit: (Int, String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val eventLoopStarted = AtomicBoolean(false)
    private val writeLock = Any()
    private val diagnostics = ConcurrentLinkedDeque<String>()
    private var endpoint: MpvIpcEndpoint? = null
    private var process: Process? = null
    @Volatile private var transport: MpvIpcTransport? = null

    val isRunning: Boolean
        get() = process?.isAlive == true && transport != null && !closed.get()

    suspend fun start() = withContext(Dispatchers.IO) {
        check(!closed.get()) { "mpv host is closed" }
        if (isRunning) return@withContext

        val newEndpoint = MpvIpcEndpoint.create()
        endpoint = newEndpoint
        val arguments =
            buildList {
                add(configuration.executable)
                add("--idle=yes")
                add("--no-video")
                add("--force-window=no")
                add("--audio-display=no")
                add("--input-terminal=no")
                add("--input-ipc-server=${newEndpoint.mpvArgument}")
                add("--msg-level=all=warn")
                addAll(configuration.additionalArguments)
            }

        val newProcess =
            try {
                ProcessBuilder(arguments)
                    .redirectErrorStream(true)
                    .start()
            } catch (error: IOException) {
                newEndpoint.cleanup()
                throw MpvUnavailableException(
                    "Unable to launch '${configuration.executable}'. Install or package mpv and set " +
                        "ARCHIVETUNE_MPV_PATH when it is outside PATH.",
                    error,
                )
            }
        process = newProcess
        startDiagnosticDrain(newProcess)

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(configuration.startupTimeoutMs)
        var lastConnectError: Throwable? = null
        while (System.nanoTime() < deadline && newProcess.isAlive && !closed.get()) {
            try {
                transport = newEndpoint.connect()
                return@withContext
            } catch (error: IOException) {
                lastConnectError = error
                delay(40)
            }
        }

        val details = diagnosticText()
        close()
        throw MpvUnavailableException(
            buildString {
                append("mpv did not expose its IPC endpoint within ${configuration.startupTimeoutMs}ms")
                if (details.isNotBlank()) append(": $details")
            },
            lastConnectError,
        )
    }

    fun command(vararg arguments: Any?) {
        val payload = commandPayload(arguments)
        synchronized(writeLock) {
            val activeTransport = transport ?: throw IllegalStateException("mpv IPC is not connected")
            activeTransport.writeLine(payload)
        }
    }

    fun eventCommand(vararg arguments: Any?) {
        val activeTransport = transport ?: throw IllegalStateException("mpv IPC is not connected")
        activeTransport.writeEventLine(commandPayload(arguments))
    }

    fun startEventLoop() {
        if (!eventLoopStarted.compareAndSet(false, true)) return
        val ownedProcess = process ?: throw IllegalStateException("mpv is not running")
        startReadLoop(ownedProcess)
    }

    private fun commandPayload(arguments: Array<out Any?>): String =
        JSONObject().put(
            "command",
            JSONArray().apply { arguments.forEach { put(JSONObject.wrap(it)) } },
        ).toString()

    private fun startReadLoop(ownedProcess: Process) {
        Thread(
            {
                try {
                    while (!closed.get()) {
                        val line = transport?.readLine() ?: break
                        runCatching { JSONObject(line) }.onSuccess(onMessage)
                    }
                } catch (_: IOException) {
                    // Closing the transport is the normal way to unblock this loop.
                } finally {
                    if (!closed.get()) {
                        val code = if (ownedProcess.isAlive) -1 else runCatching { ownedProcess.exitValue() }.getOrDefault(-1)
                        onExit(code, diagnosticText())
                    }
                }
            },
            "ArchiveTune-mpv-ipc",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun startDiagnosticDrain(ownedProcess: Process) {
        Thread(
            {
                ownedProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        diagnostics += line
                        while (diagnostics.size > MAX_DIAGNOSTIC_LINES) diagnostics.pollFirst()
                    }
                }
            },
            "ArchiveTune-mpv-output",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun diagnosticText(): String = diagnostics.toList().takeLast(8).joinToString(" | ")

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { command("quit") }
        runCatching { transport?.close() }
        transport = null
        process?.let { ownedProcess ->
            if (!runCatching { ownedProcess.waitFor(configuration.shutdownTimeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                ownedProcess.destroy()
                if (!runCatching { ownedProcess.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                    ownedProcess.destroyForcibly()
                }
            }
        }
        process = null
        runCatching { endpoint?.cleanup?.invoke() }
        endpoint = null
    }

    private companion object {
        const val MAX_DIAGNOSTIC_LINES = 50
    }
}

class MpvUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)
