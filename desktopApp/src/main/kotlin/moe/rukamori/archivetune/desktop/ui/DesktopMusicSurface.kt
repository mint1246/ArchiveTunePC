package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.library.LocalTrack
import moe.rukamori.archivetune.desktop.platform.DesktopMusicServices
import moe.rukamori.archivetune.desktop.platform.DesktopDownloadServices
import moe.rukamori.archivetune.desktop.platform.AppPaths
import moe.rukamori.archivetune.desktop.lyrics.DesktopLyricsPane
import java.util.logging.Logger
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.AlbumPage
import moe.rukamori.archivetune.innertube.pages.ArtistPage
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import kotlin.math.roundToLong

private sealed interface Detail { data class Album(val page: AlbumPage) : Detail; data class Artist(val page: ArtistPage) : Detail; data class Playlist(val page: PlaylistPage) : Detail }

@Composable
fun DesktopMusicContent(state: DesktopContentState, services: DesktopMusicServices, downloadServices: DesktopDownloadServices, paths: AppPaths, logger: Logger, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var resultItems by remember { mutableStateOf<List<YTItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var localTracks by remember { mutableStateOf(services.library.tracks) }
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf<String?>(null) }
    suspend fun rescanLibrary() {
        libraryLoading = true; libraryError = null
        runCatching { services.library.rescan() }.onSuccess { localTracks = it }.onFailure { libraryError = it.message ?: "Library scan failed" }
        libraryLoading = false
    }
    LaunchedEffect(Unit) { if (services.library.folders.isNotEmpty()) rescanLibrary() }
    LaunchedEffect(state.searchQuery, state.destination) {
        if (state.destination != moe.rukamori.archivetune.desktop.state.DesktopDestination.SEARCH || state.searchQuery.isBlank()) return@LaunchedEffect
        delay(300); loading = true; failure = null
        services.backend.searchSummary(state.searchQuery).onSuccess { page -> resultItems = page.summaries.flatMap { it.items }.distinctBy { it::class to it.id } }
            .onFailure { failure = it.message ?: "Search failed. Check your connection and try again."; resultItems = emptyList() }
        loading = false
    }
    fun open(item: YTItem) {
        when (item) {
            is SongItem -> scope.launch { services.playback.replaceQueue(resultItems.filterIsInstance<SongItem>().map(::toPlaybackItem), resultItems.filterIsInstance<SongItem>().indexOf(item).coerceAtLeast(0), title = "Search results") }
            is AlbumItem -> scope.launch { loading = true; failure = null; services.backend.album(item.browseId).onSuccess { detail = Detail.Album(it) }.onFailure { failure = it.message ?: "Album could not be loaded" }; loading = false }
            is ArtistItem -> scope.launch { loading = true; failure = null; services.backend.artist(item.id).onSuccess { detail = Detail.Artist(it) }.onFailure { failure = it.message ?: "Artist could not be loaded" }; loading = false }
            is PlaylistItem -> scope.launch { loading = true; failure = null; services.backend.playlist(item.id).onSuccess { detail = Detail.Playlist(it) }.onFailure { failure = it.message ?: "Playlist could not be loaded" }; loading = false }
        }
    }
    when (val page = detail) {
        null -> when (state.destination) {
            moe.rukamori.archivetune.desktop.state.DesktopDestination.SEARCH -> SearchResults(state.searchQuery, resultItems, loading, failure, ::open, { song -> scope.launch { downloadServices.enqueue(song).onSuccess { state.navigate(moe.rukamori.archivetune.desktop.state.DesktopDestination.DOWNLOADS) }.onFailure { failure = it.message ?: "Could not resolve a stream for download" } } }, modifier)
            moe.rukamori.archivetune.desktop.state.DesktopDestination.DOWNLOADS -> DesktopDownloadsSurface(downloadServices, { path, item -> scope.launch { services.playback.replaceQueue(listOf(toPlaybackItem(item, path)), title = "Downloads") } }, modifier)
            moe.rukamori.archivetune.desktop.state.DesktopDestination.LIBRARY -> LocalLibrary(
                localTracks, services.library.folders, libraryLoading, libraryError,
                onAddFolder = { scope.launch { chooseLibraryFolder()?.let { services.library.setFolders(services.library.folders + it); rescanLibrary() } } },
                onRescan = { scope.launch { rescanLibrary() } },
                onPlay = { selected -> scope.launch { val items = localTracks.map(::toPlaybackItem); services.playback.replaceQueue(items, localTracks.indexOf(selected).coerceAtLeast(0), title = "Local library") } }, modifier,
            )
            else -> FoundationDestinationContent(state, paths, logger, services.account, modifier)
        }
        else -> DetailPage(page, loading, failure, onBack = { detail = null }, onOpen = ::open, onPlay = { songs, title -> scope.launch { services.playback.replaceQueue(songs.map(::toPlaybackItem), title = title) } }, modifier = modifier)
    }
}

@Composable private fun LocalLibrary(tracks: List<LocalTrack>, folders: List<java.nio.file.Path>, loading: Boolean, error: String?, onAddFolder: () -> Unit, onRescan: () -> Unit, onPlay: (LocalTrack) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Local library", style = MaterialTheme.typography.headlineMedium); Text(if (folders.isEmpty()) "Choose a music folder to begin." else "${tracks.size} tracks from ${folders.size} folder${if (folders.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant) }; androidx.compose.material3.Button(onClick = onAddFolder) { Text("Add folder") }; Spacer(Modifier.width(8.dp)); androidx.compose.material3.OutlinedButton(onClick = onRescan, enabled = folders.isNotEmpty() && !loading) { Text("Rescan") } }
        if (loading) Loading(); error?.let { Error(it) }
        tracks.forEach { track -> Card(Modifier.fillMaxWidth().clickable { onPlay(track) }) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(54.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium); Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Rounded.PlayArrow, "Play") } } }
    }
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

@Composable private fun SearchResults(query: String, items: List<YTItem>, loading: Boolean, error: String?, open: (YTItem) -> Unit, download: (SongItem) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (query.isBlank()) "Search YouTube Music" else "Results for “$query”", style = MaterialTheme.typography.headlineMedium)
        if (loading) Loading(); error?.let { Error(it) }
        if (!loading && error == null && query.isNotBlank() && items.isEmpty()) Text("No music results found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { item -> ResultCard(item, { open(item) }, (item as? SongItem)?.let { song -> { download(song) } }) }
    }
}

@Composable private fun DetailPage(page: Detail, loading: Boolean, error: String?, onBack: () -> Unit, onOpen: (YTItem) -> Unit, onPlay: (List<SongItem>, String) -> Unit, modifier: Modifier) {
    val title = when (page) { is Detail.Album -> page.page.album.title; is Detail.Playlist -> page.page.playlist.title; is Detail.Artist -> page.page.artist.title }
    val image = when (page) { is Detail.Album -> page.page.album.thumbnail; is Detail.Playlist -> page.page.playlist.thumbnail; is Detail.Artist -> page.page.artist.thumbnail }
    val extra = when (page) { is Detail.Album -> "Album"; is Detail.Playlist -> page.page.playlist.author?.name ?: "Playlist"; is Detail.Artist -> page.page.description ?: "Artist" }
    val tracks = when (page) { is Detail.Album -> page.page.songs; is Detail.Playlist -> page.page.songs; is Detail.Artist -> emptyList() }
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to search") }
        Row(verticalAlignment = Alignment.CenterVertically) { Artwork(image, Modifier.size(112.dp)); Spacer(Modifier.width(18.dp)); Column(Modifier.weight(1f)) { Text(extra, color = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.headlineMedium); if (tracks.isNotEmpty()) Text("${tracks.size} songs") }; if (tracks.isNotEmpty()) FilledIconButton(onClick = { onPlay(tracks, title) }) { Icon(Icons.Rounded.PlayArrow, "Play all") } }
        if (loading) Loading(); error?.let { Error(it) }
        if (page is Detail.Artist) page.page.sections.forEach { section -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(section.title, style = MaterialTheme.typography.titleLarge); section.items.forEach { ResultCard(it, { onOpen(it) }) } } }
        else tracks.forEach { song -> ResultCard(song, { onPlay(tracks, title) }) }
    }
}

@Composable private fun ResultCard(item: YTItem, open: () -> Unit, download: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().clickable(onClick = open)) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Artwork(item.thumbnail, Modifier.size(58.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium); Text(itemSubtitle(item), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }; if (download != null) IconButton(onClick = download) { Icon(Icons.Rounded.Download, "Download") }; Icon(if (item is SongItem) Icons.Rounded.PlayArrow else Icons.Rounded.MusicNote, null) } }
}
private fun itemSubtitle(item: YTItem) = when (item) { is SongItem -> item.artists.joinToString { it.name } + (item.album?.let { " • ${it.name}" } ?: "") + (item.duration?.let { " • ${format(it * 1000L)}" } ?: ""); is AlbumItem -> "Album • " + item.artists.orEmpty().joinToString { it.name }; is ArtistItem -> "Artist"; is PlaylistItem -> listOfNotNull("Playlist", item.author?.name, item.songCountText).joinToString(" • ") }
@Composable private fun Artwork(url: String?, modifier: Modifier) { if (!url.isNullOrBlank()) AsyncImage(url, null, modifier.clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop) else Box(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, null) } }
@Composable private fun Loading() = Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
@Composable private fun Error(message: String) = Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) { Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer) }

@Composable fun DesktopMiniPlayer(services: DesktopMusicServices, modifier: Modifier = Modifier) {
    val snapshot by services.playback.state.collectAsState(); val scope = androidx.compose.runtime.rememberCoroutineScope(); val item = snapshot.currentItem
    var lyricsExpanded by remember(item?.metadata?.id) { mutableStateOf(false) }
    Surface(modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) { Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Artwork(item?.metadata?.artworkUri, Modifier.size(46.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(item?.metadata?.title ?: "Nothing playing", maxLines = 1, overflow = TextOverflow.Ellipsis); Text(snapshot.error?.let { if (it.kind.name == "ENGINE_UNAVAILABLE") "mpv is required for desktop playback — install mpv or set ARCHIVETUNE_MPV_PATH" else it.message } ?: item?.metadata?.artistText ?: "Choose a song to begin", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = if (snapshot.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton(onClick = { lyricsExpanded = !lyricsExpanded }, enabled = item != null) { Text(if (lyricsExpanded) "Hide lyrics" else "Lyrics") }; IconButton({ scope.launch { services.playback.skipPrevious() } }, enabled = snapshot.canSkipPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous") }; FilledIconButton({ scope.launch { if (snapshot.isPlaying) services.playback.pause() else services.playback.play() } }, enabled = item != null) { Icon(if (snapshot.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (snapshot.isPlaying) "Pause" else "Play") }; IconButton({ scope.launch { services.playback.skipNext() } }, enabled = snapshot.canSkipNext) { Icon(Icons.Rounded.SkipNext, "Next") }; Icon(Icons.Rounded.VolumeUp, null); Slider(snapshot.volume, { scope.launch { services.playback.setVolume(it) } }, Modifier.width(90.dp)) }
        if (item != null) Slider(if (snapshot.durationMs > 0) snapshot.positionMs.toFloat() / snapshot.durationMs else 0f, { scope.launch { services.playback.seekTo((it * snapshot.durationMs).roundToLong()) } }, Modifier.fillMaxWidth())
        if (lyricsExpanded) Surface(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp).padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceContainer) { DesktopLyricsPane(services.lyrics, Modifier.fillMaxSize().padding(vertical = 16.dp)) }
    } }
}
private fun toPlaybackItem(song: SongItem) = PlaybackItem(PlaybackMetadata(song.id, song.title, song.artists.map { PlaybackArtist(it.id, it.name) }, song.album?.let { PlaybackAlbum(it.id, it.name) }, song.thumbnail, song.duration?.times(1000L), song.explicit))
private fun toPlaybackItem(track: LocalTrack) = PlaybackItem(PlaybackMetadata(track.id, track.title, listOf(PlaybackArtist(name = track.artist)), track.album?.let { PlaybackAlbum(title = it) }), moe.rukamori.archivetune.playback.api.PlaybackSource.LocalFile(track.path.toString()))
private fun toPlaybackItem(item: moe.rukamori.archivetune.downloads.DownloadSnapshot, path: java.nio.file.Path) = PlaybackItem(PlaybackMetadata(item.id, item.title, item.artist?.let { listOf(PlaybackArtist(name = it)) }.orEmpty()), moe.rukamori.archivetune.playback.api.PlaybackSource.LocalFile(path.toString()))
private fun format(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
