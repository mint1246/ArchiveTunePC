/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.social.AndroidSocialManager
import moe.rukamori.archivetune.social.SocialAccountState
import moe.rukamori.archivetune.social.SocialChatMessage
import moe.rukamori.archivetune.social.SocialChatMessageKind
import moe.rukamori.archivetune.social.SocialPartyInvite
import moe.rukamori.archivetune.social.SocialPresence
import moe.rukamori.archivetune.social.SocialSharedPlaylist
import moe.rukamori.archivetune.social.SocialSharedQueue
import moe.rukamori.archivetune.social.SocialSharedSong
import moe.rukamori.archivetune.social.SocialStatisticsMedia
import moe.rukamori.archivetune.social.SocialUser

/**
 * Standalone Android social surface. The app navigation owner only needs to route to this composable;
 * playback, party and statistics actions are deliberately callbacks so the social feature does not
 * own global navigation or playback state.
 */
@Composable
fun ArchiveTuneFriendsScreen(
    social: AndroidSocialManager,
    modifier: Modifier = Modifier,
    currentSong: SocialSharedSong? = null,
    currentQueue: SocialSharedQueue? = null,
    playlists: List<SocialSharedPlaylist> = emptyList(),
    activePartyInviteUrl: String? = null,
    onStartParty: (suspend () -> Result<String>)? = null,
    onJoinParty: (suspend (String) -> Result<Unit>)? = null,
    onPlaySharedSong: (SocialSharedSong) -> Unit = {},
    onPlaySharedQueue: (SocialSharedQueue) -> Unit = {},
    onOpenSharedPlaylist: (SocialSharedPlaylist) -> Unit = {},
    onOpenFriendStatistics: ((SocialUser) -> Unit)? = null,
    onOpenChat: ((SocialUser) -> Unit)? = null,
) {
    val state by social.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedFriend by remember { mutableStateOf<SocialUser?>(null) }

    val friend = selectedFriend
    LaunchedEffect(friend?.id, state.friends) {
        if (friend != null && state.friends.none { it.id == friend.id }) selectedFriend = null
    }

    if (friend != null) {
        ArchiveTuneChatScreen(
            friend = friend,
            ownUserId = (state.account as? SocialAccountState.SignedIn)?.user?.id.orEmpty(),
            messages = state.chats[friend.id].orEmpty(),
            loading = friend.id in state.loadingChats,
            social = social,
            currentSong = currentSong,
            currentQueue = currentQueue,
            playlists = playlists,
            onBack = { selectedFriend = null },
            onPlaySharedSong = onPlaySharedSong,
            onPlaySharedQueue = onPlaySharedQueue,
            onOpenSharedPlaylist = onOpenSharedPlaylist,
            modifier = modifier,
        )
        return
    }

    when (val account = state.account) {
        SocialAccountState.SignedOut -> {
            Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Friends", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Sign in with Discord to add ArchiveTune friends, see what they're listening to and message them.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
            FriendsList(
                social = social,
                account = account.user,
                state = state,
                activePartyInviteUrl = activePartyInviteUrl,
                onStartParty = onStartParty,
                onJoinParty = onJoinParty,
                onOpenChat = { selected ->
                    if (onOpenChat != null) {
                        onOpenChat(selected)
                    } else {
                        selectedFriend = selected
                        scope.launch { social.loadChat(selected.id) }
                    }
                },
                onOpenFriendStatistics = onOpenFriendStatistics,
                modifier = modifier,
            )
        }
    }
}

@Composable
fun ArchiveTuneChatRoute(
    social: AndroidSocialManager,
    friendId: String,
    modifier: Modifier = Modifier,
    currentSong: SocialSharedSong? = null,
    currentQueue: SocialSharedQueue? = null,
    playlists: List<SocialSharedPlaylist> = emptyList(),
    onBack: () -> Unit,
    onPlaySharedSong: (SocialSharedSong) -> Unit = {},
    onPlaySharedQueue: (SocialSharedQueue) -> Unit = {},
    onOpenSharedPlaylist: (SocialSharedPlaylist) -> Unit = {},
) {
    val state by social.state.collectAsState()
    val friend = remember(friendId, state.friends) { state.friends.firstOrNull { it.id == friendId } }

    LaunchedEffect(friendId, friend?.id) {
        if (friend != null) social.loadChat(friendId)
    }

    when {
        friend != null -> ArchiveTuneChatScreen(
            friend = friend,
            ownUserId = (state.account as? SocialAccountState.SignedIn)?.user?.id.orEmpty(),
            messages = state.chats[friend.id].orEmpty(),
            loading = friend.id in state.loadingChats,
            social = social,
            currentSong = currentSong,
            currentQueue = currentQueue,
            playlists = playlists,
            onBack = onBack,
            onPlaySharedSong = onPlaySharedSong,
            onPlaySharedQueue = onPlaySharedQueue,
            onOpenSharedPlaylist = onOpenSharedPlaylist,
            modifier = modifier,
        )

        state.account is SocialAccountState.SignedOut -> Box(
            modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Sign in to ArchiveTune friends first.")
        }

        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun ArchiveTuneFriendStatisticsRoute(
    social: AndroidSocialManager,
    friendId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlaySong: (SocialStatisticsMedia) -> Unit = {},
    onOpenArtist: (SocialStatisticsMedia) -> Unit = {},
    onOpenAlbum: (SocialStatisticsMedia) -> Unit = {},
) {
    val state by social.state.collectAsState()
    val scope = rememberCoroutineScope()
    val friend = state.friends.firstOrNull { it.id == friendId }
    val snapshot = state.friendStatistics[friendId]
    val loading = friendId in state.loadingFriendStatistics
    var rangeKey by remember(friendId) { mutableStateOf("DAYS_7") }

    LaunchedEffect(friendId, friend?.id) {
        if (friend != null && snapshot == null) social.loadFriendStatistics(friendId)
    }

    val ranges = listOf(
        "DAYS_7" to "7 days",
        "MONTH_1" to "1 month",
        "MONTHS_3" to "3 months",
        "MONTHS_6" to "6 months",
        "YEAR_1" to "1 year",
        "ALL_TIME" to "All time",
    )
    val availableKey = rangeKey.takeIf { snapshot?.ranges?.containsKey(it) == true }
        ?: ranges.firstOrNull { snapshot?.ranges?.containsKey(it.first) == true }?.first
        ?: snapshot?.ranges?.keys?.firstOrNull()
    val range = availableKey?.let { snapshot?.ranges?.get(it) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                Column(Modifier.weight(1f)) {
                    Text(
                        friend?.let { "${it.name}'s statistics" } ?: "Friend statistics",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    friend?.let { Text("@${it.username}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                TextButton(
                    enabled = friend != null && !loading,
                    onClick = { if (friend != null) scope.launch { social.loadFriendStatistics(friendId) } },
                ) { Text("Refresh") }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ranges.forEach { (key, label) ->
                    TextButton(
                        onClick = { rangeKey = key },
                        enabled = snapshot?.ranges?.containsKey(key) == true,
                    ) { Text(if (availableKey == key) "• $label" else label) }
                }
            }
        }

        when {
            loading && range == null -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            range == null -> item {
                Text(
                    state.error ?: "No shared statistics available yet.",
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            else -> {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(formatSocialListeningTime(range.summary.totalListeningMillis), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "${range.summary.totalPlays} plays · ${range.summary.uniqueSongs} songs · ${range.summary.uniqueArtists} artists",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (range.topSongs.isNotEmpty()) {
                    item { Text("Top songs", style = MaterialTheme.typography.titleMedium) }
                    items(range.topSongs.take(10), key = { "stats-song-${it.id}" }) { media ->
                        StatisticsMediaRow(media, "Play") { onPlaySong(media) }
                    }
                }
                if (range.topArtists.isNotEmpty()) {
                    item { Text("Top artists", style = MaterialTheme.typography.titleMedium) }
                    items(range.topArtists.take(10), key = { "stats-artist-${it.id}" }) { media ->
                        StatisticsMediaRow(media, "Open") { onOpenArtist(media) }
                    }
                }
                if (range.topAlbums.isNotEmpty()) {
                    item { Text("Top albums", style = MaterialTheme.typography.titleMedium) }
                    items(range.topAlbums.take(10), key = { "stats-album-${it.id}" }) { media ->
                        StatisticsMediaRow(media, "Open") { onOpenAlbum(media) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun StatisticsMediaRow(
    media: SocialStatisticsMedia,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            media.artworkUrl?.takeIf(String::isNotBlank)?.let {
                AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(46.dp).clip(MaterialTheme.shapes.medium))
            }
            Column(Modifier.weight(1f)) {
                Text(media.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                media.subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}

private fun formatSocialListeningTime(millis: Long): String {
    val minutes = (millis.coerceAtLeast(0L) / 60_000L)
    val hours = minutes / 60L
    val remainingMinutes = minutes % 60L
    return if (hours > 0) "$hours h $remainingMinutes min" else "$minutes min"
}

@Composable
private fun FriendsList(
    social: AndroidSocialManager,
    account: SocialUser,
    state: moe.rukamori.archivetune.social.AndroidSocialState,
    activePartyInviteUrl: String?,
    onStartParty: (suspend () -> Result<String>)?,
    onJoinParty: (suspend (String) -> Result<Unit>)?,
    onOpenChat: (SocialUser) -> Unit,
    onOpenFriendStatistics: ((SocialUser) -> Unit)?,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SocialUser>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busyUserId by remember { mutableStateOf<String?>(null) }
    var invitedUserIds by remember(activePartyInviteUrl) { mutableStateOf<Set<String>>(emptySet()) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SocialAvatar(account, Modifier.size(46.dp))
                Column(Modifier.weight(1f)) {
                    Text(account.name, style = MaterialTheme.typography.titleLarge)
                    Text("@${account.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { scope.launch { social.refresh() } }, enabled = !state.refreshing) {
                    Text(if (state.refreshing) "Refreshing…" else "Refresh")
                }
            }
            TextButton(onClick = { scope.launch { social.logout() } }) { Text("Sign out") }
        }

        state.partyInvites.firstOrNull()?.let { invite ->
            item(key = "party-${invite.partyUrl}") {
                IncomingPartyInvite(
                    invite = invite,
                    joinEnabled = onJoinParty != null,
                    onJoin = {
                        val join = onJoinParty
                        if (join != null) {
                            scope.launch {
                                actionError = null
                                join(invite.partyUrl)
                                    .onSuccess { social.dismissPartyInvite(invite) }
                                    .onFailure { actionError = it.message ?: "Could not join party" }
                            }
                        }
                    },
                    onDismiss = { social.dismissPartyInvite(invite) },
                )
            }
        }

        item {
            Text("Add friend", style = MaterialTheme.typography.titleLarge)
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
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
                ) { Text(if (searching) "Searching…" else "Search") }
            }
        }

        items(results, key = { "search-${it.id}" }) { user ->
            SocialUserCard(user = user, presence = state.presence[user.id]) {
                Button(onClick = {
                    scope.launch {
                        social.sendFriendRequest(user.id)
                            .onSuccess { results = results.filterNot { it.id == user.id } }
                            .onFailure { actionError = it.message }
                    }
                }) { Text("Add") }
            }
        }

        if (state.incomingRequests.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Friend requests", style = MaterialTheme.typography.titleLarge)
            }
            items(state.incomingRequests, key = { "incoming-${it.user.id}" }) { request ->
                SocialUserCard(request.user, state.presence[request.user.id]) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { social.declineFriendRequest(request.user.id) } }) {
                            Text("Decline")
                        }
                        Button(onClick = { scope.launch { social.acceptFriendRequest(request.user.id) } }) {
                            Text("Accept")
                        }
                    }
                }
            }
        }

        if (state.outgoingRequests.isNotEmpty()) {
            item { Text("Pending", style = MaterialTheme.typography.titleMedium) }
            items(state.outgoingRequests, key = { "outgoing-${it.user.id}" }) { request ->
                SocialUserCard(request.user, null) {
                    Text("Request sent", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Friends", style = MaterialTheme.typography.titleLarge)
        }

        if (state.friends.isEmpty()) {
            item {
                Text(
                    "No friends yet.",
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.friends, key = { "friend-${it.id}" }) { friend ->
                val presence = state.presence[friend.id]
                SocialUserCard(user = friend, presence = presence) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onOpenChat(friend) }) { Text("Chat") }
                            onOpenFriendStatistics?.let { openStats ->
                                OutlinedButton(onClick = { openStats(friend) }) { Text("Stats") }
                            }
                        }
                        if (presence?.online == true && (activePartyInviteUrl != null || onStartParty != null)) {
                            val invited = friend.id in invitedUserIds
                            Button(
                                enabled = busyUserId != friend.id && !invited,
                                onClick = {
                                    scope.launch {
                                        busyUserId = friend.id
                                        actionError = null
                                        try {
                                            val partyUrl = activePartyInviteUrl
                                                ?: onStartParty?.invoke()?.getOrElse {
                                                    actionError = it.message ?: "Could not start party"
                                                    return@launch
                                                }
                                            if (partyUrl != null) {
                                                social.sendPartyInvite(friend.id, partyUrl)
                                                    .onSuccess { invitedUserIds = invitedUserIds + friend.id }
                                                    .onFailure { actionError = it.message ?: "Could not invite friend" }
                                            }
                                        } finally {
                                            busyUserId = null
                                        }
                                    }
                                },
                            ) {
                                if (busyUserId == friend.id) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(if (invited) "Invited" else "Invite")
                            }
                        }
                        TextButton(onClick = { scope.launch { social.removeFriend(friend.id) } }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }

        val error = actionError ?: state.error
        if (!error.isNullOrBlank()) {
            item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ArchiveTuneChatScreen(
    friend: SocialUser,
    ownUserId: String,
    messages: List<SocialChatMessage>,
    loading: Boolean,
    social: AndroidSocialManager,
    currentSong: SocialSharedSong?,
    currentQueue: SocialSharedQueue?,
    playlists: List<SocialSharedPlaylist>,
    onBack: () -> Unit,
    onPlaySharedSong: (SocialSharedSong) -> Unit,
    onPlaySharedQueue: (SocialSharedQueue) -> Unit,
    onOpenSharedPlaylist: (SocialSharedPlaylist) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var text by remember(friend.id) { mutableStateOf("") }
    var sending by remember(friend.id) { mutableStateOf(false) }
    var playlistMenu by remember(friend.id) { mutableStateOf(false) }
    var error by remember(friend.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }

    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Bottom)
                    .union(WindowInsets.ime),
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            SocialAvatar(friend, Modifier.size(38.dp))
            Column(Modifier.weight(1f)) {
                Text(friend.name, style = MaterialTheme.typography.titleMedium)
                Text("@${friend.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(Modifier.height(6.dp)) }
                items(messages, key = SocialChatMessage::id) { message ->
                    ChatMessageBubble(
                        message = message,
                        mine = message.senderId == ownUserId,
                        onPlaySharedSong = onPlaySharedSong,
                        onPlaySharedQueue = onPlaySharedQueue,
                        onOpenSharedPlaylist = onOpenSharedPlaylist,
                    )
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = currentSong != null && !sending,
                    onClick = {
                        val song = currentSong ?: return@OutlinedButton
                        scope.launch {
                            sending = true
                            social.sendSong(friend.id, song).onFailure { error = it.message }
                            sending = false
                        }
                    },
                ) { Text("Song") }
                OutlinedButton(
                    enabled = currentQueue?.songs?.isNotEmpty() == true && !sending,
                    onClick = {
                        val queue = currentQueue ?: return@OutlinedButton
                        scope.launch {
                            sending = true
                            social.sendQueue(friend.id, queue).onFailure { error = it.message }
                            sending = false
                        }
                    },
                ) { Text("Queue") }
                Box {
                    OutlinedButton(
                        enabled = playlists.isNotEmpty() && !sending,
                        onClick = { playlistMenu = true },
                    ) { Text("Playlist") }
                    DropdownMenu(expanded = playlistMenu, onDismissRequest = { playlistMenu = false }) {
                        playlists.take(30).forEach { playlist ->
                            DropdownMenuItem(
                                text = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    playlistMenu = false
                                    scope.launch {
                                        sending = true
                                        social.sendPlaylist(friend.id, playlist).onFailure { error = it.message }
                                        sending = false
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(2_000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message ${friend.name}") },
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
                    if (sending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Send")
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: SocialChatMessage,
    mine: Boolean,
    onPlaySharedSong: (SocialSharedSong) -> Unit,
    onPlaySharedQueue: (SocialSharedQueue) -> Unit,
    onOpenSharedPlaylist: (SocialSharedPlaylist) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.82f),
            colors = CardDefaults.cardColors(
                containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            when (message.kind) {
                SocialChatMessageKind.TEXT -> Text(
                    message.text.orEmpty(),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
                SocialChatMessageKind.SONG -> message.song?.let { song ->
                    SharedMediaCard("Song", song.title, song.artist, song.artworkUrl, "Play") { onPlaySharedSong(song) }
                }
                SocialChatMessageKind.QUEUE -> message.queue?.let { queue ->
                    SharedMediaCard(
                        "Queue",
                        queue.title ?: "Shared queue",
                        "${queue.songs.size} songs",
                        queue.songs.firstOrNull()?.artworkUrl,
                        "Play",
                    ) { onPlaySharedQueue(queue) }
                }
                SocialChatMessageKind.PLAYLIST -> message.playlist?.let { playlist ->
                    SharedMediaCard("Playlist", playlist.title, playlist.subtitle, playlist.artworkUrl, "Open") {
                        onOpenSharedPlaylist(playlist)
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
        Modifier.fillMaxWidth().padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        artworkUrl?.takeIf(String::isNotBlank)?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.medium),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun IncomingPartyInvite(
    invite: SocialPartyInvite,
    joinEnabled: Boolean,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SocialAvatar(invite.sender, Modifier.size(42.dp))
                Column(Modifier.weight(1f)) {
                    Text("${invite.sender.name} invited you to listen together", style = MaterialTheme.typography.titleMedium)
                    Text("Live ArchiveTune party", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                Button(onClick = onJoin, enabled = joinEnabled) { Text("Join") }
            }
        }
    }
}

@Composable
private fun SocialUserCard(
    user: SocialUser,
    presence: SocialPresence?,
    actions: @Composable () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SocialAvatar(user, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(user.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            presence?.online == true && !presence.title.isNullOrBlank() ->
                                "Listening to ${presence.title}" + presence.artist?.takeIf(String::isNotBlank)?.let { " — $it" }.orEmpty()
                            presence?.online == true -> "Online · @${user.username}"
                            else -> "Offline · @${user.username}"
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
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
        Box(modifier.clip(CircleShape), contentAlignment = Alignment.Center) {
            Text(user.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
        }
    }
}
