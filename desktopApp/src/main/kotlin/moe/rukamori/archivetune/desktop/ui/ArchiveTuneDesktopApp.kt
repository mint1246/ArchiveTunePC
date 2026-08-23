/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.desktop.platform.AppPaths
import moe.rukamori.archivetune.desktop.platform.DesktopPlatform
import moe.rukamori.archivetune.desktopauth.CookieJsonSessionImportResult
import moe.rukamori.archivetune.desktopauth.DesktopAccountState
import moe.rukamori.archivetune.desktopauth.DesktopSessionManager
import moe.rukamori.archivetune.desktopauth.SessionImportFailure
import moe.rukamori.archivetune.desktopauth.SessionImportResult
import moe.rukamori.archivetune.desktopauth.YouTubeCookieJsonFailure
import moe.rukamori.archivetune.desktopauth.YouTubeCookieJsonInspection
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import moe.rukamori.archivetune.desktop.state.DesktopDestination
import moe.rukamori.archivetune.desktop.state.DesktopNavigator
import moe.rukamori.archivetune.desktop.state.DesktopThemePreference
import moe.rukamori.archivetune.desktop.lyrics.anim.VibrantLyricsBackground
import java.util.logging.Logger
import coil3.compose.SubcomposeAsyncImage

data class DesktopContentState(
    val destination: DesktopDestination,
    val searchQuery: String,
    val setSearchQuery: (String) -> Unit,
    val navigate: (DesktopDestination) -> Unit,
    val themePreference: DesktopThemePreference,
    val setThemePreference: (DesktopThemePreference) -> Unit,
    val accentSeedArgb: Long?,
    val setAccentSeedArgb: (Long?) -> Unit,
)

private val DesktopDestination.icon: ImageVector
    get() = when (this) {
        DesktopDestination.HOME -> Icons.Rounded.Home
        DesktopDestination.EXPLORE -> Icons.Rounded.Explore
        DesktopDestination.SEARCH -> Icons.Rounded.Search
        DesktopDestination.LIBRARY -> Icons.Rounded.LibraryMusic
        DesktopDestination.DOWNLOADS -> Icons.Rounded.Download
        DesktopDestination.SETTINGS -> Icons.Rounded.Settings
    }

@Composable
fun ArchiveTuneDesktopApp(
    paths: AppPaths,
    logger: Logger,
    initialDestination: DesktopDestination,
    initialThemePreference: DesktopThemePreference,
    initialAccentSeedArgb: Long? = null,
    dynamicArtworkUri: String? = null,
    onShellStateChanged: (DesktopDestination, DesktopThemePreference, Long?) -> Unit,
    windowDragModifier: Modifier = Modifier,
    windowControls: (@Composable () -> Unit)? = null,
    immersiveContent: (@Composable (Modifier, Boolean) -> Unit)? = null,
    onDismissImmersive: () -> Unit = {},
    queueVisible: Boolean = false,
    onDismissQueue: () -> Unit = {},
    contentCanNavigateBack: Boolean = false,
    onContentNavigateBack: () -> Boolean = { false },
    content: (@Composable (DesktopContentState, Modifier) -> Unit)? = null,
    playerContent: (@Composable (Modifier) -> Unit)? = null,
    queueContent: (@Composable (Modifier, Dp) -> Unit)? = null,
) {
    val navigator = remember { DesktopNavigator(initialDestination) }
    val searchFocusRequester = remember { FocusRequester() }
    var searchQuery by remember { mutableStateOf("") }
    var themePreference by remember { mutableStateOf(initialThemePreference) }
    var accentSeedArgb by remember { mutableStateOf(initialAccentSeedArgb) }
    val dynamicActive = themePreference == DesktopThemePreference.DYNAMIC
    val dynamicPalette = rememberDynamicThemePalette(dynamicArtworkUri.takeIf { dynamicActive })

    LaunchedEffect(navigator.current, themePreference, accentSeedArgb) {
        onShellStateChanged(navigator.current, themePreference, accentSeedArgb)
    }

    ArchiveTuneDesktopTheme(themePreference, dynamicPalette, accentSeedArgb) {
        Box(Modifier.fillMaxSize()) {
            if (dynamicActive) {
                DynamicAppBackdrop(
                    artworkUri = dynamicArtworkUri,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    // Bubble shortcuts after the focused control gets first chance to consume them;
                    // this keeps text editing behavior ahead of shell navigation.
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (desktopShortcutAction(event.key, event.isCtrlPressed, event.isAltPressed)) {
                            DesktopShortcutAction.SEARCH -> {
                                if (immersiveContent != null) return@onKeyEvent false
                                navigator.navigate(DesktopDestination.SEARCH)
                                searchFocusRequester.requestFocus()
                                true
                            }
                            DesktopShortcutAction.SETTINGS -> {
                                if (immersiveContent != null) return@onKeyEvent false
                                navigator.navigate(DesktopDestination.SETTINGS)
                                true
                            }
                            DesktopShortcutAction.BACK -> {
                                if (immersiveContent != null) {
                                    onDismissImmersive()
                                    true
                                } else if (contentCanNavigateBack) {
                                    onContentNavigateBack()
                                } else {
                                    navigator.navigateBack()
                                }
                            }
                            DesktopShortcutAction.FORWARD -> {
                                if (immersiveContent != null || contentCanNavigateBack) false else navigator.navigateForward()
                            }
                            DesktopShortcutAction.ESCAPE -> when (
                                desktopEscapeAction(
                                    immersiveVisible = immersiveContent != null,
                                    queueVisible = queueVisible,
                                    hasSearchQuery = searchQuery.isNotEmpty(),
                                )
                            ) {
                                DesktopEscapeAction.DISMISS_IMMERSIVE -> {
                                    onDismissImmersive()
                                    true
                                }
                                DesktopEscapeAction.DISMISS_QUEUE -> {
                                    onDismissQueue()
                                    true
                                }
                                DesktopEscapeAction.CLEAR_SEARCH -> {
                                    searchQuery = ""
                                    true
                                }
                                null -> false
                            }
                            null -> false
                        }
                    },
                color = if (dynamicActive) Color.Transparent else MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
            val layout = when {
                maxWidth >= 1_080.dp -> DesktopNavigationLayout.EXPANDED
                maxWidth >= 760.dp -> DesktopNavigationLayout.RAIL
                else -> DesktopNavigationLayout.COMPACT
            }
            val state = DesktopContentState(
                destination = navigator.current,
                searchQuery = searchQuery,
                setSearchQuery = { query ->
                    searchQuery = query
                    if (query.isNotEmpty()) navigator.navigate(DesktopDestination.SEARCH)
                },
                navigate = navigator::navigate,
                themePreference = themePreference,
                setThemePreference = { themePreference = it },
                accentSeedArgb = accentSeedArgb,
                setAccentSeedArgb = { accentSeedArgb = it },
            )

            AnimatedContent(
                targetState = immersiveContent,
                transitionSpec = {
                    val enter = fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 18 }
                    val exit = fadeOut(tween(170, easing = FastOutSlowInEasing)) +
                        slideOutVertically(tween(170, easing = FastOutSlowInEasing)) { -it / 24 }
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "immersive-shell-transition",
                modifier = Modifier.fillMaxSize(),
            ) { immersive ->
                if (immersive != null) {
                    Column(Modifier.fillMaxSize()) {
                        DesktopTopBar(
                            navigator = navigator,
                            destination = state.destination,
                            query = searchQuery,
                            onQueryChanged = {},
                            searchFocusRequester = searchFocusRequester,
                            compact = false,
                            windowDragModifier = windowDragModifier,
                            windowControls = windowControls,
                            titleOverride = "Lyrics",
                            descriptionOverride = "Immersive now playing",
                            onNavigateBackOverride = onDismissImmersive,
                            showSearch = false,
                        )
                        immersive(Modifier.weight(1f).fillMaxWidth(), dynamicActive)
                    }
                } else Row(Modifier.fillMaxSize()) {
                    when (layout) {
                        DesktopNavigationLayout.EXPANDED -> ExpandedSidebar(state, Modifier.width(248.dp).fillMaxHeight())
                        DesktopNavigationLayout.RAIL -> DesktopRail(state, Modifier.width(88.dp).fillMaxHeight())
                        DesktopNavigationLayout.COMPACT -> Unit
                    }

                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        DesktopTopBar(
                            navigator = navigator,
                            destination = state.destination,
                            query = searchQuery,
                            onQueryChanged = state.setSearchQuery,
                            searchFocusRequester = searchFocusRequester,
                            compact = layout == DesktopNavigationLayout.COMPACT,
                            windowDragModifier = windowDragModifier,
                            windowControls = windowControls,
                            onNavigateBackOverride = if (contentCanNavigateBack) ({ onContentNavigateBack() }) else null,
                        )

                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            AnimatedContent(
                                targetState = state.destination,
                                transitionSpec = {
                                    val enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                                        slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 16 }
                                    val exit = fadeOut(tween(150, easing = FastOutSlowInEasing)) +
                                        slideOutHorizontally(tween(150, easing = FastOutSlowInEasing)) { -it / 22 }
                                    (enter togetherWith exit).using(SizeTransform(clip = false))
                                },
                                label = "destination-transition",
                                modifier = Modifier.fillMaxSize(),
                            ) { destination ->
                                val destinationState = state.copy(destination = destination)
                                if (content != null) {
                                    content(destinationState, Modifier.fillMaxSize())
                                } else {
                                    FoundationDestinationContent(
                                        state = destinationState,
                                        paths = paths,
                                        logger = logger,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }

                            if (queueContent != null) {
                                queueContent(
                                    Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                    desktopQueueOverlayWidth(maxWidth),
                                )
                            }
                        }

                        if (playerContent != null) {
                            playerContent(Modifier.fillMaxWidth())
                        } else {
                            FoundationMiniPlayer(Modifier.fillMaxWidth())
                        }

                        if (layout == DesktopNavigationLayout.COMPACT) {
                            CompactNavigation(state)
                        }
                    }
                }
            }
            }
        }
    }
}
}

private enum class DesktopNavigationLayout { EXPANDED, RAIL, COMPACT }

@Composable
private fun DynamicAppBackdrop(
    artworkUri: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        AnimatedContent(
            targetState = artworkUri,
            transitionSpec = {
                fadeIn(tween(650, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(650, easing = FastOutSlowInEasing))
            },
            label = "dynamic-app-background",
            modifier = Modifier.fillMaxSize(),
        ) { artwork ->
            VibrantLyricsBackground(
                artworkUri = artwork,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
    }
}

@Composable
private fun ExpandedSidebar(
    state: DesktopContentState,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 18.dp)) {
            BrandLockup(Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            Spacer(Modifier.height(24.dp))
            DesktopDestination.entries.forEach { destination ->
                NavigationDrawerItem(
                    icon = { AnimatedDestinationIcon(destination.icon, destination == state.destination, null) },
                    label = { Text(destination.title) },
                    selected = destination == state.destination,
                    onClick = { state.navigate(destination) },
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "ArchiveTune Desktop",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            Text(
                "14.13 • Windows x64",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DesktopRail(
    state: DesktopContentState,
    modifier: Modifier = Modifier,
) {
    NavigationRail(modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Image(
            painter = painterResource("icons/archivetune.png"),
            contentDescription = "ArchiveTune",
            modifier = Modifier.size(52.dp).padding(4.dp),
        )
        Spacer(Modifier.height(16.dp))
        DesktopDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = destination == state.destination,
                onClick = { state.navigate(destination) },
                icon = { AnimatedDestinationIcon(destination.icon, destination == state.destination, destination.title) },
                label = { Text(destination.title) },
            )
        }
    }
}

@Composable
private fun CompactNavigation(state: DesktopContentState) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        DesktopDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == state.destination,
                onClick = { state.navigate(destination) },
                icon = { AnimatedDestinationIcon(destination.icon, destination == state.destination, destination.title) },
                label = { Text(destination.title) },
            )
        }
    }
}

@Composable
private fun AnimatedDestinationIcon(
    icon: ImageVector,
    selected: Boolean,
    contentDescription: String?,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.10f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "destination-icon-scale",
    )
    Icon(
        icon,
        contentDescription = contentDescription,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    )
}

@Composable
private fun BrandLockup(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource("icons/archivetune.png"),
            contentDescription = "ArchiveTune logo",
            modifier = Modifier.size(46.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text("ArchiveTune", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun DesktopTopBar(
    navigator: DesktopNavigator,
    destination: DesktopDestination,
    query: String,
    onQueryChanged: (String) -> Unit,
    searchFocusRequester: FocusRequester,
    compact: Boolean,
    windowDragModifier: Modifier,
    windowControls: (@Composable () -> Unit)?,
    titleOverride: String? = null,
    descriptionOverride: String? = null,
    onNavigateBackOverride: (() -> Unit)? = null,
    showSearch: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (titleOverride != null && LocalDynamicThemeActive.current) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 1f)
                } else {
                    Color.Transparent
                },
            )
            // The custom controls are part of the window edge, like a browser tab strip. Keep
            // their hit targets flush with the right/top edge while retaining shell padding when
            // the host does not provide controls (e.g. previews/tests).
            .padding(
                start = if (compact) 12.dp else 24.dp,
                end = if (windowControls == null) {
                    if (compact) 12.dp else 24.dp
                } else 0.dp,
                top = if (windowControls == null) 12.dp else 0.dp,
                bottom = if (windowControls == null) 12.dp else 0.dp,
            )
            .heightIn(min = if (windowControls == null) 0.dp else 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onNavigateBackOverride != null || navigator.canNavigateBack) {
            IconButton(onClick = { if (onNavigateBackOverride != null) onNavigateBackOverride() else navigator.navigateBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = if (titleOverride != null) "Close lyrics" else "Back")
            }
        }
        if (onNavigateBackOverride == null && navigator.canNavigateForward) {
            IconButton(onClick = navigator::navigateForward) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Forward")
            }
        }
        if (!compact) {
            // Make the whole 64dp title lane draggable, not only the text's measured height.
            // The old ~45dp content-sized Column left thin dead strips above/below it, which made
            // grabbing the apparent title bar intermittently do nothing.
            Column(
                // fillMaxHeight() here can make the min-height top bar consume all available
                // vertical space. Keep the draggable lane explicitly at the intended 64dp.
                Modifier.weight(1f).height(64.dp).then(windowDragModifier),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(titleOverride ?: destination.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    descriptionOverride ?: destination.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            // A weighted Spacer has zero cross-axis size by default, so compact chrome otherwise
            // has no draggable pixels at all.
            Spacer(Modifier.weight(1f).height(64.dp).then(windowDragModifier))
        }
        if (showSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                singleLine = true,
                placeholder = { Text("Search music") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                modifier = if (compact) {
                    Modifier
                        .widthIn(min = 220.dp, max = 480.dp)
                        .weight(5f)
                        .focusRequester(searchFocusRequester)
                } else {
                    Modifier
                        .widthIn(min = 300.dp, max = 480.dp)
                        .focusRequester(searchFocusRequester)
                },
                shape = CircleShape,
            )
        }
        if (windowControls != null) {
            windowControls()
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
private fun FoundationMiniPlayer(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Nothing playing", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose a song to begin",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {}, enabled = false) { Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous") }
            FilledIconButton(onClick = {}, enabled = false) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play") }
            IconButton(onClick = {}, enabled = false) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next") }
        }
    }
}

@Composable
fun FoundationDestinationContent(
    state: DesktopContentState,
    paths: AppPaths,
    logger: Logger,
    account: DesktopSessionManager? = null,
    settingsStore: moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore? = null,
    playback: moe.rukamori.archivetune.playback.api.PlaybackController? = null,
    playbackModesEnabled: Boolean = true,
    shuffleModeEnabled: Boolean = true,
    audioEffects: moe.rukamori.archivetune.playback.desktop.DesktopAudioEffects? = null,
    audioOutput: moe.rukamori.archivetune.playback.desktop.DesktopAudioOutputControl? = null,
    downloads: moe.rukamori.archivetune.desktop.platform.DesktopDownloadServices? = null,
    libraryFolders: List<java.nio.file.Path> = emptyList(),
    libraryTrackCount: Int = 0,
    libraryScanning: Boolean = false,
    libraryScanError: String? = null,
    onLibraryFoldersChanged: (List<java.nio.file.Path>) -> Unit = {},
    onLibraryRescan: () -> Unit = {},
    onKeybindsChanged: () -> Unit = {},
    onKeybindCaptureChanged: (Boolean) -> Unit = {},
    keybindPressedKeys: kotlinx.coroutines.flow.StateFlow<List<Int>>? = null,
    modifier: Modifier = Modifier,
) {
    when (state.destination) {
        DesktopDestination.HOME -> FoundationHome(state, modifier)
        DesktopDestination.SETTINGS -> FoundationSettings(
            state,
            paths,
            logger,
            account,
            settingsStore,
            playback,
            playbackModesEnabled,
            shuffleModeEnabled,
            audioEffects,
            audioOutput,
            downloads,
            libraryFolders,
            libraryTrackCount,
            libraryScanning,
            libraryScanError,
            onLibraryFoldersChanged,
            onLibraryRescan,
            onKeybindsChanged,
            onKeybindCaptureChanged,
            keybindPressedKeys,
            modifier,
        )
        else -> FoundationEmptyState(state.destination, state.searchQuery, modifier)
    }
}

@Composable
private fun FoundationHome(
    state: DesktopContentState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().height(250.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            Row(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ).padding(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ARCHIVETUNE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Your music, private and uninterrupted.", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "A clean desktop home for YouTube Music, local tracks, downloads and live lyrics.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Image(
                    painter = painterResource("icons/archivetune.png"),
                    contentDescription = null,
                    modifier = Modifier.size(150.dp).padding(12.dp),
                )
            }
        }

        Text("Start listening", style = MaterialTheme.typography.headlineMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HomeActionCard(
                title = "Explore music",
                description = "New releases, charts and moods",
                icon = Icons.Rounded.Explore,
                modifier = Modifier.weight(1f),
                onClick = { state.navigate(DesktopDestination.EXPLORE) },
            )
            HomeActionCard(
                title = "Your library",
                description = "Songs, albums, artists and playlists",
                icon = Icons.Rounded.LibraryMusic,
                modifier = Modifier.weight(1f),
                onClick = { state.navigate(DesktopDestination.LIBRARY) },
            )
            HomeActionCard(
                title = "Offline music",
                description = "Downloads available without a connection",
                icon = Icons.Rounded.Download,
                modifier = Modifier.weight(1f),
                onClick = { state.navigate(DesktopDestination.DOWNLOADS) },
            )
        }
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.height(136.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FoundationEmptyState(
    destination: DesktopDestination,
    query: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(92.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(destination.icon, contentDescription = null, modifier = Modifier.size(42.dp))
                }
            }
            Text(
                if (destination == DesktopDestination.SEARCH && query.isNotBlank()) "No results to show yet" else destination.title,
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                when (destination) {
                    DesktopDestination.SEARCH -> if (query.isBlank()) "Type above to search your music." else "Results for “$query” will appear here."
                    DesktopDestination.EXPLORE -> "New releases, charts, moods and genres will appear here."
                    DesktopDestination.LIBRARY -> "Your saved and local music will appear here."
                    DesktopDestination.DOWNLOADS -> "Music saved for offline playback will appear here."
                    else -> destination.description
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FoundationSettings(
    state: DesktopContentState,
    paths: AppPaths,
    logger: Logger,
    account: DesktopSessionManager?,
    settingsStore: moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore?,
    playback: moe.rukamori.archivetune.playback.api.PlaybackController?,
    playbackModesEnabled: Boolean,
    shuffleModeEnabled: Boolean,
    audioEffects: moe.rukamori.archivetune.playback.desktop.DesktopAudioEffects?,
    audioOutput: moe.rukamori.archivetune.playback.desktop.DesktopAudioOutputControl?,
    downloads: moe.rukamori.archivetune.desktop.platform.DesktopDownloadServices?,
    libraryFolders: List<java.nio.file.Path>,
    libraryTrackCount: Int,
    libraryScanning: Boolean,
    libraryScanError: String?,
    onLibraryFoldersChanged: (List<java.nio.file.Path>) -> Unit,
    onLibraryRescan: () -> Unit,
    onKeybindsChanged: () -> Unit,
    onKeybindCaptureChanged: (Boolean) -> Unit,
    keybindPressedKeys: kotlinx.coroutines.flow.StateFlow<List<Int>>?,
    modifier: Modifier = Modifier,
) {
    if (settingsStore == null) {
        Text("Desktop settings are unavailable in this host.", modifier.padding(24.dp))
        return
    }
    DesktopSettingsPanel(
        state = state,
        paths = paths,
        logger = logger,
        settingsStore = settingsStore,
        playback = playback,
        playbackModesEnabled = playbackModesEnabled,
        shuffleModeEnabled = shuffleModeEnabled,
        audioEffects = audioEffects,
        audioOutput = audioOutput,
        downloads = downloads,
        libraryFolders = libraryFolders,
        libraryTrackCount = libraryTrackCount,
        libraryScanning = libraryScanning,
        libraryScanError = libraryScanError,
        onLibraryFoldersChanged = onLibraryFoldersChanged,
        onLibraryRescan = onLibraryRescan,
        onKeybindsChanged = onKeybindsChanged,
        onKeybindCaptureChanged = onKeybindCaptureChanged,
        keybindPressedKeys = keybindPressedKeys,
        account = account,
        accountContent = { if (account != null) DesktopAccountCard(account) else AnonymousAccountCard() },
        modifier = modifier,
    )
}

@Composable
private fun AnonymousAccountCard() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Anonymous mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Search and browsing use ArchiveTune's anonymous backend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DesktopAccountCard(account: DesktopSessionManager) {
    val accountState by account.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    var handoffNotice by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var managedLoginRunning by remember { mutableStateOf(false) }
    var managedLoginCompleting by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar((accountState as? DesktopAccountState.SignedIn)?.account?.avatarUrl)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    when (val value = accountState) {
                        DesktopAccountState.SignedOut -> {
                            Text("Anonymous mode", style = MaterialTheme.typography.titleMedium)
                            Text("Sign in to use your own YouTube Music account context.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is DesktopAccountState.SignedIn -> {
                            Text(value.account.name.ifBlank { "YouTube Music account" }, style = MaterialTheme.typography.titleMedium)
                            Text(value.account.email ?: value.account.channelHandle ?: "Signed in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is DesktopAccountState.Expired -> {
                            Text("Session needs refreshing", style = MaterialTheme.typography.titleMedium)
                            Text(value.account?.email ?: "Import a fresh YouTube session to continue using account features.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is DesktopAccountState.Unavailable -> {
                            Text("Secure account storage unavailable", style = MaterialTheme.typography.titleMedium)
                            Text("ArchiveTune will remain in anonymous mode until Windows secure storage is available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (accountState is DesktopAccountState.SignedIn) {
                val diagnostic = account.diagnostic
                Text(
                    "Session active · visitor context ${if (diagnostic.hasVisitorData) "available" else "not provided"} · channel context ${if (diagnostic.hasDataSyncId) "available" else "not provided"} · ${diagnostic.sessionFingerprint?.take(12) ?: "no fingerprint"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !managedLoginCompleting,
                    onClick = {
                        actionError = null
                        if (managedLoginRunning) {
                            if (!account.completeManagedBrowserLogin()) {
                                actionError = "The managed sign-in browser is not ready yet. Try again in a moment."
                            } else {
                                managedLoginCompleting = true
                                handoffNotice = "Finishing sign-in and importing your YouTube Music session…"
                            }
                        } else {
                            handoffNotice = "A separate ArchiveTune sign-in browser will open with browser sync disabled. Finish Google sign-in and make sure YouTube Music is signed in, then click Done signing in here. ArchiveTune will close only that temporary browser and read the session afterward."
                            managedLoginRunning = true
                            scope.launch {
                                try {
                                    account.signInWithManagedBrowser().fold(
                                        onSuccess = { result ->
                                            when (result) {
                                                is SessionImportResult.Success -> {
                                                    handoffNotice = "YouTube Music account signed in and stored with Windows protection."
                                                }
                                                is SessionImportResult.Rejected -> {
                                                    actionError = sessionImportMessage(result.reason)
                                                }
                                            }
                                        },
                                        onFailure = { failure ->
                                            actionError = failure.message ?: "Could not complete browser sign-in."
                                        },
                                    )
                                } finally {
                                    managedLoginRunning = false
                                    managedLoginCompleting = false
                                }
                            }
                        }
                    },
                ) {
                    Text(
                        when {
                            managedLoginCompleting -> "Finishing…"
                            managedLoginRunning -> "Done signing in"
                            else -> "Sign in with browser"
                        },
                    )
                }
                OutlinedButton(
                    enabled = !managedLoginRunning,
                    onClick = { showImport = true },
                ) { Text("Import Cookie-Editor JSON") }
                if (accountState is DesktopAccountState.SignedIn || accountState is DesktopAccountState.Expired) {
                    TextButton(
                        onClick = {
                            actionError = null
                            scope.launch {
                                account.logout().onFailure { actionError = "Could not remove the secure session." }
                            }
                        },
                    ) { Text("Sign out") }
                }
            }
            handoffNotice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actionError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showImport) {
        DesktopSessionImportDialog(
            account = account,
            onDismiss = { showImport = false },
            onCompleted = {
                showImport = false
                handoffNotice = "Account session validated and stored with Windows protection."
            },
        )
    }
}

@Composable
private fun AccountAvatar(avatarUrl: String?) {
    if (!avatarUrl.isNullOrBlank()) {
        SubcomposeAsyncImage(
            model = avatarUrl,
            contentDescription = "Account avatar",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(42.dp).clip(CircleShape),
            error = { AccountAvatarFallback() },
            loading = { AccountAvatarFallback() },
        )
    } else {
        AccountAvatarFallback()
    }
}

@Composable
private fun AccountAvatarFallback() {
    Icon(
        Icons.Rounded.AccountCircle,
        contentDescription = "Account avatar unavailable",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(42.dp),
    )
}

@Composable
private fun DesktopSessionImportDialog(
    account: DesktopSessionManager,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var cookieJson by remember { mutableStateOf("") }
    var inspection by remember { mutableStateOf<YouTubeCookieJsonInspection?>(null) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun acceptJson(value: String) {
        cookieJson = value
        inspection = account.inspectCookieJson(value)
        error = null
    }

    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text("Import YouTube Music cookies") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Manual fallback only. After signing in at music.youtube.com, export that site's cookies as JSON using Cookie-Editor or another trusted cookie-export tool. Modern Chromium browsers can rotate device-bound Google credentials, so a copied cookie snapshot may stop authenticating later; prefer Sign in with browser when possible.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = cookieJson,
                    onValueChange = ::acceptJson,
                    label = { Text("Cookie JSON export") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = !importing,
                    onClick = { scope.launch { chooseCookieJsonFile()?.let(::acceptJson) } },
                ) { Text("Choose JSON file") }
                when (val value = inspection) {
                    is YouTubeCookieJsonInspection.Ready -> Text(
                        "Ready: ${value.summary.acceptedCookieCount} allowed cookies from ${value.summary.domains.joinToString()}. Values are not displayed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is YouTubeCookieJsonInspection.Rejected -> Text(
                        cookieJsonMessage(value.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    null -> Text(
                        "Only YouTube-domain cookies are considered, matching Android's YouTube WebView cookie header; unrelated, Google-domain-only, and expired cookies are discarded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = inspection is YouTubeCookieJsonInspection.Ready && !importing,
                onClick = {
                    val submittedJson = cookieJson
                    // Clear the only raw-export buffer before any validation begins.
                    cookieJson = ""
                    inspection = null
                    importing = true
                    error = null
                    scope.launch {
                        when (val result = account.importCookieJson(submittedJson)) {
                            is CookieJsonSessionImportResult.Success -> onCompleted()
                            is CookieJsonSessionImportResult.SessionRejected -> {
                                importing = false
                                error = sessionImportMessage(result.reason)
                            }
                            is CookieJsonSessionImportResult.Rejected -> {
                                importing = false
                                error = cookieJsonMessage(result.reason)
                            }
                        }
                    }
                },
            ) { Text(if (importing) "Validating…" else "Import and validate") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !importing) { Text("Cancel") } },
    )
}

private suspend fun chooseCookieJsonFile(): String? =
    withContext(Dispatchers.IO) {
        var selected: File? = null
        SwingUtilities.invokeAndWait {
            JFileChooser().apply {
                dialogTitle = "Choose cookie JSON export"
                fileSelectionMode = JFileChooser.FILES_ONLY
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter("JSON files", "json")
                if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selected = selectedFile
            }
        }
        selected?.readBoundedCookieJson()
    }

/** Read one character past the importer limit so its existing TOO_LARGE result remains visible. */
private fun File.readBoundedCookieJson(): String =
    bufferedReader(Charsets.UTF_8).use { reader ->
        val result = StringBuilder(MAX_COOKIE_JSON_UI_CHARACTERS + 1)
        val buffer = CharArray(8_192)
        while (result.length <= MAX_COOKIE_JSON_UI_CHARACTERS) {
            val remaining = MAX_COOKIE_JSON_UI_CHARACTERS + 1 - result.length
            val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            result.append(buffer, 0, count)
        }
        result.toString()
    }

private const val MAX_COOKIE_JSON_UI_CHARACTERS = 1_000_000

private fun cookieJsonMessage(reason: YouTubeCookieJsonFailure): String =
    when (reason) {
        YouTubeCookieJsonFailure.EMPTY -> "Paste a cookie JSON export or choose a .json file."
        YouTubeCookieJsonFailure.TOO_LARGE -> "That export is too large to import safely."
        YouTubeCookieJsonFailure.MALFORMED_JSON -> "The selected text is not a supported JSON export."
        YouTubeCookieJsonFailure.NO_SUPPORTED_COOKIES -> "No live YouTube-domain cookies were found."
        YouTubeCookieJsonFailure.MISSING_LOGIN_COOKIE -> "The export does not contain a usable YouTube login cookie."
        YouTubeCookieJsonFailure.UNSAFE_COOKIE_VALUE -> "The export contains an unsafe cookie value and was rejected."
        YouTubeCookieJsonFailure.HEADER_TOO_LARGE -> "The selected login cookies are too large to import safely."
    }

private fun sessionImportMessage(reason: SessionImportFailure): String =
    when (reason) {
        SessionImportFailure.MISSING_LOGIN_COOKIE -> "A valid YouTube login cookie is required."
        SessionImportFailure.EXPIRED -> "That session export is already expired."
        SessionImportFailure.VALIDATION_FAILED -> "Google could not validate that session. Sign in again and import a fresh session."
        SessionImportFailure.SECURE_STORAGE_UNAVAILABLE -> "Windows secure storage is unavailable, so ArchiveTune did not retain this session."
    }

@Composable
private fun ThemeChoice(
    label: String,
    icon: ImageVector,
    preference: DesktopThemePreference,
    state: DesktopContentState,
    modifier: Modifier = Modifier,
) {
    val selected = state.themePreference == preference
    Surface(
        modifier = modifier.clickable { state.setThemePreference(preference) },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(label, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected")
        }
    }
}

@Composable
private fun DirectoryCard(
    title: String,
    path: String,
    icon: ImageVector,
    onOpen: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onOpen) { Text("Open") }
        }
    }
}
