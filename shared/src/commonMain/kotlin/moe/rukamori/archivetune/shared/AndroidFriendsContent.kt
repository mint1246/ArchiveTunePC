/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.resources.*
import moe.rukamori.archivetune.social.AndroidSocialState
import moe.rukamori.archivetune.social.SocialAccountState
import moe.rukamori.archivetune.social.SocialChatMessage
import moe.rukamori.archivetune.social.SocialChatMessageKind
import moe.rukamori.archivetune.social.SocialSharedPlaylist
import moe.rukamori.archivetune.social.SocialSharedQueue
import moe.rukamori.archivetune.social.SocialSharedSong
import moe.rukamori.archivetune.social.SocialUser
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun AndroidFriendsContent(controller: MusicController, modifier: Modifier = Modifier) {
    val social = controller.social
    val sdkState = social?.sdkState?.collectAsState()?.value ?: AndroidSocialState()
    val scope = rememberCoroutineScope()

    var activeChatFriend by remember { mutableStateOf<SocialUser?>(null) }
    var activeStatsFriend by remember { mutableStateOf<SocialUser?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SocialUser>>(emptyList()) }
    var searchingUsers by remember { mutableStateOf(false) }

    when (val account = sdkState.account) {
        SocialAccountState.SigningIn -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Finish signing in with Discord in your browser")
            }
        }
        SocialAccountState.SignedOut -> Box(
            modifier.fillMaxSize().padding(top = LocalAndroidHeaderInset.current, start = 24.dp, end = 24.dp, bottom = 140.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(Modifier.size(56.dp), shape = CircleShape, color = Color(0xFF5865F2).copy(alpha = 0.16f)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(Res.drawable.discord), null, Modifier.size(28.dp), tint = Color(0xFF5865F2))
                        }
                    }
                    Text("Friends", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Sign in with Discord to add ArchiveTune friends, see what they're listening to, view their stats, and share songs or queues in direct chat.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    sdkState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        onClick = { social?.signIn() },
                        enabled = social != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(Res.drawable.discord), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sign in with Discord")
                    }
                }
            }
        }
        is SocialAccountState.SignedIn -> {
            val chatFriend = activeChatFriend
            val statsFriend = activeStatsFriend
            when {
                chatFriend != null && social != null -> FriendDirectChatView(
                    ownUser = account.user,
                    friend = chatFriend,
                    sdkState = sdkState,
                    social = social,
                    controller = controller,
                    onBack = { activeChatFriend = null },
                    modifier = modifier,
                )
                statsFriend != null && social != null -> FriendStatisticsView(
                    friend = statsFriend,
                    sdkState = sdkState,
                    social = social,
                    controller = controller,
                    onBack = { activeStatsFriend = null },
                    modifier = modifier,
                )
                else -> LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = LocalAndroidHeaderInset.current + 8.dp, start = 16.dp, end = 16.dp, bottom = 190.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Own profile card
                    item(key = "own_profile") {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SocialAvatar(account.user, 48.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(account.user.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("@${account.user.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { social?.refresh() }) {
                                    Icon(painterResource(Res.drawable.sync), "Refresh")
                                }
                                IconButton(onClick = { social?.signOut() }) {
                                    Icon(painterResource(Res.drawable.logout), "Sign out")
                                }
                            }
                        }
                    }

                    sdkState.error?.let { err ->
                        item(key = "social_err") {
                            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }

                    // User search / Add friend
                    item(key = "friend_search") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Add friend by username") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(18.dp),
                            )
                            FilledTonalButton(
                                enabled = searchQuery.isNotBlank() && !searchingUsers && social != null,
                                onClick = {
                                    searchingUsers = true
                                    scope.launch {
                                        social?.searchUsers(searchQuery)?.onSuccess { searchResults = it }
                                        searchingUsers = false
                                    }
                                },
                            ) { Text(if (searchingUsers) "..." else "Find") }
                        }
                    }

                    if (searchResults.isNotEmpty()) {
                        item(key = "search_results_hdr") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Search results", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = { searchResults = emptyList(); searchQuery = "" }) { Text("Clear") }
                            }
                        }
                        items(searchResults, key = { "sr:${it.id}" }) { user ->
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SocialAvatar(user, 40.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(user.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("@${user.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    FilledTonalButton(onClick = {
                                        scope.launch {
                                            social?.sendFriendRequest(user.id)
                                            searchResults = searchResults.filterNot { it.id == user.id }
                                        }
                                    }) { Text("Add") }
                                }
                            }
                        }
                    }

                    // Incoming requests
                    if (sdkState.incomingRequests.isNotEmpty()) {
                        item(key = "incoming_hdr") {
                            Text("Friend requests (${sdkState.incomingRequests.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        items(sdkState.incomingRequests, key = { "in:${it.user.id}" }) { req ->
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SocialAvatar(req.user, 42.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(req.user.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("@${req.user.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    FilledTonalIconButton(onClick = { scope.launch { social?.acceptFriendRequest(req.user.id) } }) {
                                        Icon(painterResource(Res.drawable.check), "Accept")
                                    }
                                    IconButton(onClick = { scope.launch { social?.declineFriendRequest(req.user.id) } }) {
                                        Icon(painterResource(Res.drawable.close), "Decline")
                                    }
                                }
                            }
                        }
                    }

                    // Outgoing requests
                    if (sdkState.outgoingRequests.isNotEmpty()) {
                        item(key = "outgoing_hdr") {
                            Text("Sent requests", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(sdkState.outgoingRequests, key = { "out:${it.user.id}" }) { req ->
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SocialAvatar(req.user, 38.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(req.user.name, fontWeight = FontWeight.Medium)
                                        Text("Pending • @${req.user.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    // Friends list
                    item(key = "friends_hdr") {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Friends (${sdkState.friends.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (sdkState.refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (sdkState.friends.isEmpty()) {
                        item(key = "friends_empty") {
                            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "No friends yet. Search by username above to send a friend request!",
                                    Modifier.padding(20.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(sdkState.friends, key = { it.id }) { friend ->
                            val presence = sdkState.presence[friend.id]
                            var overflow by remember { mutableStateOf(false) }
                            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Box {
                                            SocialAvatar(friend, 44.dp)
                                            if (presence?.online == true) {
                                                Box(
                                                    Modifier.size(12.dp)
                                                        .align(Alignment.BottomEnd)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF4CAF50)),
                                                )
                                            }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(friend.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("@${friend.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { activeChatFriend = friend }) {
                                            Icon(painterResource(Res.drawable.share), "Chat & Share")
                                        }
                                        IconButton(onClick = { activeStatsFriend = friend }) {
                                            Icon(painterResource(Res.drawable.stats), "Friend statistics")
                                        }
                                        Box {
                                            IconButton(onClick = { overflow = true }) {
                                                Icon(painterResource(Res.drawable.more_vert), "More")
                                            }
                                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                                DropdownMenuItem(text = { Text("Direct chat") }, onClick = { overflow = false; activeChatFriend = friend })
                                                DropdownMenuItem(text = { Text("Listening stats") }, onClick = { overflow = false; activeStatsFriend = friend })
                                                DropdownMenuItem(text = { Text("Remove friend") }, onClick = {
                                                    overflow = false
                                                    scope.launch { social?.removeFriend(friend.id) }
                                                })
                                                DropdownMenuItem(text = { Text("Block user") }, onClick = {
                                                    overflow = false
                                                    scope.launch { social?.blockUser(friend.id) }
                                                })
                                            }
                                        }
                                    }
                                    if (!presence?.songId.isNullOrBlank() && !presence?.title.isNullOrBlank()) {
                                        Surface(
                                            onClick = {
                                                controller.playTrack(
                                                    MusicTrack(
                                                        id = presence!!.songId!!,
                                                        title = presence.title!!,
                                                        artist = presence.artist.orEmpty(),
                                                        artworkUrl = presence.artworkUrl,
                                                    ),
                                                )
                                            },
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                CatalogArtwork(presence?.artworkUrl, Modifier.size(40.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text("Listening now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                    Text(presence?.title.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    presence?.artist?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                                }
                                                Icon(painterResource(Res.drawable.play), "Play", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
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
private fun FriendDirectChatView(
    ownUser: SocialUser,
    friend: SocialUser,
    sdkState: AndroidSocialState,
    social: MusicSocialController,
    controller: MusicController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val playback by controller.playback.collectAsState()
    val uiState by controller.state.collectAsState()
    val messages = sdkState.chats[friend.id].orEmpty()
    val loading = friend.id in sdkState.loadingChats
    var draft by remember { mutableStateOf("") }
    var shareMenu by remember { mutableStateOf(false) }

    LaunchedEffect(friend.id) { social.loadChat(friend.id) }

    Column(modifier.fillMaxSize().padding(top = LocalAndroidHeaderInset.current, bottom = 110.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.arrow_back), "Back") }
            SocialAvatar(friend, 36.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(friend.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${friend.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { scope.launch { social.loadChat(friend.id) } }) {
                Icon(painterResource(Res.drawable.sync), "Refresh chat")
            }
        }
        if (loading && messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Say hello or share a song!", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(messages, key = { it.id }) { msg ->
                    val mine = msg.senderId == ownUser.id
                    ChatBubble(msg, mine, controller)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                IconButton(onClick = { shareMenu = true }) { Icon(painterResource(Res.drawable.add_circle), "Share music") }
                DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                    val currentTrack = playback.track
                    DropdownMenuItem(
                        text = { Text("Share current song") },
                        enabled = currentTrack != null,
                        onClick = {
                            shareMenu = false
                            currentTrack?.let { t ->
                                scope.launch {
                                    social.sendSong(friend.id, SocialSharedSong(t.id, t.title, t.artist, t.artworkUrl))
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Share current queue (${uiState.queue.size})") },
                        enabled = uiState.queue.isNotEmpty(),
                        onClick = {
                            shareMenu = false
                            scope.launch {
                                social.sendQueue(
                                    friend.id,
                                    SocialSharedQueue(
                                        title = uiState.queueTitle,
                                        songs = uiState.queue.take(50).map { SocialSharedSong(it.id, it.title, it.artist, it.artworkUrl) },
                                        currentIndex = uiState.queueIndex.coerceAtLeast(0),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message ${friend.name}") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    val text = draft
                    draft = ""
                    scope.launch { social.sendText(friend.id, text) }
                },
            ) {
                Icon(painterResource(Res.drawable.arrow_forward), "Send")
            }
        }
    }
}

@Composable
private fun ChatBubble(message: SocialChatMessage, mine: Boolean, controller: MusicController) {
    val alignment = if (mine) Alignment.End else Alignment.Start
    val container = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Surface(shape = RoundedCornerShape(20.dp), color = container, modifier = Modifier.widthIn(max = 300.dp)) {
            when (message.kind) {
                SocialChatMessageKind.TEXT -> Text(message.text.orEmpty(), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = content)
                SocialChatMessageKind.SONG -> message.song?.let { song ->
                    Row(
                        Modifier.clickable {
                            controller.playTrack(MusicTrack(song.id, song.title, song.artist.orEmpty(), song.artworkUrl))
                        }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CatalogArtwork(song.artworkUrl, Modifier.size(44.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Shared song", style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f))
                            Text(song.title, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            song.artist?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.8f), maxLines = 1) }
                        }
                        Icon(painterResource(Res.drawable.play), "Play", Modifier.size(20.dp), tint = content)
                    }
                }
                SocialChatMessageKind.QUEUE -> message.queue?.let { queue ->
                    Column(
                        Modifier.clickable {
                            val tracks = queue.songs.map { MusicTrack(it.id, it.title, it.artist.orEmpty(), it.artworkUrl) }
                            if (tracks.isNotEmpty()) controller.playAt(queue.currentIndex.coerceIn(0, tracks.lastIndex), tracks)
                        }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Shared queue (${queue.songs.size} tracks)", style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.75f))
                        Text(queue.title ?: queue.songs.firstOrNull()?.title.orEmpty(), fontWeight = FontWeight.SemiBold, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                SocialChatMessageKind.PLAYLIST -> message.playlist?.let { pl ->
                    Row(
                        Modifier.clickable {
                            controller.openBrowse(MusicCatalogItem.Browse(pl.id, pl.title, pl.subtitle, pl.artworkUrl, MusicBrowseKind.PLAYLIST))
                        }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CatalogArtwork(pl.artworkUrl, Modifier.size(44.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Shared playlist", style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f))
                            Text(pl.title, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendStatisticsView(
    friend: SocialUser,
    sdkState: AndroidSocialState,
    social: MusicSocialController,
    controller: MusicController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = sdkState.friendStatistics[friend.id]
    val loading = friend.id in sdkState.loadingFriendStatistics
    LaunchedEffect(friend.id) { social.loadFriendStatistics(friend.id) }
    val keys = remember(snapshot) { snapshot?.ranges?.keys?.toList().orEmpty() }
    var selectedKey by remember(keys) { mutableStateOf(keys.firstOrNull()) }

    Column(modifier.fillMaxSize().padding(top = LocalAndroidHeaderInset.current)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(Res.drawable.arrow_back), "Back") }
            Text("${friend.name}'s Stats", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (loading && snapshot == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (snapshot == null || keys.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No public statistics available for ${friend.name}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val active = snapshot.ranges[selectedKey] ?: snapshot.ranges.values.first()
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 190.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        keys.forEach { key ->
                            FilterChip(selected = selectedKey == key, onClick = { selectedKey = key }, label = { Text(key) })
                        }
                    }
                }
                item {
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${active.summary.totalListeningMillis / 60_000}m", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Listening", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${active.summary.totalPlays}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Plays", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${active.summary.uniqueSongs}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Songs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (active.topSongs.isNotEmpty()) {
                    item { Text("Top songs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(active.topSongs.take(15), key = { it.id }) { song ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { controller.playTrack(MusicTrack(song.id, song.title, song.subtitle.orEmpty(), song.artworkUrl)) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CatalogArtwork(song.artworkUrl, Modifier.size(48.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(song.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${song.subtitle.orEmpty()} • ${song.playCount} plays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SocialAvatar(user: SocialUser, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (!user.avatarUrl.isNullOrBlank()) {
            AsyncImage(user.avatarUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(user.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
