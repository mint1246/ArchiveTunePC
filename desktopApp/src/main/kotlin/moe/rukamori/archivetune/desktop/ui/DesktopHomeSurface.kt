/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random
import moe.rukamori.archivetune.desktop.platform.DesktopMusicServices
import moe.rukamori.archivetune.desktopauth.DesktopAccountState
import moe.rukamori.archivetune.innertube.home.computeLocalHomeContent
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.HomePage

/**
 * Desktop Home surface: a faithful port of the Android remote (YouTube Music) home feed. Loads
 * the remote feed, category chips, quick picks hero, signed-in playlists, and infinite scroll.
 */
@Composable
fun DesktopHomeSurface(
    services: DesktopMusicServices,
    onOpenItem: (YTItem) -> Unit,
    onPlayQueue: (List<SongItem>, Int, String) -> Unit,
    onDownload: (SongItem) -> Unit,
    onPlayNext: (SongItem) -> Unit,
    onAddToQueue: (SongItem) -> Unit,
    onAddToPlaylist: (SongItem) -> Unit,
    onSetLiked: (SongItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackState by services.playback.state.collectAsState()
    val accountState by services.account.state.collectAsState()
    val homeSnapshot by services.homeStore.snapshot.collectAsState()
    val similarRecommendations by services.similarRecommendations.collectAsState()
    val scope = rememberCoroutineScope()
    val localContent = remember(homeSnapshot) { computeLocalHomeContent(homeSnapshot) }

    var homePage by remember { mutableStateOf<HomePage?>(null) }
    var remoteQuickPicks by remember { mutableStateOf<HomePage.Section?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var playlists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var selectedChip by remember { mutableStateOf<HomePage.Chip?>(null) }
    var previousHomePage by remember { mutableStateOf<HomePage?>(null) }
    var previousQuickPicks by remember { mutableStateOf<HomePage.Section?>(null) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var feedbackHiddenItems by remember { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()

    suspend fun loadInitial() {
        isLoading = true
        error = null
        coroutineScope {
            launch {
                services.backend.home().onSuccess { page ->
                    val (pageWithoutQuickPicks, quickPicksSection) = page.extractQuickPicks()
                    homePage = pageWithoutQuickPicks
                    remoteQuickPicks = quickPicksSection
                }.onFailure { failure ->
                    error = failure.message ?: "Could not load the home feed."
                }
            }
            launch {
                services.backend.libraryPlaylists().onSuccess { page ->
                    playlists = page.items.filterIsInstance<PlaylistItem>().filterNot { it.id == "SE" }
                }
            }
        }
        isLoading = false
    }

    LaunchedEffect(Unit) { loadInitial() }

    LaunchedEffect(homePage?.continuation) {
        val continuation = homePage?.continuation ?: return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex >= layoutInfo.totalItemsCount - 3
        }.collect { nearEnd ->
            if (nearEnd && !isLoadingMore) {
                isLoadingMore = true
                services.backend.home(continuation = continuation).onSuccess { page ->
                    val current = homePage ?: return@onSuccess
                    val merged = current.copy(sections = current.sections + page.sections, continuation = page.continuation)
                    val (pageWithoutQuickPicks, quickPicksSection) = merged.extractQuickPicks()
                    quickPicksSection?.let { remoteQuickPicks = it }
                    homePage = pageWithoutQuickPicks
                }
                isLoadingMore = false
            }
        }
    }

    fun selectChip(chip: HomePage.Chip) {
        if (chip == selectedChip && previousHomePage != null) {
            homePage = previousHomePage
            remoteQuickPicks = previousQuickPicks
            previousHomePage = null
            previousQuickPicks = null
            selectedChip = null
            return
        }
        if (selectedChip == null) {
            previousHomePage = homePage
            previousQuickPicks = remoteQuickPicks
        }
        scope.launch {
            services.backend.home(params = chip.endpoint?.params).onSuccess { page ->
                val (pageWithoutQuickPicks, quickPicksSection) = page.copy(chips = homePage?.chips).extractQuickPicks()
                remoteQuickPicks = quickPicksSection
                homePage = pageWithoutQuickPicks
                selectedChip = chip
            }
        }
    }

    fun itemKey(item: YTItem) = "${item::class.simpleName}:${item.id}"
    fun visible(item: YTItem) = itemKey(item) !in feedbackHiddenItems
    fun dismiss(item: YTItem) {
        if (item.feedbackToken.isNullOrBlank()) return
        scope.launch {
            services.backend.sendNotInterestedFeedback(item).onSuccess {
                feedbackHiddenItems = feedbackHiddenItems + itemKey(item)
            }
        }
    }
    fun liked(song: SongItem) = homeSnapshot.likedSongs.any { it.id == song.id }
    val sections =
        homePage?.sections.orEmpty()
            .map { section -> section.copy(items = section.items.filter(::visible)) }
            .filter { it.items.isNotEmpty() }
    val heroSongs = remember(remoteQuickPicks) {
        remoteQuickPicks?.items.orEmpty().filterIsInstance<SongItem>().distinctBy { it.id }
    }
    val visibleHeroSongs = heroSongs.filter(::visible)
    val speedDial = homeSnapshot.speedDial
    val localQuickPicks = localContent.quickPicks
    val keepListeningSongs = localContent.keepListeningSongs
    val keepListeningAlbums = localContent.keepListeningAlbums
    val keepListeningArtists = localContent.keepListeningArtists
    val forgottenFavorites = localContent.forgottenFavorites
    fun isPinned(item: YTItem) = services.homeStore.isSpeedDialPinned(item)
    fun togglePin(item: YTItem) = services.homeStore.toggleSpeedDial(item)
    val hasContent =
        sections.isNotEmpty() ||
            visibleHeroSongs.isNotEmpty() ||
            playlists.isNotEmpty() ||
            localQuickPicks.isNotEmpty() ||
            keepListeningSongs.isNotEmpty() ||
            keepListeningAlbums.isNotEmpty() ||
            keepListeningArtists.isNotEmpty() ||
            forgottenFavorites.isNotEmpty() ||
            similarRecommendations.isNotEmpty() ||
            speedDial.isNotEmpty()
    val isPlaying = playbackState.isPlaying
    val currentItemId = playbackState.currentItem?.metadata?.id
    val currentAlbumId = playbackState.currentItem?.metadata?.album?.id
    val accountName =
        (accountState as? DesktopAccountState.SignedIn)?.account?.name?.takeIf { it.isNotBlank() } ?: "Account"
    val accountAvatarUrl = (accountState as? DesktopAccountState.SignedIn)?.account?.avatarUrl

    when {
        isLoading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    CircularProgressIndicator()
                }
            }
        }

        error != null && !hasContent -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { scope.launch { loadInitial() } }) { Text("Retry") }
                }
            }
        }

        !hasContent -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(
                        text = "No results found.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        else -> {
            val chips = homePage?.chips.orEmpty().filterNot { it.title.contains("podcasts", ignoreCase = true) }
            val quickPicksSection = remoteQuickPicks
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (chips.isNotEmpty()) {
                    item(key = "home_category_chips") {
                        HomeCategoryChips(chips = chips, selectedChip = selectedChip, onChipSelected = ::selectChip)
                    }
                }

                if (localQuickPicks.isNotEmpty()) {
                    item(key = "home_local_quick_picks_header") {
                        DesktopSectionHeader(title = "Quick picks")
                    }
                    item(key = "home_local_quick_picks") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(localQuickPicks, key = { it.id }) { song ->
                                DesktopQuickPickHeroCard(
                                    song = song,
                                    isActive = song.id == currentItemId,
                                    isPlaying = isPlaying,
                                    onClick = { onPlayQueue(listOf(song), 0, song.title) },
                                )
                            }
                        }
                    }
                } else if (quickPicksSection != null && visibleHeroSongs.isNotEmpty()) {
                    item(key = "home_quick_picks_header") {
                        DesktopSectionHeader(title = quickPicksSection.title)
                    }
                    item(key = "home_quick_picks") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(visibleHeroSongs, key = { it.id }) { song ->
                                DesktopQuickPickHeroCard(
                                    song = song,
                                    isActive = song.id == currentItemId,
                                    isPlaying = isPlaying,
                                    onClick = { onPlayQueue(listOf(song), 0, song.title) },
                                )
                            }
                        }
                    }
                }

                if (speedDial.isNotEmpty()) {
                    item(key = "home_speed_dial_header") {
                        DesktopSectionHeader(title = "Speed dial")
                    }
                    item(key = "home_speed_dial") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(speedDial, key = { "${it::class.simpleName}:${it.id}" }) { item ->
                                val songs = speedDial.filterIsInstance<SongItem>().distinctBy { it.id }
                                DesktopMediaGridCard(
                                    item = item,
                                    isActive = when (item) {
                                        is SongItem -> item.id == currentItemId
                                        is AlbumItem -> item.browseId == currentAlbumId
                                        else -> false
                                    },
                                    isPlaying = isPlaying,
                                    modifier = Modifier.width(160.dp),
                                    onClick = {
                                        when (item) {
                                            is SongItem -> onPlayQueue(songs, songs.indexOf(item).coerceAtLeast(0), "Speed dial")
                                            else -> onOpenItem(item)
                                        }
                                    },
                                    onPin = { togglePin(item) },
                                    isPinned = isPinned(item),
                                )
                            }
                            val speedDialSongs = speedDial.filterIsInstance<SongItem>().distinctBy { it.id }
                            if (speedDialSongs.isNotEmpty()) {
                                item(key = "speed_dial_random") {
                                    SpeedDialRandomTile(
                                        onClick = {
                                            onPlayQueue(
                                                speedDialSongs,
                                                Random.nextInt(speedDialSongs.size),
                                                "Speed dial",
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (
                    keepListeningSongs.isNotEmpty() ||
                        keepListeningAlbums.isNotEmpty() ||
                        keepListeningArtists.isNotEmpty()
                ) {
                    item(key = "home_keep_listening_header") {
                        DesktopSectionHeader(title = "Keep listening")
                    }
                    item(key = "home_keep_listening") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(keepListeningSongs, key = { "song:${it.id}" }) { song ->
                                DesktopMediaGridCard(
                                    item = song,
                                    isActive = song.id == currentItemId,
                                    isPlaying = isPlaying,
                                    modifier = Modifier.width(160.dp),
                                    onClick = { onPlayQueue(listOf(song), 0, song.title) },
                                    onPlayNext = { onPlayNext(song) },
                                    onAddToQueue = { onAddToQueue(song) },
                                    onDownload = { onDownload(song) },
                                    onAddToPlaylist = { onAddToPlaylist(song) },
                                    onSetLiked = { onSetLiked(song, it) },
                                    isLiked = liked(song),
                                    onPin = { togglePin(song) },
                                    isPinned = isPinned(song),
                                )
                            }
                            items(keepListeningAlbums, key = { "album:${it.id}" }) { album ->
                                DesktopMediaGridCard(
                                    item = album,
                                    isActive = album.browseId == currentAlbumId,
                                    isPlaying = isPlaying,
                                    modifier = Modifier.width(160.dp),
                                    onClick = { onOpenItem(album) },
                                    onPin = { togglePin(album) },
                                    isPinned = isPinned(album),
                                )
                            }
                            items(keepListeningArtists, key = { "artist:${it.id}" }) { artist ->
                                DesktopMediaGridCard(
                                    item = artist,
                                    isActive = false,
                                    isPlaying = isPlaying,
                                    modifier = Modifier.width(160.dp),
                                    onClick = { onOpenItem(artist) },
                                    onPin = { togglePin(artist) },
                                    isPinned = isPinned(artist),
                                )
                            }
                        }
                    }
                }

                if (playlists.isNotEmpty()) {
                    item(key = "home_account_playlists") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AccountPlaylistsHeader(accountName = accountName, accountAvatarUrl = accountAvatarUrl)
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(playlists.distinctBy { it.id }, key = { it.id }) { playlist ->
                                    DesktopMediaGridCard(
                                        item = playlist,
                                        isActive = false,
                                        isPlaying = isPlaying,
                                        modifier = Modifier.width(160.dp),
                                        onClick = { onOpenItem(playlist) },
                                        onPin = { togglePin(playlist) },
                                        isPinned = isPinned(playlist),
                                    )
                                }
                            }
                        }
                    }
                }

                if (forgottenFavorites.isNotEmpty()) {
                    item(key = "home_forgotten_favorites_header") {
                        DesktopSectionHeader(title = "Forgotten favorites")
                    }
                    item(key = "home_forgotten_favorites") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(forgottenFavorites, key = { it.id }) { song ->
                                DesktopMediaGridCard(
                                    item = song,
                                    isActive = song.id == currentItemId,
                                    isPlaying = isPlaying,
                                    modifier = Modifier.width(160.dp),
                                    onClick = {
                                        onPlayQueue(forgottenFavorites, forgottenFavorites.indexOf(song), "Forgotten favorites")
                                    },
                                    onPlayNext = { onPlayNext(song) },
                                    onAddToQueue = { onAddToQueue(song) },
                                    onDownload = { onDownload(song) },
                                    onAddToPlaylist = { onAddToPlaylist(song) },
                                    onSetLiked = { onSetLiked(song, it) },
                                    isLiked = liked(song),
                                    onPin = { togglePin(song) },
                                    isPinned = isPinned(song),
                                )
                            }
                        }
                    }
                }

                similarRecommendations.forEach { recommendation ->
                    val visibleItems = recommendation.items
                    if (visibleItems.isEmpty()) return@forEach
                    item(key = "home_similar_header_${recommendation.title.id}") {
                        DesktopSectionHeader(
                            title = recommendation.title.title,
                            label = "Similar to",
                        )
                    }
                    item(key = "home_similar_${recommendation.title.id}") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(visibleItems, key = { "${it::class.simpleName}:${it.id}" }) { item ->
                                val songs = visibleItems.filterIsInstance<SongItem>().distinctBy { it.id }
                                DesktopMediaGridCard(
                                    item = item,
                                    isActive = when (item) {
                                        is SongItem -> item.id == currentItemId
                                        is AlbumItem -> item.browseId == currentAlbumId
                                        else -> false
                                    },
                                    isPlaying = isPlaying,
                                    modifier = Modifier.width(160.dp),
                                    onClick = {
                                        when (item) {
                                            is SongItem -> onPlayQueue(songs, songs.indexOf(item).coerceAtLeast(0), recommendation.title.title)
                                            else -> onOpenItem(item)
                                        }
                                    },
                                    onPlayNext = (item as? SongItem)?.let { song -> { onPlayNext(song) } },
                                    onAddToQueue = (item as? SongItem)?.let { song -> { onAddToQueue(song) } },
                                    onDownload = (item as? SongItem)?.let { song -> { onDownload(song) } },
                                    onAddToPlaylist = (item as? SongItem)?.let { song -> { onAddToPlaylist(song) } },
                                    onSetLiked = (item as? SongItem)?.let { song -> { value -> onSetLiked(song, value) } },
                                    isLiked = (item as? SongItem)?.let(::liked) == true,
                                    onPin = { togglePin(item) },
                                    isPinned = isPinned(item),
                                )
                            }
                        }
                    }
                }

                itemsIndexed(sections, key = { index, section -> "${section.endpoint?.browseId ?: section.title}:$index" }) { _, section ->
                    HomeSectionBlock(
                        section = section,
                        isPlaying = isPlaying,
                        currentItemId = currentItemId,
                        currentAlbumId = currentAlbumId,
                        onOpenItem = onOpenItem,
                        onPlayQueue = onPlayQueue,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onDownload = onDownload,
                        onAddToPlaylist = onAddToPlaylist,
                        onSetLiked = onSetLiked,
                        onPin = ::togglePin,
                        onNotInterested = ::dismiss,
                        isLiked = ::liked,
                        isPinned = ::isPinned,
                    )
                }

                if (isLoadingMore) {
                    item(key = "home_loading_more") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeCategoryChips(
    chips: List<HomePage.Chip>,
    selectedChip: HomePage.Chip?,
    onChipSelected: (HomePage.Chip) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            val selected = chip == selectedChip
            FilterChip(
                selected = selected,
                onClick = { onChipSelected(chip) },
                label = { Text(chip.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon =
                    if (selected) {
                        {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun SpeedDialRandomTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier =
            modifier
                .width(160.dp)
                .aspectRatio(1f)
                .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Shuffle, contentDescription = "Play a random song", modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun DesktopQuickPickHeroCard(
    song: SongItem,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .width(300.dp)
                .height(170.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = song.thumbnail,
            contentDescription = song.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to Color.Black.copy(alpha = 0.1f),
                            1f to Color.Black.copy(alpha = 0.84f),
                        ),
                    ),
        )
        if (isActive && isPlaying) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(30.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.VolumeUp, contentDescription = "Now playing", modifier = Modifier.size(17.dp))
                }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AccountPlaylistsHeader(
    accountName: String,
    accountAvatarUrl: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (!accountAvatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = accountAvatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                )
            } else {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = "Your YouTube playlists",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = accountName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomeSectionBlock(
    section: HomePage.Section,
    isPlaying: Boolean,
    currentItemId: String?,
    currentAlbumId: String?,
    onOpenItem: (YTItem) -> Unit,
    onPlayQueue: (List<SongItem>, Int, String) -> Unit,
    onPlayNext: (SongItem) -> Unit,
    onAddToQueue: (SongItem) -> Unit,
    onDownload: (SongItem) -> Unit,
    onAddToPlaylist: (SongItem) -> Unit,
    onSetLiked: (SongItem, Boolean) -> Unit,
    onPin: (YTItem) -> Unit,
    onNotInterested: (YTItem) -> Unit,
    isLiked: (SongItem) -> Boolean,
    isPinned: (YTItem) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val sectionSongs = remember(section) { section.items.filterIsInstance<SongItem>().distinctBy { it.id } }
    Column(modifier) {
        DesktopSectionHeader(title = section.title, label = section.label)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(section.items, key = { index, item -> "${item.id}:$index" }) { _, item ->
                val isActive =
                    when (item) {
                        is SongItem -> item.id == currentItemId
                        is AlbumItem -> item.browseId == currentAlbumId
                        else -> false
                    }
                DesktopMediaGridCard(
                    item = item,
                    isActive = isActive,
                    isPlaying = isPlaying,
                    modifier = Modifier.width(160.dp),
                    onClick = {
                        when (item) {
                            is SongItem -> onPlayQueue(sectionSongs, sectionSongs.indexOf(item).coerceAtLeast(0), section.title)
                            else -> onOpenItem(item)
                        }
                    },
                    onPlayNext = (item as? SongItem)?.let { song -> { onPlayNext(song) } },
                    onAddToQueue = (item as? SongItem)?.let { song -> { onAddToQueue(song) } },
                    onDownload = (item as? SongItem)?.let { song -> { onDownload(song) } },
                    onAddToPlaylist = (item as? SongItem)?.let { song -> { onAddToPlaylist(song) } },
                    onSetLiked = (item as? SongItem)?.let { song -> { value -> onSetLiked(song, value) } },
                    isLiked = (item as? SongItem)?.let(isLiked) == true,
                    onPin = { onPin(item) },
                    onNotInterested = item.feedbackToken?.let { { onNotInterested(item) } },
                    isPinned = isPinned(item),
                )
            }
        }
    }
}

private fun HomePage.extractQuickPicks(): Pair<HomePage, HomePage.Section?> {
    val quickPicksIndex = sections.indexOfFirst { it.title.contains("quick pick", ignoreCase = true) }
    if (quickPicksIndex < 0) return this to null
    val quickPicks = sections[quickPicksIndex]
    return copy(sections = sections.toMutableList().also { it.removeAt(quickPicksIndex) }) to quickPicks
}
