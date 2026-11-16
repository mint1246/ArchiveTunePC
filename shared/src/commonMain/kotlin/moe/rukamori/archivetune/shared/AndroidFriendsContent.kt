/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AndroidFriendsContent(controller: MusicController, modifier: Modifier = Modifier) {
    val social = controller.social
    val state = social?.state?.collectAsState()?.value ?: MusicSocialState()
    when {
        state.signingIn -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Finish signing in with Discord in your browser")
            }
        }
        state.account == null -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Friends", style = MaterialTheme.typography.headlineMedium)
                    Text("Sign in with Discord to add ArchiveTune friends, see what they're listening to and message them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { social?.signIn() }, enabled = social != null) { Text("Sign in with Discord") }
                }
            }
        }
        else -> LazyColumn(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(state.account.name, style = MaterialTheme.typography.titleLarge); Text("@${state.account.username}") }
                    TextButton(onClick = { social?.refresh() }) { Text("Refresh") }
                }
                TextButton(onClick = { social?.signOut() }) { Text("Sign out") }
                Text("Friends", style = MaterialTheme.typography.titleLarge)
            }
            items(state.friends, key = { it.id }) { friend ->
                Column { Text(friend.name); Text("@${friend.username}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
