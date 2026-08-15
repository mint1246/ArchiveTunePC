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
import moe.rukamori.archivetune.lyrics.BuiltInLyricsProviders
import moe.rukamori.archivetune.desktop.lyrics.DesktopLyricsPresenter
import moe.rukamori.archivetune.desktop.library.DesktopLibrary
import moe.rukamori.archivetune.desktop.library.DesktopLibraryFolderStore
import moe.rukamori.archivetune.desktop.library.PropertiesDesktopLibraryFolderStore
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.desktop.DesktopConcretePlaybackSourceResolver
import moe.rukamori.archivetune.playback.desktop.MpvPlaybackController
import moe.rukamori.archivetune.playback.desktop.PlaybackQueueStore
import moe.rukamori.archivetune.windowsmedia.PlaybackMediaCommandRouter
import moe.rukamori.archivetune.windowsmedia.WindowsMediaControls
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
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
    val settingsStore get() = settings
    val backend = ArchiveTuneJvmClient(
        runtime.paths.cache.resolve("cipher").toFile().also(File::mkdirs),
        streamDiagnostics = StreamResolutionDiagnosticSink(::logStreamDiagnostic),
    )
    /** The backend consults this provider immediately before each request, including downloads. */
    val account = DesktopSessionManager(
        secureStore = DpapiFileSessionStore(runtime.paths.data.resolve("youtube-session.dpapi")),
        verifier = ArchiveTuneSessionVerifier(backend),
    )
    @Volatile private var completedDownloadLookup: ((String) -> Path?)? = null
    /** Signed sources live only in memory and are discarded before their server expiry. */
    private val resolvedSourceCache = ConcurrentHashMap<String, CachedResolvedSource>()
    val playback = MpvPlaybackController(PlaybackSourceResolver { item -> resolve(item) })
    private val queueStore = PlaybackQueueStore(runtime.paths.data.resolve("persistent-queue.json"))
    private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lyricsClient = configuredLyricsClient()
    val lyrics = DesktopLyricsPresenter(playback, lyricsClient, lyricsScope)
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
        // Restore the DPAPI session synchronously before the first UI-driven backend call can race
        // ahead of it. Network revalidation stays asynchronous so startup is not blocked on it.
        account.restore()
        runtime.applicationScope.launch {
            account.refreshRestoredSession()
            restoreQueueIfPresent()
        }
        val restored = settings.snapshot()
        runtime.applicationScope.launch {
            playback.setVolume(restored.volume)
            playback.setSpeed(restored.speed)
            playback.setRepeatMode(restored.repeatMode.toPlaybackMode())
            playback.setShuffleEnabled(restored.shuffleEnabled)
        }
        runtime.applicationScope.launch {
            playback.state
                .map { state ->
                    PlaybackPreferences(
                        volume = state.volume,
                        speed = state.speed,
                        repeatMode = state.repeatMode,
                        shuffleEnabled = state.shuffleEnabled,
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .debounce(500)
                .collect { state ->
                    settings.update {
                        it.copy(
                            volume = state.volume,
                            speed = state.speed,
                            repeatMode = state.repeatMode.toDesktopMode(),
                            shuffleEnabled = state.shuffleEnabled,
                        )
                    }
                }
        }
        runtime.applicationScope.launch {
            playback.state
                .map { state ->
                    PersistedQueueKey(
                        title = state.queueTitle,
                        queue = state.queue,
                        currentIndex = state.currentIndex,
                        positionBucket = state.positionMs / POSITION_BUCKET_MS,
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { queueStore.save(playback.state.value) }
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

    private suspend fun restoreQueueIfPresent() {
        val persisted = queueStore.load() ?: return
        if (persisted.items.isEmpty()) return
        playback.replaceQueue(
            items = persisted.items,
            startIndex = persisted.currentIndex.coerceIn(0, persisted.items.lastIndex),
            startPositionMs = persisted.positionMs,
            title = persisted.title,
            playWhenReady = false,
        )
    }

    /** Connects the download service after both platform coordinators have been constructed. */
    fun attachCompletedDownloadLookup(lookup: (String) -> Path?) {
        completedDownloadLookup = lookup
    }

    private suspend fun resolve(item: PlaybackItem): Result<ResolvedPlaybackSource> = when (val source = item.source) {
        is PlaybackSource.Deferred -> completedDownloadLookup?.invoke(source.key)?.let { path ->
            Result.success(ResolvedPlaybackSource(path.toUri().toASCIIString()))
        } ?: cachedSourceFor(source.key)?.let { Result.success(it) }
            ?: backend.resolveAudioStream(source.key).fold(
                onSuccess = {
                    val resolved = ResolvedPlaybackSource(it.url, it.mimeType, it.requestHeaders, it.expiresAtEpochMillis)
                    cacheSource(source.key, resolved)
                    Result.success(resolved)
                },
                onFailure = { failure ->
                    val classification = classifyStreamResolutionFailure(failure)
                    runtime.logger.warning("stream_resolution event=player_handoff result=${classification.name}")
                    Result.failure(DesktopStreamResolutionException(classification, failure))
                },
            )
        is PlaybackSource.Remote, is PlaybackSource.LocalFile -> DesktopConcretePlaybackSourceResolver.resolve(item)
    }

    private fun cachedSourceFor(key: String): ResolvedPlaybackSource? {
        val cached = resolvedSourceCache[key] ?: return null
        if (cached.sessionFingerprint != account.currentAuthState().fingerprint) {
            resolvedSourceCache.remove(key, cached)
            return null
        }
        val expiresAt = cached.source.expiresAtEpochMs ?: run {
            resolvedSourceCache.remove(key, cached)
            return null
        }
        if (expiresAt <= System.currentTimeMillis() + STREAM_CACHE_EXPIRY_LEAD_MILLIS) {
            resolvedSourceCache.remove(key, cached)
            return null
        }
        return cached.source
    }

    private fun cacheSource(key: String, source: ResolvedPlaybackSource) {
        val expiresAt = source.expiresAtEpochMs ?: return
        if (expiresAt <= System.currentTimeMillis() + STREAM_CACHE_EXPIRY_LEAD_MILLIS) return
        if (resolvedSourceCache.size >= MAX_RESOLVED_SOURCE_CACHE_ENTRIES) {
            resolvedSourceCache.entries.removeIf { (_, value) ->
                value.source.expiresAtEpochMs?.let { it <= System.currentTimeMillis() + STREAM_CACHE_EXPIRY_LEAD_MILLIS } != false
            }
        }
        if (resolvedSourceCache.size >= MAX_RESOLVED_SOURCE_CACHE_ENTRIES) resolvedSourceCache.clear()
        resolvedSourceCache[key] = CachedResolvedSource(source, account.currentAuthState().fingerprint)
    }

    private fun logStreamDiagnostic(event: StreamResolutionDiagnostic) {
        when (event) {
            is StreamResolutionDiagnostic.Attempt -> runtime.logger.info("stream_resolution event=attempt client=${event.client} result=${event.classification.name}")
            is StreamResolutionDiagnostic.FormatAttempt -> runtime.logger.info("stream_resolution event=format client=${event.client} format=${event.format} result=${event.classification.name}")
            is StreamResolutionDiagnostic.PlayerResponse -> runtime.logger.info("stream_resolution event=player client=${event.client} status=${event.status} adaptive=${event.adaptiveFormats} audio=${event.audioFormats} direct=${event.directUrls} cipher=${event.ciphers}")
            is StreamResolutionDiagnostic.LibraryResponse -> runtime.logger.info("library_response single_tabs=${event.singleTabs} two_tabs=${event.twoColumnTabs} direct_sections=${event.directSections} secondary_sections=${event.secondarySections} selected_sections=${event.selectedSections} renderer_items=${event.rendererItems} parsed_items=${event.parsedItems} renderer_keys=${event.rendererKeys.joinToString(",")}")
            is StreamResolutionDiagnostic.Success -> runtime.logger.info("stream_resolution event=success client=${event.client}")
            is StreamResolutionDiagnostic.Failure -> runtime.logger.warning("stream_resolution event=failure result=${event.classification.name}")
        }
    }

    override fun close() {
        val state = playback.state.value
        queueStore.save(state)
        settings.update {
            it.copy(
                volume = state.volume,
                speed = state.speed,
                repeatMode = state.repeatMode.toDesktopMode(),
                shuffleEnabled = state.shuffleEnabled,
            )
        }
        lyrics.close()
        lyricsScope.cancel()
        windowsMediaControls?.close()
        playback.close()
        resolvedSourceCache.clear()
        backend.bindSessionProvider(null)
    }

    private fun configuredLyricsClient(): JvmLyricsClient {
        val defaults = BuiltInLyricsProviders.defaults
        val selected = settings.snapshot().lyricsProviderIds
        if (selected.isEmpty()) return JvmLyricsClient(defaults)
        val byId = defaults.associateBy { it.id }
        val configured = selected.mapNotNull(byId::get)
        return JvmLyricsClient(configured.ifEmpty { defaults })
    }

    private companion object {
        const val MAX_RESOLVED_SOURCE_CACHE_ENTRIES = 128
        const val STREAM_CACHE_EXPIRY_LEAD_MILLIS = 60_000L
        const val POSITION_BUCKET_MS = 5_000L
    }
}

private data class PlaybackPreferences(
    val volume: Float,
    val speed: Float,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
)

private data class PersistedQueueKey(
    val title: String?,
    val queue: List<PlaybackItem>,
    val currentIndex: Int,
    val positionBucket: Long,
)

private data class CachedResolvedSource(
    val source: ResolvedPlaybackSource,
    val sessionFingerprint: String?,
)

private fun moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode.toPlaybackMode(): RepeatMode =
    RepeatMode.valueOf(name)

private fun RepeatMode.toDesktopMode(): moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode =
    moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode.valueOf(name)

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
