/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import moe.rukamori.archivetune.desktop.state.desktopViewStateStore

@Composable
internal fun rememberPersistedLazyListState(key: String): LazyListState {
    val restored = remember(key) { desktopViewStateStore.position(key) }
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = restored?.firstVisibleItemIndex ?: 0,
        initialFirstVisibleItemScrollOffset = restored?.firstVisibleItemScrollOffset ?: 0,
    )
    DisposableEffect(key, state) {
        onDispose {
            desktopViewStateStore.save(
                key = key,
                firstVisibleItemIndex = state.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset,
            )
        }
    }
    return state
}