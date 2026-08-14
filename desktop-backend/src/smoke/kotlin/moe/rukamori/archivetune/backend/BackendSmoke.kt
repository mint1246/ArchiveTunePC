/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backend

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.DesktopNetworkPolicy
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.probeResolvedAudioStream
import moe.rukamori.archivetune.innertube.models.SongItem
import java.nio.file.Files

/** Opt-in network smoke test. It deliberately never prints signed URLs, headers, cookies, or tokens. */
fun main() =
    runBlocking {
        val cacheDirectory = Files.createTempDirectory("archivetune-backend-smoke-").toFile()
        val client = ArchiveTuneJvmClient(
            cacheDirectory = cacheDirectory,
            networkPolicy = DesktopNetworkPolicy(gatekeeperEnabled = false),
        )
        smokeQueries.forEach { query ->
            val result = runCatching {
                withTimeout(smokeTimeoutMillis) {
                    val song =
                        client
                            .search(query, YouTube.SearchFilter.FILTER_SONG)
                            .getOrThrow()
                            .items
                            .filterIsInstance<SongItem>()
                            .firstOrNull()
                            ?: error("YouTube Music search returned no song")

                    val stream = client.resolveAudioStream(song.id).getOrThrow()
                    check(stream.expiresAtEpochMillis > System.currentTimeMillis()) { "Resolved stream is already expired" }
                    check(stream.bitrate > 0) { "Resolved stream has no bitrate" }
                    val probe = probeResolvedAudioStream(stream).getOrThrow()
                    stream to probe
                }
            }
            val (stream, probe) = result.getOrElse { failure ->
                // The concrete failure stays on the exception chain for developers, but console
                // output remains safe to paste into an issue.
                println("ArchiveTune backend smoke failed: stage=search-resolve-probe, type=${failure::class.simpleName}")
                throw failure
            }

            // Deliberately redacted: never print signed URLs, headers, cookies, tokens, or query text.
            println(
                "ArchiveTune backend smoke passed: " +
                    "client=${stream.clientName}, mime=${stream.mimeType}, bitrate=${stream.bitrate}, " +
                    "http=${probe.httpStatus}, bytes=${probe.bytesRead}, ranged=${probe.rangedResponse}",
            )
        }
    }

private val smokeQueries: List<String> =
    (System.getenv("ARCHIVETUNE_SMOKE_TRACKS") ?: "Lost In The World feat Bon Iver,Kanye West Stronger,Daft Punk Get Lucky")
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)

private val smokeTimeoutMillis: Long =
    (System.getenv("ARCHIVETUNE_SMOKE_TIMEOUT_SECONDS")?.toLongOrNull() ?: 45L)
        .coerceIn(5L, 120L) * 1_000L
