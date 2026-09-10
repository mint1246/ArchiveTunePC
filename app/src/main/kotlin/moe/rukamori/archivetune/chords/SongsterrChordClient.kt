/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.chords

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class ChordRequest(
    val title: String,
    val artist: String,
    val durationMs: Long? = null,
    val videoId: String? = null,
)

enum class ChordTrackSource {
    SONGSTERR_AUTHORED,
    LOCAL_NEURAL,
    SONGSTERR_DERIVED_FROM_TAB,
}

data class ChordEvent(
    val startMs: Long,
    val endMs: Long,
    val name: String,
    val section: String? = null,
    val confidence: Float = 1f,
)

data class ChordTrack(
    val songsterrSongId: Int,
    val title: String,
    val artist: String,
    val guitarTrackName: String?,
    val source: ChordTrackSource,
    val events: List<ChordEvent>,
    val tabDurationMs: Long,
    val timingScale: Double = 1.0,
) {
    fun alignedTo(durationMs: Long?): ChordTrack {
        val target = durationMs?.takeIf { it > 0L } ?: return this
        if (tabDurationMs <= 0L || events.isEmpty()) return this
        val ratio = target.toDouble() / tabDurationMs.toDouble()
        // Only compensate for a different master / small tab tempo drift. A huge mismatch usually
        // means Songsterr matched a live/acoustic/cover version and scaling it would make things worse.
        if (ratio !in 0.88..1.12 || abs(ratio - 1.0) < 0.002) return this
        return copy(
            events = events.map { event ->
                event.copy(
                    startMs = (event.startMs * ratio).toLong(),
                    endMs = (event.endMs * ratio).toLong(),
                )
            },
            timingScale = ratio,
        )
    }
}

class ChordUnavailableException(message: String) : IllegalStateException(message)

/**
 * Keyless Songsterr chord provider.
 *
 * Song search and metadata come from Songsterr's JSON endpoints. Detailed player data is fetched
 * from the same CDN JSON used by Songsterr's web player. Authored chord labels are preferred.
 * When a useful tab has no labels, a conservative chord estimate is derived from the transcribed
 * notes (not from audio), so this path remains tiny and does not add a model/runtime to ArchiveTune.
 */
class SongsterrChordClient(
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build(),
) {
    suspend fun getChords(
        request: ChordRequest,
        allowDerived: Boolean = true,
    ): Result<ChordTrack> =
        withContext(Dispatchers.IO) {
            try {
                val normalized = request.normalized()
                val candidates = search(normalized)
                if (candidates.isEmpty()) throw ChordUnavailableException("No matching Songsterr tab was found")
                val (candidate, meta) = selectCandidate(normalized, candidates)
                val parsed = loadBestTrack(candidate, meta, allowDerived)
                    ?: throw ChordUnavailableException("Songsterr has a tab for this song, but no usable guitar chords")
                Result.success(parsed.alignedTo(normalized.durationMs))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }

    private fun ChordRequest.normalized(): ChordRequest =
        copy(
            title = title.trim(),
            artist = artist.trim(),
            durationMs = durationMs?.takeIf { it > 0L },
            videoId = videoId?.trim()?.takeIf(String::isNotEmpty),
        ).also {
            require(it.title.isNotEmpty()) { "Song title is required" }
            require(it.artist.isNotEmpty()) { "Song artist is required" }
        }

    private fun search(request: ChordRequest): List<SearchCandidate> {
        val query = "${request.title} ${request.artist}".trim()
        val url = "$SONGSTERR_BASE/api/songs?pattern=${urlEncode(query)}&size=$SEARCH_LIMIT"
        val results = getArray(url)
        val candidates = mutableListOf<SearchCandidate>()
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val songId = item.optInt("songId", -1)
            if (songId <= 0 || !item.optBoolean("hasPlayer", false)) continue
            val title = item.optString("title").trim()
            val artist = item.optString("artist").trim()
            if (title.isEmpty() || artist.isEmpty()) continue
            val score = songMatchScore(request.title, request.artist, title, artist)
            if (score >= MIN_MATCH_SCORE) {
                candidates += SearchCandidate(
                    songId = songId,
                    title = title,
                    artist = artist,
                    hasChords = item.optBoolean("hasChords", false),
                    score = score,
                )
            }
        }
        return candidates.sortedByDescending(SearchCandidate::score)
    }

    private fun selectCandidate(
        request: ChordRequest,
        candidates: List<SearchCandidate>,
    ): Pair<SearchCandidate, JSONObject> {
        val youtubeVideoId = request.videoId?.takeIf { YOUTUBE_VIDEO_ID.matches(it) }
        var fallback: Pair<SearchCandidate, JSONObject>? = null
        var lastFailure: Throwable? = null
        val limit = if (youtubeVideoId != null) MAX_CANDIDATES_FOR_VIDEO_MATCH else 1
        for (candidate in candidates.take(limit)) {
            val meta =
                try {
                    getObject("$SONGSTERR_BASE/api/meta/${candidate.songId}")
                } catch (failure: Exception) {
                    lastFailure = failure
                    continue
                }
            if (fallback == null) fallback = candidate to meta
            if (youtubeVideoId != null && meta.referencesVideo(youtubeVideoId)) return candidate to meta
        }
        return fallback ?: throw lastFailure ?: ChordUnavailableException("No readable Songsterr tab metadata was found")
    }

    private fun loadBestTrack(
        candidate: SearchCandidate,
        meta: JSONObject,
        allowDerived: Boolean,
    ): ChordTrack? {
        val songId = meta.optInt("songId", candidate.songId).takeIf { it > 0 } ?: candidate.songId
        val revisionId = meta.optInt("revisionId", -1)
        val image = meta.optString("image").trim()
        if (revisionId <= 0 || image.isEmpty()) return null

        val tracks = meta.optJSONArray("tracks") ?: JSONArray()
        val popularGuitar = meta.optInt("popularTrackGuitar", -1)
        val guitarIndices = buildList {
            if (popularGuitar in 0 until tracks.length()) add(popularGuitar)
            val other =
                (0 until tracks.length())
                    .filter { it != popularGuitar }
                    .mapNotNull { index ->
                        val track = tracks.optJSONObject(index) ?: return@mapNotNull null
                        if (!track.looksLikeGuitar()) return@mapNotNull null
                        index to track.optLong("views", 0L)
                    }
                    .sortedByDescending { it.second }
                    .map { it.first }
            addAll(other)
        }.distinct().take(MAX_GUITAR_TRACKS_TO_TRY)

        if (guitarIndices.isEmpty()) return null

        var fallback: Pair<ParsedTrack, Int>? = null
        for (trackIndex in guitarIndices) {
            val trackMeta = tracks.optJSONObject(trackIndex)
            val trackJson =
                runCatching {
                    getObject("$SONGSTERR_CDN/$songId/$revisionId/$image/$trackIndex.json")
                }.getOrNull() ?: continue
            val parsed = parseTrackJson(trackJson, allowDerived = allowDerived && fallback == null)
            if (parsed.authored.isNotEmpty()) {
                return ChordTrack(
                    songsterrSongId = songId,
                    title = candidate.title,
                    artist = candidate.artist,
                    guitarTrackName = trackMeta?.optString("name")?.trim()?.takeIf(String::isNotEmpty),
                    source = ChordTrackSource.SONGSTERR_AUTHORED,
                    events = parsed.authored,
                    tabDurationMs = parsed.durationMs,
                )
            }
            if (allowDerived && fallback == null && parsed.derived.size >= MIN_DERIVED_EVENTS) {
                fallback = parsed to trackIndex
            }
            // Songsterr advertises authored chords somewhere in the arrangement, so keep looking.
            if (!candidate.hasChords && (fallback != null || !allowDerived)) break
        }

        if (!allowDerived) return null
        val (derived, derivedTrackIndex) = fallback ?: return null
        val trackMeta = tracks.optJSONObject(derivedTrackIndex)
        return ChordTrack(
            songsterrSongId = songId,
            title = candidate.title,
            artist = candidate.artist,
            guitarTrackName = trackMeta?.optString("name")?.trim()?.takeIf(String::isNotEmpty),
            source = ChordTrackSource.SONGSTERR_DERIVED_FROM_TAB,
            events = derived.derived,
            tabDurationMs = derived.durationMs,
        )
    }

    private fun getArray(url: String): JSONArray = JSONArray(get(url))

    private fun getObject(url: String): JSONObject = JSONObject(get(url))

    private fun get(url: String): String {
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Songsterr request failed (${response.code})")
            }
            val body = response.body.string()
            if (body.length > MAX_JSON_CHARS) throw IllegalStateException("Songsterr response was unexpectedly large")
            return body
        }
    }

    private data class SearchCandidate(
        val songId: Int,
        val title: String,
        val artist: String,
        val hasChords: Boolean,
        val score: Double,
    )

    companion object {
        private const val SONGSTERR_BASE = "https://www.songsterr.com"
        private const val SONGSTERR_CDN = "https://dqsljvtekg760.cloudfront.net"
        private const val SEARCH_LIMIT = 8
        private const val MAX_CANDIDATES_FOR_VIDEO_MATCH = 3
        private const val MAX_GUITAR_TRACKS_TO_TRY = 4
        private const val MAX_JSON_CHARS = 4 * 1024 * 1024
        private const val MIN_MATCH_SCORE = 0.68
        private const val MIN_DERIVED_EVENTS = 4
        private const val USER_AGENT = "ArchiveTune/android chord-provider"
        private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        internal fun parseTrackJsonForTest(json: String, allowDerived: Boolean = true): ParsedTrack =
            parseTrackJson(JSONObject(json), allowDerived)
    }
}

internal data class ParsedTrack(
    val authored: List<ChordEvent>,
    val derived: List<ChordEvent>,
    val durationMs: Long,
)

private data class TimedNote(
    val startMs: Long,
    val endMs: Long,
    val pitches: IntArray,
    val section: String?,
)

private data class TempoPoint(val measure: Int, val bpm: Double)

private data class ChordTemplate(val suffix: String, val intervals: IntArray)

private fun parseTrackJson(track: JSONObject, allowDerived: Boolean): ParsedTrack {
    val measures = track.optJSONArray("measures") ?: JSONArray()
    if (measures.length() == 0) return ParsedTrack(emptyList(), emptyList(), 0L)
    val tuning =
        track.optJSONArray("tuning")
            ?.let { array -> IntArray(array.length()) { index -> array.optInt(index, Int.MIN_VALUE) } }
            ?: IntArray(0)
    val tempos = parseTempos(track.optJSONObject("automations"))
    var bpm = tempos.firstOrNull()?.takeIf { it.measure <= 0 }?.bpm ?: 120.0
    var tempoIndex = 0
    while (tempoIndex < tempos.size && tempos[tempoIndex].measure <= 0) {
        bpm = tempos[tempoIndex].bpm
        tempoIndex++
    }

    var timelineMs = 0.0
    var section: String? = null
    val authoredRaw = mutableListOf<ChordEvent>()
    val notes = if (allowDerived) mutableListOf<TimedNote>() else null

    for (measureIndex in 0 until measures.length()) {
        while (tempoIndex < tempos.size && tempos[tempoIndex].measure <= measureIndex) {
            bpm = tempos[tempoIndex].bpm
            tempoIndex++
        }
        val measure = measures.optJSONObject(measureIndex) ?: continue
        measure.optJSONObject("marker")
            ?.optString("text")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { section = it }

        val voices = measure.optJSONArray("voices") ?: JSONArray()
        var measureQuarterBeats = 0.0
        for (voiceIndex in 0 until voices.length()) {
            val voice = voices.optJSONObject(voiceIndex) ?: continue
            val beats = voice.optJSONArray("beats") ?: continue
            var beatOffsetQuarter = 0.0
            for (beatIndex in 0 until beats.length()) {
                val beat = beats.optJSONObject(beatIndex) ?: continue
                val durationQuarter = beatQuarterDuration(beat)
                val startMs = timelineMs + quarterBeatsToMs(beatOffsetQuarter, bpm)
                val endMs = startMs + quarterBeatsToMs(durationQuarter, bpm)

                beat.optJSONObject("chord")
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { chord ->
                        authoredRaw +=
                            ChordEvent(
                                startMs = startMs.toLong().coerceAtLeast(0L),
                                endMs = endMs.toLong().coerceAtLeast(0L),
                                name = chord,
                                section = section,
                            )
                    }

                if (notes != null && tuning.isNotEmpty()) {
                    val pitches = beatPitches(beat, tuning)
                    if (pitches.isNotEmpty()) {
                        notes +=
                            TimedNote(
                                startMs = startMs.toLong().coerceAtLeast(0L),
                                endMs = endMs.toLong().coerceAtLeast(0L),
                                pitches = pitches,
                                section = section,
                            )
                    }
                }
                beatOffsetQuarter += durationQuarter
            }
            measureQuarterBeats = max(measureQuarterBeats, beatOffsetQuarter)
        }
        if (measureQuarterBeats <= 0.0) {
            val signature = measure.optJSONArray("signature")
            val numerator = signature?.optInt(0, 4)?.coerceAtLeast(1) ?: 4
            val denominator = signature?.optInt(1, 4)?.coerceAtLeast(1) ?: 4
            measureQuarterBeats = numerator * 4.0 / denominator
        }
        timelineMs += quarterBeatsToMs(measureQuarterBeats, bpm)
    }

    val durationMs = timelineMs.toLong().coerceAtLeast(0L)
    val authored = finalizeAuthoredEvents(authoredRaw, durationMs)
    val derived =
        if (allowDerived && authored.isEmpty() && notes != null) deriveChords(notes, durationMs)
        else emptyList()
    return ParsedTrack(authored, derived, durationMs)
}

private fun parseTempos(automations: JSONObject?): List<TempoPoint> {
    val tempo = automations?.optJSONArray("tempo") ?: return emptyList()
    return buildList {
        for (index in 0 until tempo.length()) {
            val point = tempo.optJSONObject(index) ?: continue
            // Position zero is the common/current Songsterr representation. In-measure tempo ramps
            // are intentionally ignored until their position contract is stable.
            if (point.optDouble("position", 0.0) != 0.0) continue
            val bpm = point.optDouble("bpm", 0.0)
            if (bpm > 0.0) add(TempoPoint(point.optInt("measure", 0).coerceAtLeast(0), bpm))
        }
    }.sortedBy { it.measure }
}

private fun beatQuarterDuration(beat: JSONObject): Double {
    val duration = beat.optJSONArray("duration") ?: return 0.0
    val numerator = duration.optDouble(0, 0.0)
    val denominator = duration.optDouble(1, 0.0)
    if (numerator <= 0.0 || denominator <= 0.0) return 0.0
    return 4.0 * numerator / denominator
}

private fun quarterBeatsToMs(quarterBeats: Double, bpm: Double): Double =
    if (bpm > 0.0) quarterBeats * 60_000.0 / bpm else 0.0

private fun beatPitches(beat: JSONObject, tuning: IntArray): IntArray {
    val notes = beat.optJSONArray("notes") ?: return IntArray(0)
    val result = mutableListOf<Int>()
    for (index in 0 until notes.length()) {
        val note = notes.optJSONObject(index) ?: continue
        if (note.optBoolean("rest", false)) continue
        val string = note.optInt("string", -1)
        val fret = note.optInt("fret", -1)
        if (string !in tuning.indices || fret < 0 || tuning[string] == Int.MIN_VALUE) continue
        result += tuning[string] + fret
    }
    return result.distinct().sorted().toIntArray()
}

private fun finalizeAuthoredEvents(raw: List<ChordEvent>, durationMs: Long): List<ChordEvent> {
    if (raw.isEmpty()) return emptyList()
    val sorted = raw.sortedBy { it.startMs }
    val collapsed = mutableListOf<ChordEvent>()
    for (event in sorted) {
        val previous = collapsed.lastOrNull()
        // Multiple voices can annotate the same beat. Prefer the first/primary voice instead of
        // flashing two different chord labels at the exact same timestamp.
        if (previous != null && previous.startMs == event.startMs) continue
        if (previous != null && previous.name == event.name && event.startMs - previous.startMs <= 120L) continue
        collapsed += event
    }
    return collapsed.mapIndexed { index, event ->
        val end = collapsed.getOrNull(index + 1)?.startMs ?: durationMs
        event.copy(endMs = max(event.startMs + 1L, end))
    }
}

private val chordTemplates =
    listOf(
        ChordTemplate("maj7", intArrayOf(0, 4, 7, 11)),
        ChordTemplate("m7b5", intArrayOf(0, 3, 6, 10)),
        ChordTemplate("m7", intArrayOf(0, 3, 7, 10)),
        ChordTemplate("7", intArrayOf(0, 4, 7, 10)),
        ChordTemplate("6", intArrayOf(0, 4, 7, 9)),
        ChordTemplate("m6", intArrayOf(0, 3, 7, 9)),
        ChordTemplate("add9", intArrayOf(0, 2, 4, 7)),
        ChordTemplate("madd9", intArrayOf(0, 2, 3, 7)),
        ChordTemplate("sus2", intArrayOf(0, 2, 7)),
        ChordTemplate("sus4", intArrayOf(0, 5, 7)),
        ChordTemplate("dim", intArrayOf(0, 3, 6)),
        ChordTemplate("aug", intArrayOf(0, 4, 8)),
        ChordTemplate("m", intArrayOf(0, 3, 7)),
        ChordTemplate("", intArrayOf(0, 4, 7)),
    )

private fun deriveChords(notes: List<TimedNote>, durationMs: Long): List<ChordEvent> {
    if (notes.isEmpty()) return emptyList()
    val windowMs = DERIVED_WINDOW_MS
    val first = (notes.first().startMs / windowMs) * windowMs
    val raw = mutableListOf<ChordEvent>()
    var windowStart = first
    while (windowStart < durationMs) {
        val windowEnd = (windowStart + windowMs).coerceAtMost(durationMs)
        val overlapping = notes.filter { it.startMs < windowEnd && it.endMs > windowStart }
        deriveWindowChord(overlapping)?.let { derived ->
            raw +=
                ChordEvent(
                    startMs = windowStart,
                    endMs = windowEnd,
                    name = derived.first,
                    section = overlapping.firstOrNull()?.section,
                    confidence = derived.second,
                )
        }
        windowStart += windowMs
    }
    if (raw.isEmpty()) return emptyList()

    val merged = mutableListOf<ChordEvent>()
    for (event in raw) {
        val previous = merged.lastOrNull()
        if (previous != null && previous.name == event.name && event.startMs - previous.endMs <= 80L) {
            merged[merged.lastIndex] =
                previous.copy(
                    endMs = event.endMs,
                    confidence = ((previous.confidence + event.confidence) / 2f),
                )
        } else {
            merged += event
        }
    }
    return merged.filter { it.endMs - it.startMs >= DERIVED_MIN_HOLD_MS }
}

private fun deriveWindowChord(notes: List<TimedNote>): Pair<String, Float>? {
    if (notes.isEmpty()) return null
    val weights = DoubleArray(12)
    var lowestPitch = Int.MAX_VALUE
    for (note in notes) {
        val weight = (note.endMs - note.startMs).coerceAtLeast(1L).toDouble()
        for (pitch in note.pitches) {
            weights[mod12(pitch)] += weight
            if (pitch < lowestPitch) lowestPitch = pitch
        }
    }
    val present = (0 until 12).filter { weights[it] > 0.0 }
    if (present.size == 2 && lowestPitch != Int.MAX_VALUE) {
        val bassPc = mod12(lowestPitch)
        val other = present.firstOrNull { it != bassPc }
        if (other != null) {
            val interval = mod12(other - bassPc)
            if (interval == 7) return "${pitchName(bassPc)}5" to 0.84f
            if (interval == 5) return "${pitchName(other)}5/${pitchName(bassPc)}" to 0.80f
        }
    }
    if (present.size < 3) return null
    val totalWeight = weights.sum().takeIf { it > 0.0 } ?: return null

    var bestName: String? = null
    var bestScore = 0.0
    for (root in 0 until 12) {
        for (template in chordTemplates) {
            val templateSet = template.intervals.map { mod12(root + it) }.toSet()
            val matchedWeight = templateSet.sumOf { weights[it] }
            val coverage = templateSet.count { weights[it] > 0.0 }.toDouble() / templateSet.size
            val precision = matchedWeight / totalWeight
            val rootPresent = weights[root] > 0.0
            var score = precision * 0.64 + coverage * 0.36
            if (rootPresent) score += 0.035
            if (template.intervals.size >= 4 && coverage < 0.75) score -= 0.08
            if (score > bestScore) {
                val bassPc = mod12(lowestPitch)
                val bassSuffix =
                    if (bassPc != root && bassPc in templateSet) "/${pitchName(bassPc)}" else ""
                bestName = pitchName(root) + template.suffix + bassSuffix
                bestScore = score
            }
        }
    }
    if (bestName == null || bestScore < DERIVED_MIN_CONFIDENCE) return null
    return bestName to bestScore.coerceIn(0.0, 1.0).toFloat()
}

private fun JSONObject.looksLikeGuitar(): Boolean {
    val instrument = optString("instrument").lowercase(Locale.ROOT)
    val name = optString("name").lowercase(Locale.ROOT)
    return "guitar" in instrument || "guitar" in name
}

private fun JSONObject.referencesVideo(videoId: String): Boolean {
    val videos = optJSONArray("videos") ?: return false
    for (index in 0 until videos.length()) {
        if (videos.optJSONObject(index)?.optString("videoId") == videoId) return true
    }
    return false
}

internal fun songMatchScore(
    wantedTitle: String,
    wantedArtist: String,
    candidateTitle: String,
    candidateArtist: String,
): Double {
    val titleScore = textSimilarity(wantedTitle, candidateTitle)
    val artistScore = textSimilarity(wantedArtist, candidateArtist)
    val exactTitle = normalizeText(wantedTitle) == normalizeText(candidateTitle)
    val exactArtist = normalizeText(wantedArtist) == normalizeText(candidateArtist)
    return (titleScore * 0.72 + artistScore * 0.28 + if (exactTitle) 0.08 else 0.0 + if (exactArtist) 0.04 else 0.0)
        .coerceIn(0.0, 1.0)
}

private fun textSimilarity(left: String, right: String): Double {
    val a = normalizeText(left)
    val b = normalizeText(right)
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    val containment =
        if (a.length >= 4 && b.length >= 4 && (a.contains(b) || b.contains(a))) {
            minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length) * 0.92
        } else {
            0.0
        }
    val aTokens = a.split(' ').filter(String::isNotEmpty).toSet()
    val bTokens = b.split(' ').filter(String::isNotEmpty).toSet()
    val intersection = aTokens.intersect(bTokens).size
    val tokenDice =
        if (aTokens.isEmpty() || bTokens.isEmpty()) 0.0
        else 2.0 * intersection / (aTokens.size + bTokens.size)
    val edit = 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
    return maxOf(containment, tokenDice, edit.coerceAtLeast(0.0))
}

private fun normalizeText(value: String): String {
    val normalized = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
    return buildString(normalized.length) {
        var previousSpace = false
        for (ch in normalized) {
            val category = Character.getType(ch)
            if (category == Character.NON_SPACING_MARK.toInt()) continue
            if (ch.isLetterOrDigit()) {
                append(ch)
                previousSpace = false
            } else if (!previousSpace && isNotEmpty()) {
                append(' ')
                previousSpace = true
            }
        }
    }.trim()
}

private fun levenshtein(a: String, b: String): Int {
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in a.indices) {
        current[0] = i + 1
        for (j in b.indices) {
            val cost = if (a[i] == b[j]) 0 else 1
            current[j + 1] =
                minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost,
                )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

private fun urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

private fun mod12(value: Int): Int = ((value % 12) + 12) % 12

private fun pitchName(pc: Int): String = PITCH_NAMES[mod12(pc)]

private val PITCH_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

private const val DERIVED_WINDOW_MS = 1_000L
private const val DERIVED_MIN_HOLD_MS = 900L
private const val DERIVED_MIN_CONFIDENCE = 0.76
