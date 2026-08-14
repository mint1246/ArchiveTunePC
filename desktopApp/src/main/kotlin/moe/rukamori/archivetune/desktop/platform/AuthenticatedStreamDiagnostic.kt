package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.desktopauth.DesktopSessionManager
import moe.rukamori.archivetune.desktopauth.DesktopSessionVerifier
import moe.rukamori.archivetune.desktopauth.DpapiFileSessionStore
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.StreamResolutionDiagnostic
import moe.rukamori.archivetune.innertube.StreamResolutionDiagnosticSink
import moe.rukamori.archivetune.innertube.classifyStreamResolutionFailure

/**
 * Local-only diagnostic for a user-approved installed session. It uses the existing DPAPI-backed
 * session manager and emits categories only: no cookie, token, URL, title, account, or reason.
 */
fun main() = runBlocking {
    val videoId = System.getenv("ARCHIVETUNE_DIAGNOSTIC_VIDEO_ID")?.trim().orEmpty()
    require(videoId.isNotEmpty()) { "Set ARCHIVETUNE_DIAGNOSTIC_VIDEO_ID to run this local diagnostic" }
    val paths = AppPaths.resolve()
    val session = DesktopSessionManager(
        secureStore = DpapiFileSessionStore(paths.data.resolve("youtube-session.dpapi")),
        verifier = DesktopSessionVerifier { Result.failure(IllegalStateException("Diagnostic does not verify or modify sessions")) },
    )
    session.restore()
    val diagnostic = session.diagnostic
    if (!diagnostic.signedIn) {
        println("authenticated_stream_diagnostic session=unavailable")
        return@runBlocking
    }

    val backend = ArchiveTuneJvmClient(
        cacheDirectory = paths.cache.resolve("cipher").toFile(),
        sessionProvider = session,
        streamDiagnostics = StreamResolutionDiagnosticSink { event ->
            when (event) {
                is StreamResolutionDiagnostic.Attempt -> println("authenticated_stream_diagnostic attempt client=${event.client} result=${event.classification.name}")
                is StreamResolutionDiagnostic.FormatAttempt -> println("authenticated_stream_diagnostic format client=${event.client} format=${event.format} result=${event.classification.name}")
                is StreamResolutionDiagnostic.Success -> println("authenticated_stream_diagnostic success client=${event.client}")
                is StreamResolutionDiagnostic.Failure -> println("authenticated_stream_diagnostic failure result=${event.classification.name}")
            }
        },
    )
    backend.resolveAudioStream(videoId).fold(
        onSuccess = { stream ->
            val source = if (stream.format.url != null) "direct" else "cipher"
            println("authenticated_stream_diagnostic resolved format=$source mime=${stream.mimeType.substringBefore(';')} bitrate=${stream.bitrate}")
        },
        onFailure = { failure ->
            println("authenticated_stream_diagnostic unresolved result=${classifyStreamResolutionFailure(failure).name}")
        },
    )
}
