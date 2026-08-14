/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.util.UUID

internal data class MpvIpcEndpoint(
    val mpvArgument: String,
    val connect: () -> MpvIpcTransport,
    val cleanup: () -> Unit,
) {
    companion object {
        fun create(): MpvIpcEndpoint =
            if (MpvExecutableLocator.isWindows()) {
                val pipe = "\\\\.\\pipe\\archivetune-mpv-${UUID.randomUUID()}"
                MpvIpcEndpoint(
                    mpvArgument = pipe,
                    connect = { WindowsNamedPipeTransport(pipe) },
                    cleanup = {},
                )
            } else {
                val socket =
                    Path.of(System.getProperty("java.io.tmpdir"))
                        .resolve("archivetune-mpv-${UUID.randomUUID()}.sock")
                MpvIpcEndpoint(
                    mpvArgument = socket.toString(),
                    connect = { UnixSocketTransport(socket) },
                    cleanup = { Files.deleteIfExists(socket) },
                )
            }
    }
}

internal interface MpvIpcTransport : Closeable {
    fun readLine(): String?
    fun writeLine(value: String)
}

private class WindowsNamedPipeTransport(path: String) : MpvIpcTransport {
    private val file = RandomAccessFile(path, "rw")

    override fun readLine(): String? = file.readLine()

    override fun writeLine(value: String) {
        // JSON emitted by the controller is ASCII-safe except for metadata. UTF-8 is required by mpv.
        file.write(value.toByteArray(StandardCharsets.UTF_8))
        file.write('\n'.code)
    }

    override fun close() = file.close()
}

private class UnixSocketTransport(path: Path) : MpvIpcTransport {
    private val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    private val reader: BufferedReader
    private val writer: BufferedWriter

    init {
        channel.connect(UnixDomainSocketAddress.of(path))
        reader = Channels.newReader(channel, StandardCharsets.UTF_8).buffered()
        writer = Channels.newWriter(channel, StandardCharsets.UTF_8).buffered()
    }

    override fun readLine(): String? = reader.readLine()

    override fun writeLine(value: String) {
        writer.write(value)
        writer.newLine()
        writer.flush()
    }

    override fun close() = channel.close()
}
