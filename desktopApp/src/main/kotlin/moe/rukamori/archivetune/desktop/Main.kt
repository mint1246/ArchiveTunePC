/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.desktop.platform.DesktopPartyPlaybackPolicy
import moe.rukamori.archivetune.desktop.platform.DesktopAudioAnalysisRepository
import moe.rukamori.archivetune.desktop.platform.DesktopRuntime
import moe.rukamori.archivetune.desktop.platform.DesktopRuntimeBenchmark
import moe.rukamori.archivetune.desktop.platform.DesktopMusicServices
import moe.rukamori.archivetune.desktop.platform.DesktopDownloadServices
import moe.rukamori.archivetune.desktop.platform.DesktopUpdateCoordinator
import moe.rukamori.archivetune.desktop.party.PlayTogetherManager
import moe.rukamori.archivetune.desktop.party.PartyMode
import moe.rukamori.archivetune.desktop.party.PartyPhase
import moe.rukamori.archivetune.desktop.social.DesktopSocialApi
import moe.rukamori.archivetune.desktop.social.DesktopSocialManager
import moe.rukamori.archivetune.desktop.social.DesktopSocialSessionStore
import moe.rukamori.archivetune.desktop.social.SocialAccountState
import moe.rukamori.archivetune.desktop.social.SocialPresence
import moe.rukamori.archivetune.desktop.social.SocialRealtimeEvent
import moe.rukamori.archivetune.desktopauth.secureSessionStore
import moe.rukamori.archivetune.desktop.state.DesktopDestination
import moe.rukamori.archivetune.desktop.ui.ArchiveTuneDesktopApp
import moe.rukamori.archivetune.desktop.ui.ArchiveTuneWindowChrome
import moe.rukamori.archivetune.desktop.ui.DesktopBeatSyncController
import moe.rukamori.archivetune.desktop.ui.DesktopBeatSyncHost
import moe.rukamori.archivetune.desktop.ui.DesktopNotificationCenter
import moe.rukamori.archivetune.desktop.ui.DesktopNotificationKind
import moe.rukamori.archivetune.desktop.ui.socialSnapshot
import moe.rukamori.archivetune.desktop.settings.DesktopLastDestination
import moe.rukamori.archivetune.desktop.settings.DesktopKeybindAction
import moe.rukamori.archivetune.desktop.settings.DesktopThemeMode
import moe.rukamori.archivetune.desktop.settings.DesktopWindowPlacement
import moe.rukamori.archivetune.windowsmedia.WindowsWorkingSetTrimmer
import moe.rukamori.archivetune.linuxmedia.LinuxMemoryReclaimer
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import kotlin.math.max
import kotlin.math.min

fun main() {
    System.setProperty("awt.useSystemAAFontSettings", "lcd")
    System.setProperty("swing.aatext", "true")
    configureDesktopImageLoader()
    val runtime = DesktopRuntime.start()
    val musicServices = runtime.own(DesktopMusicServices(runtime))
    val runtimeBenchmark = DesktopRuntimeBenchmark.startIfRequested(runtime, musicServices)
    val downloadServices = runtime.own(DesktopDownloadServices(runtime, musicServices.backend))
    val updateCoordinator = DesktopUpdateCoordinator(runtime, musicServices.playback)
    val playTogether = runtime.own(PlayTogetherManager(musicServices.playback, runtime.paths.cache, runtime.logger))
    val social = runtime.own(
        DesktopSocialManager(
            scope = runtime.applicationScope,
            api = DesktopSocialApi(),
            sessionStore = DesktopSocialSessionStore(
                secureSessionStore(runtime.paths.data.resolve("social-session.dpapi")),
            ),
        ),
    )
    val notificationCenter = DesktopNotificationCenter(runtime.applicationScope)
    musicServices.attachPartyPlaybackPolicyProvider {
        val party = playTogether.state.value
        DesktopPartyPlaybackPolicy(
            localTransportAllowed = party.localTransportCommandsAllowed,
            persistSpeedAndRepeat = party.persistSpeedAndRepeat,
            persistShuffle = party.persistShuffle,
            automaticSeekingAllowed = playTogether.state.value.mode != PartyMode.GUEST,
        )
    }
    musicServices.attachPartyPlayPauseHandler(playTogether::togglePlayback)
    musicServices.attachPartySkipHandlers(playTogether::skipNext, playTogether::skipPrevious)
    musicServices.attachPartySeekHandler(playTogether::seekTo)
    musicServices.attachCompletedDownloadLookup(downloadServices::completedFileFor)
    runtime.applicationScope.launch {
        playTogether.state
            .map { state -> state.mode != PartyMode.NONE }
            .distinctUntilChanged()
            .collect(musicServices::setStatisticsPartyActive)
    }
    runtime.applicationScope.launch {
        social.restore()
    }
    runtime.applicationScope.launch {
        social.events.collect { event ->
            when (event) {
                is SocialRealtimeEvent.FriendRequest -> notificationCenter.post(
                    title = "Friend request",
                    message = "${event.user.name} (@${event.user.username}) sent you a friend request",
                    kind = DesktopNotificationKind.FRIEND,
                    destination = DesktopDestination.FRIENDS,
                )
                is SocialRealtimeEvent.FriendAccepted -> notificationCenter.post(
                    title = "Friend request accepted",
                    message = "${event.user.name} is now your friend on ArchiveTune",
                    kind = DesktopNotificationKind.SUCCESS,
                    destination = DesktopDestination.FRIENDS,
                )
                is SocialRealtimeEvent.PartyInvite -> notificationCenter.post(
                    title = "Listening party invite",
                    message = "${event.invite.sender.name} invited you to listen together",
                    kind = DesktopNotificationKind.PARTY,
                    destination = DesktopDestination.FRIENDS,
                    durationMs = 9_000L,
                )
                is SocialRealtimeEvent.ChatMessage -> {
                    val friend = social.state.value.friends.firstOrNull { it.id == event.message.senderId }
                    val sender = friend?.name ?: "Friend"
                    val preview = when (event.message.kind) {
                        moe.rukamori.archivetune.desktop.social.SocialChatMessageKind.TEXT ->
                            event.message.text.orEmpty().take(120)
                        moe.rukamori.archivetune.desktop.social.SocialChatMessageKind.SONG -> "Sent you a song"
                        moe.rukamori.archivetune.desktop.social.SocialChatMessageKind.QUEUE -> "Sent you a queue"
                        moe.rukamori.archivetune.desktop.social.SocialChatMessageKind.PLAYLIST -> "Sent you a playlist"
                    }
                    notificationCenter.post(
                        title = "Message from " + sender,
                        message = preview,
                        kind = DesktopNotificationKind.MESSAGE,
                        destination = DesktopDestination.FRIENDS,
                    )
                }
            }
        }
    }
    runtime.applicationScope.launch {
        val listeningPresence = musicServices.playback.state
            .map { snapshot ->
                snapshot.currentItem?.metadata?.let { metadata ->
                    SocialPresence(
                        songId = metadata.id,
                        title = metadata.title,
                        artist = metadata.artistText,
                        artworkUrl = metadata.artworkUri,
                    )
                } ?: SocialPresence()
            }
            .distinctUntilChanged()
        val partyJoinable = playTogether.state
            .map { state ->
                state.mode == PartyMode.HOST &&
                    state.phase == PartyPhase.CONNECTED &&
                    !state.invite.isNullOrBlank()
            }
            .distinctUntilChanged()
        combine(listeningPresence, partyJoinable) { presence, joinable ->
            presence.copy(partyJoinable = joinable)
        }.collect(social::publishPresence)
    }
    runtime.applicationScope.launch {
        combine(
            social.state.map { it.account is SocialAccountState.SignedIn },
            musicServices.statistics.revision,
        ) { signedIn, revision -> signedIn to revision }
            .distinctUntilChanged()
            .collect { (signedIn, _) ->
                if (signedIn) {
                    social.uploadStatistics(musicServices.statistics.socialSnapshot())
                }
            }
    }
    updateCoordinator.start()
    val restored = runtime.settings.snapshot()
    val beatAnalysisRepository = DesktopAudioAnalysisRepository(runtime.paths, musicServices.playback, runtime.logger)
    val beatSync = DesktopBeatSyncController(
        enabled = restored.beatSyncedVisualsEnabled,
        intensity = restored.beatSyncedVisualsIntensity,
    )
    var latestDestination = restored.lastDestination.asDesktopDestination()
    var latestTheme = restored.themeMode.asDesktopThemePreference()
    var latestAccentSeedArgb = restored.accentSeedArgb

    try {
        application(exitProcessOnExit = true) {
            val windowState = rememberWindowState(
                placement = if (restored.windowPlacement.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
                position = restored.windowPlacement.asWindowPosition(),
                size = DpSize(restored.windowPlacement.widthDp.dp, restored.windowPlacement.heightDp.dp),
            )

            fun closeApplication() {
                val absolutePosition = windowState.position as? WindowPosition.Absolute
                runtime.settings.update {
                    it.copy(
                        themeMode = latestTheme.asSettingsThemeMode(),
                        accentSeedArgb = latestAccentSeedArgb,
                        lastDestination = latestDestination.asSettingsDestination(),
                        windowPlacement = DesktopWindowPlacement(
                            widthDp = windowState.size.width.value,
                            heightDp = windowState.size.height.value,
                            xDp = absolutePosition?.x?.value,
                            yDp = absolutePosition?.y?.value,
                            maximized = windowState.placement == WindowPlacement.Maximized,
                        ),
                    )
                }
                runtime.close()
                exitApplication()
            }

            // GNOME/Wayland can map a translucent XWayland AWT window without painting it.
            // Prefer a standard opaque window on Linux until transparent Compose surfaces are verified.
            val linuxWindow = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
            Window(
                onCloseRequest = ::closeApplication,
                state = windowState,
                title = "ArchiveTune",
                icon = painterResource("icons/archivetune.png"),
                undecorated = !linuxWindow,
                transparent = !linuxWindow,
                resizable = true,
            ) {
                val frameDensity = LocalDensity.current
                LaunchedEffect(runtimeBenchmark) {
                    if (runtimeBenchmark != null) {
                        androidx.compose.runtime.withFrameNanos { }
                        runtimeBenchmark.onWindowFrame(frameDensity.density, frameDensity.fontScale)
                    }
                }
                var showLyrics by remember { mutableStateOf(false) }
                var showStems by remember { mutableStateOf(false) }
                var showQueue by remember { mutableStateOf(false) }
                var hasEnteredLyrics by remember { mutableStateOf(false) }
                var openChatFriendId by remember { mutableStateOf<String?>(null) }
                val notifications by notificationCenter.notifications.collectAsState()
                val socialState by social.state.collectAsState()
                val remoteLibrary by musicServices.libraryRepository.state.collectAsState()
                val openChatFriend = openChatFriendId?.let { friendId ->
                    socialState.friends.firstOrNull { it.id == friendId }
                }
                val beatPulseProvider = remember(beatSync) { { beatSync.visualPulse } }

                DesktopBeatSyncHost(
                    playback = musicServices.playback,
                    repository = beatAnalysisRepository,
                    controller = beatSync,
                    active = !windowState.isMinimized,
                )

                // Reclaim at the same lifecycle boundaries on both platforms, away from the UI thread.
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(WORKING_SET_TRIM_INTERVAL_MS)
                        reclaimIdleMemory()
                    }
                }
                LaunchedEffect(windowState.isMinimized) {
                    if (windowState.isMinimized) {
                        delay(MINIMIZED_WORKING_SET_TRIM_DELAY_MS)
                        if (windowState.isMinimized) {
                            reclaimIdleMemory()
                        }
                    }
                }
                LaunchedEffect(showLyrics) {
                    if (showLyrics) {
                        hasEnteredLyrics = true
                    } else if (hasEnteredLyrics) {
                        delay(LEAVING_LYRICS_WORKING_SET_TRIM_DELAY_MS)
                        reclaimIdleMemory()
                    }
                }
                LaunchedEffect(musicServices) {
                    musicServices.appKeybindActions.collect { action ->
                        when (action) {
                            DesktopKeybindAction.TOGGLE_LYRICS -> showLyrics = !showLyrics
                            DesktopKeybindAction.TOGGLE_QUEUE -> showQueue = !showQueue
                            else -> Unit
                        }
                    }
                }
                LaunchedEffect(updateCoordinator) {
                    updateCoordinator.restartRequests.collect {
                        musicServices.prepareForUpdateRestart()
                        if (updateCoordinator.launchInstallerHandoff()) {
                            closeApplication()
                        }
                    }
                }
                val browse = remember(musicServices) {
                    moe.rukamori.archivetune.desktop.ui.DesktopBrowseController(
                        repository = musicServices.browseRepository,
                        podcasts = musicServices.podcastRepository,
                        initialDestination = latestDestination,
                    )
                }
                val dynamicArtworkUri by remember(musicServices) {
                    musicServices.playback.state
                        .map { it.currentItem?.metadata?.artworkUri }
                        .distinctUntilChanged()
                }.collectAsState(musicServices.playback.state.value.currentItem?.metadata?.artworkUri)
                val lyricsContent: @Composable (Modifier, Boolean) -> Unit = remember {
                    { modifier, useShellDynamicBackdrop ->
                        moe.rukamori.archivetune.desktop.ui.DesktopLyricsView(
                            services = musicServices,
                            playTogether = playTogether,
                            browse = browse,
                            beatSync = beatSync,
                            onBrowseOpened = { showLyrics = false },
                            modifier = modifier,
                            useShellDynamicBackdrop = useShellDynamicBackdrop,
                        )
                    }
                }
                val stemContent: @Composable (Modifier, Boolean) -> Unit = remember {
                    { modifier, useShellDynamicBackdrop ->
                        moe.rukamori.archivetune.desktop.ui.DesktopStemPlayerView(
                            services = musicServices,
                            paths = runtime.paths,
                            modifier = modifier,
                            useShellDynamicBackdrop = useShellDynamicBackdrop,
                        )
                    }
                }
                DisposableEffect(window) {
                    musicServices.attachWindowsMediaWindow(window)
                    val previousMinimum = window.minimumSize
                    val previousResizable = window.isResizable
                    window.minimumSize = Dimension(640, 480)
                    window.isResizable = true
                    onDispose {
                        window.minimumSize = previousMinimum
                        window.isResizable = previousResizable
                    }
                }

                ArchiveTuneWindowChrome(
                    windowState = windowState,
                    onCloseRequest = ::closeApplication,
                ) { windowDragModifier, windowControls ->
                    ArchiveTuneDesktopApp(
                        paths = runtime.paths,
                        logger = runtime.logger,
                        initialDestination = latestDestination,
                        initialThemePreference = latestTheme,
                        initialAccentSeedArgb = latestAccentSeedArgb,
                        dynamicArtworkUri = dynamicArtworkUri,
                        beatPulse = beatPulseProvider,
                        windowDragModifier = windowDragModifier,
                        windowControls = windowControls,
                        immersiveContent = when {
                            showStems -> stemContent
                            showLyrics -> lyricsContent
                            else -> null
                        },
                        immersiveTitle = if (showStems) "Stem player" else "Lyrics",
                        immersiveDescription = if (showStems) "4-track stem mix" else "Immersive now playing",
                        onDismissImmersive = {
                            showLyrics = false
                            showStems = false
                        },
                        queueVisible = showQueue,
                        onDismissQueue = { showQueue = false },
                        contentCanNavigateBack = browse.detail != null,
                        onContentNavigateBack = browse::goBack,
                        notifications = notifications,
                        onDismissNotification = notificationCenter::dismiss,
                        chatContent = openChatFriend?.let { friend ->
                            { modifier ->
                                moe.rukamori.archivetune.desktop.ui.DesktopChatDock(
                                    friend = friend,
                                    ownUserId = (socialState.account as? SocialAccountState.SignedIn)?.user?.id.orEmpty(),
                                    messages = socialState.chats[friend.id].orEmpty(),
                                    loading = friend.id in socialState.loadingChats,
                                    services = musicServices,
                                    playTogether = playTogether,
                                    playlists = remoteLibrary.playlists,
                                    social = social,
                                    onClose = { openChatFriendId = null },
                                    modifier = modifier,
                                )
                            }
                        },
                        onShellStateChanged = { destination, theme, accentSeedArgb ->
                            latestDestination = destination
                            latestTheme = theme
                            latestAccentSeedArgb = accentSeedArgb
                            // Theme/navigation are user preferences, not shutdown state. Persist them
                            // as soon as Compose reports a change so an abnormal process exit, update,
                            // or installer replacement cannot silently revert the visible selection.
                            runtime.settings.update { settings ->
                                settings.copy(
                                    themeMode = theme.asSettingsThemeMode(),
                                    accentSeedArgb = accentSeedArgb,
                                    lastDestination = destination.asSettingsDestination(),
                                )
                            }
                        },
                        content = { contentState, modifier ->
                            moe.rukamori.archivetune.desktop.ui.DesktopMusicContent(
                                contentState,
                                musicServices,
                                downloadServices,
                                runtime.paths,
                                runtime.logger,
                                browse,
                                playTogether,
                                social,
                                onOpenChat = { friend -> openChatFriendId = friend.id },
                                onBeatVisualPreferencesChanged = beatSync::setVisualPreferences,
                                modifier = modifier,
                            )
                        },
                        playerContent = { modifier ->
                            moe.rukamori.archivetune.desktop.ui.DesktopMiniPlayer(
                                services = musicServices,
                                playTogether = playTogether,
                                browse = browse,
                                beatSync = beatSync,
                                onShowLyrics = {
                                    showStems = false
                                    showLyrics = true
                                },
                                onShowStems = {
                                    showLyrics = false
                                    showStems = true
                                },
                                onShowQueue = { showQueue = !showQueue },
                                modifier = modifier,
                            )
                        },
                        queueContent = { modifier, queueWidth ->
                            AnimatedVisibility(
                                visible = showQueue,
                                enter = slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it } +
                                    fadeIn(tween(180, easing = FastOutSlowInEasing)),
                                exit = slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { it } +
                                    fadeOut(tween(140, easing = FastOutSlowInEasing)),
                                modifier = modifier,
                            ) {
                                moe.rukamori.archivetune.desktop.ui.DesktopQueuePanel(
                                    services = musicServices,
                                    playTogether = playTogether,
                                    onClose = { showQueue = false },
                                    modifier = Modifier.fillMaxHeight().width(queueWidth),
                                )
                            }
                        },
                    )
                }
            }
        }
    } finally {
        runtime.close()
    }
}

/**
 * Coil's generic JVM defaults are intentionally broad. Album-heavy screens can otherwise retain
 * far more decoded native Skia pixels than a music client needs, especially on machines where the
 * JVM reports a very large available heap. Keep a useful hot artwork set without allowing the
 * image cache to become a major part of ArchiveTune's idle working set.
 */
private fun configureDesktopImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(DESKTOP_IMAGE_MEMORY_CACHE_BYTES)
                    .build()
            }
            .build()
    }
}

private fun DesktopWindowPlacement.asWindowPosition(): WindowPosition {
    val x = xDp
    val y = yDp
    return if (x != null && y != null && hasVisibleTitlebar(x, y)) {
        WindowPosition.Absolute(x.dp, y.dp)
    } else {
        WindowPosition.PlatformDefault
    }
}

/**
 * Reject stale coordinates from a disconnected monitor or a bad drag. A small titlebar-sized
 * rectangle must intersect at least one current display; negative coordinates are valid for
 * secondary displays and are intentionally handled by the same intersection test.
 */
private fun hasVisibleTitlebar(x: Float, y: Float): Boolean = runCatching {
    if (GraphicsEnvironment.isHeadless()) return@runCatching true
    val titlebarRight = x + MIN_VISIBLE_TITLEBAR_WIDTH_DP
    val titlebarBottom = y + MIN_VISIBLE_TITLEBAR_HEIGHT_DP
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
        val bounds = device.defaultConfiguration.bounds
        val visibleWidth = min(titlebarRight, bounds.maxX.toFloat()) - max(x, bounds.minX.toFloat())
        val visibleHeight = min(titlebarBottom, bounds.maxY.toFloat()) - max(y, bounds.minY.toFloat())
        visibleWidth >= MIN_VISIBLE_TITLEBAR_WIDTH_DP && visibleHeight >= MIN_VISIBLE_TITLEBAR_HEIGHT_DP
    }
}.getOrDefault(false)

private const val MIN_VISIBLE_TITLEBAR_WIDTH_DP = 96f
private const val MIN_VISIBLE_TITLEBAR_HEIGHT_DP = 32f
private const val DESKTOP_IMAGE_MEMORY_CACHE_BYTES = 32L * 1024L * 1024L
private const val WORKING_SET_TRIM_INTERVAL_MS = 60_000L
private const val MINIMIZED_WORKING_SET_TRIM_DELAY_MS = 30_000L
private const val LEAVING_LYRICS_WORKING_SET_TRIM_DELAY_MS = 2_000L

private val linuxMemoryReclaimer by lazy { LinuxMemoryReclaimer() }

private suspend fun reclaimIdleMemory() = withContext(Dispatchers.IO) {
    when {
        System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true) ->
            WindowsWorkingSetTrimmer.trimCurrentProcess()
        System.getProperty("os.name", "").startsWith("Linux", ignoreCase = true) ->
            linuxMemoryReclaimer.trimCurrentProcess()
        else -> false
    }
}

private fun DesktopLastDestination.asDesktopDestination(): DesktopDestination = DesktopDestination.valueOf(name)

private fun DesktopDestination.asSettingsDestination(): DesktopLastDestination = DesktopLastDestination.valueOf(name)

private fun DesktopThemeMode.asDesktopThemePreference() =
    when (this) {
        DesktopThemeMode.SYSTEM -> moe.rukamori.archivetune.desktop.state.DesktopThemePreference.SYSTEM
        DesktopThemeMode.LIGHT -> moe.rukamori.archivetune.desktop.state.DesktopThemePreference.LIGHT
        DesktopThemeMode.DARK -> moe.rukamori.archivetune.desktop.state.DesktopThemePreference.DARK
        DesktopThemeMode.DYNAMIC -> moe.rukamori.archivetune.desktop.state.DesktopThemePreference.DYNAMIC
    }

private fun moe.rukamori.archivetune.desktop.state.DesktopThemePreference.asSettingsThemeMode(): DesktopThemeMode =
    when (this) {
        moe.rukamori.archivetune.desktop.state.DesktopThemePreference.SYSTEM -> DesktopThemeMode.SYSTEM
        moe.rukamori.archivetune.desktop.state.DesktopThemePreference.LIGHT -> DesktopThemeMode.LIGHT
        moe.rukamori.archivetune.desktop.state.DesktopThemePreference.DARK -> DesktopThemeMode.DARK
        moe.rukamori.archivetune.desktop.state.DesktopThemePreference.DYNAMIC -> DesktopThemeMode.DYNAMIC
    }
