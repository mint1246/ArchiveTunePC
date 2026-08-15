package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.platform.AppPaths
import moe.rukamori.archivetune.desktop.platform.DesktopDownloadServices
import moe.rukamori.archivetune.desktop.platform.DesktopPlatform
import moe.rukamori.archivetune.desktop.settings.DesktopPartialCleanupPolicy
import moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode
import moe.rukamori.archivetune.desktop.settings.DesktopSettings
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import moe.rukamori.archivetune.desktop.state.DesktopThemePreference
import moe.rukamori.archivetune.desktopauth.DesktopSessionManager
import moe.rukamori.archivetune.lyrics.BuiltInLyricsProviders
import moe.rukamori.archivetune.playback.api.PlaybackController
import java.nio.file.Path
import java.util.logging.Logger
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** Desktop equivalents of Android settings that have an actual JVM/Windows consumer. */
@Composable
fun DesktopSettingsPanel(
    state: DesktopContentState,
    paths: AppPaths,
    logger: Logger,
    settingsStore: DesktopSettingsStore,
    playback: PlaybackController?,
    downloads: DesktopDownloadServices?,
    libraryFolders: List<Path>,
    onLibraryFoldersChanged: (List<Path>) -> Unit,
    account: DesktopSessionManager?,
    accountContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var settings by remember(settingsStore) { mutableStateOf(settingsStore.snapshot()) }
    // Keep this mirror in sync when the host reloads/migrates its folder list while this
    // settings surface remains mounted. Without the key, a successful external update leaves
    // the rows showing stale folders until the whole screen is recreated.
    var localFolders by remember(libraryFolders) { mutableStateOf(libraryFolders) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun update(transform: (DesktopSettings) -> DesktopSettings) {
        settings = settingsStore.update(transform)
    }

    fun reconfigureDownloads(nextConcurrency: Int, nextPolicy: DesktopPartialCleanupPolicy) {
        val previous = settings
        if (downloads == null) {
            settings = settingsStore.update { it.copy(downloadConcurrency = nextConcurrency, partialCleanupPolicy = nextPolicy) }
            return
        }
        val result = downloads.configure(nextConcurrency, nextPolicy)
        result
            .onSuccess {
                settings = settingsStore.snapshot()
                error = null
                notice = "Download behavior updated."
            }
            .onFailure {
                // Active work is intentionally left untouched; do not persist a value the manager
                // could not apply, and leave the visible controls at their previous state.
                settings = previous
                error = it.message ?: "Could not update download behavior."
            }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 980.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Appearance", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeChoice("System", Icons.Rounded.BrightnessAuto, DesktopThemePreference.SYSTEM, state, Modifier.weight(1f))
                ThemeChoice("Light", Icons.Rounded.LightMode, DesktopThemePreference.LIGHT, state, Modifier.weight(1f))
                ThemeChoice("Dark", Icons.Rounded.DarkMode, DesktopThemePreference.DARK, state, Modifier.weight(1f))
            }
        }

        Text("Playback defaults", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingSlider("Startup volume", "${(settings.volume * 100).toInt()}%", Icons.Rounded.VolumeUp)
                Slider(settings.volume, { value ->
                    settings = settingsStore.update { it.copy(volume = value) }
                    scope.launch { playback?.setVolume(value) }
                }, Modifier.fillMaxWidth())
                SettingSlider("Playback speed", "${"%.2f".format(settings.speed)}×", Icons.Rounded.Speed)
                Slider(settings.speed, { value ->
                    val safe = (value * 4f).toInt() / 4f
                    settings = settingsStore.update { it.copy(speed = safe) }
                    scope.launch { playback?.setSpeed(safe) }
                }, Modifier.fillMaxWidth(), valueRange = .25f..2f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeat", modifier = Modifier.weight(1f))
                    DesktopRepeatMode.entries.forEach { mode ->
                        TextButton(onClick = {
                            update { it.copy(repeatMode = mode) }
                            scope.launch { playback?.setRepeatMode(mode.toPlaybackMode()) }
                        }) { Text(if (settings.repeatMode == mode) "✓ ${mode.label}" else mode.label) }
                    }
                }
                ToggleRow("Shuffle queue by default", settings.shuffleEnabled, Icons.Rounded.Shuffle) { value ->
                    update { it.copy(shuffleEnabled = value) }
                    scope.launch { playback?.setShuffleEnabled(value) }
                }
            }
        }

        Text("Downloads and local library", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DirectorySetting("Download folder", settings.downloadLocation ?: paths.downloads, Icons.Rounded.Download) {
                    scope.launch {
                        chooseDirectory("Choose download folder")?.let { selected ->
                            val result = downloads?.changeDirectory(selected) ?: run {
                                update { it.copy(downloadLocation = selected) }; Result.success(Unit)
                            }
                            result.onSuccess { notice = "Download folder updated." }.onFailure { error = it.message }
                            settings = settingsStore.snapshot()
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Concurrent downloads", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val next = (settings.downloadConcurrency - 1).coerceAtLeast(1)
                        reconfigureDownloads(next, settings.partialCleanupPolicy)
                    }) { Text("−") }
                    Text("${settings.downloadConcurrency}")
                    TextButton(onClick = {
                        val next = (settings.downloadConcurrency + 1).coerceAtMost(8)
                        reconfigureDownloads(next, settings.partialCleanupPolicy)
                    }) { Text("+") }
                }
                ToggleRow("Keep partial files when canceled", settings.partialCleanupPolicy == DesktopPartialCleanupPolicy.KEEP_ON_CANCEL, Icons.Rounded.Download) { value ->
                    val policy = if (value) DesktopPartialCleanupPolicy.KEEP_ON_CANCEL else DesktopPartialCleanupPolicy.DELETE_ON_CANCEL
                    reconfigureDownloads(settings.downloadConcurrency, policy)
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Local music folders", style = MaterialTheme.typography.titleMedium)
                        Text(if (localFolders.isEmpty()) "No folders selected" else localFolders.joinToString(" · ") { it.toString() }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = {
                        scope.launch { chooseDirectory("Add local music folder")?.let { folder ->
                            val next = (localFolders + folder).distinct()
                            localFolders = next
                            onLibraryFoldersChanged(next)
                            update { it.copy(libraryFolders = next) }
                        } }
                    }) { Text("Add") }
                }
                localFolders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.toString(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            val next = localFolders - folder
                            localFolders = next
                            onLibraryFoldersChanged(next)
                            update { it.copy(libraryFolders = next) }
                        }) { Text("Remove") }
                    }
                }
            }
        }

        Text("Lyrics", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lyrics, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Enabled providers", style = MaterialTheme.typography.titleMedium)
                }
                Text("Provider changes apply after restarting the desktop app. Disabled providers are skipped during fallback search.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BuiltInLyricsProviders.defaults.forEach { provider ->
                    val enabled = settings.lyricsProviderIds.isEmpty() || provider.id in settings.lyricsProviderIds
                    Row(Modifier.fillMaxWidth().clickable {
                        val current = if (settings.lyricsProviderIds.isEmpty()) BuiltInLyricsProviders.defaults.map { it.id } else settings.lyricsProviderIds
                        val next = if (enabled) current - provider.id else current + provider.id
                        if (next.isEmpty()) {
                            error = "Keep at least one lyrics provider enabled."
                        } else {
                            error = null
                            update { it.copy(lyricsProviderIds = if (next.size == BuiltInLyricsProviders.defaults.size) emptyList() else next) }
                        }
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(enabled, null)
                        Text(provider.name)
                    }
                }
            }
        }

        Text("Account", style = MaterialTheme.typography.headlineMedium)
        accountContent()

        Text("Windows and startup", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Media keys", style = MaterialTheme.typography.titleMedium)
                Text("Windows media keys are enabled automatically when the native integration is available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Window size, position, maximized state, theme and last destination are restored on the next launch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("Application data", style = MaterialTheme.typography.headlineMedium)
        DirectorySetting("Application data", paths.data, Icons.Rounded.FolderOpen, actionLabel = "Open") { DesktopPlatform.openDirectory(paths.data, logger) }
        DirectorySetting("Logs", paths.logs, Icons.Rounded.FolderOpen, actionLabel = "Open") { DesktopPlatform.openDirectory(paths.logs, logger) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SettingSlider(title: String, summary: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, Modifier.weight(1f))
        Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onCheckedChange: (Boolean) -> Unit) {
    // Expose one toggle semantics node instead of nesting a clickable row and Checkbox. The
    // latter produced two keyboard/accessibility targets that both changed the same setting.
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, Modifier.weight(1f))
        Checkbox(checked, onCheckedChange = null)
    }
}

@Composable
private fun DirectorySetting(
    title: String,
    path: Path,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String = "Choose",
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(path.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun ThemeChoice(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, preference: DesktopThemePreference, state: DesktopContentState, modifier: Modifier = Modifier) {
    val selected = state.themePreference == preference
    Surface(modifier = modifier.clickable { state.setThemePreference(preference) }, shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(10.dp))
            Text(label, Modifier.weight(1f))
            if (selected) Icon(Icons.Rounded.Check, "Selected")
        }
    }
}

private suspend fun chooseDirectory(title: String): Path? = withContext(Dispatchers.IO) {
    var selected: Path? = null
    SwingUtilities.invokeAndWait {
        JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selected = selectedFile.toPath()
        }
    }
    selected
}

private fun DesktopRepeatMode.toPlaybackMode() = moe.rukamori.archivetune.playback.api.RepeatMode.valueOf(name)
private val DesktopRepeatMode.label: String get() = name.lowercase().replaceFirstChar(Char::uppercase)
