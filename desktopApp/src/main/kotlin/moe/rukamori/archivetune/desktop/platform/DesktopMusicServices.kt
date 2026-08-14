package moe.rukamori.archivetune.desktop.platform

import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.StreamResolutionDiagnostic
import moe.rukamori.archivetune.innertube.StreamResolutionDiagnosticSink
import moe.rukamori.archivetune.innertube.StreamResolutionFailureKind
import moe.rukamori.archivetune.innertube.classifyStreamResolutionFailure
import moe.rukamori.archivetune.desktopauth.ArchiveTuneSessionVerifier
import moe.rukamori.archivetune.desktopauth.DesktopSessionManager
import moe.rukamori.archivetune.desktopauth.DpapiFileSessionStore
import moe.rukamori.archivetune.lyrics.JvmLyricsClient
import moe.rukamori.archivetune.desktop.lyrics.DesktopLyricsPresenter
import moe.rukamori.archivetune.desktop.library.DesktopLibrary
import moe.rukamori.archivetune.desktop.library.DesktopLibraryFolderStore
import moe.rukamori.archivetune.desktop.library.PropertiesDesktopLibraryFolderStore
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.desktop.MpvPlaybackController
import moe.rukamori.archivetune.windowsmedia.PlaybackMediaCommandRouter
import moe.rukamori.archivetune.windowsmedia.WindowsMediaControls
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The small desktop-only seam between ArchiveTune's backend and the native playback engine. */
class DesktopMusicServices(private val runtime: DesktopRuntime) : AutoCloseable {
    private val settings = runtime.settings
    val backend = ArchiveTuneJvmClient(
        runtime.paths.cache.resolve("cipher").toFile().also(File::mkdirs),
        streamDiagnostics = StreamResolutionDiagnosticSink(::logStreamDiagnostic),
    )
    /** The backend consults this provider immediately before each request, including downloads. */
    val account = DesktopSessionManager(
        secureStore = DpapiFileSessionStore(runtime.paths.data.resolve("youtube-session.dpapi")),
        verifier = ArchiveTuneSessionVerifier(backend),
    )
    val playback = MpvPlaybackController(PlaybackSourceResolver { item -> resolve(item) })
    private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val lyrics = DesktopLyricsPresenter(playback, JvmLyricsClient(), lyricsScope)
    private val legacyLibraryFile = runtime.paths.data.resolve("local-library.properties")
    val library = DesktopLibrary(legacyLibraryFile, folderStore = SettingsFolderStore(settings, legacyLibraryFile))
    // This is intentionally hardware-key only. Windows SMTC/flyout publishing is not implemented.
    private val windowsMediaControls = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        WindowsMediaControls()
    } else {
        null
    }
    private val mediaCommandRouter = windowsMediaControls?.let { PlaybackMediaCommandRouter(playback) }

    init {
        backend.bindSessionProvider(account)
        runtime.applicationScope.launch {
            account.restore()
        }
        val restored = settings.snapshot()
        runtime.applicationScope.launch {
            playback.setVolume(restored.volume)
            playback.setSpeed(restored.speed)
        }
        runtime.applicationScope.launch {
            playback.state
                .map { it.volume to it.speed }
                .distinctUntilChanged()
                .drop(1)
                .debounce(500)
                .collect { (volume, speed) -> settings.update { it.copy(volume = volume, speed = speed) } }
        }
        windowsMediaControls?.let { controls ->
            controls.start()
            runtime.applicationScope.launch {
                playback.state.collect(controls::update)
            }
            runtime.applicationScope.launch {
                controls.commands.collect { command -> mediaCommandRouter?.handle(command) }
            }
        }
    }

    private suspend fun resolve(item: PlaybackItem): Result<ResolvedPlaybackSource> = when (val source = item.source) {
        is PlaybackSource.Deferred -> backend.resolveAudioStream(source.key).fold(
            onSuccess = { Result.success(ResolvedPlaybackSource(it.url, it.mimeType, it.requestHeaders, it.expiresAtEpochMillis)) },
            onFailure = { failure ->
                val classification = classifyStreamResolutionFailure(failure)
                runtime.logger.warning("stream_resolution event=player_handoff result=${classification.name}")
                Result.failure(DesktopStreamResolutionException(classification, failure))
            },
        )
        is PlaybackSource.Remote -> Result.success(
            ResolvedPlaybackSource(source.url, source.mimeType, source.headers, source.expiresAtEpochMs),
        )
        is PlaybackSource.LocalFile -> Result.success(ResolvedPlaybackSource(File(source.path).toURI().toString()))
    }

    private fun logStreamDiagnostic(event: StreamResolutionDiagnostic) {
        when (event) {
            is StreamResolutionDiagnostic.Attempt -> runtime.logger.info("stream_resolution event=attempt client=${event.client} result=${event.classification.name}")
            is StreamResolutionDiagnostic.FormatAttempt -> runtime.logger.info("stream_resolution event=format client=${event.client} format=${event.format} result=${event.classification.name}")
            is StreamResolutionDiagnostic.Success -> runtime.logger.info("stream_resolution event=success client=${event.client}")
            is StreamResolutionDiagnostic.Failure -> runtime.logger.warning("stream_resolution event=failure result=${event.classification.name}")
        }
    }

    override fun close() {
        val state = playback.state.value
        settings.update { it.copy(volume = state.volume, speed = state.speed) }
        lyrics.close()
        lyricsScope.cancel()
        windowsMediaControls?.close()
        playback.close()
        backend.bindSessionProvider(null)
    }
}

private class DesktopStreamResolutionException(
    classification: StreamResolutionFailureKind,
    cause: Throwable,
) : IllegalStateException(classification.userMessage, cause)

private class SettingsFolderStore(
    private val settings: moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore,
    private val legacyFile: Path,
) : DesktopLibraryFolderStore {
    override fun load(): List<Path> {
        val saved = settings.snapshot().libraryFolders
        if (saved.isNotEmpty()) return saved
        val legacy = PropertiesDesktopLibraryFolderStore(legacyFile).load()
        if (legacy.isEmpty()) return emptyList()
        settings.update { it.copy(libraryFolders = legacy) }
        Files.deleteIfExists(legacyFile)
        return legacy
    }

    override fun save(folders: List<Path>) {
        settings.update { it.copy(libraryFolders = folders) }
        Files.deleteIfExists(legacyFile)
    }
}
