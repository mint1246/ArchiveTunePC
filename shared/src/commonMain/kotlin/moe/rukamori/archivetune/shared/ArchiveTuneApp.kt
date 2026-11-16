/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlin.time.TimeSource
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.shared.resources.*
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArchiveTuneApp(controller: MusicController) {
    val browse by controller.browse.collectAsState()
    val library = controller.library?.collectAsState()?.value
    val track by remember(controller) { controller.playback.map { it.track }.distinctUntilChanged() }.collectAsState(null)
    var page by rememberSaveable { mutableStateOf("Home") }
    var route by rememberSaveable { mutableStateOf("main") }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var playerProgress by remember { mutableFloatStateOf(0f) }
    var playerPresence by remember { mutableFloatStateOf(0f) }
    var more by remember { mutableStateOf(false) }
    val theme = library?.settings?.theme ?: MusicThemeChoice.SYSTEM
    val screenStates = rememberSaveableStateHolder()
    val scrollRequests = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    val navigationScope = rememberCoroutineScope()
    val frame = AndroidRouteFrame(route, page, browse)
    val retainedRoutes = remember { mutableListOf<String>() }
    LaunchedEffect(frame.key) {
        retainedRoutes.remove(frame.key)
        retainedRoutes += frame.key
        while (retainedRoutes.size > 16) screenStates.removeState(retainedRoutes.removeAt(0))
    }
    val homeHeader = androidAppBarScrollBehavior(canScroll = { !playerOpen })
    val searchHeader = androidAppBarScrollBehavior(canScroll = { !playerOpen })
    val libraryHeader = androidAppBarScrollBehavior(canScroll = { !playerOpen })
    val currentHeader = when (page) { "Home" -> homeHeader; "Library" -> libraryHeader; else -> searchHeader }
    val topInset = WindowInsets.safeDrawing.getTop(LocalDensity.current)
    var headerHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentHeader.state, headerHeight, topInset) {
        if (headerHeight > 0) currentHeader.state.heightOffsetLimit = -(headerHeight + topInset).toFloat()
    }
    val navVisible = frame.isTopLevel
    val navHeight by animateDpAsState(if (navVisible) 78.dp else 0.dp,
        animationSpec = AndroidMotion.navigationBar, label = "androidNavigationHeight")
    val motionClock = remember { TimeSource.Monotonic.markNow() }
    val doubleTapTimeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
    var lastSearchClick by remember { mutableStateOf<Long?>(null) }
    ArchiveTuneTheme(theme, artworkUrl = track?.artworkUrl) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 1f))) {
            ShellArtworkBackdrop(controller)
            // MainActivity hides the shell's drawing as soon as the dynamic player's
            // expanded anchor is targeted. Keep its composition/scroll state alive.
            Box(Modifier.fillMaxSize().drawWithContent { if (!playerOpen) drawContent() }
                .then(if (playerOpen) Modifier.clearAndSetSemantics { } else Modifier)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                if (route == "main" && browse.item == null) {
                    AndroidShellHeader(modifier = Modifier.zIndex(1f).onSizeChanged {
                        headerHeight = it.height
                    }.offset { IntOffset(0, if (page in listOf("Home", "Search", "Library")) currentHeader.state.heightOffset.roundToInt() else 0) }, actions = {
                        ShellAction(Res.drawable.history, "History") { route = "history" }
                        ShellAction(Res.drawable.newspaper, "News") { controller.news?.load(); route = "news" }
                        ShellAction(Res.drawable.new_release, "New release albums") { controller.openBrowse(MusicCatalogItem.Browse("FEmusic_new_releases_albums", "New release albums")) }
                        ShellAction(Res.drawable.settings, "Settings") { route = "settings" }
                    })
                }
                AnimatedContent(targetState = frame, modifier = Modifier.fillMaxSize().then(
                    if (frame.isTopLevel && page in listOf("Home", "Search", "Library")) Modifier.nestedScroll(currentHeader.nestedScrollConnection) else Modifier),
                    contentKey = { it.key }, transitionSpec = { androidRouteTransition().using(null) },
                    label = "androidRoute") { displayed ->
                    screenStates.SaveableStateProvider(displayed.key) {
                    CompositionLocalProvider(LocalAndroidBrowseSnapshot provides displayed.browse,
                        LocalAndroidHeaderInset provides if (displayed.isTopLevel) 64.dp else 0.dp,
                        LocalAndroidScrollRequests provides scrollRequests) {
                    Column(Modifier.fillMaxSize().then(
                        if (displayed.isTopLevel && displayed.page == "Library") Modifier.layout { measurable, constraints ->
                            val offset = libraryHeader.state.heightOffset.roundToInt().coerceAtMost(0)
                            val placeable = measurable.measure(constraints.copy(
                                minHeight = (constraints.minHeight - offset).coerceAtLeast(0),
                                maxHeight = (constraints.maxHeight - offset).coerceAtLeast(0)))
                            layout(placeable.width, constraints.maxHeight) { placeable.placeRelative(0, offset) }
                        }.padding(top = 64.dp)
                        else if (displayed.isTopLevel && displayed.page == "Friends") Modifier.padding(top = 64.dp)
                        else Modifier)) {
                when {
                    displayed.browse.item != null -> AndroidBrowseContent(controller, Modifier.weight(1f))
                    displayed.route == "query" -> AndroidQueryContent(controller, { route = "main" }, Modifier.weight(1f))
                    displayed.route == "settings" -> AndroidSettingsContent(controller, onBack = { route = "main" }, modifier = Modifier.weight(1f))
                    displayed.route == "history" -> AndroidHistoryContent(controller, { route = "main" }, Modifier.weight(1f))
                    displayed.route == "news" -> AndroidNewsContent(controller, onBack = { route = "main" }, modifier = Modifier.weight(1f))
                    displayed.page == "Home" -> AndroidHomeContent(controller, Modifier.weight(1f))
                    displayed.page == "Search" -> AndroidSearchContent(controller, { route = "query" }, Modifier.weight(1f))
                    displayed.page == "Library" -> AndroidLibraryContent(controller, Modifier.weight(1f), onSearch = { page = "Search"; route = "query" })
                    else -> AndroidFriendsContent(controller, Modifier.weight(1f).padding(bottom = navHeight + 10.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (track != null) 74.dp else 0.dp))
                }
                    }
                    }
                    }
                }
            }
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val navProximity = (navHeight / 78.dp).coerceIn(0f, 1f)
            val navSlideDistance = bottomInset + 10.dp + 78.dp
            Box(Modifier.align(Alignment.BottomCenter).zIndex(2f).height(navSlideDistance).offset {
                IntOffset(0, (navSlideDistance * (1f - navProximity) + navSlideDistance * playerProgress).roundToPx())
            }) {
                val proximity = if (track != null) navProximity * playerPresence else 0f
                Surface(modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 420.dp).fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = bottomInset + 10.dp).height(78.dp),
                    shape = RoundedCornerShape(topStart = (32 - 20 * proximity).dp, topEnd = (32 - 20 * proximity).dp,
                        bottomStart = (32 - 4 * proximity).dp, bottomEnd = (32 - 4 * proximity).dp),
                    color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = NavigationBarDefaults.Elevation,
                    shadowElevation = NavigationBarDefaults.Elevation) {
                    ShortNavigationBar(Modifier.fillMaxSize(), containerColor = Color.Transparent,
                        windowInsets = WindowInsets(0,0,0,0), arrangement = ShortNavigationBarArrangement.EqualWeight) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Row(Modifier.widthIn(max = 360.dp).fillMaxWidth().fillMaxHeight().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf("Home", "Search", "Library", "Friends").forEach { destination ->
                                ShortNavigationBarItem(selected = destination == page, onClick = {
                                    val now = motionClock.elapsedNow().inWholeMilliseconds
                                    val doubleSearch = destination == "Search" && lastSearchClick?.let { now - it <= doubleTapTimeout } == true
                                    lastSearchClick = if (destination == "Search" && !doubleSearch) now else null
                                    if (page == destination && route == "main" && !doubleSearch) {
                                        scrollRequests.tryEmit(destination)
                                        navigationScope.launch { currentHeader.state.resetAndroidHeaderOffset() }
                                    }
                                    page = destination; route = if (doubleSearch) "query" else "main"; controller.clearBrowse()
                                }, modifier = Modifier.weight(1f), icon = {
                                    Crossfade(targetState = page == destination, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "navigationItemIcon") { selected ->
                                        Glyph(if (selected && destination !in listOf("Search", "Friends")) destination.lowercase() + "Selected" else destination.lowercase(), Modifier.size(24.dp))
                                    }
                                }, label = { Text(destination, maxLines = 1) })
                            }
                        }
                        }
                    }
                }
            }
            AnimatedVisibility(visible = page == "Home" && route == "main" && browse.item == null && !playerOpen,
                enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleIn(initialScale = .8f, animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleOut(targetScale = .8f, animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()),
                modifier = Modifier.align(Alignment.BottomEnd)) {
            Box(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = if (track == null) 100.dp else 174.dp)) {
                FloatingActionButton(onClick = { more = true }) { Icon(painterResource(Res.drawable.more_horiz), "more") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text("Refresh") }, onClick = { more = false; controller.loadHome(true) })
                    DropdownMenuItem(text = { Text("Search") }, onClick = { more = false; page = "Search"; route = "query" })
                }
            }
            }
            AndroidPlayerHost(controller, expanded = playerOpen, onExpandedChange = { playerOpen = it }, modifier = Modifier.zIndex(1f),
                navigationProximity = navProximity,
                collapsedBottomInset = bottomInset + navHeight + 14.dp * navProximity,
                onProgressChange = { playerProgress = it }, onPresenceChange = { playerPresence = it })
            AndroidPlaybackErrorDialog(controller)
        }
    }
}

@Composable
private fun ShellArtworkBackdrop(controller: MusicController) {
    val artwork by remember(controller) { controller.playback.map { it.track?.artworkUrl to it.isPlaying }.distinctUntilChanged() }.collectAsState(null to false)
    if (artwork.first != null) AndroidDynamicBackdrop(artwork.first, isPlaying = artwork.second)
}

@Composable
private fun ShellAction(resource: org.jetbrains.compose.resources.DrawableResource, description: String, action: () -> Unit) {
    IconButton(onClick = action, colors = IconButtonDefaults.iconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .48f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Icon(painterResource(resource), description) }
}

@Composable
internal fun RouteHeading(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.arrow_back), "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Glyph(name: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val resource = when (name) {
        "play" -> Res.drawable.play
        "pause" -> Res.drawable.pause
        "next" -> Res.drawable.skip_next
        "previous" -> Res.drawable.skip_previous
        "home" -> Res.drawable.home_outlined
        "homeSelected" -> Res.drawable.home_filled
        "search" -> Res.drawable.search
        "library" -> Res.drawable.library_outlined
        "librarySelected" -> Res.drawable.library_filled
        "friends" -> Res.drawable.person
        "down" -> Res.drawable.expand_more
        "queue" -> Res.drawable.queue_music
        "heart" -> Res.drawable.favorite_border
        "heartFilled" -> Res.drawable.favorite
        else -> Res.drawable.music_note
    }
    Icon(painterResource(resource), contentDescription = null, modifier = modifier, tint = color)
}

internal fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
