package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.MissingFieldException
import moe.rukamori.archivetune.desktopauth.ArchiveTuneSessionVerifier
import moe.rukamori.archivetune.desktopauth.DesktopAccountState
import moe.rukamori.archivetune.desktopauth.DesktopSessionManager
import moe.rukamori.archivetune.desktopauth.DpapiFileSessionStore
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.StreamResolutionDiagnostic
import moe.rukamori.archivetune.innertube.StreamResolutionDiagnosticSink
import moe.rukamori.archivetune.innertube.classifyStreamResolutionFailure
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.probeResolvedAudioStream

/**
 * Local-only diagnostic for a user-approved installed session. It uses the existing DPAPI-backed
 * session manager and emits categories only: no cookie, token, URL, title, account, or reason.
 */
fun main() = runBlocking {
    val videoId = System.getenv("ARCHIVETUNE_DIAGNOSTIC_VIDEO_ID")?.trim().orEmpty()
    require(videoId.isNotEmpty()) { "Set ARCHIVETUNE_DIAGNOSTIC_VIDEO_ID to run this local diagnostic" }
    val paths = AppPaths.resolve()
    val backend = ArchiveTuneJvmClient(
        cacheDirectory = paths.cache.resolve("cipher").toFile(),
        streamDiagnostics = StreamResolutionDiagnosticSink { event ->
            when (event) {
                is StreamResolutionDiagnostic.Attempt -> println("authenticated_stream_diagnostic attempt client=${event.client} result=${event.classification.name}")
                is StreamResolutionDiagnostic.FormatAttempt -> println("authenticated_stream_diagnostic format client=${event.client} format=${event.format} result=${event.classification.name}")
                is StreamResolutionDiagnostic.PlayerResponse -> println("authenticated_stream_diagnostic player client=${event.client} status=${event.status} adaptive=${event.adaptiveFormats} audio=${event.audioFormats} direct=${event.directUrls} cipher=${event.ciphers}")
                is StreamResolutionDiagnostic.LibraryResponse -> println("authenticated_stream_diagnostic library_shape single_tabs=${event.singleTabs} two_tabs=${event.twoColumnTabs} direct_sections=${event.directSections} secondary_sections=${event.secondarySections} selected_sections=${event.selectedSections} renderer_items=${event.rendererItems} parsed_items=${event.parsedItems} renderer_keys=${event.rendererKeys.joinToString(",")}")
                is StreamResolutionDiagnostic.Success -> println("authenticated_stream_diagnostic success client=${event.client}")
                is StreamResolutionDiagnostic.Failure -> println("authenticated_stream_diagnostic failure result=${event.classification.name}")
            }
        },
    )
    val session = DesktopSessionManager(
        secureStore = DpapiFileSessionStore(paths.data.resolve("youtube-session.dpapi")),
        verifier = ArchiveTuneSessionVerifier(backend),
    )
    session.restore()
    backend.bindSessionProvider(session)
    val diagnostic = session.diagnostic
    if (!diagnostic.signedIn) {
        println("authenticated_stream_diagnostic session=unavailable")
        return@runBlocking
    }
    if (session.state.value is DesktopAccountState.SignedIn) {
        session.refreshRestoredSession()
    }
    val auth = session.currentAuthState()
    // Presence flags only: never print a proof-token value or session identifier.
    println(
        "authenticated_stream_diagnostic auth_context " +
            "visitor=${!auth.visitorData.isNullOrBlank()} data_sync=${!auth.dataSyncId.isNullOrBlank()} " +
            "delegated=${!auth.delegatedSessionId.isNullOrBlank()} " +
            "web_po_enabled=${auth.webClientPoTokenEnabled} player_po=${!auth.poTokenPlayer.isNullOrBlank()} " +
            "gvs_po=${!auth.poTokenGvs.isNullOrBlank()}",
    )
    // Section titles are user-facing labels, not credentials or identifiers, and directly show
    // whether the signed-in home feed is personalized or degraded to the anonymous region feed.
    backend.home().fold(
        onSuccess = { home ->
            val titles = home.sections.map { it.title }
            println(
                "authenticated_stream_diagnostic home result=OK sections=${home.sections.size} " +
                    "titles=${titles.joinToString("|")}",
            )
        },
        onFailure = { failure ->
            val status = Regex("\\b[45]\\d\\d\\b").find(failure.message.orEmpty())?.value ?: "none"
            println(
                "authenticated_stream_diagnostic home result=FAILED type=${failure::class.simpleName} status=$status",
            )
        },
    )
    if (!System.getenv("ARCHIVETUNE_DIAGNOSTIC_PLAYLISTS_ONLY").equals("true", ignoreCase = true)) backend.resolveAudioStream(videoId).fold(
        onSuccess = { stream ->
            val source = if (stream.format.url != null) "direct" else "cipher"
            println("authenticated_stream_diagnostic resolved format=$source mime=${stream.mimeType.substringBefore(';')} bitrate=${stream.bitrate}")
            probeResolvedAudioStream(stream).fold(
                onSuccess = { probe ->
                    println(
                        "authenticated_stream_diagnostic probe status=${probe.httpStatus} " +
                            "bytes=${probe.bytesRead} ranged=${probe.rangedResponse}",
                    )
                },
                onFailure = {
                    println("authenticated_stream_diagnostic probe result=FAILED")
                },
            )
        },
        onFailure = { failure ->
            println("authenticated_stream_diagnostic unresolved result=${classifyStreamResolutionFailure(failure).name}")
        },
    )

    backend.libraryPlaylists().fold(
        onSuccess = { library ->
            val playlists = library.items.filterIsInstance<PlaylistItem>()
            println(
                "authenticated_stream_diagnostic playlists result=OK total=${library.items.size} " +
                    "count=${playlists.size} continuation=${library.continuation != null}",
            )
            playlists.forEachIndexed { index, playlist ->
                backend.playlist(playlist.id).fold(
                    onSuccess = { detail ->
                        println("authenticated_stream_diagnostic playlist_detail ordinal=$index result=OK songs=${detail.songs.size}")
                    },
                    onFailure = { failure ->
                        val status = Regex("\\b[45]\\d\\d\\b").find(failure.message.orEmpty())?.value ?: "none"
                        val causes = generateSequence(failure as Throwable?) { it.cause }.toList()
                        val missing = causes.filterIsInstance<MissingFieldException>().flatMap { it.missingFields }.joinToString(",")
                        val types = causes.joinToString(",") { it::class.simpleName ?: "unknown" }
                        println(
                            "authenticated_stream_diagnostic playlist_detail result=FAILED " +
                                "ordinal=$index types=$types status=$status missing=$missing",
                        )
                    },
                )
            }
        },
        onFailure = { failure ->
            val status = Regex("\\b[45]\\d\\d\\b").find(failure.message.orEmpty())?.value ?: "none"
            val missing = (failure as? MissingFieldException)?.missingFields.orEmpty().joinToString(",")
            println(
                "authenticated_stream_diagnostic playlists result=FAILED type=${failure::class.simpleName} " +
                    "status=$status missing=$missing",
            )
        },
    )
    Unit
}
