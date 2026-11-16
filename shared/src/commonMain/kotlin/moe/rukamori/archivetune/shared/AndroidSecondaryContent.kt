package moe.rukamori.archivetune.shared

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import moe.rukamori.archivetune.shared.resources.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun AndroidSettingsContent(controller: MusicController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val library = controller.library?.collectAsState()?.value
    val options by controller.playbackOptions.collectAsState()
    var page by rememberSaveable { mutableStateOf("Settings") }
    Column(modifier.fillMaxSize()) {
        RouteHeading(page) { if (page == "Settings") onBack() else page = "Settings" }
        AnimatedContent(page, label = "settingsRoute", transitionSpec = {
            val pop = targetState == "Settings"
            (fadeIn(tween(250)) + slideInHorizontally { if (pop) -it / 2 else it / 2 }) togetherWith
                (fadeOut(tween(200)) + slideOutHorizontally { if (pop) it / 2 else -it / 2 })
        }) { displayedPage ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp)) {
            when (displayedPage) {
                "Settings" -> {
                    item { Text("Personalization", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 12.dp)) }
                    item { SecondarySettingsRow("Appearance", "Theme and colors") { page = "Appearance" } }
                    item { SecondarySettingsRow("Player and audio", "Playback settings") { page = "Player and audio" } }
                }
                "Appearance" -> {
                    item { AndroidListPreference("Dark theme", library?.settings?.theme ?: MusicThemeChoice.SYSTEM,
                        MusicThemeChoice.entries, Res.drawable.dark_mode,
                        { when(it) { MusicThemeChoice.SYSTEM -> "Follow system"; MusicThemeChoice.LIGHT -> "Off"; MusicThemeChoice.DARK -> "On" } }, controller::setTheme) }
                }
                else -> {
                    item { AndroidListPreference("Playback speed", options.speed,
                        listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f), Res.drawable.speed,
                        { "${it}x" }, controller::setSpeed) }
                }
            }
        }
        }
    }
}

@Composable
private fun SecondarySettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AndroidHistoryContent(controller: MusicController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val history = controller.library?.collectAsState()?.value?.history.orEmpty()
    Column(modifier.fillMaxSize()) {
        RouteHeading("History", onBack)
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No listening history yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(history, key = { it.id }) { track ->
                    Row(Modifier.fillMaxWidth().clickable { controller.playTrack(track) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CatalogArtwork(track.artworkUrl, Modifier.size(52.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(track.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            Text(track.artist, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AndroidNewsContent(controller: MusicController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val news = controller.news
    val state = news?.state?.collectAsState()?.value ?: MusicNewsState()
    var query by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        RouteHeading("News") { if (state.article != null) news?.closeArticle() else onBack() }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Row(Modifier.padding(24.dp)) { Text(it, Modifier.weight(1f)); TextButton(onClick = { news?.load(true) }) { Text("Retry") } } }
        AnimatedContent(
            targetState = state.article,
            transitionSpec = { fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith fadeOut(spring(stiffness = Spring.StiffnessMediumLow)) },
            label = "news content",
        ) { article ->
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (article != null) item { Text(article, style = MaterialTheme.typography.bodyLarge) }
                else {
                    item {
                        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search news") })
                    }
                    items(state.items.filter { it.title.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) }, key = { it.id }) { item ->
                        ElevatedCard(onClick = { news?.open(item.id) }) {
                            Column(Modifier.fillMaxWidth()) {
                                item.image?.let { CatalogArtwork(it, Modifier.fillMaxWidth().height(180.dp)) }
                                Column(Modifier.padding(16.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(item.description, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                                    Text(item.author, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
