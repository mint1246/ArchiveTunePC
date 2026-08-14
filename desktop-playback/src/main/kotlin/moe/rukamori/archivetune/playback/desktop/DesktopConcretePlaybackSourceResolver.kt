/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Resolves already concrete desktop sources; applications supply YouTube/deferred resolution. */
object DesktopConcretePlaybackSourceResolver : PlaybackSourceResolver {
    override suspend fun resolve(item: PlaybackItem): Result<ResolvedPlaybackSource> =
        runCatching {
            when (val source = item.source) {
                is PlaybackSource.Remote -> {
                    require(source.url.isNotBlank()) { "Remote playback URL is blank" }
                    ResolvedPlaybackSource(
                        uri = source.url,
                        mimeType = source.mimeType,
                        headers = source.headers,
                        expiresAtEpochMs = source.expiresAtEpochMs,
                    )
                }

                is PlaybackSource.LocalFile -> {
                    val path = source.path.toPlaybackPath()
                    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                        throw FileNotFoundException("Local audio file is unavailable")
                    }
                    ResolvedPlaybackSource(uri = path.toUri().toASCIIString())
                }

                is PlaybackSource.Deferred -> {
                    error("No deferred playback resolver is configured for ${source.key}")
                }
            }
        }

    private fun String.toPlaybackPath(): Path =
        try {
            if (startsWith("file:", ignoreCase = true)) Path.of(URI(this)) else Path.of(this)
        } catch (error: InvalidPathException) {
            throw IllegalArgumentException("Invalid local audio path", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid local audio path", error)
        }
}
