/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import moe.rukamori.archivetune.desktop.platform.DesktopRuntime
import moe.rukamori.archivetune.desktop.platform.DesktopMusicServices
import moe.rukamori.archivetune.desktop.platform.DesktopDownloadServices
import moe.rukamori.archivetune.desktop.state.DesktopDestination
import moe.rukamori.archivetune.desktop.ui.ArchiveTuneDesktopApp
import moe.rukamori.archivetune.desktop.settings.DesktopLastDestination
import moe.rukamori.archivetune.desktop.settings.DesktopThemeMode
import moe.rukamori.archivetune.desktop.settings.DesktopWindowPlacement
import java.awt.Dimension

fun main() {
    System.setProperty("awt.useSystemAAFontSettings", "lcd")
    System.setProperty("swing.aatext", "true")
    val runtime = DesktopRuntime.start()
    val musicServices = runtime.own(DesktopMusicServices(runtime))
    val downloadServices = runtime.own(DesktopDownloadServices(runtime, musicServices.backend))
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
            ) {
                DisposableEffect(window) {
                    val previousMinimum = window.minimumSize
                    window.minimumSize = Dimension(640, 480)
                    onDispose { window.minimumSize = previousMinimum }
                }

                ArchiveTuneDesktopApp(
                    paths = runtime.paths,
                    logger = runtime.logger,
                    initialDestination = latestDestination,
                    initialThemePreference = latestTheme,
                    onShellStateChanged = { destination, theme ->
                        latestDestination = destination
                        latestTheme = theme
                    },
                    content = { contentState, modifier ->
                        moe.rukamori.archivetune.desktop.ui.DesktopMusicContent(contentState, musicServices, downloadServices, runtime.paths, runtime.logger, modifier)
                    },
                    playerContent = { modifier ->
                        moe.rukamori.archivetune.desktop.ui.DesktopMiniPlayer(musicServices, modifier)
                    },
                )
            }
        }
    } finally {
        runtime.close()
    }
}

private fun DesktopWindowPlacement.asWindowPosition(): WindowPosition =
    if (xDp != null && yDp != null) {
        WindowPosition.Absolute(requireNotNull(xDp).dp, requireNotNull(yDp).dp)
    } else {
        WindowPosition.PlatformDefault
    }

private fun DesktopLastDestination.asDesktopDestination(): DesktopDestination = DesktopDestination.valueOf(name)

private fun DesktopDestination.asSettingsDestination(): DesktopLastDestination = DesktopLastDestination.valueOf(name)

private fun DesktopThemeMode.asDesktopThemePreference() =
    moe.rukamori.archivetune.desktop.state.DesktopThemePreference.valueOf(name)

private fun moe.rukamori.archivetune.desktop.state.DesktopThemePreference.asSettingsThemeMode(): DesktopThemeMode =
    DesktopThemeMode.valueOf(name)
