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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.desktop.platform.DesktopMusicServices
import moe.rukamori.archivetune.innertube.home.sortNewReleasesByArtistPreference
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.ChartsPage
import moe.rukamori.archivetune.innertube.pages.ExplorePage

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
    val snapshot by services.playback.state.collectAsState()
    val isPlaying = snapshot.isPlaying
    val currentItemId = snapshot.currentItem?.metadata?.id
    val currentAlbumId = snapshot.currentItem?.metadata?.album?.id

    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var explorePage by remember { mutableStateOf<ExplorePage?>(null) }
    var chartsPage by remember { mutableStateOf<ChartsPage?>(null) }
    var loadAttempt by remember { mutableStateOf(0) }

    LaunchedEffect(loadAttempt) {
        isLoading = true
        error = null
        explorePage = null
        chartsPage = null
        services.backend.explore().fold(
            onSuccess = { explorePage = it },
            onFailure = { error = it.message ?: "Could not load the explore page." },
        )
        if (error == null) {
            services.backend.charts().fold(
                onSuccess = { chartsPage = it },
                onFailure = { error = it.message ?: "Could not load charts." },
            )
        }
        isLoading = false
    }

    val sections = chartsPage?.sections.orEmpty()
    val regularSections = sections.filterNot { section ->
        section.title.equals(TOP_MUSIC_VIDEOS, ignoreCase = true)
    }
    val topMusicVideos = sections.firstOrNull { section ->
        section.title.equals(TOP_MUSIC_VIDEOS, ignoreCase = true)
    }
    val newReleaseAlbums =
        sortNewReleasesByArtistPreference(
            albums = explorePage?.newReleaseAlbums.orEmpty(),
            snapshot = services.homeStore.snapshot.value,
        )
    val hasContent =
        regularSections.any { section -> section.items.filterIsInstance<SongItem>().isNotEmpty() } ||
            newReleaseAlbums.isNotEmpty() ||
            topMusicVideos?.items?.filterIsInstance<SongItem>()?.isNotEmpty() == true

    Box(modifier.fillMaxSize()) {
        when {
            isLoading -> ExploreLoadingState()
            error != null -> ExploreErrorState(message = error ?: "", onRetry = { loadAttempt += 1 })
            !hasContent -> ExploreEmptyState()
            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
                ) {
                    regularSections.forEachIndexed { sectionIndex, section ->
                        val sectionSongs =
                            section.items.filterIsInstance<SongItem>().distinctBy { it.id }
                        if (sectionSongs.isNotEmpty()) {
                            item(key = "section:$sectionIndex:${section.title}") {
                                DesktopSectionHeader(title = section.title)
                            }
                            itemsIndexed(
                                items = sectionSongs,
                                key = { index, song -> "section:$sectionIndex:${song.id}:$index" },
                            ) { index, song ->
                                DesktopMediaListRow(
                                    song = song,
                                    isActive = song.id == currentItemId,
                                    isPlaying = isPlaying,
                                    onClick = { onPlayQueue(sectionSongs, index, section.title) },
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
                    }

                    if (newReleaseAlbums.isNotEmpty()) {
                        item(key = "section:new-releases") {
                            DesktopSectionHeader(title = "New releases")
                        }
                        item(key = "new-releases-row") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp)) {
                                itemsIndexed(
                                    items = newReleaseAlbums.distinctBy { it.id },
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
                        val sectionSongs =
                            section.items.filterIsInstance<SongItem>().distinctBy { it.id }
                        if (sectionSongs.isNotEmpty()) {
                            item(key = "section:top-music-videos") {
                                DesktopSectionHeader(title = TOP_MUSIC_VIDEOS)
                            }
                            item(key = "top-music-videos-row") {
                                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp)) {
                                    itemsIndexed(
                                        items = sectionSongs,
                                        key = { index, song -> "top-video:${song.id}:$index" },
                                    ) { index, song ->
                                        DesktopMediaGridCard(
                                            item = song,
                                            isActive = song.id == currentItemId,
                                            isPlaying = isPlaying,
                                            onClick = { onPlayQueue(sectionSongs, index, section.title) },
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
}

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
