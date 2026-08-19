/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.desktop.platform.DesktopMusicServices
import moe.rukamori.archivetune.innertube.home.sortNewReleasesByArtistPreference
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem

/** Desktop port of the Android Explore screen: charts, new releases and top music videos. */
@Composable
fun DesktopExploreSurface(
    services: DesktopMusicServices,
    onOpenItem: (YTItem) -> Unit,
    onPlayQueue: (List<SongItem>, Int, String) -> Unit,
    onDownload: (SongItem) -> Unit,
    onPlayNext: (SongItem) -> Unit,
    onAddToQueue: (SongItem) -> Unit,
    onAddToPlaylist: (SongItem) -> Unit,
    onSetLiked: (SongItem, Boolean) -> Unit,
    isLiked: (SongItem) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val playbackIdentity = rememberPlaybackIdentity(services.playback)
    val isPlaying = playbackIdentity.isPlaying
    val currentItemId = playbackIdentity.currentItemId
    val currentAlbumId = playbackIdentity.currentAlbumId
    val exploreState by services.exploreRepository.state.collectAsState()
    val homeSnapshot by services.homeStore.snapshot.collectAsState()
    val listState = rememberPersistedLazyListState("explore")

    LaunchedEffect(Unit) { services.exploreRepository.activate() }

    val explorePage = exploreState.explorePage
    val chartsPage = exploreState.chartsPage

    val regularSections = remember(chartsPage) {
        chartsPage?.sections.orEmpty()
            .filterNot { it.title.equals(TOP_MUSIC_VIDEOS, ignoreCase = true) }
            .mapNotNull { section ->
                section.items.filterIsInstance<SongItem>().distinctBy { it.id }
                    .takeIf(List<SongItem>::isNotEmpty)
                    ?.let { songs -> ExploreSongSection(section.title, songs) }
            }
    }
    val topMusicVideos = remember(chartsPage) {
        chartsPage?.sections.orEmpty()
            .firstOrNull { it.title.equals(TOP_MUSIC_VIDEOS, ignoreCase = true) }
            ?.let { section ->
                section.items.filterIsInstance<SongItem>().distinctBy { it.id }
                    .takeIf(List<SongItem>::isNotEmpty)
                    ?.let { songs -> ExploreSongSection(section.title, songs) }
            }
    }
    val newReleaseAlbums = remember(explorePage?.newReleaseAlbums, homeSnapshot) {
        sortNewReleasesByArtistPreference(
            albums = explorePage?.newReleaseAlbums.orEmpty(),
            snapshot = homeSnapshot,
        ).distinctBy { it.id }
    }
    val hasContent =
        regularSections.isNotEmpty() ||
            newReleaseAlbums.isNotEmpty() ||
            topMusicVideos != null

    Box(modifier.fillMaxSize()) {
        when {
            exploreState.isInitialLoading -> ExploreLoadingState()
            exploreState.error != null && !exploreState.hasContent ->
                ExploreErrorState(message = exploreState.error ?: "", onRetry = services.exploreRepository::refresh)
            !hasContent -> ExploreEmptyState()
            else ->
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
                ) {
                    if (exploreState.isRefreshing) {
                        item(key = "explore-refreshing") {
                            Text(
                                "Refreshing…",
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    exploreState.error?.let { refreshError ->
                        item(key = "explore-refresh-error") {
                            Text(
                                refreshError,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    regularSections.forEachIndexed { sectionIndex, section ->
                        item(key = "section:$sectionIndex:${section.title}") {
                            DesktopSectionHeader(title = section.title)
                        }
                        itemsIndexed(
                            items = section.songs,
                            key = { index, song -> "section:$sectionIndex:${song.id}:$index" },
                        ) { index, song ->
                            DesktopMediaListRow(
                                song = song,
                                isActive = song.id == currentItemId,
                                isPlaying = isPlaying,
                                onClick = { onPlayQueue(section.songs, index, section.title) },
                                onPlayNext = { onPlayNext(song) },
                                onAddToQueue = { onAddToQueue(song) },
                                onDownload = { onDownload(song) },
                                onAddToPlaylist = { onAddToPlaylist(song) },
                                onSetLiked = { onSetLiked(song, it) },
                                isLiked = isLiked(song),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (newReleaseAlbums.isNotEmpty()) {
                        item(key = "section:new-releases") {
                            DesktopSectionHeader(title = "New releases")
                        }
                        item(key = "new-releases-row") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp)) {
                                itemsIndexed(
                                    items = newReleaseAlbums,
                                    key = { index, album -> "release:${album.id}:$index" },
                                ) { _, album ->
                                    DesktopMediaGridCard(
                                        item = album,
                                        isActive = album.browseId == currentAlbumId,
                                        isPlaying = isPlaying,
                                        onClick = { onOpenItem(album) },
                                        modifier = Modifier.width(160.dp),
                                    )
                                }
                            }
                        }
                    }

                    topMusicVideos?.let { section ->
                        item(key = "section:top-music-videos") {
                            DesktopSectionHeader(title = TOP_MUSIC_VIDEOS)
                        }
                        item(key = "top-music-videos-row") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp)) {
                                itemsIndexed(
                                    items = section.songs,
                                    key = { index, song -> "top-video:${song.id}:$index" },
                                ) { index, song ->
                                    DesktopMediaGridCard(
                                        item = song,
                                        isActive = song.id == currentItemId,
                                        isPlaying = isPlaying,
                                        onClick = { onPlayQueue(section.songs, index, section.title) },
                                        onPlayNext = { onPlayNext(song) },
                                        onAddToQueue = { onAddToQueue(song) },
                                        onDownload = { onDownload(song) },
                                        onAddToPlaylist = { onAddToPlaylist(song) },
                                        onSetLiked = { onSetLiked(song, it) },
                                        isLiked = isLiked(song),
                                        modifier = Modifier.width(160.dp),
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}

private data class ExploreSongSection(
    val title: String,
    val songs: List<SongItem>,
)

@Composable
private fun ExploreLoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ExploreErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun ExploreEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Nothing to explore yet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val TOP_MUSIC_VIDEOS = "Top music videos"
