/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.desktop.platform.DesktopRuntime
import moe.rukamori.archivetune.desktop.platform.DesktopMusicServices
import moe.rukamori.archivetune.desktop.platform.DesktopDownloadServices
import moe.rukamori.archivetune.desktop.state.DesktopDestination
import moe.rukamori.archivetune.desktop.ui.ArchiveTuneDesktopApp
import moe.rukamori.archivetune.desktop.ui.ArchiveTuneWindowChrome
import moe.rukamori.archivetune.desktop.settings.DesktopLastDestination
import moe.rukamori.archivetune.desktop.settings.DesktopThemeMode
import moe.rukamori.archivetune.desktop.settings.DesktopWindowPlacement
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
    val downloadServices = runtime.own(DesktopDownloadServices(runtime, musicServices.backend))
    musicServices.attachCompletedDownloadLookup(downloadServices::completedFileFor)
    val restored = runtime.settings.snapshot()
    var latestDestination = restored.lastDestination.asDesktopDestination()
    var latestTheme = restored.themeMode.asDesktopThemePreference()

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

            Window(
                onCloseRequest = ::closeApplication,
                state = windowState,
                title = "ArchiveTune",
                icon = painterResource("icons/archivetune.png"),
                undecorated = true,
                transparent = true,
            ) {
                var showLyrics by remember { mutableStateOf(false) }
                var showQueue by remember { mutableStateOf(false) }
                val browse = remember { moe.rukamori.archivetune.desktop.ui.DesktopBrowseController() }
                val dynamicArtworkUri by remember(musicServices) {
                    musicServices.playback.state
                        .map { it.currentItem?.metadata?.artworkUri }
                        .distinctUntilChanged()
                }.collectAsState(musicServices.playback.state.value.currentItem?.metadata?.artworkUri)
                val lyricsContent: @Composable (Modifier, Boolean) -> Unit = remember {
                    { modifier, useShellDynamicBackdrop ->
                        moe.rukamori.archivetune.desktop.ui.DesktopLyricsView(
                            services = musicServices,
                            browse = browse,
                            modifier = modifier,
                            useShellDynamicBackdrop = useShellDynamicBackdrop,
                        )
                    }
                }
                DisposableEffect(window) {
                    val previousMinimum = window.minimumSize
                    window.minimumSize = Dimension(640, 480)
                    onDispose { window.minimumSize = previousMinimum }
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
                        dynamicArtworkUri = dynamicArtworkUri,
                        windowDragModifier = windowDragModifier,
                        windowControls = windowControls,
                        immersiveContent = lyricsContent.takeIf { showLyrics },
                        onDismissImmersive = { showLyrics = false },
                        onShellStateChanged = { destination, theme ->
                            latestDestination = destination
                            latestTheme = theme
                            // Theme/navigation are user preferences, not shutdown state. Persist them
                            // as soon as Compose reports a change so an abnormal process exit, update,
                            // or installer replacement cannot silently revert the visible selection.
                            runtime.settings.update { settings ->
                                settings.copy(
                                    themeMode = theme.asSettingsThemeMode(),
                                    lastDestination = destination.asSettingsDestination(),
                                )
                            }
                        },
                        content = { contentState, modifier ->
                            moe.rukamori.archivetune.desktop.ui.DesktopMusicContent(contentState, musicServices, downloadServices, runtime.paths, runtime.logger, browse, modifier)
                        },
                        playerContent = { modifier ->
                            moe.rukamori.archivetune.desktop.ui.DesktopMiniPlayer(
                                services = musicServices,
                                browse = browse,
                                onShowLyrics = { showLyrics = true },
                                onShowQueue = { showQueue = !showQueue },
                                modifier = modifier,
                            )
                        },
                        queueContent = { modifier ->
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
                                    onClose = { showQueue = false },
                                    modifier = Modifier.fillMaxHeight().width(360.dp),
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
private const val DESKTOP_IMAGE_MEMORY_CACHE_BYTES = 64L * 1024L * 1024L

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
