/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

private const val MATCH_CONCURRENCY = 8
private const val STRONG_TITLE_SCORE = 0.86
private const val MEDIUM_TITLE_SCORE = 0.45
private const val MIN_DURATION_SCORE = 0.50
private const val STRONG_DURATION_SCORE = 0.80
private const val STRONG_ARTIST_SCORE = 0.72
private const val VERY_STRONG_ARTIST_SCORE = 0.80
private const val STRONG_ALBUM_SCORE = 0.65
private const val POST_CREATE_SETTLE_MS = 1_000L
private const val PLAYLIST_ADD_BATCH_SIZE = 3
private const val PLAYLIST_ADD_BATCH_INTERVAL_MS = 1_000L
private const val PLAYLIST_ADD_ATTEMPTS = 3
private const val PLAYLIST_ADD_RETRY_MS = 400L
private const val SPOTIFY_PAGE_SIZE = 100
private const val MAX_COVER_BYTES = 8 * 1024 * 1024L
private val VERSION_MARKERS =
    listOf(
        Regex("(?i)(\\binstrumental\\b|אינסטרומנטל(?:י|ית)?)"),
        Regex("(?i)(\\bkaraoke\\b|קריוקי)"),
        Regex("(?i)(\\bplayback\\b|פלייבק)"),
        Regex("(?i)(\\bacoustic\\b|אקוסטי(?:ת)?)"),
        Regex("(?i)(\\bremix\\b|רמיקס)"),
        Regex("(?i)(\\blive\\b|בהופעה|הופעה חיה)"),
        Regex("(?i)(\\bsped[ -]?up\\b|\\bslowed\\b|\\bnightcore\\b)"),
    )

/** Imports a public Spotify playlist into the currently signed-in YouTube Music account. */
class DesktopSpotifyPlaylistImporter(
    private val backend: ArchiveTuneJvmClient,
    private val diagnostic: (String) -> Unit = {},
    private val imageClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
) {
    suspend fun import(
        link: String,
        onProgress: (SpotifyPlaylistImportProgress) -> Unit = {},
    ): Result<SpotifyPlaylistImportResult> =
        try {
            val spotifyPlaylistId = SpotifyPlaylistLink.parse(link).getOrThrow()
            diagnostic("spotify_import event=start playlistId=$spotifyPlaylistId")
            onProgress(SpotifyPlaylistImportProgress("Reading Spotify playlist…"))

            val previousToken = Spotify.accessToken
            val spotifyPlaylist: SpotifyPlaylist
            val spotifyTracks: List<SpotifyTrack>
            try {
                val token = SpotifyAuth.fetchAnonymousAccessToken().getOrThrow()
                Spotify.accessToken = token.accessToken
                spotifyPlaylist =
                    Spotify.playlist(spotifyPlaylistId).getOrElse { failure ->
                        throw IllegalArgumentException(
                            "This Spotify playlist couldn't be read. Make sure it is public and the link is correct.",
                            failure,
                        )
                    }
                spotifyTracks = loadAllTracks(spotifyPlaylistId)
            } finally {
                Spotify.accessToken = previousToken
            }

            require(spotifyPlaylist.name.isNotBlank()) { "Spotify returned a playlist with no name" }
            require(spotifyTracks.isNotEmpty()) { "The Spotify playlist has no importable tracks" }

            val matchedSongIds = matchTracks(spotifyTracks, onProgress)

            require(matchedSongIds.isNotEmpty()) { "None of the Spotify songs could be matched on YouTube Music" }

            val unmatched = spotifyTracks.size - matchedSongIds.size
            diagnostic(
                "spotify_import event=matching_complete spotifyTracks=${spotifyTracks.size} matched=${matchedSongIds.size} unmatched=$unmatched",
            )

            // Create with one seed song, then append the rest through edit_playlist. Sending the whole
            // playlist in playlist/create silently drops/merges entries for larger playlists (and in
            // particular loses duplicate video IDs). Seeding also avoids the STATUS_FAILED race seen
            // when edit_playlist is called immediately after creating a completely empty playlist.
            onProgress(
                SpotifyPlaylistImportProgress(
                    message = "Creating playlist… 1/${matchedSongIds.size}",
                    completed = 1,
                    total = matchedSongIds.size,
                ),
            )
            val youtubePlaylistId =
                backend.createPlaylist(spotifyPlaylist.name, listOf(matchedSongIds.first())).getOrThrow()

            val remainingSongIds = matchedSongIds.drop(1)
            var addedSongs = 1
            var failedAdditions = 0
            if (remainingSongIds.isNotEmpty()) {
                delay(POST_CREATE_SETTLE_MS)
                val addBatches = orderedPlaylistAddBatches(remainingSongIds)
                var processedAdds = 0
                var previousBatchStartedAtNanos: Long? = null
                addBatches.forEachIndexed { batchIndex, batch ->
                    previousBatchStartedAtNanos?.let { previousStart ->
                        val elapsedMs = (System.nanoTime() - previousStart).coerceAtLeast(0L) / 1_000_000L
                        val remainingDelayMs = (PLAYLIST_ADD_BATCH_INTERVAL_MS - elapsedMs).coerceAtLeast(0L)
                        if (remainingDelayMs > 0) delay(remainingDelayMs)
                    }
                    previousBatchStartedAtNanos = System.nanoTime()
                    var added = false
                    var lastFailure: Throwable? = null
                    for (attempt in 1..PLAYLIST_ADD_ATTEMPTS) {
                        val result = backend.addSongsToPlaylist(youtubePlaylistId, batch)
                        if (result.isSuccess) {
                            added = true
                            addedSongs += batch.size
                            break
                        }
                        lastFailure = result.exceptionOrNull()
                        diagnostic(
                            "spotify_import event=add_batch_retry youtubePlaylistId=$youtubePlaylistId " +
                                "batch=${batchIndex + 1}/${addBatches.size} songs=${batch.size} " +
                                "attempt=$attempt/$PLAYLIST_ADD_ATTEMPTS message=${lastFailure?.message.orEmpty().logSafe()}",
                        )
                        if (attempt < PLAYLIST_ADD_ATTEMPTS) {
                            delay(PLAYLIST_ADD_RETRY_MS * attempt)
                        }
                    }
                    if (!added) {
                        failedAdditions += batch.size
                        diagnostic(
                            "spotify_import event=add_batch_failed youtubePlaylistId=$youtubePlaylistId " +
                                "batch=${batchIndex + 1}/${addBatches.size} songs=${batch.size} " +
                                "message=${lastFailure?.message.orEmpty().logSafe()}",
                        )
                    }
                    processedAdds += batch.size
                    val processed = 1 + processedAdds
                    onProgress(
                        SpotifyPlaylistImportProgress(
                            message = "Adding songs… $processed/${matchedSongIds.size}",
                            completed = processed,
                            total = matchedSongIds.size,
                        ),
                    )
                }
            }
            diagnostic(
                "spotify_import event=playlist_created youtubePlaylistId=$youtubePlaylistId requestedSongs=${matchedSongIds.size} " +
                    "addedSongs=$addedSongs failedAdditions=$failedAdditions",
            )

            val coverUrl = SpotifyMapper.getPlaylistThumbnail(spotifyPlaylist)
            var coverCopied = coverUrl == null
            var coverFailure: String? = null
            if (coverUrl != null) {
                onProgress(SpotifyPlaylistImportProgress("Copying playlist image…"))
                runCatching { downloadImage(coverUrl) }
                    .mapCatching { bytes -> backend.uploadCustomPlaylistCover(youtubePlaylistId, bytes).getOrThrow() }
                    .onSuccess { coverCopied = true }
                    .onFailure { coverFailure = it.message ?: "playlist image upload failed" }
            }

            val skipped = spotifyTracks.size - addedSongs
            val warnings = mutableListOf<String>()
            if (unmatched > 0) warnings += "$unmatched Spotify tracks could not be confidently matched on YouTube Music."
            if (failedAdditions > 0) warnings += "$failedAdditions matched songs could not be added after retries."
            coverFailure?.let { warnings += "The Spotify image could not be copied: $it" }

            onProgress(SpotifyPlaylistImportProgress("Import complete", addedSongs, spotifyTracks.size))
            diagnostic(
                "spotify_import event=complete youtubePlaylistId=$youtubePlaylistId imported=$addedSongs skipped=$skipped " +
                    "matched=${matchedSongIds.size} failedAdditions=$failedAdditions coverCopied=$coverCopied",
            )
            Result.success(
                SpotifyPlaylistImportResult(
                    playlistId = youtubePlaylistId,
                    name = spotifyPlaylist.name,
                    importedSongs = addedSongs,
                    skippedSongs = skipped,
                    coverCopied = coverCopied,
                    warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" "),
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            diagnostic(
                "spotify_import event=failed type=${failure::class.simpleName.orEmpty()} message=${failure.message.orEmpty().logSafe()}",
            )
            Result.failure(failure)
        }

    private suspend fun loadAllTracks(playlistId: String): List<SpotifyTrack> {
        val tracks = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val page =
                Spotify.playlistTracks(
                    playlistId = playlistId,
                    limit = SPOTIFY_PAGE_SIZE,
                    offset = offset,
                ).getOrElse { failure ->
                    throw IllegalArgumentException(
                        "This Spotify playlist couldn't be read. Make sure it is public and the link is correct.",
                        failure,
                    )
                }
            if (page.items.isEmpty()) break
            tracks += page.items.mapNotNull { item -> item.track?.takeUnless { item.isLocal } }
            offset += page.items.size
            if (page.items.size < SPOTIFY_PAGE_SIZE || (page.total > 0 && offset >= page.total)) break
        }
        return tracks
    }

    private suspend fun matchTracks(
        tracks: List<SpotifyTrack>,
        onProgress: (SpotifyPlaylistImportProgress) -> Unit,
    ): List<String> = coroutineScope {
        val semaphore = Semaphore(MATCH_CONCURRENCY)
        val progressMutex = Mutex()
        var completed = 0
        onProgress(SpotifyPlaylistImportProgress("Matching songs… 0/${tracks.size}", 0, tracks.size))

        tracks.map { track ->
            async {
                val match = semaphore.withPermit { resolveTrack(track)?.id }
                progressMutex.withLock {
                    completed += 1
                    onProgress(
                        SpotifyPlaylistImportProgress(
                            message = "Matching songs… $completed/${tracks.size}",
                            completed = completed,
                            total = tracks.size,
                        ),
                    )
                }
                match
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun resolveTrack(track: SpotifyTrack): SongItem? {
        val primaryQuery = SpotifyMapper.buildSearchQuery(track)
        val primaryCandidates = backend.searchSongs(primaryQuery).getOrNull().orEmpty()
        if (primaryCandidates.isEmpty()) {
            diagnostic(
                "spotify_import event=match spotifyId=${track.id} spotifyTitle=${track.name.logSafe()} result=no_candidates",
            )
        }

        val precomputed =
            SpotifyMapper.precompute(
                title = track.name,
                artist = track.artists.joinToString(" ") { it.name },
                durationMs = track.durationMs,
            )
        var allCandidates = primaryCandidates
        var ranked = rankCandidates(track, precomputed, allCandidates)
        var selected = ranked.firstOrNull { candidate -> isAcceptableMatch(track, candidate) }

        // Most songs resolve from the normal artist+title search. Only uncertain tracks pay for
        // fallback searches. This is especially important for Spotify's Latin transliterations of
        // Hebrew artists while YouTube Music returns the same artist name in Hebrew (or no artist).
        var queryCount = 1
        if (selected == null) {
            val fallbackQueries =
                buildList {
                    track.artists.forEach { artist ->
                        if (artist.name.isNotBlank()) add("${artist.name} ${track.name}")
                    }
                    track.album?.artists.orEmpty().forEach { artist ->
                        if (artist.name.isNotBlank()) add("${artist.name} ${track.name}")
                    }
                    add(track.name)
                }.map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .filterNot { it.equals(primaryQuery, ignoreCase = true) }
                    .take(5)

            val expanded = allCandidates.toMutableList()
            fallbackQueries.forEach { query ->
                queryCount += 1
                expanded += backend.searchSongs(query).getOrNull().orEmpty()
            }
            allCandidates = expanded.distinctBy(SongItem::id)
            ranked = rankCandidates(track, precomputed, allCandidates)
            selected = ranked.firstOrNull { candidate -> isAcceptableMatch(track, candidate) }
        }

        diagnostic(matchDiagnostic(track, ranked.take(5), selected, queryCount))
        return selected?.song
    }

    private fun rankCandidates(
        track: SpotifyTrack,
        precomputed: SpotifyMapper.PrecomputedTrack,
        candidates: List<SongItem>,
    ): List<MatchCandidate> =
        candidates
            .asSequence()
            .map { candidate ->
                val components =
                    SpotifyMapper.matchScoreComponentsPrecomputed(
                        precomputed = precomputed,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artists.joinToString(" ") { it.name },
                        candidateDurationSec = candidate.duration,
                    )
                val albumScore =
                    if (!track.album?.name.isNullOrBlank() && !candidate.album?.name.isNullOrBlank()) {
                        SpotifyMapper.textSimilarity(track.album!!.name, candidate.album!!.name)
                    } else {
                        0.0
                    }
                val artistScore = bestArtistScore(track, candidate)
                MatchCandidate(
                    song = candidate,
                    score = components,
                    artistScore = artistScore,
                    albumScore = albumScore,
                    rank =
                        components.title * 0.50 +
                            components.duration * 0.27 +
                            artistScore * 0.13 +
                            albumScore * 0.10,
                )
            }.sortedByDescending(MatchCandidate::rank)
            .toList()

    private fun bestArtistScore(track: SpotifyTrack, candidate: SongItem): Double {
        if (track.artists.isEmpty() || candidate.artists.isEmpty()) return 0.0
        return track.artists.maxOf { spotifyArtist ->
            candidate.artists.maxOf { youtubeArtist ->
                SpotifyMapper.textSimilarity(spotifyArtist.name, youtubeArtist.name)
            }
        }
    }

    private fun isAcceptableMatch(track: SpotifyTrack, candidate: MatchCandidate): Boolean {
        if (hasVersionMismatch(track, candidate.song)) return false
        if (candidate.score.duration < MIN_DURATION_SCORE) return false

        // Exact title + effectively exact runtime is strong enough on its own. This covers common
        // Spotify/YouTube metadata disagreements such as Peled vs פלד or a track being surfaced as a
        // single on YouTube while Spotify stores it under the album. Requiring artist/album here was
        // responsible for many of the false negatives on Hebrew playlists.
        if (candidate.score.title >= 0.98 && candidate.score.duration >= 1.0) return true

        val metadataSupport =
            candidate.artistScore >= STRONG_ARTIST_SCORE ||
                candidate.albumScore >= STRONG_ALBUM_SCORE ||
                candidate.song.artists.isEmpty()
        if (candidate.score.title >= STRONG_TITLE_SCORE && metadataSupport) return true

        return candidate.score.title >= MEDIUM_TITLE_SCORE &&
            candidate.score.duration >= STRONG_DURATION_SCORE &&
            candidate.artistScore >= VERY_STRONG_ARTIST_SCORE
    }

    private fun hasVersionMismatch(track: SpotifyTrack, candidate: SongItem): Boolean =
        VERSION_MARKERS.any { marker ->
            marker.containsMatchIn(track.name) != marker.containsMatchIn(candidate.title)
        }

    private fun matchDiagnostic(
        track: SpotifyTrack,
        candidates: List<MatchCandidate>,
        selected: MatchCandidate?,
        queryCount: Int,
    ): String =
        buildString {
            append("spotify_import event=match")
            append(" spotifyId=").append(track.id)
            append(" spotifyTitle=").append(track.name.logSafe())
            append(" spotifyArtist=").append(track.artists.joinToString(" ") { it.name }.logSafe())
            append(" spotifyAlbum=").append(track.album?.name.orEmpty().logSafe())
            append(" spotifyDurationMs=").append(track.durationMs)
            append(" queries=").append(queryCount)
            append(" accepted=").append(selected != null)
            append(" selected=").append(selected?.song?.id.orEmpty())
            append(" candidates=[")
            candidates.forEachIndexed { index, candidate ->
                if (index > 0) append(" ; ")
                append(candidate.song.id)
                append('|').append(candidate.song.title.logSafe())
                append('|').append(candidate.song.artists.joinToString(" ") { it.name }.logSafe())
                append('|').append(candidate.song.album?.name.orEmpty().logSafe())
                append('|').append(candidate.song.duration ?: -1)
                append("|score=").append("%.3f".format(candidate.score.total))
                append("/title=").append("%.3f".format(candidate.score.title))
                append("/artistJoined=").append("%.3f".format(candidate.score.artist))
                append("/artistBest=").append("%.3f".format(candidate.artistScore))
                append("/duration=").append("%.3f".format(candidate.score.duration))
                append("/album=").append("%.3f".format(candidate.albumScore))
                append("/versionMismatch=").append(hasVersionMismatch(track, candidate.song))
                append("/ok=").append(isAcceptableMatch(track, candidate))
            }
            append(']')
        }

    private suspend fun downloadImage(url: String): ByteArray = withContext(Dispatchers.IO) {
        val uri = URI.create(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Spotify image URL was not HTTPS" }
        val request = Request.Builder().url(url).get().build()
        imageClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Spotify image request failed with HTTP ${response.code}" }
            val body = response.body
            val declaredLength = body.contentLength()
            require(declaredLength <= MAX_COVER_BYTES || declaredLength == -1L) { "Spotify image is too large" }
            val bytes = body.bytes()
            require(bytes.isNotEmpty()) { "Spotify image was empty" }
            require(bytes.size.toLong() <= MAX_COVER_BYTES) { "Spotify image is too large" }
            bytes
        }
    }
}

internal fun orderedPlaylistAddBatches(
    videoIds: List<String>,
    batchSize: Int = PLAYLIST_ADD_BATCH_SIZE,
): List<List<String>> {
    require(batchSize > 0) { "batchSize must be positive" }
    return videoIds.chunked(batchSize)
}

private data class MatchCandidate(
    val song: SongItem,
    val score: SpotifyMapper.MatchScoreComponents,
    val artistScore: Double,
    val albumScore: Double,
    val rank: Double,
)

private fun String.logSafe(): String =
    replace('\r', ' ')
        .replace('\n', ' ')
        .replace('|', '/')
        .take(240)

data class SpotifyPlaylistImportProgress(
    val message: String,
    val completed: Int = 0,
    val total: Int = 0,
)

data class SpotifyPlaylistImportResult(
    val playlistId: String,
    val name: String,
    val importedSongs: Int,
    val skippedSongs: Int,
    val coverCopied: Boolean,
    val warning: String? = null,
)

object SpotifyPlaylistLink {
    private val spotifyId = Regex("^[A-Za-z0-9]+$")

    fun parse(value: String): Result<String> =
        runCatching {
            val input = value.trim()
            require(input.isNotEmpty()) { "Paste a Spotify playlist link" }

            if (input.startsWith("spotify:playlist:", ignoreCase = true)) {
                return@runCatching validateId(input.substringAfterLast(':'))
            }

            val uri = URI.create(input)
            require(uri.scheme.equals("https", ignoreCase = true)) { "Use an https://open.spotify.com playlist link" }
            require(uri.host.equals("open.spotify.com", ignoreCase = true)) { "Use an open.spotify.com playlist link" }
            val parts = uri.path.split('/').filter(String::isNotBlank)
            val playlistIndex = parts.indexOfLast { it.equals("playlist", ignoreCase = true) }
            require(playlistIndex >= 0 && playlistIndex + 1 < parts.size) { "That is not a Spotify playlist link" }
            validateId(parts[playlistIndex + 1])
        }

    private fun validateId(value: String): String {
        val id = value.trim()
        require(id.length in 10..64 && spotifyId.matches(id)) { "That Spotify playlist ID is invalid" }
        return id
    }
}
