/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.download

/** Stateful sampler that derives bytes-per-second from successive progress snapshots. */
internal class DownloadSpeedSampler {
    private val byteSamples = mutableMapOf<String, ByteSample>()

    fun calculate(
        downloads: Map<String, DownloadSnapshot>,
        nowMs: Long,
    ): Map<String, Long> {
        byteSamples.keys.retainAll(downloads.keys)
        return downloads.mapValues { (songId, download) ->
            val previous = byteSamples[songId]
            val currentBytes = download.bytesDownloaded.coerceAtLeast(0L)
            if (download.state != DownloadState.DOWNLOADING || previous == null || currentBytes < previous.bytes) {
                byteSamples[songId] = ByteSample(currentBytes, nowMs, 0L)
                return@mapValues 0L
            }

            if (currentBytes == previous.bytes) {
                return@mapValues if (nowMs - previous.timestampMs <= SPEED_SAMPLE_HOLD_MS) {
                    previous.speedBytesPerSecond
                } else {
                    0L
                }
            }

            val elapsedMs = (nowMs - previous.timestampMs).coerceAtLeast(1L)
            val downloadedBytes = currentBytes - previous.bytes
            val speed = downloadedBytes * 1_000L / elapsedMs
            byteSamples[songId] = ByteSample(currentBytes, nowMs, speed)
            speed
        }
    }

    private data class ByteSample(
        val bytes: Long,
        val timestampMs: Long,
        val speedBytesPerSecond: Long,
    )

    private companion object {
        const val SPEED_SAMPLE_HOLD_MS = 2_500L
    }
}
