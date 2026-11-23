/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.flow.MutableSharedFlow

/** MainActivity NavHost and Dimensions.kt, rather than platform navigation defaults. */
internal object AndroidMotion {
    const val RouteEnterMillis = 250
    const val RouteExitMillis = 200
    val navigationBar = spring<Dp>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
}

internal data class AndroidRouteFrame(val route: String, val page: String, val browse: MusicBrowseState) {
    val isTopLevel get() = route == "main" && browse.item == null
    val key get() = browse.item?.let { "browse:${it.id}:${it.params}" } ?: if (route == "main") page else route
}

internal val LocalAndroidBrowseSnapshot = staticCompositionLocalOf<MusicBrowseState?> { null }

internal fun AnimatedContentTransitionScope<AndroidRouteFrame>.androidRouteTransition(): ContentTransform {
    val fadeOnly = initialState.isTopLevel && targetState.isTopLevel ||
        initialState.route == "query" && targetState.isTopLevel
    val pop = targetState.isTopLevel && !initialState.isTopLevel || targetState.browse.depth < initialState.browse.depth
    val enter = fadeIn(tween(AndroidMotion.RouteEnterMillis))
    val exit = fadeOut(tween(AndroidMotion.RouteExitMillis))
    return if (fadeOnly) enter togetherWith exit
    else (enter + slideInHorizontally { if (pop) -it / 2 else it / 2 }) togetherWith
        (exit + slideOutHorizontally { if (pop) it / 2 else -it / 2 })
}

internal val LocalAndroidHeaderInset = staticCompositionLocalOf { 0.dp }
internal val LocalAndroidScrollRequests = staticCompositionLocalOf { MutableSharedFlow<String>() }

@Composable
internal fun AndroidScrollToTopRequests(page: String, state: LazyListState, enabled: Boolean = true) {
    val requests = LocalAndroidScrollRequests.current
    LaunchedEffect(requests, page, state, enabled) {
        if (enabled) requests.collect { if (it == page) state.animateScrollToItem(0) }
    }
}
