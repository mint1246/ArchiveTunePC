package moe.rukamori.archivetune.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.library.LocalTrack
import moe.rukamori.archivetune.desktopauth.DesktopAccountState
import moe.rukamori.archivetune.desktop.platform.DesktopMusicServices
import moe.rukamori.archivetune.desktop.platform.DesktopDownloadServices
import moe.rukamori.archivetune.desktop.platform.AppPaths
import moe.rukamori.archivetune.desktop.platform.DesktopAlbumIndex
import moe.rukamori.archivetune.desktop.party.PartyMode
import moe.rukamori.archivetune.desktop.party.PartyPhase
import moe.rukamori.archivetune.desktop.party.PartyTransport
import moe.rukamori.archivetune.desktop.party.PlayTogetherManager
import moe.rukamori.archivetune.desktop.lyrics.DesktopLyricsPane
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.logging.Logger
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.AlbumPage
import moe.rukamori.archivetune.innertube.pages.ArtistItemsContinuationPage
import moe.rukamori.archivetune.innertube.pages.ArtistItemsPage
import moe.rukamori.archivetune.innertube.pages.ArtistPage
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import kotlin.math.roundToLong

internal sealed interface Detail {
    data class Album(val page: AlbumPage) : Detail
    data class Artist(val page: ArtistPage) : Detail
    data class ArtistItems(val page: ArtistItemsPage, val parent: ArtistPage) : Detail
    data class Playlist(val page: PlaylistPage) : Detail
}

/**
 * Shares the currently-viewed detail page (album/artist/playlist) between the main content area
 * and the mini player, so the mini player can open the album of the song that is playing.
 */
internal class DesktopBrowseController {
    var detail by mutableStateOf<Detail?>(null)
    var loading by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)

    fun clear() {
        detail = null
        failure = null
    }

    fun openAlbum(browseId: String, backend: ArchiveTuneJvmClient, scope: CoroutineScope) =
        open(scope) { backend.album(browseId).map { Detail.Album(it) } }

    fun openArtist(id: String, backend: ArchiveTuneJvmClient, scope: CoroutineScope) =
        open(scope) { backend.artist(id).map { Detail.Artist(it) } }

    fun openArtistItems(endpoint: BrowseEndpoint, parent: ArtistPage, backend: ArchiveTuneJvmClient, scope: CoroutineScope) =
        open(scope) { backend.artistItems(endpoint).map { Detail.ArtistItems(it, parent) } }

    fun openPlaylist(id: String, backend: ArchiveTuneJvmClient, scope: CoroutineScope) =
        open(scope) { backend.playlist(id).map { Detail.Playlist(it) } }

    private fun open(scope: CoroutineScope, load: suspend () -> Result<Detail>) {
        scope.launch {
            loading = true
            failure = null
            load()
                .onSuccess { detail = it }
                .onFailure { failure = it.message ?: "Could not load this item" }
            loading = false
        }
    }
}

@Composable
internal fun DesktopMusicContent(state: DesktopContentState, services: DesktopMusicServices, downloadServices: DesktopDownloadServices, paths: AppPaths, logger: Logger, browse: DesktopBrowseController, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val accountState by services.account.state.collectAsState()
    val homeSnapshot by services.homeStore.snapshot.collectAsState()
    val remoteLibraryState by services.libraryRepository.state.collectAsState()
    val localTracks by services.library.tracksFlow.collectAsState()
    val onlinePlaylists = remoteLibraryState.playlists
    var resultItems by remember { mutableStateOf<List<YTItem>>(emptyList()) }
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var showSpotifyImport by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var createPlaylistSong by remember { mutableStateOf<SongItem?>(null) }
    var addToPlaylistSong by remember { mutableStateOf<SongItem?>(null) }
    var playlistPickerLoading by remember { mutableStateOf(false) }
    suspend fun rescanLibrary() {
        libraryLoading = true; libraryError = null
        runCatching { services.library.rescan() }.onFailure { libraryError = it.message ?: "Library scan failed" }
        libraryLoading = false
    }
    LaunchedEffect(state.destination) {
        browse.clear()
        if (state.destination != moe.rukamori.archivetune.desktop.state.DesktopDestination.LIBRARY) return@LaunchedEffect
        services.libraryRepository.activate()
    }
    LaunchedEffect(state.searchQuery, state.destination) {
        if (state.destination != moe.rukamori.archivetune.desktop.state.DesktopDestination.SEARCH || state.searchQuery.isBlank()) return@LaunchedEffect
        delay(300); browse.loading = true; browse.failure = null
        services.backend.searchSummary(state.searchQuery).onSuccess { page -> resultItems = page.summaries.flatMap { it.items }.distinctBy { it::class to it.id } }
            .onFailure { browse.failure = it.message ?: "Search failed. Check your connection and try again."; resultItems = emptyList() }
        browse.loading = false
    }
    fun open(item: YTItem) {
        when (item) {
            is SongItem -> scope.launch {
                browse.failure = null
                services.backend.upNext(item.id)
                    .onSuccess { next ->
                        val songs = next.items
                        if (songs.isEmpty()) {
                            services.playback.replaceQueue(
                                listOf(toPlaybackItem(item, services.albumIndex)),
                                title = "Up next",
                            )
                            return@onSuccess
                        }

                        val selectedIndex =
                            next.currentIndex
                                ?: songs.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }
                                ?: 0
                        services.playback.replaceQueue(
                            songs.map { toPlaybackItem(it, services.albumIndex) },
                            selectedIndex.coerceIn(0, songs.lastIndex),
                            title = next.title ?: "Up next",
                        )
                    }
                    .onFailure {
                        browse.failure = it.message ?: "Could not load Up next"
                        services.playback.replaceQueue(
                            listOf(toPlaybackItem(item, services.albumIndex)),
                            title = "Up next",
                        )
                    }
            }
            is AlbumItem -> browse.openAlbum(item.browseId, services.backend, scope)
            is ArtistItem -> browse.openArtist(item.id, services.backend, scope)
            is PlaylistItem -> browse.openPlaylist(item.id, services.backend, scope)
        }
    }
    fun openBrowseItem(item: YTItem) {
        when (item) {
            is AlbumItem -> browse.openAlbum(item.browseId, services.backend, scope)
            is ArtistItem -> browse.openArtist(item.id, services.backend, scope)
            is PlaylistItem -> browse.openPlaylist(item.id, services.backend, scope)
            is SongItem -> Unit
        }
    }
    fun playQueue(songs: List<SongItem>, startIndex: Int, title: String) {
        if (songs.isEmpty()) return
        scope.launch {
            services.playback.replaceQueue(
                songs.map { toPlaybackItem(it, services.albumIndex) },
                startIndex.coerceIn(0, songs.lastIndex),
                title = title,
            )
        }
    }
    fun downloadSong(song: SongItem) {
        scope.launch {
            downloadServices.enqueue(song)
                .onSuccess { state.navigate(moe.rukamori.archivetune.desktop.state.DesktopDestination.DOWNLOADS) }
                .onFailure { browse.failure = it.message ?: "Could not resolve a stream for download" }
        }
    }
    fun downloadSongs(songs: List<SongItem>) {
        scope.launch {
            val result = downloadServices.enqueueAll(songs)
            if (result.failures.isNotEmpty()) {
                browse.failure = "Queued ${result.succeeded}/${result.requested} songs. ${result.failures.first()}"
            } else if (result.succeeded > 0) {
                state.navigate(moe.rukamori.archivetune.desktop.state.DesktopDestination.DOWNLOADS)
            }
        }
    }
    fun playNextSong(song: SongItem) { scope.launch { services.playback.playNext(listOf(toPlaybackItem(song, services.albumIndex))) } }
    fun enqueueSong(song: SongItem) { scope.launch { services.playback.enqueue(listOf(toPlaybackItem(song, services.albumIndex))) } }
    fun playNextLocal(track: LocalTrack) { scope.launch { services.playback.playNext(listOf(toPlaybackItem(track))) } }
    fun enqueueLocal(track: LocalTrack) { scope.launch { services.playback.enqueue(listOf(toPlaybackItem(track))) } }
    fun isLiked(song: SongItem) = homeSnapshot.likedSongs.any { it.id == song.id }
    fun setLiked(song: SongItem, liked: Boolean) {
        scope.launch {
            services.backend.setSongLiked(song.id, liked)
                .onSuccess { services.homeStore.setSongLiked(song, liked) }
                .onFailure { browse.failure = it.message ?: "Could not update like" }
        }
    }
    fun choosePlaylist(song: SongItem) {
        addToPlaylistSong = song
        scope.launch {
            playlistPickerLoading = true
            services.libraryRepository.activate()
            playlistPickerLoading = false
        }
    }
    fun createPlaylist(song: SongItem? = null) {
        addToPlaylistSong = null
        createPlaylistSong = song
        showCreatePlaylist = true
    }
    AnimatedContent(
        targetState = browse.detail,
        transitionSpec = {
            val enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 18 }
            val exit = fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                slideOutHorizontally(tween(160, easing = FastOutSlowInEasing)) { -it / 24 }
            (enter togetherWith exit).using(SizeTransform(clip = false))
        },
        label = "detail-page-transition",
        modifier = modifier.fillMaxSize(),
    ) { page ->
        when (page) {
        null -> when (state.destination) {
            moe.rukamori.archivetune.desktop.state.DesktopDestination.SEARCH -> SearchResults(state.searchQuery, resultItems, browse.loading, browse.failure, ::open, ::downloadSong, ::playNextSong, ::enqueueSong, ::choosePlaylist, ::setLiked, ::isLiked, modifier)
            moe.rukamori.archivetune.desktop.state.DesktopDestination.DOWNLOADS -> DesktopDownloadsSurface(downloadServices, { path, item -> scope.launch { services.playback.replaceQueue(listOf(toPlaybackItem(item, path, services.albumIndex)), title = "Downloads") } }, { path, item -> scope.launch { services.playback.playNext(listOf(toPlaybackItem(item, path, services.albumIndex))) } }, { path, item -> scope.launch { services.playback.enqueue(listOf(toPlaybackItem(item, path, services.albumIndex))) } }, modifier)
            moe.rukamori.archivetune.desktop.state.DesktopDestination.LIBRARY -> LocalLibrary(
                localTracks, onlinePlaylists, services.library.folders,
                libraryLoading || remoteLibraryState.isInitialLoading || browse.loading,
                browse.failure ?: libraryError ?: remoteLibraryState.error,
                onAddFolder = { scope.launch { chooseLibraryFolder()?.let { services.library.setFolders(services.library.folders + it); rescanLibrary() } } },
                onRescan = { scope.launch { rescanLibrary() } },
                spotifyImportEnabled = accountState is DesktopAccountState.SignedIn,
                onImportSpotify = { showSpotifyImport = true },
                onCreatePlaylist = { createPlaylist() },
                onOpenPlaylist = ::open,
                onPlay = { selected -> scope.launch { val items = localTracks.map(::toPlaybackItem); services.playback.replaceQueue(items, localTracks.indexOf(selected).coerceAtLeast(0), title = "Local library") } },
                onPlayNext = ::playNextLocal,
                onAddToQueue = ::enqueueLocal,
                modifier,
            )
            moe.rukamori.archivetune.desktop.state.DesktopDestination.HOME -> DesktopHomeSurface(
                services,
                onOpenItem = ::openBrowseItem,
                onPlayQueue = ::playQueue,
                onDownload = ::downloadSong,
                onPlayNext = ::playNextSong,
                onAddToQueue = ::enqueueSong,
                onAddToPlaylist = ::choosePlaylist,
                onSetLiked = ::setLiked,
                modifier = modifier,
            )
            moe.rukamori.archivetune.desktop.state.DesktopDestination.EXPLORE -> DesktopExploreSurface(
                services,
                onOpenItem = ::openBrowseItem,
                onPlayQueue = ::playQueue,
                onDownload = ::downloadSong,
                onPlayNext = ::playNextSong,
                onAddToQueue = ::enqueueSong,
                onAddToPlaylist = ::choosePlaylist,
                onSetLiked = ::setLiked,
                isLiked = ::isLiked,
                modifier = modifier,
            )
            moe.rukamori.archivetune.desktop.state.DesktopDestination.SETTINGS -> FoundationDestinationContent(
                state,
                paths,
                logger,
                services.account,
                settingsStore = services.settingsStore,
                playback = services.playback,
                downloads = downloadServices,
                libraryFolders = services.library.folders,
                onLibraryFoldersChanged = { folders ->
                    services.library.setFolders(folders)
                    scope.launch { rescanLibrary() }
                },
                modifier = modifier,
            )
        }
        is Detail.Playlist -> PlaylistDetailPage(
            page.page,
            onBack = { browse.clear() },
            onPlay = { songs, index, title ->
                scope.launch { services.playback.replaceQueue(songs.map { toPlaybackItem(it, services.albumIndex) }, index, title = title) }
            },
            onDownload = ::downloadSong,
            onDownloadAll = ::downloadSongs,
            onPlayNext = ::playNextSong,
            onAddToQueue = ::enqueueSong,
            onAddToPlaylist = ::choosePlaylist,
            onSetLiked = ::setLiked,
            isLiked = ::isLiked,
            onRemoveFromPlaylist = { song ->
                scope.launch {
                    services.backend.removeSongFromPlaylist(page.page.playlist.id, song)
                        .onSuccess { browse.openPlaylist(page.page.playlist.id, services.backend, scope) }
                        .onFailure { browse.failure = it.message ?: "Could not remove song from playlist" }
                }
            },
            onRenamePlaylist = { name ->
                val original = page.page.playlist
                services.libraryRepository.renameCached(original.id, name)
                scope.launch {
                    services.backend.renamePlaylist(original.id, name)
                        .onSuccess {
                            browse.openPlaylist(original.id, services.backend, scope)
                        }
                        .onFailure {
                            services.libraryRepository.upsertCached(original)
                            browse.failure = it.message ?: "Could not rename playlist"
                        }
                }
            },
            onDeletePlaylist = {
                val original = page.page.playlist
                services.libraryRepository.removeCached(original.id)
                browse.clear()
                scope.launch {
                    services.backend.deletePlaylist(original.id)
                        .onFailure {
                            services.libraryRepository.upsertCached(original)
                            browse.failure = it.message ?: "Could not delete playlist"
                        }
                }
            },
            modifier = modifier,
        )
        is Detail.ArtistItems -> ArtistItemsDetailPage(
            detail = page,
            onBack = { browse.detail = Detail.Artist(page.parent) },
            onOpen = ::open,
            onDownload = ::downloadSong,
            onPlayNext = ::playNextSong,
            onAddToQueue = ::enqueueSong,
            onAddToPlaylist = ::choosePlaylist,
            onSetLiked = ::setLiked,
            isLiked = ::isLiked,
            loadMore = services.backend::artistItemsContinuation,
            modifier = modifier,
        )
        else -> DetailPage(
            page,
            browse.loading,
            browse.failure,
            onBack = { browse.clear() },
            onOpen = ::open,
            onOpenArtistItems = { endpoint ->
                if (page is Detail.Artist) browse.openArtistItems(endpoint, page.page, services.backend, scope)
            },
            onPlay = { songs, title, index -> scope.launch { services.playback.replaceQueue(songs.map { toPlaybackItem(it, services.albumIndex) }, index, title = title) } },
            onDownload = ::downloadSong,
            onDownloadAll = ::downloadSongs,
            onPlayNext = ::playNextSong,
            onAddToQueue = ::enqueueSong,
            onAddToPlaylist = ::choosePlaylist,
            onSetLiked = ::setLiked,
            isLiked = ::isLiked,
            isArtistSubscribed = { artist -> homeSnapshot.subscribedArtists.any { it.id == artist.id || (it.channelId != null && it.channelId == artist.channelId) } },
            onSetArtistSubscribed = { artist, subscribed ->
                scope.launch {
                    val channelId = artist.channelId ?: artist.id
                    services.backend.setArtistSubscribed(channelId, subscribed)
                        .onSuccess { services.homeStore.setArtistSubscribed(artist, subscribed) }
                        .onFailure { browse.failure = it.message ?: "Could not update artist subscription" }
                }
            },
            modifier = modifier,
        )
        }
    }

    if (showSpotifyImport) {
        SpotifyPlaylistImportDialog(
            services = services,
            onDismiss = { showSpotifyImport = false },
            onImported = { result ->
                showSpotifyImport = false
                libraryError = result.warning
                scope.launch {
                    services.libraryRepository.activate(forceRefresh = true)
                    if (services.libraryRepository.state.value.error != null) {
                        libraryError = "Playlist imported, but the library could not be refreshed."
                    }
                }
            },
        )
    }

    addToPlaylistSong?.let { song ->
        AddToPlaylistDialog(
            song = song,
            playlists = onlinePlaylists.filter { it.isEditable },
            loading = playlistPickerLoading,
            onDismiss = { addToPlaylistSong = null },
            onCreate = { createPlaylist(song) },
            onAdd = { playlist ->
                addToPlaylistSong = null
                scope.launch {
                    services.backend.addSongToPlaylist(playlist.id, song.id)
                        .onFailure { browse.failure = it.message ?: "Could not add song to playlist" }
                }
            },
        )
    }

    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            song = createPlaylistSong,
            onDismiss = { showCreatePlaylist = false; createPlaylistSong = null },
            onCreate = { name ->
                val song = createPlaylistSong
                showCreatePlaylist = false
                createPlaylistSong = null
                scope.launch {
                    services.backend.createPlaylist(name, song?.let { listOf(it.id) }.orEmpty())
                        .onSuccess { playlistId -> services.libraryRepository.addCached(playlistId, name) }
                        .onFailure { browse.failure = it.message ?: "Could not create playlist" }
                }
            },
        )
    }
}

@Composable private fun LocalLibrary(tracks: List<LocalTrack>, onlinePlaylists: List<PlaylistItem>, folders: List<java.nio.file.Path>, loading: Boolean, error: String?, onAddFolder: () -> Unit, onRescan: () -> Unit, spotifyImportEnabled: Boolean, onImportSpotify: () -> Unit, onCreatePlaylist: () -> Unit, onOpenPlaylist: (PlaylistItem) -> Unit, onPlay: (LocalTrack) -> Unit, onPlayNext: (LocalTrack) -> Unit, onAddToQueue: (LocalTrack) -> Unit, modifier: Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("YouTube Music playlists", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCreatePlaylist, enabled = spotifyImportEnabled) { Text("Create playlist") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onImportSpotify, enabled = spotifyImportEnabled) { Text("Import Spotify") }
            }
        }
        if (onlinePlaylists.isEmpty()) {
            item { Text("No signed-in playlists found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(onlinePlaylists, key = { it.id }) { playlist -> ResultCard(playlist, { onOpenPlaylist(playlist) }) }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Local library", style = MaterialTheme.typography.headlineMedium)
                    Text(if (folders.isEmpty()) "Choose a music folder to begin." else "${tracks.size} tracks from ${folders.size} folder${if (folders.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Button(onClick = onAddFolder) { Text("Add folder") }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.OutlinedButton(onClick = onRescan, enabled = folders.isNotEmpty() && !loading) { Text("Rescan") }
            }
        }
        item {
            AnimatedVisibility(
                visible = loading,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(120)),
                label = "library-loading",
            ) { Loading() }
        }
        error?.let { message -> item { Error(message) } }
        items(tracks, key = { it.id }) { track ->
            Card(Modifier.fillMaxWidth().clickable { onPlay(track) }) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, "Track artwork unavailable") }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SongQueueMenu(
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) },
                    )
                    Icon(Icons.Rounded.PlayArrow, "Play ${track.title}")
                }
            }
        }
    }
}

@Composable
private fun AddToPlaylistDialog(
    song: SongItem,
    playlists: List<PlaylistItem>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onAdd: (PlaylistItem) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium)
                when {
                    loading -> Loading()
                    playlists.isEmpty() -> Text("No editable playlists found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            TextButton(onClick = { onAdd(playlist) }, modifier = Modifier.fillMaxWidth()) {
                                Text(playlist.title, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = onCreate) { Text("New playlist") } },
    )
}

@Composable
private fun CreatePlaylistDialog(
    song: SongItem?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember(song?.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Playlist name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                song?.let { Text("${it.title} will be added to the new playlist.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") }
        },
    )
}

@Composable
private fun SpotifyPlaylistImportDialog(
    services: DesktopMusicServices,
    onDismiss: () -> Unit,
    onImported: (moe.rukamori.archivetune.spotify.SpotifyPlaylistImportResult) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text("Import Spotify playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste a public Spotify playlist link. ArchiveTune will match the songs on YouTube Music and copy the playlist name and image.")
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; error = null },
                    enabled = !importing,
                    singleLine = true,
                    label = { Text("Spotify playlist link") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (importing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(progress ?: "Importing…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(
                enabled = !importing && link.isNotBlank(),
                onClick = {
                    importing = true
                    error = null
                    scope.launch {
                        services.importSpotifyPlaylist(link) { update -> progress = update.message }
                            .onSuccess(onImported)
                            .onFailure { failure ->
                                error = failure.message ?: "Spotify playlist import failed"
                                importing = false
                            }
                    }
                },
            ) { Text(if (importing) "Importing…" else "Import") }
        },
    )
}

private suspend fun chooseLibraryFolder(): java.nio.file.Path? = withContext(Dispatchers.IO) {
    var selected: java.io.File? = null
    javax.swing.SwingUtilities.invokeAndWait {
        javax.swing.JFileChooser().apply {
            dialogTitle = "Select a music folder"
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            if (showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) selected = selectedFile
        }
    }
    selected?.toPath()
}

@Composable private fun SearchResults(query: String, items: List<YTItem>, loading: Boolean, error: String?, open: (YTItem) -> Unit, download: (SongItem) -> Unit, onPlayNext: (SongItem) -> Unit, onAddToQueue: (SongItem) -> Unit, onAddToPlaylist: (SongItem) -> Unit, onSetLiked: (SongItem, Boolean) -> Unit, isLiked: (SongItem) -> Boolean, modifier: Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(if (query.isBlank()) "Search YouTube Music" else "Results for “$query”", style = MaterialTheme.typography.headlineMedium) }
        item {
            AnimatedVisibility(
                visible = loading,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(120)),
                label = "search-loading",
            ) { Loading() }
        }
        error?.let { message -> item { Error(message) } }
        if (!loading && error == null && query.isNotBlank() && items.isEmpty()) {
            item { Text("No music results found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(items, key = { item -> item::class to item.id }) { item ->
            ResultCard(
                item,
                { open(item) },
                (item as? SongItem)?.let { song -> { download(song) } },
                (item as? SongItem)?.let { song -> { onPlayNext(song) } },
                (item as? SongItem)?.let { song -> { onAddToQueue(song) } },
                (item as? SongItem)?.let { song -> { onAddToPlaylist(song) } },
                (item as? SongItem)?.let { song -> { liked -> onSetLiked(song, liked) } },
                (item as? SongItem)?.let(isLiked) == true,
            )
        }
    }
}

@Composable private fun DetailPage(page: Detail, loading: Boolean, error: String?, onBack: () -> Unit, onOpen: (YTItem) -> Unit, onOpenArtistItems: (BrowseEndpoint) -> Unit, onPlay: (List<SongItem>, String, Int) -> Unit, onDownload: (SongItem) -> Unit, onDownloadAll: (List<SongItem>) -> Unit, onPlayNext: (SongItem) -> Unit, onAddToQueue: (SongItem) -> Unit, onAddToPlaylist: (SongItem) -> Unit, onSetLiked: (SongItem, Boolean) -> Unit, isLiked: (SongItem) -> Boolean, isArtistSubscribed: (ArtistItem) -> Boolean, onSetArtistSubscribed: (ArtistItem, Boolean) -> Unit, modifier: Modifier) {
    val title = when (page) { is Detail.Album -> page.page.album.title; is Detail.Playlist -> page.page.playlist.title; is Detail.Artist -> page.page.artist.title; is Detail.ArtistItems -> page.page.title }
    val image = when (page) { is Detail.Album -> page.page.album.thumbnail; is Detail.Playlist -> page.page.playlist.thumbnail; is Detail.Artist -> page.page.artist.thumbnail; is Detail.ArtistItems -> page.parent.artist.thumbnail }
    val extra = when (page) { is Detail.Album -> "Album"; is Detail.Playlist -> page.page.playlist.author?.name ?: "Playlist"; is Detail.Artist -> page.page.description ?: "Artist"; is Detail.ArtistItems -> "Artist" }
    val tracks = when (page) { is Detail.Album -> page.page.songs; is Detail.Playlist -> page.page.songs; is Detail.Artist, is Detail.ArtistItems -> emptyList() }
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to search") } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(image, Modifier.size(112.dp))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(extra, color = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    if (tracks.isNotEmpty()) Text("${tracks.size} songs")
                }
                if (tracks.isNotEmpty()) {
                    FilledIconButton(onClick = { onPlay(tracks, title, 0) }) { Icon(Icons.Rounded.PlayArrow, "Play all") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onDownloadAll(tracks) }) { Icon(Icons.Rounded.Download, "Download all"); Spacer(Modifier.width(6.dp)); Text("Download all") }
                }
                if (page is Detail.Artist) {
                    val subscribed = isArtistSubscribed(page.page.artist)
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onSetArtistSubscribed(page.page.artist, !subscribed) }) {
                        Text(if (subscribed) "Unsubscribe" else "Subscribe")
                    }
                }
            }
        }
        item {
            AnimatedVisibility(
                visible = loading,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(120)),
                label = "detail-loading",
            ) { Loading() }
        }
        error?.let { message -> item { Error(message) } }
        if (page is Detail.Artist) {
            page.page.sections.forEach { section ->
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(section.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        section.moreEndpoint?.let { endpoint ->
                            TextButton(onClick = { onOpenArtistItems(endpoint) }) { Text("See all") }
                        }
                    }
                }
                itemsIndexed(section.items, key = { index, item -> "${section.title}:${item::class}:${item.id}:$index" }) { _, item ->
                    val song = item as? SongItem
                    ResultCard(
                        item,
                        { onOpen(item) },
                        song?.let { { onDownload(it) } },
                        song?.let { { onPlayNext(it) } },
                        song?.let { { onAddToQueue(it) } },
                        song?.let { { onAddToPlaylist(it) } },
                        song?.let { { liked -> onSetLiked(it, liked) } },
                        song?.let(isLiked) == true,
                    )
                }
            }
        } else {
            itemsIndexed(tracks, key = { index, song -> "${song.id}:$index" }) { index, song ->
                ResultCard(song, { onPlay(tracks, title, index) }, { onDownload(song) }, { onPlayNext(song) }, { onAddToQueue(song) }, { onAddToPlaylist(song) }, { onSetLiked(song, it) }, isLiked(song))
            }
        }
    }
}

@Composable
private fun ArtistItemsDetailPage(
    detail: Detail.ArtistItems,
    onBack: () -> Unit,
    onOpen: (YTItem) -> Unit,
    onDownload: (SongItem) -> Unit,
    onPlayNext: (SongItem) -> Unit,
    onAddToQueue: (SongItem) -> Unit,
    onAddToPlaylist: (SongItem) -> Unit,
    onSetLiked: (SongItem, Boolean) -> Unit,
    isLiked: (SongItem) -> Boolean,
    loadMore: suspend (String) -> Result<ArtistItemsContinuationPage>,
    modifier: Modifier,
) {
    val page = detail.page
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var items by remember(page) { mutableStateOf(page.items) }
    var continuation by remember(page) { mutableStateOf(page.continuation) }
    var loadingMore by remember(page) { mutableStateOf(false) }
    var loadMoreError by remember(page) { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to ${detail.parent.artist.title}") }
                Spacer(Modifier.width(8.dp))
                Text(
                    page.title.ifBlank { "More from ${detail.parent.artist.title}" },
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        itemsIndexed(items, key = { index, item -> "artist-items:${item::class}:${item.id}:$index" }) { _, item ->
            ResultCard(
                item = item,
                open = { onOpen(item) },
                download = (item as? SongItem)?.let { song -> { onDownload(song) } },
                onPlayNext = (item as? SongItem)?.let { song -> { onPlayNext(song) } },
                onAddToQueue = (item as? SongItem)?.let { song -> { onAddToQueue(song) } },
                onAddToPlaylist = (item as? SongItem)?.let { song -> { onAddToPlaylist(song) } },
                onSetLiked = (item as? SongItem)?.let { song -> { liked -> onSetLiked(song, liked) } },
                isLiked = (item as? SongItem)?.let(isLiked) == true,
            )
        }
        loadMoreError?.let { message -> item { Error(message) } }
        continuation?.let { token ->
            item {
                TextButton(
                    enabled = !loadingMore,
                    onClick = {
                        loadingMore = true
                        loadMoreError = null
                        scope.launch {
                            loadMore(token)
                                .onSuccess { next ->
                                    items = (items + next.items).distinctBy { it::class to it.id }
                                    continuation = next.continuation
                                }
                                .onFailure { loadMoreError = it.message ?: "Could not load more artist items" }
                            loadingMore = false
                        }
                    },
                ) {
                    if (loadingMore) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (loadingMore) "Loading…" else "Load more")
                }
            }
        }
    }
}
@Composable
private fun PlaylistDetailPage(
    page: PlaylistPage,
    onBack: () -> Unit,
    onPlay: (List<SongItem>, Int, String) -> Unit,
    onDownload: (SongItem) -> Unit,
    onDownloadAll: (List<SongItem>) -> Unit,
    onPlayNext: (SongItem) -> Unit,
    onAddToQueue: (SongItem) -> Unit,
    onAddToPlaylist: (SongItem) -> Unit,
    onSetLiked: (SongItem, Boolean) -> Unit,
    isLiked: (SongItem) -> Boolean,
    onRemoveFromPlaylist: (SongItem) -> Unit,
    onRenamePlaylist: (String) -> Unit,
    onDeletePlaylist: () -> Unit,
    modifier: Modifier,
) {
    var query by remember(page.playlist.id) { mutableStateOf("") }
    var showRename by remember(page.playlist.id) { mutableStateOf(false) }
    var showDeleteConfirm by remember(page.playlist.id) { mutableStateOf(false) }
    val filtered = remember(page.songs, query) {
        if (query.isBlank()) page.songs else page.songs.filter { song ->
            song.title.contains(query, ignoreCase = true) ||
                song.artists.any { it.name.contains(query, ignoreCase = true) } ||
                song.album?.name?.contains(query, ignoreCase = true) == true
        }
    }
    val queueIndices = remember(page.songs) { page.songs.withIndex().associate { it.value.id to it.index } }
    val subtitle = listOfNotNull(
        page.playlist.author?.name,
        page.playlist.songCountText ?: "${page.songs.size} songs",
    ).joinToString(" • ")

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to library") } }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // The minimum supported window is 640dp wide. Stack the artwork and actions there;
                // the old fixed 180dp image plus three buttons could otherwise force the text column
                // below zero width and clip the hero card.
                BoxWithConstraints {
                    val narrow = maxWidth < 720.dp
                    if (narrow) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Artwork(page.playlist.thumbnail, Modifier.size(140.dp))
                            PlaylistHeroDetails(page, subtitle, onPlay, onDownloadAll, onRename = { showRename = true }, onDelete = { showDeleteConfirm = true })
                        }
                    } else {
                        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(page.playlist.thumbnail, Modifier.size(180.dp))
                            Spacer(Modifier.width(24.dp))
                            PlaylistHeroDetails(page, subtitle, onPlay, onDownloadAll, Modifier.weight(1f), onRename = { showRename = true }, onDelete = { showDeleteConfirm = true })
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tracks", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, "Search playlist tracks") },
                    placeholder = { Text("Search in playlist") },
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        if (page.songs.isEmpty()) {
            item { Text("This playlist has no available tracks.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (filtered.isEmpty()) {
            item { Text("No matching tracks.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        itemsIndexed(filtered, key = { index, song -> "${song.id}:$index" }) { _, song ->
            val queueIndex = queueIndices[song.id] ?: 0
            ResultCard(
                song,
                { onPlay(page.songs, queueIndex, page.playlist.title) },
                { onDownload(song) },
                { onPlayNext(song) },
                { onAddToQueue(song) },
                { onAddToPlaylist(song) },
                { onSetLiked(song, it) },
                isLiked(song),
                if (page.playlist.isEditable) ({ onRemoveFromPlaylist(song) }) else null,
            )
        }
    }

    if (showRename) {
        var name by remember(page.playlist.id) { mutableStateOf(page.playlist.title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Playlist name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name.trim() != page.playlist.title,
                    onClick = { showRename = false; onRenamePlaylist(name.trim()) },
                ) { Text("Rename") }
            },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete playlist?") },
            text = { Text("${page.playlist.title} will be permanently deleted from YouTube Music.") },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = { showDeleteConfirm = false; onDeletePlaylist() }) { Text("Delete") } },
        )
    }
}

@Composable
private fun PlaylistHeroDetails(
    page: PlaylistPage,
    subtitle: String,
    onPlay: (List<SongItem>, Int, String) -> Unit,
    onDownloadAll: (List<SongItem>) -> Unit,
    modifier: Modifier = Modifier,
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PLAYLIST", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text(page.playlist.title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        page.playlist.description?.takeIf(String::isNotBlank)?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Button(
                onClick = { if (page.songs.isNotEmpty()) onPlay(page.songs, 0, page.playlist.title) },
                enabled = page.songs.isNotEmpty(),
            ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Play") }
            OutlinedButton(
                onClick = {
                    if (page.songs.isNotEmpty()) {
                        val shuffled = page.songs.shuffled()
                        onPlay(shuffled, 0, page.playlist.title)
                    }
                },
                enabled = page.songs.isNotEmpty(),
            ) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("Shuffle") }
            OutlinedButton(
                onClick = { onDownloadAll(page.songs) },
                enabled = page.songs.isNotEmpty(),
            ) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp)); Text("Download all") }
            if (page.playlist.isEditable) {
                var menuOpen by remember(page.playlist.id) { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Playlist actions") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename playlist") }, onClick = { menuOpen = false; onRename() })
                        DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
        }
    }
}

@Composable private fun ResultCard(item: YTItem, open: () -> Unit, download: (() -> Unit)? = null, onPlayNext: (() -> Unit)? = null, onAddToQueue: (() -> Unit)? = null, onAddToPlaylist: (() -> Unit)? = null, onSetLiked: ((Boolean) -> Unit)? = null, isLiked: Boolean = false, onRemoveFromPlaylist: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().animateContentSize(tween(180, easing = FastOutSlowInEasing)).clickable(onClick = open)) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Artwork(item.thumbnail, Modifier.size(58.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium); Text(itemSubtitle(item), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }; if (download != null) IconButton(onClick = download) { Icon(Icons.Rounded.Download, "Download") }; if (item is SongItem) SongQueueMenu(onPlayNext = onPlayNext, onAddToQueue = onAddToQueue, onAddToPlaylist = onAddToPlaylist, onSetLiked = onSetLiked, isLiked = isLiked, onRemoveFromPlaylist = onRemoveFromPlaylist); Icon(if (item is SongItem) Icons.Rounded.PlayArrow else Icons.Rounded.MusicNote, null) } }
}

@Composable
private fun SongQueueMenu(onPlayNext: (() -> Unit)? = null, onAddToQueue: (() -> Unit)? = null, onDownload: (() -> Unit)? = null, onAddToPlaylist: (() -> Unit)? = null, onSetLiked: ((Boolean) -> Unit)? = null, isLiked: Boolean = false, onRemoveFromPlaylist: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onPlayNext?.let { action -> DropdownMenuItem(text = { Text("Play next") }, onClick = { open = false; action() }) }
            onAddToQueue?.let { action -> DropdownMenuItem(text = { Text("Add to queue") }, onClick = { open = false; action() }) }
            onDownload?.let { action -> DropdownMenuItem(text = { Text("Download") }, onClick = { open = false; action() }) }
            onSetLiked?.let { action -> DropdownMenuItem(text = { Text(if (isLiked) "Remove like" else "Like song") }, onClick = { open = false; action(!isLiked) }) }
            onAddToPlaylist?.let { action -> DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { open = false; action() }) }
            onRemoveFromPlaylist?.let { action -> DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { open = false; action() }) }
        }
    }
}
private fun itemSubtitle(item: YTItem) = when (item) { is SongItem -> item.artists.joinToString { it.name } + (item.album?.let { " • ${it.name}" } ?: "") + (item.duration?.let { " • ${format(it * 1000L)}" } ?: ""); is AlbumItem -> "Album • " + item.artists.orEmpty().joinToString { it.name }; is ArtistItem -> "Artist"; is PlaylistItem -> listOfNotNull("Playlist", item.author?.name, item.songCountText).joinToString(" • ") }
@Composable private fun Artwork(url: String?, modifier: Modifier) { if (!url.isNullOrBlank()) AsyncImage(url, null, modifier.clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop) else Box(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null) } }
@Composable private fun Loading() = Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
@Composable private fun Error(message: String) = Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) { Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer) }

private const val ALBUM_LINK_TAG = "album-link"
private const val ARTIST_LINK_TAG = "artist-link"

@Composable
private fun NowPlayingTitle(item: PlaybackItem?, onAlbumClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val albumId = item?.metadata?.album?.id?.takeIf { it.isNotBlank() }
    Text(
        text = item?.metadata?.title ?: "Nothing playing",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = androidx.compose.material3.LocalContentColor.current,
        modifier = modifier.then(
            if (albumId == null) Modifier
            else Modifier
                .pointerInput(albumId) { detectTapGestures { onAlbumClick(albumId) } }
                .pointerHoverIcon(PointerIcon.Hand),
        ),
    )
}

@Composable
private fun NowPlayingSubtitle(
    snapshot: PlaybackSnapshot,
    item: PlaybackItem?,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorText = snapshot.error?.let {
        if (it.kind.name == "ENGINE_UNAVAILABLE") {
            "VLC runtime is required for desktop playback — package VLC or set ARCHIVETUNE_VLC_DIR"
        } else {
            it.message
        }
    }
    val busyText = when (snapshot.status) {
        PlaybackStatus.RESOLVING -> "Resolving audio stream…"
        PlaybackStatus.BUFFERING -> "Buffering…"
        else -> null
    }
    if (errorText != null || busyText != null || item == null) {
        Text(
            text = errorText ?: busyText ?: "Choose a song to begin",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = if (snapshot.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val album = item.metadata.album
    val albumLink = album?.takeIf { !it.id.isNullOrBlank() && !it.title.isBlank() }
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated = buildAnnotatedString {
        item.metadata.artists.forEachIndexed { index, artist ->
            pushStringAnnotation(tag = ARTIST_LINK_TAG, annotation = artist.id.orEmpty())
            withStyle(SpanStyle(color = baseColor)) { append(artist.name) }
            pop()
            if (index != item.metadata.artists.lastIndex) append(", ")
        }
        if (albumLink != null) {
            if (item.metadata.artists.isNotEmpty()) append(" • ")
            pushStringAnnotation(tag = ALBUM_LINK_TAG, annotation = albumLink.id.orEmpty())
            withStyle(SpanStyle(color = baseColor)) { append(albumLink.title) }
            pop()
        }
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall,
        color = baseColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures { offset ->
                val layout = layoutResult ?: return@detectTapGestures
                val position = layout.getOffsetForPosition(offset)
                annotated.getStringAnnotations(position, position).firstOrNull()?.let { range ->
                    when (range.tag) {
                        ALBUM_LINK_TAG -> onAlbumClick(range.item)
                        ARTIST_LINK_TAG -> if (range.item.isNotBlank()) onArtistClick(range.item)
                    }
                }
            }
        }.pointerHoverIcon(PointerIcon.Hand),
    )
}

@Composable internal fun DesktopMiniPlayer(
    services: DesktopMusicServices,
    playTogether: PlayTogetherManager,
    browse: DesktopBrowseController,
    onShowLyrics: () -> Unit,
    onShowQueue: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snapshot by services.playback.state.collectAsState()
    val partyState by playTogether.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val item = snapshot.currentItem
    var showPartyDialog by remember { mutableStateOf(false) }
    var inviteInput by remember { mutableStateOf("") }

    if (showPartyDialog) {
        AlertDialog(
            onDismissRequest = { if (partyState.phase != PartyPhase.STARTING) showPartyDialog = false },
            title = { Text("Play together") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        partyState.phase == PartyPhase.STARTING -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text(partyState.message ?: "Connecting…")
                            }
                        }
                        partyState.mode == PartyMode.HOST && partyState.phase == PartyPhase.CONNECTED -> {
                            Text("${partyState.participantCount} connected. share this invite with anyone you want in the party.")
                            if (partyState.participantCount > 1) {
                                val fallbackPeers = (partyState.participantCount - 1 - partyState.directPeerCount).coerceAtLeast(0)
                                Text(
                                    buildString {
                                        append("${partyState.directPeerCount} direct P2P")
                                        if (fallbackPeers > 0) append(" · $fallbackPeers tunnel fallback")
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedTextField(
                                value = partyState.invite.orEmpty(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Invite") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        partyState.mode == PartyMode.GUEST && partyState.phase == PartyPhase.CONNECTED -> {
                            Text(
                                when (partyState.transport) {
                                    PartyTransport.DIRECT -> "Connected directly P2P. the host controls playback."
                                    PartyTransport.RELAY -> "Connected through the Quick Tunnel fallback. the host controls playback."
                                    else -> partyState.message ?: "Connected. the host controls playback."
                                },
                            )
                            partyState.roundTripMs?.let { Text("${it} ms round trip", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        else -> {
                            partyState.message?.takeIf { partyState.phase == PartyPhase.ERROR }?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                            Text("Create a temporary party, or paste an invite from someone else.")
                            OutlinedTextField(
                                value = inviteInput,
                                onValueChange = { inviteInput = it },
                                singleLine = true,
                                label = { Text("Party invite") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    partyState.phase == PartyPhase.STARTING -> Unit
                    partyState.mode == PartyMode.HOST && partyState.phase == PartyPhase.CONNECTED -> {
                        TextButton(onClick = {
                            partyState.invite?.let { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null) }
                        }) { Text("Copy invite") }
                    }
                    partyState.mode == PartyMode.GUEST && partyState.phase == PartyPhase.CONNECTED -> {
                        TextButton(onClick = { playTogether.leave(); showPartyDialog = false }) { Text("Leave") }
                    }
                    else -> {
                        TextButton(onClick = { scope.launch { playTogether.startHosting() } }) { Text("Create party") }
                    }
                }
            },
            dismissButton = {
                when {
                    partyState.phase == PartyPhase.STARTING -> Unit
                    partyState.mode == PartyMode.HOST && partyState.phase == PartyPhase.CONNECTED -> {
                        TextButton(onClick = { playTogether.leave(); showPartyDialog = false }) { Text("End party") }
                    }
                    partyState.mode == PartyMode.GUEST && partyState.phase == PartyPhase.CONNECTED -> Unit
                    else -> {
                        TextButton(
                            onClick = { scope.launch { playTogether.join(inviteInput) } },
                            enabled = inviteInput.isNotBlank(),
                        ) { Text("Join") }
                    }
                }
            },
        )
    }

    Surface(
        modifier.padding(horizontal = 12.dp, vertical = 8.dp).animateContentSize(tween(220, easing = FastOutSlowInEasing)),
        color = dynamicGlassColor(MaterialTheme.colorScheme.surfaceContainerHigh, alpha = 0.62f),
        shape = MaterialTheme.shapes.large,
        border = dynamicGlassBorder(alpha = 0.14f),
    ) { Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Artwork(item?.metadata?.artworkUri, Modifier.size(46.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { NowPlayingTitle(item, { albumId -> browse.openAlbum(albumId, services.backend, scope) }); NowPlayingSubtitle(snapshot, item, onAlbumClick = { albumId -> browse.openAlbum(albumId, services.backend, scope) }, onArtistClick = { artistId -> browse.openArtist(artistId, services.backend, scope) }); }; TextButton(onClick = onShowLyrics, enabled = item != null) { Text("Lyrics") }; IconButton({ scope.launch { services.playback.skipPrevious() } }, enabled = snapshot.canSkipPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous") }; FilledIconButton({ scope.launch { if (snapshot.isPlaying) services.playback.pause() else services.playback.play() } }, enabled = item != null) { Icon(if (snapshot.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (snapshot.isPlaying) "Pause" else "Play") }; IconButton({ scope.launch { services.playback.skipNext() } }, enabled = snapshot.canSkipNext) { Icon(Icons.Rounded.SkipNext, "Next") }; IconButton({ scope.launch { services.playback.setShuffleEnabled(!snapshot.shuffleEnabled) } }, enabled = item != null) { Icon(Icons.Rounded.Shuffle, if (snapshot.shuffleEnabled) "Shuffle on" else "Shuffle off", tint = if (snapshot.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton({ scope.launch { services.playback.setRepeatMode(when (snapshot.repeatMode) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF }) } }, enabled = item != null) { Icon(if (snapshot.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, if (snapshot.repeatMode == RepeatMode.OFF) "Repeat off" else "Repeat on", tint = if (snapshot.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onClick = { showPartyDialog = true }) { Icon(Icons.Rounded.Group, "Play together", tint = if (partyState.phase == PartyPhase.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onClick = onShowQueue, enabled = item != null) { Icon(Icons.Rounded.QueueMusic, "Queue") }; Icon(Icons.Rounded.VolumeUp, null); Slider(snapshot.volume, { scope.launch { services.playback.setVolume(it) } }, Modifier.width(90.dp)) }
        if (item != null) Slider(if (snapshot.durationMs > 0) snapshot.positionMs.toFloat() / snapshot.durationMs else 0f, { scope.launch { services.playback.seekTo((it * snapshot.durationMs).roundToLong()) } }, Modifier.fillMaxWidth())
    } }
}

/** Immersive lyrics content rendered inside the app shell so it shares the integrated chrome. */
@Composable
internal fun DesktopLyricsView(
    services: DesktopMusicServices,
    browse: DesktopBrowseController,
    modifier: Modifier = Modifier,
    useShellDynamicBackdrop: Boolean = false,
) {
    val snapshot by services.playback.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val item = snapshot.currentItem
    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (useShellDynamicBackdrop) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface,
    ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(item?.metadata?.artworkUri, Modifier.size(42.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        NowPlayingTitle(item, { albumId -> browse.openAlbum(albumId, services.backend, scope) }, Modifier.fillMaxWidth())
                        NowPlayingSubtitle(snapshot, item, onAlbumClick = { albumId -> browse.openAlbum(albumId, services.backend, scope) }, onArtistClick = { artistId -> browse.openArtist(artistId, services.backend, scope) }, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = { scope.launch { services.playback.skipPrevious() } }, enabled = snapshot.canSkipPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                    FilledIconButton(onClick = { scope.launch { if (snapshot.isPlaying) services.playback.pause() else services.playback.play() } }, enabled = item != null) {
AnimatedContent(targetState = snapshot.isPlaying, transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) }, label = "mini-player-play-state") { playing -> Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
                    }
                    IconButton(onClick = { scope.launch { services.playback.skipNext() } }, enabled = snapshot.canSkipNext) { Icon(Icons.Rounded.SkipNext, "Next") }
                }
                if (item != null) {
                    Slider(
                        value = if (snapshot.durationMs > 0) (snapshot.positionMs.toFloat() / snapshot.durationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { scope.launch { services.playback.seekTo((it * snapshot.durationMs).roundToLong()) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    )
                }
                DesktopLyricsPane(
                    presenter = services.lyrics,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    drawBackground = !useShellDynamicBackdrop,
                )
            }
    }
}
private fun toPlaybackItem(song: SongItem, albumIndex: DesktopAlbumIndex) = PlaybackItem(
    PlaybackMetadata(
        id = song.id,
        title = song.title,
        artists = song.artists.map { PlaybackArtist(it.id, it.name) },
        album =
            song.album?.let { PlaybackAlbum(it.id, it.name) }
                ?.takeIf { !it.id.isNullOrBlank() && !it.title.isBlank() }
                ?.also { albumIndex.learn(song.id, it) }
                ?: albumIndex.albumFor(song.id),
        artworkUri = song.thumbnail,
        durationHintMs = song.duration?.times(1000L),
        explicit = song.explicit,
    ),
)
private fun toPlaybackItem(track: LocalTrack) = PlaybackItem(PlaybackMetadata(track.id, track.title, listOf(PlaybackArtist(name = track.artist)), track.album?.let { PlaybackAlbum(title = it) }), moe.rukamori.archivetune.playback.api.PlaybackSource.LocalFile(track.path.toString()))
private fun toPlaybackItem(item: moe.rukamori.archivetune.downloads.DownloadSnapshot, path: java.nio.file.Path, albumIndex: DesktopAlbumIndex) = PlaybackItem(
    PlaybackMetadata(
        id = item.mediaId ?: item.id,
        title = item.title,
        artists = item.artist?.let { listOf(PlaybackArtist(name = it)) }.orEmpty(),
        album =
            item.albumTitle?.let { PlaybackAlbum(item.albumId, it) }
                ?.takeIf { !it.id.isNullOrBlank() && !it.title.isBlank() }
                ?.also { albumIndex.learn(item.mediaId ?: item.id, it) }
                ?: albumIndex.albumFor(item.mediaId ?: item.id),
        artworkUri = item.artworkUrl,
        durationHintMs = item.durationMs,
        explicit = item.explicit,
    ),
    moe.rukamori.archivetune.playback.api.PlaybackSource.LocalFile(path.toString()),
)
private fun format(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
