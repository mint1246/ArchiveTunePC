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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
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
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.CancellationException
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
import moe.rukamori.archivetune.desktop.platform.DesktopBrowseRepository
import moe.rukamori.archivetune.desktop.platform.DesktopBrowseRoute
import moe.rukamori.archivetune.desktop.party.PartyMode
import moe.rukamori.archivetune.desktop.party.PartyPhase
import moe.rukamori.archivetune.desktop.party.PartyTransport
import moe.rukamori.archivetune.desktop.party.PlayTogetherManager
import moe.rukamori.archivetune.desktop.lyrics.DesktopLyricsPane
import moe.rukamori.archivetune.desktop.state.DesktopBrowseHistory
import moe.rukamori.archivetune.desktop.state.DesktopDestination
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.logging.Logger
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
import moe.rukamori.archivetune.innertube.pages.ArtistSectionLayout
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
internal class DesktopBrowseController(
    private val repository: DesktopBrowseRepository,
    initialDestination: DesktopDestination = DesktopDestination.HOME,
) {
    private val history = DesktopBrowseHistory(initialDestination)
    private var generation = 0L
    var detail by mutableStateOf<Detail?>(null)
        private set
    var loading by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)

    fun activate(destination: DesktopDestination) {
        generation++
        loading = false
        failure = null
        history.activate(destination)
        restoreCurrent()
    }

    fun goBack(): Boolean {
        if (history.current() == null) return false
        generation++
        loading = false
        failure = null
        history.pop()
        restoreCurrent()
        return true
    }

    fun clearCurrentStack() {
        generation++
        loading = false
        failure = null
        history.clearCurrent()
        detail = null
    }

    fun clearAll() {
        generation++
        loading = false
        failure = null
        history.clearAll()
        detail = null
    }

    fun openAlbum(
        browseId: String,
        scope: CoroutineScope,
        forceRefresh: Boolean = false,
        onOpened: () -> Unit = {},
    ) {
        val route = DesktopBrowseRoute.Album(browseId)
        open(route, scope, forceRefresh, repository::cachedAlbum, onOpened) {
            repository.album(route, forceRefresh).map { Detail.Album(it) }
        }
    }

    fun openArtist(
        id: String,
        scope: CoroutineScope,
        forceRefresh: Boolean = false,
        onOpened: () -> Unit = {},
    ) {
        val route = DesktopBrowseRoute.Artist(id)
        open(route, scope, forceRefresh, repository::cachedArtist, onOpened) {
            repository.artist(route, forceRefresh).map { Detail.Artist(it) }
        }
    }

    fun openArtistItems(endpoint: BrowseEndpoint, parent: ArtistPage, scope: CoroutineScope, forceRefresh: Boolean = false) {
        val route = DesktopBrowseRoute.ArtistItems(parent.artist.id, endpoint)
        val cached: (DesktopBrowseRoute.ArtistItems) -> ArtistItemsPage? = repository::cachedArtistItems
        open(route, scope, forceRefresh, cached) {
            repository.artistItems(route, forceRefresh).map { Detail.ArtistItems(it, parent) }
        }
    }

    fun openPlaylist(id: String, scope: CoroutineScope, forceRefresh: Boolean = false) {
        val route = DesktopBrowseRoute.Playlist(id)
        open(route, scope, forceRefresh, repository::cachedPlaylist) {
            repository.playlist(route, forceRefresh).map { Detail.Playlist(it) }
        }
    }

    fun invalidatePlaylist(id: String) {
        repository.invalidate(DesktopBrowseRoute.Playlist(id))
    }

    fun refreshPlaylist(id: String, scope: CoroutineScope) {
        invalidatePlaylist(id)
        openPlaylist(id, scope, forceRefresh = true)
    }

    private fun <R : DesktopBrowseRoute, P : Any> open(
        route: R,
        scope: CoroutineScope,
        forceRefresh: Boolean,
        cached: (R) -> P?,
        onOpened: () -> Unit = {},
        load: suspend () -> Result<Detail>,
    ) {
        if (!forceRefresh) {
            cached(route)?.let { page ->
                history.push(route)
                detail = when (route) {
                    is DesktopBrowseRoute.Album -> Detail.Album(page as AlbumPage)
                    is DesktopBrowseRoute.Artist -> Detail.Artist(page as ArtistPage)
                    is DesktopBrowseRoute.Playlist -> Detail.Playlist(page as PlaylistPage)
                    is DesktopBrowseRoute.ArtistItems -> {
                        val parent = repository.cachedArtist(DesktopBrowseRoute.Artist(route.parentArtistId))
                        if (parent == null) null else Detail.ArtistItems(page as ArtistItemsPage, parent)
                    }
                }
                if (detail != null) {
                    loading = false
                    failure = null
                    onOpened()
                    return
                }
            }
        }

        val requestGeneration = ++generation
        val destination = history.activeDestination
        scope.launch {
            loading = true
            failure = null
            load()
                .onSuccess { loaded ->
                    if (requestGeneration == generation && history.activeDestination == destination) {
                        history.push(route)
                        detail = loaded
                        onOpened()
                    }
                }
                .onFailure {
                    if (requestGeneration == generation && history.activeDestination == destination) {
                        failure = it.message ?: "Could not load this item"
                    }
                }
            if (requestGeneration == generation && history.activeDestination == destination) loading = false
        }
    }

    private fun restoreCurrent() {
        while (true) {
            val route = history.current() ?: run {
                detail = null
                return
            }
            val restored = when (route) {
                is DesktopBrowseRoute.Album -> repository.cachedAlbum(route)?.let(Detail::Album)
                is DesktopBrowseRoute.Artist -> repository.cachedArtist(route)?.let(Detail::Artist)
                is DesktopBrowseRoute.Playlist -> repository.cachedPlaylist(route)?.let(Detail::Playlist)
                is DesktopBrowseRoute.ArtistItems -> {
                    val page = repository.cachedArtistItems(route)
                    val parent = repository.cachedArtist(DesktopBrowseRoute.Artist(route.parentArtistId))
                    if (page != null && parent != null) Detail.ArtistItems(page, parent) else null
                }
            }
            if (restored != null) {
                detail = restored
                return
            }
            // A route can outlive its parsed page after LRU eviction. Drop only that compact route
            // and continue restoring older cached history rather than issuing a surprise request.
            history.pop()
        }
    }
}

@Composable
internal fun DesktopMusicContent(state: DesktopContentState, services: DesktopMusicServices, downloadServices: DesktopDownloadServices, paths: AppPaths, logger: Logger, browse: DesktopBrowseController, playTogether: PlayTogetherManager, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val partyState by playTogether.state.collectAsState()
    val guestInParty = partyState.mode == PartyMode.GUEST && partyState.phase == PartyPhase.CONNECTED
    val accountState by services.account.state.collectAsState()
    val browseAccountScope = services.currentBrowseAccountScope()
    val homeSnapshot by services.homeStore.snapshot.collectAsState()
    val remoteLibraryState by services.libraryRepository.state.collectAsState()
    val searchState by services.searchRepository.state.collectAsState()
    val localTracks by services.library.tracksFlow.collectAsState()
    val onlinePlaylists = remoteLibraryState.playlists
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    var showSpotifyImport by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var createPlaylistSong by remember { mutableStateOf<SongItem?>(null) }
    var playlistPickerSession by remember { mutableStateOf<AddToPlaylistPickerSession?>(null) }
    var downloadBatchNotice by remember { mutableStateOf<String?>(null) }
    suspend fun rescanLibrary() {
        if (libraryLoading) return
        libraryLoading = true
        libraryError = null
        try {
            services.library.rescan()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            libraryError = failure.message ?: "Library scan failed"
        } finally {
            libraryLoading = false
        }
    }
    LaunchedEffect(state.destination) {
        browse.activate(state.destination)
        if (state.destination != moe.rukamori.archivetune.desktop.state.DesktopDestination.DOWNLOADS) {
            downloadBatchNotice = null
        }
        if (state.destination != moe.rukamori.archivetune.desktop.state.DesktopDestination.LIBRARY) return@LaunchedEffect
        services.libraryRepository.activate()
    }
    LaunchedEffect(browseAccountScope) {
        browse.clearAll()
        browse.activate(state.destination)
    }
    LaunchedEffect(state.searchQuery, state.destination) {
        if (state.destination != moe.rukamori.archivetune.desktop.state.DesktopDestination.SEARCH) return@LaunchedEffect
        if (state.searchQuery.isNotBlank()) delay(250)
        services.searchRepository.activate(state.searchQuery)
    }
    fun open(item: YTItem) {
        when (item) {
            is SongItem -> {
                if (guestInParty && !partyState.canChangeSong) return
                scope.launch {
                    browse.failure = null
                    services.upNext(item.id)
                    .onSuccess { next ->
                        val songs = next.items
                        if (songs.isEmpty()) {
                            logger.info(
                                "up_next_selection event=empty requestedVideoId=${item.id} backendCurrentIndex=${next.currentIndex}",
                            )
                            playTogether.playQueue(
                                listOf(toPlaybackItem(item, services.albumIndex)),
                                title = "Up next",
                            )
                            return@onSuccess
                        }

                        // Search and artist-profile clicks are explicit requests for this exact
                        // video. YouTube's `selected` flag/currentIndex can point at a different
                        // queue entry for some account/region responses, so never let it override
                        // the song the user actually clicked.
                        val selectedIndex = requestedSongQueueIndex(songs.map(SongItem::id), item.id)
                        val backendSelectedId = next.currentIndex?.let(songs::getOrNull)?.id
                        logger.info(
                            "up_next_selection event=resolved requestedVideoId=${item.id} " +
                                "backendCurrentIndex=${next.currentIndex} backendSelectedVideoId=$backendSelectedId " +
                                "matchedIndex=$selectedIndex queueSize=${songs.size}",
                        )
                        if (selectedIndex == null) {
                            logger.warning(
                                "up_next_selection event=requested_missing requestedVideoId=${item.id} " +
                                    "backendCurrentIndex=${next.currentIndex} backendSelectedVideoId=$backendSelectedId " +
                                    "queueVideoIds=${songs.take(12).joinToString(",") { it.id }}",
                            )
                            playTogether.playQueue(
                                listOf(toPlaybackItem(item, services.albumIndex)),
                                title = next.title ?: "Up next",
                            )
                            return@onSuccess
                        }
                        playTogether.playQueue(
                            songs.map { toPlaybackItem(it, services.albumIndex) },
                            selectedIndex,
                            title = next.title ?: "Up next",
                        )
                    }
                    .onFailure { failure ->
                        logger.warning(
                            "up_next_selection event=failure requestedVideoId=${item.id} " +
                                "errorType=${failure::class.simpleName} error=${failure.message?.replace(Regex("\\s+"), " ")?.take(240)}",
                        )
                        browse.failure = failure.message ?: "Could not load Up next"
                        playTogether.playQueue(
                            listOf(toPlaybackItem(item, services.albumIndex)),
                            title = "Up next",
                        )
                    }
                }
            }
            is AlbumItem -> browse.openAlbum(item.browseId, scope)
            is ArtistItem -> browse.openArtist(item.id, scope)
            is PlaylistItem -> browse.openPlaylist(item.id, scope)
        }
    }
    fun openBrowseItem(item: YTItem) {
        when (item) {
            is AlbumItem -> browse.openAlbum(item.browseId, scope)
            is ArtistItem -> browse.openArtist(item.id, scope)
            is PlaylistItem -> browse.openPlaylist(item.id, scope)
            is SongItem -> Unit
        }
    }
    fun playQueue(songs: List<SongItem>, startIndex: Int, title: String) {
        if (songs.isEmpty() || (guestInParty && !partyState.canChangeSong)) return
        scope.launch {
            playTogether.playQueue(
                songs.map { toPlaybackItem(it, services.albumIndex) },
                startIndex.coerceIn(0, songs.lastIndex),
                title = title,
            )
        }
    }
    fun downloadSong(song: SongItem) {
        scope.launch {
            downloadServices.enqueue(song)
                .onSuccess {
                    downloadBatchNotice = null
                    state.navigate(moe.rukamori.archivetune.desktop.state.DesktopDestination.DOWNLOADS)
                }
                .onFailure { browse.failure = it.message ?: "Could not resolve a stream for download" }
        }
    }
    fun downloadSongs(songs: List<SongItem>) {
        scope.launch {
            val outcome = downloadServices.enqueueAll(songs).toUiOutcome()
            if (outcome.navigateToDownloads) {
                downloadBatchNotice = outcome.message
                browse.failure = null
                state.navigate(moe.rukamori.archivetune.desktop.state.DesktopDestination.DOWNLOADS)
            } else if (outcome.message != null) {
                browse.failure = outcome.message
            }
        }
    }
    fun playNextSong(song: SongItem) {
        scope.launch {
            if (!playTogether.playNext(listOf(toPlaybackItem(song, services.albumIndex))) && guestInParty) {
                browse.failure = if (partyState.canEditQueue) "Could not add this song next in the shared queue." else "The party owner disabled queue editing for you."
            }
        }
    }
    fun enqueueSong(song: SongItem) {
        scope.launch {
            if (!playTogether.addToQueue(listOf(toPlaybackItem(song, services.albumIndex))) && guestInParty) {
                browse.failure = if (partyState.canEditQueue) "Could not add this song to the shared queue." else "The party owner disabled queue editing for you."
            }
        }
    }
    fun playLocal(track: LocalTrack) {
        if (partyState.mode != PartyMode.NONE) return
        scope.launch {
            val queue = localTracks.map(::toPlaybackItem)
            services.playback.replaceQueue(
                queue,
                localTracks.indexOf(track).coerceAtLeast(0),
                title = "Local library",
            )
        }
    }
    fun playNextLocal(track: LocalTrack) {
        if (partyState.mode != PartyMode.NONE) return
        scope.launch { services.playback.playNext(listOf(toPlaybackItem(track))) }
    }
    fun enqueueLocal(track: LocalTrack) {
        if (partyState.mode != PartyMode.NONE) return
        scope.launch { services.playback.enqueue(listOf(toPlaybackItem(track))) }
    }
    fun isLiked(song: SongItem) = homeSnapshot.likedSongs.any { it.id == song.id }
    fun setLiked(song: SongItem, liked: Boolean) {
        scope.launch {
            services.backend.setSongLiked(song.id, liked)
                .onSuccess { services.homeStore.setSongLiked(song, liked) }
                .onFailure { browse.failure = it.message ?: "Could not update like" }
        }
    }
    fun choosePlaylist(song: SongItem) {
        playlistPickerSession = AddToPlaylistPickerSession(song)
        scope.launch {
            services.libraryRepository.refresh()
        }
    }
    fun createPlaylist(song: SongItem? = null) {
        playlistPickerSession = null
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
            moe.rukamori.archivetune.desktop.state.DesktopDestination.SEARCH -> SearchResults(
                query = state.searchQuery,
                items = searchState.items,
                localItems = localSearchMatches(state.searchQuery, localTracks),
                history = searchState.history,
                loading = searchState.isInitialLoading,
                refreshing = searchState.isRefreshing,
                error = searchState.error,
                onRetry = { scope.launch { services.searchRepository.retry() } },
                onHistorySelected = state.setSearchQuery,
                onHistoryRemoved = services.searchRepository::removeHistory,
                onHistoryCleared = services.searchRepository::clearHistory,
                open = ::open,
                download = ::downloadSong,
                onPlayNext = ::playNextSong,
                onAddToQueue = ::enqueueSong,
                onAddToPlaylist = ::choosePlaylist,
                onSetLiked = ::setLiked,
                isLiked = ::isLiked,
                openLocal = ::playLocal,
                onPlayNextLocal = ::playNextLocal,
                onAddToQueueLocal = ::enqueueLocal,
                modifier = modifier,
            )
            moe.rukamori.archivetune.desktop.state.DesktopDestination.DOWNLOADS -> DesktopDownloadsSurface(
                downloadServices,
                { path, item -> if (partyState.mode == PartyMode.NONE) scope.launch { services.playback.replaceQueue(listOf(toPlaybackItem(item, path, services.albumIndex)), title = "Downloads") } },
                { path, item -> if (partyState.mode == PartyMode.NONE) scope.launch { services.playback.playNext(listOf(toPlaybackItem(item, path, services.albumIndex))) } },
                { path, item -> if (partyState.mode == PartyMode.NONE) scope.launch { services.playback.enqueue(listOf(toPlaybackItem(item, path, services.albumIndex))) } },
                notice = downloadBatchNotice,
                modifier = modifier,
            )
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
                onPlay = ::playLocal,
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
                playbackModesEnabled = partyState.mode != PartyMode.GUEST,
                shuffleModeEnabled = partyState.mode == PartyMode.NONE,
                audioEffects = services.playback.audioEffects,
                audioOutput = services.playback,
                downloads = downloadServices,
                libraryFolders = services.library.folders,
                libraryTrackCount = localTracks.size,
                libraryScanning = libraryLoading,
                libraryScanError = libraryError,
                onLibraryFoldersChanged = { folders ->
                    services.library.setFolders(folders)
                    scope.launch { rescanLibrary() }
                },
                onLibraryRescan = { scope.launch { rescanLibrary() } },
                onKeybindsChanged = services::refreshKeybindings,
                onKeybindCaptureChanged = services::setKeybindCaptureActive,
                keybindPressedKeys = services.keybindPressedKeys,
                modifier = modifier,
            )
        }
        is Detail.Playlist -> PlaylistDetailPage(
            page.page,
            error = browse.failure,
            onPlay = { songs, index, title -> playQueue(songs, index, title) },
            onDownload = ::downloadSong,
            onDownloadAll = ::downloadSongs,
            onPlayNext = ::playNextSong,
            onAddToQueue = ::enqueueSong,
            onAddToPlaylist = ::choosePlaylist,
            onSetLiked = ::setLiked,
            isLiked = ::isLiked,
            onRemoveFromPlaylist = { song ->
                browse.failure = null
                scope.launch {
                    services.backend.removeSongFromPlaylist(page.page.playlist.id, song)
                        .onSuccess { browse.refreshPlaylist(page.page.playlist.id, scope) }
                        .onFailure { browse.failure = it.message ?: "Could not remove song from playlist" }
                }
            },
            onRenamePlaylist = { name ->
                val original = page.page.playlist
                browse.failure = null
                services.libraryRepository.renameCached(original.id, name)
                scope.launch {
                    services.backend.renamePlaylist(original.id, name)
                        .onSuccess { browse.refreshPlaylist(original.id, scope) }
                        .onFailure {
                            services.libraryRepository.upsertCached(original)
                            browse.failure = it.message ?: "Could not rename playlist"
                        }
                }
            },
            onDeletePlaylist = {
                val original = page.page.playlist
                browse.failure = null
                scope.launch {
                    services.backend.deletePlaylist(original.id)
                        .onSuccess {
                            browse.invalidatePlaylist(original.id)
                            services.libraryRepository.removeCached(original.id)
                            browse.clearCurrentStack()
                        }
                        .onFailure { browse.failure = it.message ?: "Could not delete playlist" }
                }
            },
            modifier = modifier,
        )
        is Detail.ArtistItems -> ArtistItemsDetailPage(
            detail = page,
            onOpen = ::open,
            onDownload = ::downloadSong,
            onPlayNext = ::playNextSong,
            onAddToQueue = ::enqueueSong,
            onAddToPlaylist = ::choosePlaylist,
            onSetLiked = ::setLiked,
            isLiked = ::isLiked,
            loadMore = services.browseRepository::artistItemsContinuation,
            modifier = modifier,
        )
        else -> DetailPage(
            page,
            browse.loading,
            browse.failure,
            onOpen = ::open,
            onOpenArtistItems = { endpoint ->
                if (page is Detail.Artist) browse.openArtistItems(endpoint, page.page, scope)
            },
            onPlay = { songs, title, index -> playQueue(songs, index, title) },
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

    playlistPickerSession?.let { session ->
        val editablePlaylists = onlinePlaylists.filter { it.isEditable }
        AddToPlaylistDialog(
            song = session.song,
            playlists = editablePlaylists,
            refreshing = remoteLibraryState.isInitialLoading || remoteLibraryState.isRefreshing,
            refreshError = remoteLibraryState.error,
            adding = session.adding,
            addError = session.addError,
            onDismiss = { playlistPickerSession = null },
            onRetry = { scope.launch { services.libraryRepository.refresh() } },
            onCreate = { createPlaylist(session.song) },
            onAdd = { playlist ->
                browse.failure = null
                playlistPickerSession = session.beginAdd()
                scope.launch {
                    val result = services.backend.addSongToPlaylist(playlist.id, session.song.id)
                    val current = playlistPickerSession
                    if (current?.song?.id != session.song.id || !current.adding) return@launch
                    if (result.isSuccess) {
                        playlistPickerSession = null
                        result.onSuccess {
                            browse.invalidatePlaylist(playlist.id)
                            if ((browse.detail as? Detail.Playlist)?.page?.playlist?.id == playlist.id) {
                                browse.openPlaylist(playlist.id, scope, forceRefresh = true)
                            }
                        }
                    } else {
                        playlistPickerSession = current.finishAdd(result)
                        result.onFailure { browse.failure = it.message ?: "Could not add song to playlist" }
                    }
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

internal data class LocalLibraryFilterResult(
    val playlists: List<PlaylistItem>,
    val tracks: List<LocalTrack>,
)

internal fun filterLocalLibrary(
    playlists: List<PlaylistItem>,
    tracks: List<LocalTrack>,
    query: String,
): LocalLibraryFilterResult {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return LocalLibraryFilterResult(playlists, tracks)
    return LocalLibraryFilterResult(
        playlists = playlists.filter { playlist ->
            playlist.title.contains(trimmedQuery, ignoreCase = true) ||
                playlist.author?.name?.contains(trimmedQuery, ignoreCase = true) == true
        },
        tracks = tracks.filter { track ->
            track.title.contains(trimmedQuery, ignoreCase = true) ||
                track.artist.contains(trimmedQuery, ignoreCase = true) ||
                track.album?.contains(trimmedQuery, ignoreCase = true) == true
        },
    )
}

@Composable private fun LocalLibrary(tracks: List<LocalTrack>, onlinePlaylists: List<PlaylistItem>, folders: List<java.nio.file.Path>, loading: Boolean, error: String?, onAddFolder: () -> Unit, onRescan: () -> Unit, spotifyImportEnabled: Boolean, onImportSpotify: () -> Unit, onCreatePlaylist: () -> Unit, onOpenPlaylist: (PlaylistItem) -> Unit, onPlay: (LocalTrack) -> Unit, onPlayNext: (LocalTrack) -> Unit, onAddToQueue: (LocalTrack) -> Unit, modifier: Modifier) {
    val listState = rememberPersistedLazyListState("library")
    var filterQuery by remember { mutableStateOf("") }
    val filtered = remember(onlinePlaylists, tracks, filterQuery) {
        filterLocalLibrary(onlinePlaylists, tracks, filterQuery)
    }
    val filtering = filterQuery.isNotBlank()
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                label = { Text("Filter library") },
                placeholder = { Text("Playlists, artists, albums, tracks") },
            )
        }
        if (filtering && filtered.playlists.isEmpty() && filtered.tracks.isEmpty()) {
            item {
                Text(
                    "No playlists or local tracks match “${filterQuery.trim()}”.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (filtering) "YouTube Music playlists • ${filtered.playlists.size} of ${onlinePlaylists.size}" else "YouTube Music playlists",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onCreatePlaylist, enabled = spotifyImportEnabled) { Text("Create playlist") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onImportSpotify, enabled = spotifyImportEnabled) { Text("Import Spotify") }
            }
        }
        if (onlinePlaylists.isEmpty()) {
            item { Text("No signed-in playlists found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (filtered.playlists.isEmpty()) {
            item { Text("No playlists match this filter.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(filtered.playlists, key = { it.id }) { playlist -> ResultCard(playlist, { onOpenPlaylist(playlist) }) }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Local library", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        when {
                            folders.isEmpty() -> "Choose a music folder to begin."
                            filtering -> "${filtered.tracks.size} of ${tracks.size} tracks from ${folders.size} folder${if (folders.size == 1) "" else "s"}"
                            else -> "${tracks.size} tracks from ${folders.size} folder${if (folders.size == 1) "" else "s"}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Button(onClick = onAddFolder, enabled = !loading) { Text("Add folder") }
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
        if (tracks.isNotEmpty() && filtered.tracks.isEmpty() && filtering) {
            item { Text("No local tracks match this filter.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(filtered.tracks, key = { it.id }) { track ->
            LocalTrackCard(track, { onPlay(track) }, { onPlayNext(track) }, { onAddToQueue(track) })
        }
    }
}

@Composable
private fun LocalTrackCard(
    track: LocalTrack,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onPlay)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(54.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.MusicNote, "Track artwork unavailable") }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(track.artist, track.album?.takeIf(String::isNotBlank), track.durationMs?.let(::format)).joinToString(" • "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SongQueueMenu(onPlayNext = onPlayNext, onAddToQueue = onAddToQueue)
            Icon(Icons.Rounded.PlayArrow, "Play ${track.title}")
        }
    }
}

@Composable
private fun AddToPlaylistDialog(
    song: SongItem,
    playlists: List<PlaylistItem>,
    refreshing: Boolean,
    refreshError: String?,
    adding: Boolean,
    addError: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCreate: () -> Unit,
    onAdd: (PlaylistItem) -> Unit,
) {
    val presentation = addToPlaylistPickerPresentation(playlists.size, refreshing, refreshError)
    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium)
                if (presentation.showInitialLoading) Loading()
                presentation.refreshError?.let { message ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onRetry, enabled = !refreshing && !adding) { Text("Retry") }
                    }
                }
                addError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (presentation.showEmptyState) {
                    Text("No editable playlists found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (playlists.isNotEmpty()) {
                    if (refreshing) {
                        Text("Refreshing playlists…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            TextButton(onClick = { onAdd(playlist) }, enabled = !adding, modifier = Modifier.fillMaxWidth()) {
                                Text(playlist.title, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (adding) Loading()
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !adding) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = onCreate, enabled = !adding) { Text("New playlist") } },
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

internal fun localSearchMatches(
    query: String,
    tracks: List<LocalTrack>,
    maxResults: Int = 20,
): List<LocalTrack> {
    val needle = query.trim()
    if (needle.isEmpty() || maxResults <= 0) return emptyList()
    return tracks.asSequence()
        .filter { track ->
            track.title.contains(needle, ignoreCase = true) ||
                track.artist.contains(needle, ignoreCase = true) ||
                track.album?.contains(needle, ignoreCase = true) == true
        }
        .take(maxResults)
        .toList()
}

internal fun requestedSongQueueIndex(
    songIds: List<String>,
    requestedVideoId: String,
): Int? = songIds.indexOf(requestedVideoId).takeIf { it >= 0 }

@Composable
private fun SearchResults(
    query: String,
    items: List<YTItem>,
    localItems: List<LocalTrack>,
    history: List<String>,
    loading: Boolean,
    refreshing: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onHistorySelected: (String) -> Unit,
    onHistoryRemoved: (String) -> Unit,
    onHistoryCleared: () -> Unit,
    open: (YTItem) -> Unit,
    download: (SongItem) -> Unit,
    onPlayNext: (SongItem) -> Unit,
    onAddToQueue: (SongItem) -> Unit,
    onAddToPlaylist: (SongItem) -> Unit,
    onSetLiked: (SongItem, Boolean) -> Unit,
    isLiked: (SongItem) -> Boolean,
    openLocal: (LocalTrack) -> Unit,
    onPlayNextLocal: (LocalTrack) -> Unit,
    onAddToQueueLocal: (LocalTrack) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberPersistedLazyListState("search:${query.trim().lowercase(java.util.Locale.ROOT)}")
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                if (query.isBlank()) "Search YouTube Music + local library" else "Results for “$query”",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        if (query.isBlank()) {
            if (history.isEmpty()) {
                item { Text("Your recent searches will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onHistoryCleared) { Text("Clear") }
                    }
                }
                items(history, key = { "search-history:$it" }) { recent ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onHistorySelected(recent) }, modifier = Modifier.weight(1f)) {
                            Text(recent, modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(onClick = { onHistoryRemoved(recent) }) { Text("Remove") }
                    }
                }
            }
        }
        if (localItems.isNotEmpty()) {
            item { Text("Local library", style = MaterialTheme.typography.titleLarge) }
            items(localItems, key = { "local:${it.id}" }) { track ->
                LocalTrackCard(
                    track = track,
                    onPlay = { openLocal(track) },
                    onPlayNext = { onPlayNextLocal(track) },
                    onAddToQueue = { onAddToQueueLocal(track) },
                )
            }
            item { Text("YouTube Music", style = MaterialTheme.typography.titleLarge) }
        } else if (query.isNotBlank()) {
            item { Text("YouTube Music", style = MaterialTheme.typography.titleLarge) }
        }
        if (refreshing && items.isNotEmpty()) {
            item { Text("Refreshing results…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item {
            AnimatedVisibility(
                visible = loading,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(120)),
                label = "search-loading",
            ) { Loading() }
        }
        error?.let { message ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Error(message)
                    OutlinedButton(onClick = onRetry, enabled = !loading && !refreshing) { Text("Retry") }
                }
            }
        }
        if (!loading && error == null && query.isNotBlank() && items.isEmpty() && localItems.isEmpty()) {
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

@Composable private fun DetailPage(page: Detail, loading: Boolean, error: String?, onOpen: (YTItem) -> Unit, onOpenArtistItems: (BrowseEndpoint) -> Unit, onPlay: (List<SongItem>, String, Int) -> Unit, onDownload: (SongItem) -> Unit, onDownloadAll: (List<SongItem>) -> Unit, onPlayNext: (SongItem) -> Unit, onAddToQueue: (SongItem) -> Unit, onAddToPlaylist: (SongItem) -> Unit, onSetLiked: (SongItem, Boolean) -> Unit, isLiked: (SongItem) -> Boolean, isArtistSubscribed: (ArtistItem) -> Boolean, onSetArtistSubscribed: (ArtistItem, Boolean) -> Unit, modifier: Modifier) {
    val title = when (page) { is Detail.Album -> page.page.album.title; is Detail.Playlist -> page.page.playlist.title; is Detail.Artist -> page.page.artist.title; is Detail.ArtistItems -> page.page.title }
    val image = when (page) { is Detail.Album -> page.page.album.thumbnail; is Detail.Playlist -> page.page.playlist.thumbnail; is Detail.Artist -> page.page.artist.thumbnail; is Detail.ArtistItems -> page.parent.artist.thumbnail }
    val extra = when (page) { is Detail.Album -> "Album"; is Detail.Playlist -> page.page.playlist.author?.name ?: "Playlist"; is Detail.Artist -> page.page.description ?: "Artist"; is Detail.ArtistItems -> "Artist" }
    val tracks = when (page) { is Detail.Album -> page.page.songs; is Detail.Playlist -> page.page.songs; is Detail.Artist, is Detail.ArtistItems -> emptyList() }
    val listState = rememberPersistedLazyListState(
        when (page) {
            is Detail.Album -> "detail:album:${page.page.album.browseId}"
            is Detail.Playlist -> "detail:playlist:${page.page.playlist.id}"
            is Detail.Artist -> "detail:artist:${page.page.artist.id}"
            is Detail.ArtistItems -> "detail:artist-items:${page.parent.artist.id}:${page.page.title}"
        },
    )
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
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
                val sectionItems = section.items.distinctBy { it.id }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(section.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        section.moreEndpoint?.let { endpoint ->
                            TextButton(onClick = { onOpenArtistItems(endpoint) }) { Text("See all") }
                        }
                    }
                }
                if (section.layout == ArtistSectionLayout.LIST && sectionItems.all { it is SongItem }) {
                    itemsIndexed(sectionItems, key = { index, item -> "${section.title}:${item::class}:${item.id}:$index" }) { _, item ->
                        val song = item as SongItem
                        ResultCard(
                            item,
                            { onOpen(item) },
                            { onDownload(song) },
                            { onPlayNext(song) },
                            { onAddToQueue(song) },
                            { onAddToPlaylist(song) },
                            { liked -> onSetLiked(song, liked) },
                            isLiked(song),
                        )
                    }
                } else {
                    item {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(sectionItems, key = { item -> "${section.title}:${item::class}:${item.id}" }) { item ->
                                val song = item as? SongItem
                                Box(Modifier.width(280.dp)) {
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
                        }
                    }
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
    val listState = rememberPersistedLazyListState("detail:artist-items:${detail.parent.artist.id}:${page.title}")

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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

internal data class PlaylistSongOccurrence(
    val song: SongItem,
    val originalIndex: Int,
) {
    val stableKey: String
        get() = "${song.id}:$originalIndex"
}

internal fun playlistSongOccurrences(
    songs: List<SongItem>,
    query: String,
): List<PlaylistSongOccurrence> =
    songs.mapIndexedNotNull { index, song ->
        if (
            query.isBlank() ||
            song.title.contains(query, ignoreCase = true) ||
            song.artists.any { it.name.contains(query, ignoreCase = true) } ||
            song.album?.name?.contains(query, ignoreCase = true) == true
        ) {
            PlaylistSongOccurrence(song, index)
        } else {
            null
        }
    }

@Composable
private fun PlaylistDetailPage(
    page: PlaylistPage,
    error: String?,
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
        playlistSongOccurrences(page.songs, query)
    }
    val subtitle = listOfNotNull(
        page.playlist.author?.name,
        page.playlist.songCountText ?: "${page.songs.size} songs",
    ).joinToString(" • ")
    val listState = rememberPersistedLazyListState("detail:playlist:${page.playlist.id}")

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
        error?.let { message -> item { Error(message) } }
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
        itemsIndexed(filtered, key = { _, occurrence -> occurrence.stableKey }) { _, occurrence ->
            val song = occurrence.song
            ResultCard(
                song,
                { onPlay(page.songs, occurrence.originalIndex, page.playlist.title) },
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
    onShowStems: () -> Unit,
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
                            partyState.participants.forEach { participant ->
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(participant.name, style = MaterialTheme.typography.titleSmall)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Queue", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Switch(
                                            checked = participant.canEditQueue,
                                            onCheckedChange = { playTogether.setGuestQueueEditing(participant.id, it) },
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Text("Play / pause", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(8.dp))
                                        Switch(
                                            checked = participant.canControlPlayback,
                                            onCheckedChange = { playTogether.setGuestPlaybackControl(participant.id, it) },
                                        )
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Skip / play new songs", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Switch(
                                            checked = participant.canChangeSong,
                                            onCheckedChange = { playTogether.setGuestSongChanging(participant.id, it) },
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Text("Seek", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(8.dp))
                                        Switch(
                                            checked = participant.canSeek,
                                            onCheckedChange = { playTogether.setGuestSeeking(participant.id, it) },
                                        )
                                    }
                                }
                            }
                        }
                        partyState.mode == PartyMode.GUEST && partyState.phase == PartyPhase.CONNECTED -> {
                            Text(
                                when (partyState.transport) {
                                    PartyTransport.DIRECT -> if (partyState.canControlPlayback) "Connected directly P2P. you can play or pause for everyone." else "Connected directly P2P. the host controls playback."
                                    PartyTransport.RELAY -> if (partyState.canControlPlayback) "Connected through the Quick Tunnel fallback. you can play or pause for everyone." else "Connected through the Quick Tunnel fallback. the host controls playback."
                                    else -> partyState.message ?: if (partyState.canControlPlayback) "Connected. you can play or pause for everyone." else "Connected. the host controls playback."
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
                            onClick = {
                                scope.launch {
                                    if (playTogether.join(inviteInput).isSuccess) showPartyDialog = false
                                }
                            },
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
        val guestInParty = partyState.mode == PartyMode.GUEST && partyState.phase == PartyPhase.CONNECTED
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(item?.metadata?.artworkUri, Modifier.size(46.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                NowPlayingTitle(item, { albumId -> browse.openAlbum(albumId, scope) })
                NowPlayingSubtitle(snapshot, item, onAlbumClick = { albumId -> browse.openAlbum(albumId, scope) }, onArtistClick = { artistId -> browse.openArtist(artistId, scope) })
            }
            TextButton(onClick = onShowLyrics, enabled = item != null) { Text("Lyrics") }
            DesktopTooltip("Stem player") {
                IconButton(onClick = onShowStems, enabled = item != null) {
                    Icon(Icons.Rounded.GraphicEq, "Stem player")
                }
            }
            IconButton({ scope.launch { playTogether.skipPrevious() } }, enabled = snapshot.canSkipPrevious && (!guestInParty || partyState.canChangeSong)) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
            FilledIconButton({ scope.launch { playTogether.togglePlayback() } }, enabled = item != null && (!guestInParty || partyState.canControlPlayback)) { Icon(if (snapshot.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (snapshot.isPlaying) "Pause" else "Play") }
            IconButton({ scope.launch { playTogether.skipNext() } }, enabled = snapshot.canSkipNext && (!guestInParty || partyState.canChangeSong)) { Icon(Icons.Rounded.SkipNext, "Next") }
            DesktopTooltip(if (snapshot.shuffleEnabled) "Shuffle on" else "Shuffle off") {
                IconButton({ scope.launch { services.playback.setShuffleEnabled(!snapshot.shuffleEnabled) } }, enabled = partyState.mode == PartyMode.NONE && item != null) {
                    Icon(Icons.Rounded.Shuffle, if (snapshot.shuffleEnabled) "Shuffle on" else "Shuffle off", tint = if (snapshot.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DesktopTooltip(when (snapshot.repeatMode) { RepeatMode.OFF -> "Repeat off"; RepeatMode.ALL -> "Repeat all"; RepeatMode.ONE -> "Repeat one" }) {
                IconButton({ scope.launch { services.playback.setRepeatMode(when (snapshot.repeatMode) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF }) } }, enabled = !guestInParty && item != null) {
                    Icon(if (snapshot.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, if (snapshot.repeatMode == RepeatMode.OFF) "Repeat off" else "Repeat on", tint = if (snapshot.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DesktopTooltip("Play together") { IconButton(onClick = { showPartyDialog = true }) { Icon(Icons.Rounded.Group, "Play together", tint = if (partyState.phase == PartyPhase.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
            DesktopTooltip("Queue") { IconButton(onClick = onShowQueue, enabled = item != null) { Icon(Icons.Rounded.QueueMusic, "Queue") } }
            Icon(Icons.Rounded.VolumeUp, null)
            Slider(snapshot.volume, { scope.launch { services.playback.setVolume(it) } }, Modifier.width(90.dp))
        }
        if (item != null) Slider(if (snapshot.durationMs > 0) snapshot.positionMs.toFloat() / snapshot.durationMs else 0f, { scope.launch { playTogether.seekTo((it * snapshot.durationMs).roundToLong()) } }, Modifier.fillMaxWidth(), enabled = !guestInParty || partyState.canSeek)
    } }
}

/** Immersive lyrics content rendered inside the app shell so it shares the integrated chrome. */
@Composable
internal fun DesktopLyricsView(
    services: DesktopMusicServices,
    playTogether: PlayTogetherManager,
    browse: DesktopBrowseController,
    onBrowseOpened: () -> Unit = {},
    modifier: Modifier = Modifier,
    useShellDynamicBackdrop: Boolean = false,
) {
    val snapshot by services.playback.state.collectAsState()
    val partyState by playTogether.state.collectAsState()
    val guestInParty = partyState.mode == PartyMode.GUEST && partyState.phase == PartyPhase.CONNECTED
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
                        NowPlayingTitle(item, { albumId -> browse.openAlbum(albumId, scope, onOpened = onBrowseOpened) }, Modifier.fillMaxWidth())
                        NowPlayingSubtitle(
                            snapshot,
                            item,
                            onAlbumClick = { albumId -> browse.openAlbum(albumId, scope, onOpened = onBrowseOpened) },
                            onArtistClick = { artistId -> browse.openArtist(artistId, scope, onOpened = onBrowseOpened) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    IconButton(onClick = { scope.launch { playTogether.skipPrevious() } }, enabled = snapshot.canSkipPrevious && (!guestInParty || partyState.canChangeSong)) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                    FilledIconButton(onClick = { scope.launch { playTogether.togglePlayback() } }, enabled = item != null && (!guestInParty || partyState.canControlPlayback)) {
AnimatedContent(targetState = snapshot.isPlaying, transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) }, label = "mini-player-play-state") { playing -> Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
                    }
                    IconButton(onClick = { scope.launch { playTogether.skipNext() } }, enabled = snapshot.canSkipNext && (!guestInParty || partyState.canChangeSong)) { Icon(Icons.Rounded.SkipNext, "Next") }
                }
                if (item != null) {
                    Slider(
                        value = if (snapshot.durationMs > 0) (snapshot.positionMs.toFloat() / snapshot.durationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { scope.launch { playTogether.seekTo((it * snapshot.durationMs).roundToLong()) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                        enabled = !guestInParty || partyState.canSeek,
                    )
                }
                DesktopLyricsPane(
                    presenter = services.lyrics,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    drawBackground = !useShellDynamicBackdrop,
                    seekEnabled = !guestInParty || partyState.canSeek,
                    onSeek = { positionMs -> scope.launch { playTogether.seekTo(positionMs) } },
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
internal fun toPlaybackItem(track: LocalTrack) = PlaybackItem(
    PlaybackMetadata(
        id = track.id,
        title = track.title,
        artists = listOf(PlaybackArtist(name = track.artist)),
        album = track.album?.let { PlaybackAlbum(title = it) },
        durationHintMs = track.durationMs,
    ),
    moe.rukamori.archivetune.playback.api.PlaybackSource.LocalFile(track.path.toString()),
)
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
