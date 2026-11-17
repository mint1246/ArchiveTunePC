package example

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.social.AndroidSocialApi
import moe.rukamori.archivetune.social.presence.LastAppliedPresence
import moe.rukamori.archivetune.social.presence.PresenceHoldContext
import moe.rukamori.archivetune.social.presence.PresenceMode
import moe.rukamori.archivetune.social.presence.PresencePlaybackStage
import moe.rukamori.archivetune.social.presence.PresencePolicyInputs
import moe.rukamori.archivetune.social.presence.deriveFinalPresenceDecision
import moe.rukamori.archivetune.social.scrobble.ScrobbleSessionCoordinator
import moe.rukamori.archivetune.social.scrobble.ScrobbleSink
import moe.rukamori.archivetune.social.scrobble.ScrobbleTrack

fun main(args: Array<String>) = runBlocking {
    val localServer: HttpServer? =
        if (args.isEmpty()) {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/me") { exchange ->
                    val payload =
                        """{"user":{"id":"u1","discordId":"d1","username":"headless","displayName":"Headless Demo"}}"""
                            .toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
                start()
            }
        } else {
            require(args.size == 1) { "Pass at most one social service base URL" }
            null
        }
    try {
        val baseUrl = localServer?.let { "http://127.0.0.1:${it.address.port}" } ?: args.single()
        val token =
            if (localServer != null) {
                "fixture-token"
            } else {
                requireNotNull(System.getenv("ARCHIVETUNE_SOCIAL_TOKEN")) { "Set ARCHIVETUNE_SOCIAL_TOKEN" }
            }
        val user = AndroidSocialApi(baseUrl).me(token)
        println("Signed in as ${user.name}")

    // Exercise platform-independent Rich Presence hold -> timeout state machine
    val lastVisible = LastAppliedPresence(songId = "demo-song", mode = PresenceMode.Playing, appliedAtMs = 1_000L)
    val bufferingInputs =
        PresencePolicyInputs(
            enabled = true,
            hasToken = true,
            songId = "demo-song",
            isPlaying = false,
            showWhenPaused = true,
            playWhenReady = true,
            playbackStage = PresencePlaybackStage.BUFFERING,
        )
    val holdResolution =
        deriveFinalPresenceDecision(
            input = bufferingInputs,
            holdContext =
                PresenceHoldContext(
                    nowMs = 2_000L,
                    activeHoldState = null,
                    lastAppliedVisiblePresence = lastVisible,
                    holdTimeoutMs = 5_000L,
                ),
        )
    val timedOutResolution =
        deriveFinalPresenceDecision(
            input = bufferingInputs,
            holdContext =
                PresenceHoldContext(
                    nowMs = 7_500L,
                    activeHoldState = holdResolution.nextHoldState,
                    lastAppliedVisiblePresence = lastVisible,
                    holdTimeoutMs = 5_000L,
                ),
        )
    println("Presence policy: hold=${holdResolution.decision}, timeout=${timedOutResolution.decision}")

    // Exercise headless ScrobbleSessionCoordinator
    val sink =
        object : ScrobbleSink {
            override suspend fun scrobble(
                track: ScrobbleTrack,
                startedAtEpochSeconds: Long,
            ): Result<Unit> {
                println("Scrobbled ${track.title} by ${track.formattedArtistString()} (startedAt=$startedAtEpochSeconds)")
                return Result.success(Unit)
            }

            override suspend fun updateNowPlaying(track: ScrobbleTrack): Result<Unit> {
                println("Now playing: ${track.title} by ${track.formattedArtistString()}")
                return Result.success(Unit)
            }
        }
    val coordinator =
        ScrobbleSessionCoordinator(
            scope = this,
            sink = sink,
        ).apply {
            minSongDurationSeconds = 10
            scrobbleDelayPercent = 0.01f
            scrobbleDelaySeconds = 1
        }
    val track = ScrobbleTrack(title = "Headless Demo", artists = listOf("ArchiveTune"), durationSeconds = 60)
    coordinator.onSongStart(track)
    delay(700L)
    coordinator.onSongPause()
    coordinator.onSongResume(track)
    coordinator.close()
    } finally {
        localServer?.stop(0)
    }
}
