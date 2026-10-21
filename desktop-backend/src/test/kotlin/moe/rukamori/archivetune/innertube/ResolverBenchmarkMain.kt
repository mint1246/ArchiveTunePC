package moe.rukamori.archivetune.innertube

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.nio.file.Files

/** Developer harness: launch directly with the test runtime classpath so Gradle is outside measurements. */
fun main(arguments: Array<String>) {
    runBlocking {
        require(arguments.size >= 2) { "Usage: ResolverBenchmarkMainKt legacy|ytdlp VIDEO_ID [REPEATS]" }
        val mode = arguments[0]
        require(mode in setOf("legacy", "ytdlp"))
        val videoId = arguments[1]
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        val repetitions = arguments.getOrNull(2)?.toInt() ?: 1
        val cache = Files.createTempDirectory("archivetune-benchmark-")
        try {
            val client = ArchiveTuneJvmClient(cache.toFile(), audioStreamExtractor =
                if (mode == "legacy") null else YtDlpDesktopStreamExtractor(cache.toFile()))
            client.authState = PlaybackAuthState() // Public-video comparisons use no saved account/session.
            repeat(repetitions) { iteration ->
                val started = System.nanoTime()
                val result = client.resolveAudioStream(videoId, quality = JvmAudioQuality.HIGH)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val stream = result.getOrNull()
                val descendants = ProcessHandle.current().descendants().use { children -> children.filter { it.isAlive }.count() }
                val record = JSONObject().put("backend", mode).put("video_id", videoId).put("iteration", iteration)
                    .put("elapsed_ms", elapsedMs).put("success", result.isSuccess).put("remaining_children", descendants)
                if (stream != null) {
                    record.put("format", stream.format.itag).put("bitrate", stream.bitrate)
                        .put("mime", stream.mimeType).put("header_names", stream.requestHeaders.keys.sorted())
                        .put("loudness_db", stream.audioConfig?.loudnessDb ?: JSONObject.NULL)
                        .put("perceptual_loudness_db", stream.audioConfig?.perceptualLoudnessDb ?: JSONObject.NULL)
                        .put("expiry_seconds", (stream.expiresAtEpochMillis - System.currentTimeMillis()) / 1000)
                } else {
                    record.put("failure", classifyStreamResolutionFailure(result.exceptionOrNull()!!).name)
                }
                // No signed URLs, exception messages, auth fingerprints or header values in reports.
                println("ARCHIVETUNE_BENCHMARK\t$record")
            }
        } finally { cache.toFile().deleteRecursively() }
    }
    // The benchmark owns this JVM; do not measure OkHttp dispatcher keep-alive after the last request.
    kotlin.system.exitProcess(0)
}
