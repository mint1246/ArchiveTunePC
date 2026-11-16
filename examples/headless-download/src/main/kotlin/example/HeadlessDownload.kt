package example

import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.downloads.DesktopDownloadManager
import moe.rukamori.archivetune.downloads.DownloadRequest
import moe.rukamori.archivetune.downloads.DownloadStatus

fun main(args: Array<String>) = runBlocking {
    require(args.size == 3) { "Pass a direct media URL, output directory, and file extension" }
    val directory = Path.of(args[1]).toAbsolutePath().normalize()
    DesktopDownloadManager(directory).use { downloads ->
        val id = downloads.enqueue(
            DownloadRequest(url = args[0], headers = emptyMap(), title = "Example", extension = args[2]),
        )
        val result = withTimeout(60_000) {
            downloads.downloads.first { rows ->
                rows.any { it.id == id && it.status in terminalStatuses }
            }.first { it.id == id }
        }
        check(result.status == DownloadStatus.COMPLETED) { "Download failed: ${result.error}" }
        println("Downloaded ${result.bytesDownloaded} bytes to ${directory.resolve(result.fileName)}")
    }
}

private val terminalStatuses = setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
