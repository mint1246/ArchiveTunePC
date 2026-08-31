package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import moe.rukamori.archivetune.desktop.party.PartyMode
import moe.rukamori.archivetune.desktop.party.PartyPhase
import moe.rukamori.archivetune.desktop.party.PlayTogetherManager
import moe.rukamori.archivetune.desktop.platform.DesktopBrowseRoute
import moe.rukamori.archivetune.desktop.platform.DesktopMusicServices
import moe.rukamori.archivetune.desktop.social.DesktopSocialManager
import moe.rukamori.archivetune.desktop.social.SocialAccountState
import moe.rukamori.archivetune.desktop.social.SocialPartyInvite
import moe.rukamori.archivetune.desktop.social.SocialPresence
import moe.rukamori.archivetune.desktop.social.SocialChatMessage
import moe.rukamori.archivetune.desktop.social.SocialChatMessageKind
import moe.rukamori.archivetune.desktop.social.SocialSharedPlaylist
import moe.rukamori.archivetune.desktop.social.SocialSharedQueue
import moe.rukamori.archivetune.desktop.social.SocialSharedSong
import moe.rukamori.archivetune.desktop.social.SocialUser
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource

@Composable
internal fun DesktopFriendsSurface(
    social: DesktopSocialManager,
    playTogether: PlayTogetherManager,
    onOpenChat: (SocialUser) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by social.state.collectAsState()
    val party by playTogether.state.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SocialUser>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var invitingUserId by remember { mutableStateOf<String?>(null) }
    var invitedUserIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var statisticsFriend by remember { mutableStateOf<SocialUser?>(null) }
    var statisticsRange by remember { mutableStateOf(DesktopStatisticsRange.DAYS_7) }

    LaunchedEffect(party.mode, party.invite) {
        if (party.mode != PartyMode.HOST || party.invite.isNullOrBlank()) {
            invitedUserIds = emptySet()
        }
    }

    when (val account = state.account) {
        SocialAccountState.SignedOut -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.62f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(
                        Modifier.padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Friends", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Sign in with Discord to add friends, see what they are listening to and invite them straight into ArchiveTune parties.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                        Button(onClick = { scope.launch { social.signIn() } }) {
                            Text("Sign in with Discord")
                        }
                    }
                }
            }
        }

        SocialAccountState.SigningIn -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Finish signing in with Discord in your browser")
                }
            }
        }

        is SocialAccountState.SignedIn -> {
            statisticsFriend?.let { friend ->
                FriendStatisticsView(
                    friend = friend,
                    snapshot = state.friendStatistics[friend.id],
                    loading = friend.id in state.loadingFriendStatistics,
                    selectedRange = statisticsRange,
                    onRangeSelected = { statisticsRange = it },
                    onBack = { statisticsFriend = null },
                    onRefresh = { scope.launch { social.loadFriendStatistics(friend.id) } },
                    modifier = modifier,
                )
                return
            }
            LazyColumn(
                modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SocialAvatar(account.user, Modifier.size(48.dp))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(account.user.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "@${account.user.username}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { scope.launch { social.refresh() } }, enabled = !state.refreshing) {
                            Text(if (state.refreshing) "Refreshing…" else "Refresh")
                        }
                        TextButton(onClick = { scope.launch { social.logout() } }) {
                            Text("Sign out")
                        }
                    }
                }

                state.partyInvites.firstOrNull()?.let { invite ->
                    item(key = "party-invite-${invite.partyUrl}") {
                        IncomingPartyInvite(
                            invite = invite,
                            onJoin = {
                                scope.launch {
                                    actionError = null
                                    playTogether.join(invite.partyUrl)
                                        .onSuccess { social.dismissPartyInvite(invite) }
                                        .onFailure { actionError = it.message ?: "Could not join party" }
                                }
                            },
                            onDismiss = { social.dismissPartyInvite(invite) },
                        )
                    }
                }

                item {
                    Text("Add friend", style = MaterialTheme.typography.titleLarge)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Exact Discord username or display name") },
                        )
                        Button(
                            enabled = query.trim().length >= 2 && !searching,
                            onClick = {
                                scope.launch {
                                    searching = true
                                    actionError = null
                                    social.search(query).fold(
                                        onSuccess = { results = it },
                                        onFailure = { actionError = it.message ?: "Search failed" },
                                    )
                                    searching = false
                                }
                            },
                        ) {
                            Text(if (searching) "Searching…" else "Search")
                        }
                    }
                }

                if (results.isNotEmpty()) {
                    items(results, key = { "search-${it.id}" }) { user ->
                        SocialUserRow(
                            user = user,
                            presence = state.presence[user.id],
                            trailing = {
                                Button(onClick = {
                                    scope.launch {
                                        social.sendFriendRequest(user.id)
                                            .onSuccess { results = results.filterNot { it.id == user.id } }
                                            .onFailure { actionError = it.message }
                                    }
                                }) {
                                    Text("Add")
                                }
                            },
                        )
                    }
                }

                if (state.incomingRequests.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))
                        Text("Friend requests", style = MaterialTheme.typography.titleLarge)
                    }
                    items(state.incomingRequests, key = { "incoming-${it.user.id}" }) { request ->
                        SocialUserRow(
                            user = request.user,
                            presence = state.presence[request.user.id],
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        scope.launch { social.declineFriendRequest(request.user.id) }
                                    }) {
                                        Text("Decline")
                                    }
                                    Button(onClick = {
                                        scope.launch { social.acceptFriendRequest(request.user.id) }
                                    }) {
                                        Text("Accept")
                                    }
                                }
                            },
                        )
                    }
                }

                if (state.outgoingRequests.isNotEmpty()) {
                    item {
                        Text("Pending", style = MaterialTheme.typography.titleMedium)
                    }
                    items(state.outgoingRequests, key = { "outgoing-${it.user.id}" }) { request ->
                        SocialUserRow(
                            user = request.user,
                            presence = null,
                            trailing = { Text("Request sent", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Text("Friends", style = MaterialTheme.typography.titleLarge)
                }

                if (state.friends.isEmpty()) {
                    item {
                        Text(
                            "No friends yet.",
                            modifier = Modifier.padding(vertical = 18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.friends, key = { "friend-${it.id}" }) { friend ->
                        val invite = party.invite
                            ?.takeIf {
                                party.mode == PartyMode.HOST &&
                                    party.phase == PartyPhase.CONNECTED
                            }
                        SocialUserRow(
                            user = friend,
                            presence = state.presence[friend.id],
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        onOpenChat(friend)
                                        scope.launch { social.loadChat(friend.id) }
                                    }) {
                                        Text("Chat")
                                    }
                                    OutlinedButton(onClick = {
                                        statisticsFriend = friend
                                        scope.launch { social.loadFriendStatistics(friend.id) }
                                    }) {
                                        Text("Statistics")
                                    }
                                    if (state.presence[friend.id]?.online == true && party.mode != PartyMode.GUEST) {
                                        val isInviting = invitingUserId == friend.id
                                        val wasInvited = friend.id in invitedUserIds
                                        Button(onClick = {
                                            scope.launch {
                                                actionError = null
                                                invitingUserId = friend.id
                                                yield()
                                                try {
                                                    val partyInvite = invite ?: playTogether.startHosting()
                                                        .onFailure { actionError = it.message ?: "Could not start party" }
                                                        .getOrNull()
                                                    if (partyInvite != null) {
                                                        social.sendPartyInvite(friend.id, partyInvite)
                                                            .onSuccess { invitedUserIds = invitedUserIds + friend.id }
                                                            .onFailure { actionError = it.message ?: "Could not invite friend" }
                                                    }
                                                } finally {
                                                    invitingUserId = null
                                                }
                                            }
                                        }, enabled = !isInviting && !wasInvited) {
                                            if (isInviting) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                                Spacer(Modifier.size(8.dp))
                                                Text(if (invite != null) "Inviting…" else "Starting…")
                                            } else {
                                                Text(
                                                    when {
                                                        wasInvited -> "Invited"
                                                        invite != null -> "Invite"
                                                        else -> "Start party & invite"
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    TextButton(onClick = { scope.launch { social.removeFriend(friend.id) } }) {
                                        Text("Remove")
                                    }
                                }
                            },
                        )
                    }
                }

                val error = actionError ?: state.error
                if (!error.isNullOrBlank()) {
                    item {
                        Text(
                            error,
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
internal fun DesktopChatDock(
    friend: SocialUser,
    ownUserId: String,
    messages: List<SocialChatMessage>,
    loading: Boolean,
    services: DesktopMusicServices,
    playTogether: PlayTogetherManager,
    playlists: List<PlaylistItem>,
    social: DesktopSocialManager,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val playback by services.playback.state.collectAsState()
    val listState = rememberLazyListState()
    var collapsed by remember(friend.id) { mutableStateOf(false) }
    var text by remember(friend.id) { mutableStateOf("") }
    var sending by remember(friend.id) { mutableStateOf(false) }
    var playlistMenu by remember(friend.id) { mutableStateOf(false) }
    var error by remember(friend.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Card(
        modifier = modifier
            .requiredWidth(440.dp)
            .wrapContentHeight()
            .animateContentSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.10f),
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (collapsed) Modifier else Modifier.requiredHeight(340.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SocialAvatar(friend, Modifier.size(34.dp))
            Column(Modifier.weight(1f)) {
                Text(friend.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "@" + friend.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { collapsed = !collapsed }) {
                Icon(
                    if (collapsed) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (collapsed) "Expand chat" else "Collapse chat",
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close chat")
            }
        }
        if (collapsed) return@Column
        HorizontalDivider()

        when {
            loading && messages.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            messages.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No messages yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                items(messages, key = SocialChatMessage::id) { message ->
                    ChatMessageBubble(
                        message = message,
                        mine = message.senderId == ownUserId,
                        onPlaySong = { song ->
                            scope.launch {
                                if (!playTogether.playQueue(listOf(song.toPlaybackItem()), title = "Shared by " + friend.name)) {
                                    error = "Could not play shared song right now"
                                }
                            }
                        },
                        onPlayQueue = { queue ->
                            scope.launch {
                                if (queue.songs.isNotEmpty()) {
                                    if (!playTogether.playQueue(
                                        items = queue.songs.map(SocialSharedSong::toPlaybackItem),
                                        startIndex = queue.currentIndex.coerceIn(0, queue.songs.lastIndex),
                                        title = queue.title ?: friend.name + "'s queue",
                                    )) error = "Could not play shared queue right now"
                                }
                            }
                        },
                        onPlayPlaylist = { playlist ->
                            scope.launch {
                                services.browseRepository
                                    .playlist(DesktopBrowseRoute.Playlist(playlist.id))
                                    .onSuccess { page ->
                                        val items = page.songs.map(SongItem::toSharedPlaybackItem)
                                        if (items.isNotEmpty()) {
                                            if (!playTogether.playQueue(items, title = playlist.title)) {
                                                error = "Could not play shared playlist right now"
                                            }
                                        }
                                    }
                                    .onFailure { error = it.message ?: "Could not load shared playlist" }
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }

        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        playback.currentItem?.takeIf { it.source !is PlaybackSource.LocalFile }?.metadata?.let { metadata ->
                            scope.launch {
                                sending = true
                                social.sendSong(friend.id, metadata.toSharedSong())
                                    .onFailure { error = it.message }
                                sending = false
                            }
                        }
                    },
                    enabled = !sending && playback.currentItem?.source !is PlaybackSource.LocalFile && playback.currentItem != null,
                ) { Text("Song") }
                OutlinedButton(
                    onClick = {
                        val rows = playback.queueEntries.takeIf(List<*>::isNotEmpty)
                        val sharedRows = rows
                            ?.filter { it.item.source !is PlaybackSource.LocalFile }
                            ?.take(50)
                        val sharedItems = sharedRows?.map { it.item }
                            ?: playback.queue.filter { it.source !is PlaybackSource.LocalFile }.take(50)
                        if (sharedItems.isNotEmpty()) {
                            val currentIndex = sharedRows
                                ?.indexOfFirst { it.isCurrent }
                                ?.takeIf { it >= 0 }
                                ?: playback.currentIndex.coerceIn(0, sharedItems.lastIndex)
                            scope.launch {
                                sending = true
                                social.sendQueue(
                                    friend.id,
                                    SocialSharedQueue(
                                        title = playback.queueTitle,
                                        songs = sharedItems.map { it.metadata.toSharedSong() },
                                        currentIndex = currentIndex,
                                    ),
                                ).onFailure { error = it.message }
                                sending = false
                            }
                        }
                    },
                    enabled = !sending && playback.queue.any { it.source !is PlaybackSource.LocalFile },
                ) { Text("Queue") }
                Box {
                    OutlinedButton(
                        onClick = { playlistMenu = true },
                        enabled = !sending && playlists.isNotEmpty(),
                    ) { Text("Playlist") }
                    DropdownMenu(expanded = playlistMenu, onDismissRequest = { playlistMenu = false }) {
                        playlists.take(30).forEach { playlist ->
                            DropdownMenuItem(
                                text = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    playlistMenu = false
                                    scope.launch {
                                        sending = true
                                        social.sendPlaylist(
                                            friend.id,
                                            SocialSharedPlaylist(
                                                id = playlist.id,
                                                title = playlist.title,
                                                subtitle = playlist.author?.name ?: playlist.songCountText,
                                                artworkUrl = playlist.thumbnail,
                                            ),
                                        ).onFailure { error = it.message }
                                        sending = false
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(2000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message " + friend.name) },
                    maxLines = 4,
                )
                Button(
                    enabled = text.isNotBlank() && !sending,
                    onClick = {
                        val outgoing = text.trim()
                        if (outgoing.isEmpty()) return@Button
                        scope.launch {
                            sending = true
                            error = null
                            social.sendText(friend.id, outgoing)
                                .onSuccess { text = "" }
                                .onFailure { error = it.message }
                            sending = false
                        }
                    },
                ) {
                    if (sending) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Send")
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun ChatMessageBubble(
    message: SocialChatMessage,
    mine: Boolean,
    onPlaySong: (SocialSharedSong) -> Unit,
    onPlayQueue: (SocialSharedQueue) -> Unit,
    onPlayPlaylist: (SocialSharedPlaylist) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.72f),
            colors = CardDefaults.cardColors(
                containerColor = if (mine) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.12f)
                },
            ),
        ) {
            when (message.kind) {
                SocialChatMessageKind.TEXT -> Text(
                    message.text.orEmpty(),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                )
                SocialChatMessageKind.SONG -> message.song?.let { song ->
                    SharedMediaCard("Song", song.title, song.artist, song.artworkUrl, "Play") { onPlaySong(song) }
                }
                SocialChatMessageKind.QUEUE -> message.queue?.let { queue ->
                    SharedMediaCard(
                        "Queue",
                        queue.title ?: "Shared queue",
                        queue.songs.size.toString() + " songs",
                        queue.songs.firstOrNull()?.artworkUrl,
                        "Play queue",
                    ) { onPlayQueue(queue) }
                }
                SocialChatMessageKind.PLAYLIST -> message.playlist?.let { playlist ->
                    SharedMediaCard("Playlist", playlist.title, playlist.subtitle, playlist.artworkUrl, "Play") {
                        onPlayPlaylist(playlist)
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedMediaCard(
    type: String,
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(MaterialTheme.shapes.medium),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onClick) { Text(action) }
    }
}

private fun PlaybackMetadata.toSharedSong(): SocialSharedSong =
    SocialSharedSong(
        id = id,
        title = title,
        artist = artistText.takeIf(String::isNotBlank),
        artworkUrl = artworkUri,
    )

private fun SocialSharedSong.toPlaybackItem(): PlaybackItem =
    PlaybackItem(
        PlaybackMetadata(
            id = id,
            title = title,
            artists = artist?.takeIf(String::isNotBlank)?.let { listOf(PlaybackArtist(name = it)) }.orEmpty(),
            artworkUri = artworkUrl,
        ),
    )

private fun SongItem.toSharedPlaybackItem(): PlaybackItem =
    PlaybackItem(
        PlaybackMetadata(
            id = id,
            title = title,
            artists = artists.map { PlaybackArtist(it.id, it.name) },
            artworkUri = thumbnail,
            durationHintMs = duration?.times(1000L),
            explicit = explicit,
        ),
    )

@Composable
private fun FriendStatisticsView(
    friend: SocialUser,
    snapshot: moe.rukamori.archivetune.desktop.social.SocialStatisticsSnapshot?,
    loading: Boolean,
    selectedRange: DesktopStatisticsRange,
    onRangeSelected: (DesktopStatisticsRange) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            SocialAvatar(friend, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text("${friend.name}'s statistics", style = MaterialTheme.typography.titleLarge)
                Text("@${friend.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onRefresh, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (loading) "Loading…" else "Refresh")
            }
        }

        val rangeSnapshot = snapshot?.ranges?.get(selectedRange.name)
        when {
            rangeSnapshot != null -> DesktopStatisticsSurface(
                model = rangeSnapshot.toDesktopStatisticsUiModel(),
                selectedRange = selectedRange,
                onRangeSelected = onRangeSelected,
                onPlaySong = {},
                onOpenSong = {},
                onOpenArtist = {},
                onOpenAlbum = {},
                modifier = Modifier.fillMaxSize(),
            )
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No shared statistics available yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IncomingPartyInvite(
    invite: SocialPartyInvite,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SocialAvatar(invite.sender, Modifier.size(44.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("${invite.sender.name} invited you to listen together", style = MaterialTheme.typography.titleMedium)
                Text("Live ArchiveTune party", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            Button(onClick = onJoin) { Text("Join") }
        }
    }
}

@Composable
private fun SocialUserRow(
    user: SocialUser,
    presence: SocialPresence?,
    trailing: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SocialAvatar(user, Modifier.size(44.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(user.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (presence?.online == true) "  • online" else "  • offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (presence?.online == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when {
                        presence?.online == true && !presence.title.isNullOrBlank() ->
                            "Listening to ${presence.title}" +
                                presence.artist?.takeIf(String::isNotBlank)?.let { " — $it" }.orEmpty()
                        else -> "@${user.username}"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
        }
    }
}

@Composable
private fun SocialAvatar(
    user: SocialUser,
    modifier: Modifier = Modifier,
) {
    if (!user.avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = user.avatarUrl,
            contentDescription = user.name,
            modifier = modifier.clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier.clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(user.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
        }
    }
}
