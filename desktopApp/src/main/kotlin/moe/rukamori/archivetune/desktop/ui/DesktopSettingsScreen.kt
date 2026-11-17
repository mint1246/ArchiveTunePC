package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.platform.AppPaths
import moe.rukamori.archivetune.desktop.platform.DesktopDownloadServices
import moe.rukamori.archivetune.desktop.platform.DesktopPlatform
import moe.rukamori.archivetune.desktop.platform.DesktopLastFmState
import moe.rukamori.archivetune.desktop.settings.DesktopPartialCleanupPolicy
import moe.rukamori.archivetune.desktop.settings.DesktopKeybind
import moe.rukamori.archivetune.desktop.settings.DesktopKeybindAction
import moe.rukamori.archivetune.desktop.settings.DesktopEqualizerSettings
import moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode
import moe.rukamori.archivetune.desktop.settings.DesktopSettings
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import moe.rukamori.archivetune.desktop.settings.DesktopLyricsTranslationProvider
import moe.rukamori.archivetune.desktop.settings.DesktopCanvasSource
import moe.rukamori.archivetune.desktop.settings.supportsHoldRepeat
import moe.rukamori.archivetune.desktop.sponsorblock.DesktopSponsorBlockCategory
import moe.rukamori.archivetune.desktop.sponsorblock.normalizeDesktopSponsorBlockApiUrl
import moe.rukamori.archivetune.desktop.state.DesktopThemePreference
import moe.rukamori.archivetune.desktopauth.DesktopSessionManager
import moe.rukamori.archivetune.lyrics.BuiltInLyricsProviders
import moe.rukamori.archivetune.linuxmedia.LinuxKeyCapture
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.desktop.DesktopAudioEffects
import moe.rukamori.archivetune.playback.desktop.DesktopAudioOutputControl
import moe.rukamori.archivetune.playback.desktop.DesktopCrossfadeConfiguration
import moe.rukamori.archivetune.playback.desktop.DesktopCrossfadeControl
import moe.rukamori.archivetune.playback.desktop.DesktopEqualizerCapabilities
import moe.rukamori.archivetune.playback.desktop.DesktopEqualizerProfile
import moe.rukamori.archivetune.sdk.settings.DesktopSettingsPolicy
import moe.rukamori.archivetune.sdk.settings.DesktopSettingsSession
import moe.rukamori.archivetune.sdk.settings.LyricsProviderToggleResult
import java.nio.file.Path
import java.awt.event.KeyEvent
import java.util.logging.Logger
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** Desktop equivalents of Android settings that have an actual JVM/Windows consumer. */
@Composable
fun DesktopSettingsPanel(
    state: DesktopContentState,
    paths: AppPaths,
    logger: Logger,
    settingsStore: DesktopSettingsStore,
    playback: PlaybackController?,
    playbackModesEnabled: Boolean = true,
    shuffleModeEnabled: Boolean = true,
    audioEffects: DesktopAudioEffects?,
    audioOutput: DesktopAudioOutputControl?,
    downloads: DesktopDownloadServices?,
    libraryFolders: List<Path>,
    libraryTrackCount: Int,
    libraryScanning: Boolean,
    libraryScanError: String?,
    onLibraryFoldersChanged: (List<Path>) -> Unit,
    onLibraryRescan: () -> Unit,
    onKeybindsChanged: () -> Unit = {},
    onKeybindCaptureChanged: (Boolean) -> Unit = {},
    onStatisticsPreferenceChanged: () -> Unit = {},
    onBeatVisualPreferencesChanged: (Boolean, Float) -> Unit = { _, _ -> },
    paxsenixApiKeyConfigured: Boolean = false,
    onPaxsenixApiKeyChanged: (String?) -> Unit = {},
    lyricsTranslationApiKeyConfigured: Boolean = false,
    onLyricsTranslationApiKeyChanged: (String?) -> Unit = {},
    lastFmStateFlow: StateFlow<DesktopLastFmState>? = null,
    onLastFmCredentialsChanged: (String, String, String) -> Unit = { _, _, _ -> },
    onLastFmStartAuthorization: () -> Unit = {},
    onLastFmFinishAuthorization: () -> Unit = {},
    onLastFmDisconnect: () -> Unit = {},
    onLastFmScrobblingChanged: (Boolean) -> Unit = {},
    onLastFmNowPlayingChanged: (Boolean) -> Unit = {},
    keybindPressedKeys: StateFlow<List<Int>>? = null,
    keybindStatus: StateFlow<String?>? = null,
    account: DesktopSessionManager?,
    accountContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val emptyKeybindPressedKeys = remember { MutableStateFlow<List<Int>>(emptyList()) }
    val serviceKeybindKeys by (keybindPressedKeys ?: emptyKeybindPressedKeys).collectAsState()
    val emptyKeybindStatus = remember { MutableStateFlow<String?>(null) }
    val globalKeybindStatus by (keybindStatus ?: emptyKeybindStatus).collectAsState()
    // Linux records the shortcut locally, because the global shortcut portal can only express
    // modifiers plus a single key while the settings surface has to show the keys as they are
    // pressed.
    val linuxKeyCapture = remember { LinuxKeyCapture() }
    val linuxRecording by linuxKeyCapture.state.collectAsState()
    var linuxCaptureSession by remember { mutableStateOf(0L) }
    val isLinuxDesktop = remember { isLinuxDesktop() }
    val observedKeybindKeys = if (isLinuxDesktop) linuxRecording.bestChord else serviceKeybindKeys
    val settingsSession = remember(settingsStore) { DesktopSettingsSession.forStore(settingsStore) }
    var settings by remember(settingsStore) { mutableStateOf(settingsSession.snapshot()) }
    val emptyLastFmState = remember { MutableStateFlow(DesktopLastFmState()) }
    val lastFm by (lastFmStateFlow ?: emptyLastFmState).collectAsState()
    // Keep this mirror in sync when the host reloads/migrates its folder list while this
    // settings surface remains mounted. Without the key, a successful external update leaves
    // the rows showing stale folders until the whole screen is recreated.
    var localFolders by remember(libraryFolders) { mutableStateOf(libraryFolders) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var recordingKeybindAction by remember { mutableStateOf<DesktopKeybindAction?>(null) }
    var showLyricsApiKeyDialog by remember { mutableStateOf(false) }
    var lyricsApiKeyDraft by remember { mutableStateOf("") }
    var showPaxsenixApiKeyDialog by remember { mutableStateOf(false) }
    var paxsenixApiKeyDraft by remember { mutableStateOf("") }
    var sponsorApiDraft by remember { mutableStateOf(settings.sponsorBlockApiUrl) }
    var lyricsLanguageDraft by remember { mutableStateOf(settings.lyricsTranslationTargetLanguage) }
    var lyricsEndpointDraft by remember { mutableStateOf(settings.lyricsTranslationEndpoint) }
    var lyricsModelDraft by remember { mutableStateOf(settings.lyricsTranslationModel) }
    var lyricsPromptDraft by remember { mutableStateOf(settings.lyricsTranslationCustomPrompt) }
    var lastFmEndpointDraft by remember(settingsStore) { mutableStateOf(settings.lastFmEndpoint) }
    var lastFmApiKeyDraft by remember { mutableStateOf("") }
    var lastFmSecretDraft by remember { mutableStateOf("") }
    var canvasSourceMenuOpen by remember { mutableStateOf(false) }
    var canvasCacheMenuOpen by remember { mutableStateOf(false) }
    var equalizerCapabilities by remember(audioEffects) { mutableStateOf<DesktopEqualizerCapabilities?>(null) }
    var equalizerUnavailable by remember(audioEffects) { mutableStateOf(audioEffects == null) }
    val libraryFeedback = localLibraryScanFeedback(
        folderCount = localFolders.size,
        trackCount = libraryTrackCount,
        scanning = libraryScanning,
        error = libraryScanError,
    )

    DisposableEffect(Unit) {
        onDispose {
            onKeybindCaptureChanged(false)
            linuxKeyCapture.close()
        }
    }

    fun setRecordingKeybindAction(action: DesktopKeybindAction?) {
        val wasRecording = recordingKeybindAction != null
        val isRecording = action != null
        // Release any existing desktop grab before attaching the local recorder.
        if (isRecording && !wasRecording) onKeybindCaptureChanged(true)
        if (isLinuxDesktop) {
            if (action == null) linuxKeyCapture.stop()
            else linuxCaptureSession = linuxKeyCapture.start()
        }
        recordingKeybindAction = action
        if (wasRecording && !isRecording) onKeybindCaptureChanged(false)
    }

    fun update(transform: (DesktopSettings) -> DesktopSettings) {
        settings = settingsSession.update(transform)
    }

    fun updateKeybind(binding: DesktopKeybind) {
        settings = settingsSession.assignKeybind(binding)
        onKeybindsChanged()
    }

    // Completed chords are retained by the event-thread recorder. Compose can skip every
    // intermediate press/release frame of a mouse macro without losing any of its keys.
    LaunchedEffect(linuxRecording) {
        if (isLinuxDesktop && linuxRecording.session == linuxCaptureSession) {
            val action = recordingKeybindAction
            val chord = linuxRecording.completedChord
            if (action != null && chord != null) {
                val binding = settings.keybinds.firstOrNull { it.action == action }
                updateKeybind(DesktopKeybind(action, chord, binding?.repeatWhileHeld == true))
                setRecordingKeybindAction(null)
            } else if (linuxRecording.cancelled) {
                setRecordingKeybindAction(null)
            }
        }
    }

    fun clearKeybind(action: DesktopKeybindAction) {
        settings = settingsSession.clearKeybind(action)
        onKeybindsChanged()
    }

    fun applyEqualizer(next: DesktopEqualizerSettings) {
        settings = settingsSession.setEqualizer(next)
        val profile = settings.equalizer.toPlaybackProfile()
        scope.launch {
            runCatching { audioEffects?.applyEqualizer(profile) }
                .onFailure { error = it.message ?: "Could not apply equalizer settings." }
        }
    }

    fun updateCrossfade(transform: (DesktopSettings) -> DesktopSettings) {
        settings = settingsSession.update(transform)
        val configuration = settings.toCrossfadeConfiguration()
        scope.launch {
            runCatching { (playback as? DesktopCrossfadeControl)?.setCrossfadeConfiguration(configuration) }
                .onFailure { error = it.message ?: "Could not update crossfade settings." }
        }
    }

    LaunchedEffect(audioEffects) {
        equalizerCapabilities = null
        equalizerUnavailable = audioEffects == null
        val effects = audioEffects ?: return@LaunchedEffect
        runCatching { effects.equalizerCapabilities() }
            .onSuccess {
                equalizerCapabilities = it
                equalizerUnavailable = false
            }
            .onFailure { equalizerUnavailable = true }
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
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeChoice("System", Icons.Rounded.BrightnessAuto, DesktopThemePreference.SYSTEM, state, Modifier.weight(1f))
                    ThemeChoice("Light", Icons.Rounded.LightMode, DesktopThemePreference.LIGHT, state, Modifier.weight(1f))
                    ThemeChoice("Dark", Icons.Rounded.DarkMode, DesktopThemePreference.DARK, state, Modifier.weight(1f))
                    ThemeChoice("Dynamic", Icons.Rounded.Palette, DesktopThemePreference.DYNAMIC, state, Modifier.weight(1f))
                }
                HorizontalDivider()
                AccentChoices(state)
                HorizontalDivider()
                ToggleRow(
                    "Beat-synced visuals",
                    settings.beatSyncedVisualsEnabled,
                    Icons.Rounded.GraphicEq,
                ) { enabled ->
                    update { it.copy(beatSyncedVisualsEnabled = enabled) }
                    onBeatVisualPreferencesChanged(enabled, settings.beatSyncedVisualsIntensity)
                }
                Text(
                    "Subtly pulses now-playing artwork and the dynamic player glow using the song's analyzed beat grid.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.beatSyncedVisualsEnabled) {
                    SettingSlider(
                        "Beat visual intensity",
                        "${(settings.beatSyncedVisualsIntensity * 100f).roundToInt()}%",
                        Icons.Rounded.GraphicEq,
                    )
                    Slider(
                        value = settings.beatSyncedVisualsIntensity,
                        onValueChange = { value ->
                            val snapped = (value * 20f).roundToInt() / 20f
                            update { it.copy(beatSyncedVisualsIntensity = snapped) }
                            onBeatVisualPreferencesChanged(true, settings.beatSyncedVisualsIntensity)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        valueRange = 0f..1f,
                        steps = 19,
                    )
                }
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
                ToggleRow(
                    "Normalize track loudness",
                    settings.loudnessNormalizationEnabled,
                    Icons.Rounded.VolumeUp,
                ) { enabled ->
                    update { it.copy(loudnessNormalizationEnabled = enabled) }
                    scope.launch {
                        runCatching { audioEffects?.setLoudnessNormalizationEnabled(enabled) }
                            .onFailure { error = it.message ?: "Could not update loudness normalization." }
                    }
                }
                Text(
                    "Uses YouTube's per-track loudness metadata when available. Your volume slider stays unchanged; local files without metadata are played at the normal level.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                ToggleRow(
                    "Crossfade between tracks",
                    settings.crossfadeEnabled,
                    Icons.Rounded.Shuffle,
                ) { enabled ->
                    updateCrossfade { it.copy(crossfadeEnabled = enabled) }
                }
                Text(
                    "Overlap the end of the current track with the start of the next track.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.crossfadeEnabled) {
                    val duration = settings.crossfadeDurationSeconds
                    val durationLabel = if (duration % 1f == 0f) "${duration.toInt()} s" else "${"%.1f".format(duration)} s"
                    SettingSlider("Crossfade duration", durationLabel, Icons.Rounded.Shuffle)
                    Slider(
                        value = duration,
                        onValueChange = { value ->
                            val snapped = DesktopSettingsPolicy.snapCrossfadeDurationSeconds(value)
                            updateCrossfade { it.copy(crossfadeDurationSeconds = snapped) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        valueRange = 0f..10f,
                        steps = 19,
                    )
                    ToggleRow(
                        "Preserve gapless album transitions",
                        settings.crossfadeGaplessAlbumTransitions,
                        Icons.Rounded.LibraryMusic,
                    ) { enabled ->
                        updateCrossfade { it.copy(crossfadeGaplessAlbumTransitions = enabled) }
                    }
                    Text(
                        "Skips crossfade between consecutive tracks from the same album.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                SettingSlider("Playback speed", "${"%.2f".format(settings.speed)}×", Icons.Rounded.Speed)
                Slider(settings.speed, { value ->
                    val safe = (value * 4f).toInt() / 4f
                    settings = settingsStore.update { it.copy(speed = safe) }
                    scope.launch { playback?.setSpeed(safe) }
                }, Modifier.fillMaxWidth(), enabled = playbackModesEnabled, valueRange = .25f..2f)
                val pitchLabel = settings.pitchSemitones.let { pitch ->
                    val sign = if (pitch > 0f) "+" else ""
                    "$sign${"%.1f".format(pitch)} st"
                }
                SettingSlider("Pitch", pitchLabel, Icons.Rounded.GraphicEq)
                Slider(
                    value = settings.pitchSemitones,
                    onValueChange = { value ->
                        val safe = (value * 2f).roundToInt() / 2f
                        settings = settingsStore.update { it.copy(pitchSemitones = safe) }
                        scope.launch { playback?.setPitch(safe) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = playbackModesEnabled,
                    valueRange = -12f..12f,
                    steps = 47,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeat", modifier = Modifier.weight(1f))
                    DesktopRepeatMode.entries.forEach { mode ->
                        TextButton(enabled = playbackModesEnabled, onClick = {
                            update { it.copy(repeatMode = mode) }
                            scope.launch { playback?.setRepeatMode(mode.toPlaybackMode()) }
                        }) { Text(if (settings.repeatMode == mode) "✓ ${mode.label}" else mode.label) }
                    }
                }
                ToggleRow("Shuffle queue by default", settings.shuffleEnabled, Icons.Rounded.Shuffle, enabled = shuffleModeEnabled) { value ->
                    update { it.copy(shuffleEnabled = value) }
                    scope.launch { playback?.setShuffleEnabled(value) }
                }
                if (!playbackModesEnabled) {
                    Text(
                        "Speed and repeat are controlled by the party host while you are connected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (!shuffleModeEnabled) {
                    Text(
                        "Shuffle stays off while Play Together is active so every participant follows the same queue order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                audioOutput?.let { output ->
                    AudioOutputSetting(
                        audioOutput = output,
                        onNotice = { notice = it },
                    )
                }
            }
        }

        Text("Canvas videos", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleRow("Show Canvas videos in the lyrics player", settings.desktopCanvasEnabled, Icons.Rounded.Lyrics) { enabled ->
                    update { it.copy(desktopCanvasEnabled = enabled) }
                }
                Text(
                    "Fetches matching animated artwork from Better Lyrics, Apple Music, Tidal, or Spotify. Videos are muted and fall back to the normal artwork when unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Provider", modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { canvasSourceMenuOpen = true }) {
                            Text(settings.desktopCanvasSource.displayName)
                        }
                        DropdownMenu(
                            expanded = canvasSourceMenuOpen,
                            onDismissRequest = { canvasSourceMenuOpen = false },
                        ) {
                            DesktopCanvasSource.entries.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(if (settings.desktopCanvasSource == source) "✓ ${source.displayName}" else source.displayName) },
                                    onClick = {
                                        canvasSourceMenuOpen = false
                                        update { it.copy(desktopCanvasSource = source) }
                                    },
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Video cache", modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { canvasCacheMenuOpen = true }) {
                            Text(settings.desktopCanvasCacheLimitMb.canvasCacheLabel)
                        }
                        DropdownMenu(
                            expanded = canvasCacheMenuOpen,
                            onDismissRequest = { canvasCacheMenuOpen = false },
                        ) {
                            listOf(0, 64, 128, 256, 512, 1024, 2048, 4096, 8192).forEach { limit ->
                                DropdownMenuItem(
                                    text = { Text(if (settings.desktopCanvasCacheLimitMb == limit) "✓ ${limit.canvasCacheLabel}" else limit.canvasCacheLabel) },
                                    onClick = {
                                        canvasCacheMenuOpen = false
                                        update { it.copy(desktopCanvasCacheLimitMb = limit) }
                                    },
                                )
                            }
                        }
                    }
                }
                Text(
                    "Choose Off to stream videos without saving them. Cached videos are capped at 128 MB each.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("SponsorBlock", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleRow("Skip selected SponsorBlock segments", settings.sponsorBlockEnabled, Icons.Rounded.SkipNext) { enabled ->
                    update { it.copy(sponsorBlockEnabled = enabled) }
                }
                Text(
                    "SponsorBlock uses community-submitted timestamps for YouTube tracks. Local files and party guests are left untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                DesktopSponsorBlockCategory.entries.forEach { category ->
                    val selected = category.apiValue in settings.sponsorBlockCategories
                    Row(Modifier.fillMaxWidth().clickable {
                        settings = settingsSession.toggleSponsorBlockCategory(category.apiValue)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected, null)
                        Text(category.displayName)
                    }
                }
                HorizontalDivider()
                Text("API server", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = sponsorApiDraft,
                        onValueChange = { sponsorApiDraft = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("HTTPS base URL") },
                    )
                    Button(onClick = {
                        val normalized = normalizeDesktopSponsorBlockApiUrl(sponsorApiDraft)
                        if (normalized == null) {
                            error = "SponsorBlock API URLs must use HTTPS and omit query parameters."
                        } else {
                            error = null
                            settings = settingsStore.update { it.copy(sponsorBlockApiUrl = normalized) }
                            sponsorApiDraft = normalized
                        }
                    }) { Text("Save") }
                }
            }
        }

        Text("Equalizer", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleRow("Enable equalizer", settings.equalizer.enabled, Icons.Rounded.GraphicEq) { enabled ->
                    applyEqualizer(DesktopSettingsPolicy.setEqualizerEnabled(settings.equalizer, enabled))
                }
                when {
                    equalizerUnavailable -> Text(
                        "Equalizer controls are unavailable because the VLC audio engine could not be initialized.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    equalizerCapabilities == null -> Text(
                        "Loading VLC equalizer controls…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        val capabilities = requireNotNull(equalizerCapabilities)
                        if (capabilities.presets.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                capabilities.presets.forEach { preset ->
                                    TextButton(onClick = {
                                        scope.launch {
                                            val effects = audioEffects ?: return@launch
                                            val profile = runCatching { effects.presetEqualizer(preset) }.getOrNull() ?: return@launch
                                            settings = settingsSession.applyEqualizerPreset(
                                                presetName = preset,
                                                preampDb = profile.preampDb,
                                                bandGainsDb = profile.bandGainsDb,
                                            )
                                            runCatching { effects.applyEqualizer(settings.equalizer.toPlaybackProfile()) }
                                                .onFailure { error = it.message ?: "Could not apply equalizer preset." }
                                        }
                                    }) {
                                        Text(if (settings.equalizer.presetName == preset) "✓ $preset" else preset)
                                    }
                                }
                            }
                        }

                        EqualizerGainRow(
                            label = "Preamp",
                            value = settings.equalizer.preampDb,
                            enabled = settings.equalizer.enabled,
                        ) { value ->
                            applyEqualizer(DesktopSettingsPolicy.updateEqualizerPreamp(settings.equalizer, value))
                        }
                        capabilities.bandFrequenciesHz.forEachIndexed { index, frequencyHz ->
                            val gain = settings.equalizer.bandGainsDb.getOrElse(index) { 0f }
                            EqualizerGainRow(
                                label = formatEqualizerFrequency(frequencyHz),
                                value = gain,
                                enabled = settings.equalizer.enabled,
                            ) { value ->
                                applyEqualizer(
                                    DesktopSettingsPolicy.updateEqualizerBand(
                                        equalizer = settings.equalizer,
                                        bandIndex = index,
                                        bandCount = capabilities.bandFrequenciesHz.size,
                                        gainDb = value,
                                    ),
                                )
                            }
                        }
                    }
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
                            if (next == localFolders) return@let
                            localFolders = next
                            onLibraryFoldersChanged(next)
                            update { it.copy(libraryFolders = next) }
                        } }
                    }, enabled = libraryFeedback.folderActionsEnabled) { Text("Add") }
                }
                localFolders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.toString(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            val next = localFolders - folder
                            localFolders = next
                            onLibraryFoldersChanged(next)
                            update { it.copy(libraryFolders = next) }
                        }, enabled = libraryFeedback.folderActionsEnabled) { Text("Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (libraryFeedback.showProgress) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        libraryFeedback.status,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (libraryFeedback.showRetry) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onLibraryRescan,
                        enabled = libraryFeedback.rescanEnabled,
                    ) { Text(if (libraryFeedback.showRetry) "Retry" else "Rescan") }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (paxsenixApiKeyConfigured) "Paxsenix key saved with Windows DPAPI" else "Paxsenix providers need an API key",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        paxsenixApiKeyDraft = ""
                        showPaxsenixApiKeyDialog = true
                    }) { Text(if (paxsenixApiKeyConfigured) "Replace key" else "Set API key") }
                    if (paxsenixApiKeyConfigured) {
                        TextButton(onClick = { onPaxsenixApiKeyChanged(null) }) { Text("Clear") }
                    }
                }
                BuiltInLyricsProviders.defaults.forEach { provider ->
                    val enabled = DesktopSettingsPolicy.isLyricsProviderEnabled(settings.lyricsProviderIds, provider.id)
                    Row(Modifier.fillMaxWidth().clickable {
                        val defaultIds = BuiltInLyricsProviders.defaults.map { it.id }
                        when (val result = settingsSession.toggleLyricsProvider(provider.id, defaultIds)) {
                            LyricsProviderToggleResult.EmptySelectionNotAllowed -> {
                                error = "Keep at least one lyrics provider enabled."
                            }
                            is LyricsProviderToggleResult.Updated -> {
                                error = null
                                settings = result.settings
                            }
                        }
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(enabled, null)
                        Text(provider.name)
                    }
                }
            }
        }

        Text("Lyrics translation", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Translation provider", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopLyricsTranslationProvider.entries.forEach { provider ->
                        TextButton(onClick = { update { it.copy(lyricsTranslationProvider = provider) } }) {
                            Text(if (settings.lyricsTranslationProvider == provider) "✓ ${provider.label}" else provider.label)
                        }
                    }
                }
                TextField(
                    value = lyricsLanguageDraft,
                    onValueChange = { value -> lyricsLanguageDraft = value; update { it.copy(lyricsTranslationTargetLanguage = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Target language") },
                )
                if (settings.lyricsTranslationProvider == DesktopLyricsTranslationProvider.OPENAI_COMPATIBLE) {
                    TextField(
                        value = lyricsEndpointDraft,
                        onValueChange = { value -> lyricsEndpointDraft = value; update { it.copy(lyricsTranslationEndpoint = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Chat completions endpoint") },
                    )
                    TextField(
                        value = lyricsModelDraft,
                        onValueChange = { value -> lyricsModelDraft = value; update { it.copy(lyricsTranslationModel = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Model") },
                    )
                    TextField(
                        value = lyricsPromptDraft,
                        onValueChange = { value -> lyricsPromptDraft = value; update { it.copy(lyricsTranslationCustomPrompt = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom translation instructions") },
                        minLines = 2,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (lyricsTranslationApiKeyConfigured) "API key saved with Windows DPAPI" else "No API key saved", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            lyricsApiKeyDraft = ""
                            showLyricsApiKeyDialog = true
                        }) { Text(if (lyricsTranslationApiKeyConfigured) "Replace key" else "Set key") }
                        if (lyricsTranslationApiKeyConfigured) {
                            TextButton(onClick = { onLyricsTranslationApiKeyChanged(null) }) { Text("Clear") }
                        }
                    }
                }
            }
        }

        if (showLyricsApiKeyDialog) {
            AlertDialog(
                onDismissRequest = { showLyricsApiKeyDialog = false },
                title = { Text("Lyrics translation API key") },
                text = {
                    TextField(
                        value = lyricsApiKeyDraft,
                        onValueChange = { lyricsApiKeyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("API key") },
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = lyricsApiKeyDraft.isNotBlank(),
                        onClick = {
                            onLyricsTranslationApiKeyChanged(lyricsApiKeyDraft.trim())
                            lyricsApiKeyDraft = ""
                            showLyricsApiKeyDialog = false
                        },
                    ) { Text("Save key") }
                },
                dismissButton = { TextButton(onClick = { showLyricsApiKeyDialog = false }) { Text("Cancel") } },
            )
        }

        if (showPaxsenixApiKeyDialog) {
            AlertDialog(
                onDismissRequest = { showPaxsenixApiKeyDialog = false },
                title = { Text("Paxsenix lyrics API key") },
                text = {
                    TextField(
                        value = paxsenixApiKeyDraft,
                        onValueChange = { paxsenixApiKeyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("API key") },
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = paxsenixApiKeyDraft.isNotBlank(),
                        onClick = {
                            onPaxsenixApiKeyChanged(paxsenixApiKeyDraft.trim())
                            paxsenixApiKeyDraft = ""
                            showPaxsenixApiKeyDialog = false
                        },
                    ) { Text("Save key") }
                },
                dismissButton = { TextButton(onClick = { showPaxsenixApiKeyDialog = false }) { Text("Cancel") } },
            )
        }

        Text("Last.fm scrobbling", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        lastFm.connected -> "Linked as ${lastFm.username}"
                        lastFm.credentialsConfigured -> "API credentials are configured; link your account to enable scrobbling."
                        else -> "Link Last.fm-compatible services and scrobble songs played on Desktop."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextField(
                    value = lastFmEndpointDraft,
                    onValueChange = { lastFmEndpointDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("API endpoint") },
                )
                TextField(
                    value = lastFmApiKeyDraft,
                    onValueChange = { lastFmApiKeyDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("API key") },
                )
                TextField(
                    value = lastFmSecretDraft,
                    onValueChange = { lastFmSecretDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Shared secret") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = lastFmApiKeyDraft.isNotBlank() && lastFmSecretDraft.isNotBlank(),
                        onClick = {
                            onLastFmCredentialsChanged(lastFmEndpointDraft, lastFmApiKeyDraft, lastFmSecretDraft)
                            lastFmApiKeyDraft = ""
                            lastFmSecretDraft = ""
                        },
                    ) { Text("Save securely") }
                    OutlinedButton(
                        enabled = lastFm.credentialsConfigured && !lastFm.busy,
                        onClick = onLastFmStartAuthorization,
                    ) { Text(if (lastFm.busy) "Connecting…" else "Link account") }
                    if (lastFm.pendingAuthorization) {
                        Button(enabled = !lastFm.busy, onClick = onLastFmFinishAuthorization) { Text("Finish linking") }
                    }
                    if (lastFm.connected) TextButton(onClick = onLastFmDisconnect) { Text("Disconnect") }
                }
                lastFm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                lastFm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                ToggleRow("Scrobble eligible listening", settings.lastFmEnabled, Icons.Rounded.BarChart, enabled = lastFm.connected) { enabled ->
                    update { it.copy(lastFmEnabled = enabled && lastFm.connected) }
                    onLastFmScrobblingChanged(enabled)
                }
                ToggleRow("Send now-playing updates", settings.lastFmNowPlayingEnabled, Icons.Rounded.Lyrics, enabled = lastFm.connected) { enabled ->
                    update { it.copy(lastFmNowPlayingEnabled = enabled) }
                    onLastFmNowPlayingChanged(enabled)
                }
                Text("Minimum track length: ${settings.lastFmMinTrackDurationSeconds}s", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = settings.lastFmMinTrackDurationSeconds.toFloat(),
                    onValueChange = { value -> update { it.copy(lastFmMinTrackDurationSeconds = value.roundToInt()) } },
                    valueRange = 0f..600f,
                    steps = 59,
                )
                Text("Scrobble after ${"%.0f".format(settings.lastFmScrobbleDelayPercent * 100)}% or ${settings.lastFmScrobbleDelaySeconds}s, whichever comes first", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = settings.lastFmScrobbleDelayPercent,
                    onValueChange = { value -> update { it.copy(lastFmScrobbleDelayPercent = value) } },
                    valueRange = 0.1f..1f,
                    steps = 17,
                )
                Slider(
                    value = settings.lastFmScrobbleDelaySeconds.toFloat(),
                    onValueChange = { value -> update { it.copy(lastFmScrobbleDelaySeconds = value.roundToInt()) } },
                    valueRange = 1f..600f,
                    steps = 59,
                )
                Text(
                    "Only READY playback time counts. Pauses, buffering and seeks add no time; downloads and local music are supported. Podcast episodes and ineligible party listening are skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("Statistics", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleRow(
                    "Count party listening",
                    settings.countPartyListeningInStatistics,
                    Icons.Rounded.BarChart,
                ) { enabled ->
                    update { it.copy(countPartyListeningInStatistics = enabled) }
                    onStatisticsPreferenceChanged()
                }
                Text(
                    "When disabled, listening while hosting or joining a party is excluded from statistics. Existing statistics are not deleted or changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("Account", style = MaterialTheme.typography.headlineMedium)
        accountContent()

        Text("Keybinds", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Keyboard, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Global shortcuts", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (isLinuxDesktop) {
                                "System-wide shortcuts use modifiers plus one key, for example Ctrl+Shift+K. " +
                                    "Press Set, hold the keys together and release them to record a shortcut."
                            } else {
                                "Use any key combination, including 3+ keys. Shortcuts keep working while other keys are held, and continuous actions can repeat."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                globalKeybindStatus?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.size(6.dp))
                DesktopKeybindAction.entries.forEachIndexed { index, action ->
                    val binding = settings.keybinds.firstOrNull { it.action == action }
                    KeybindRow(
                        action = action,
                        binding = binding,
                        recording = recordingKeybindAction == action,
                        observedKeys = observedKeybindKeys,
                        completeOnRelease = !isLinuxDesktop,
                        onRecordingChanged = { recording ->
                            setRecordingKeybindAction(action.takeIf { recording })
                        },
                        onRecorded = { virtualKeys ->
                            updateKeybind(
                                DesktopKeybind(
                                    action = action,
                                    virtualKeys = virtualKeys,
                                    repeatWhileHeld = binding?.repeatWhileHeld == true,
                                ),
                            )
                        },
                        onRepeatChanged = { repeat ->
                            binding?.let { updateKeybind(it.copy(repeatWhileHeld = repeat)) }
                        },
                        onClear = { clearKeybind(action) },
                    )
                    if (index != DesktopKeybindAction.entries.lastIndex) HorizontalDivider()
                }
            }
        }

        Text("Window and startup", style = MaterialTheme.typography.headlineMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Media keys", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isLinuxDesktop) "Media keys and desktop playback controls use your desktop’s media player integration."
                    else "Windows media keys are enabled automatically when the native integration is available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
private fun KeybindRow(
    action: DesktopKeybindAction,
    binding: DesktopKeybind?,
    recording: Boolean,
    observedKeys: List<Int>,
    completeOnRelease: Boolean = true,
    onRecordingChanged: (Boolean) -> Unit,
    onRecorded: (List<Int>) -> Unit,
    onRepeatChanged: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    var chordPreview by remember(action) { mutableStateOf<List<Int>>(emptyList()) }
    var bestChord by remember(action) { mutableStateOf<List<Int>>(emptyList()) }

    LaunchedEffect(recording, observedKeys) {
        if (!recording) {
            bestChord = emptyList()
            chordPreview = emptyList()
            return@LaunchedEffect
        }

        val keys = observedKeys.distinct()
        if (keys.isNotEmpty()) {
            if (keys.size >= bestChord.size) {
                bestChord = keys
                chordPreview = keys
            }
        } else if (completeOnRelease && bestChord.isNotEmpty()) {
            val completed = bestChord
            bestChord = emptyList()
            chordPreview = emptyList()
            onRecorded(completed)
            onRecordingChanged(false)
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(action.keybindLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    action.keybindDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = {
                    onRecordingChanged(!recording)
                    bestChord = emptyList()
                    chordPreview = emptyList()
                },
            ) { Text(if (recording) "Cancel" else if (binding == null) "Set" else "Change") }
            if (binding != null) {
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
        val shownKeys = if (recording) chordPreview else binding?.virtualKeys.orEmpty()
        Text(
            when {
                recording && shownKeys.isEmpty() -> "Press the shortcut keys together…"
                recording -> "Recording: ${formatKeyChord(shownKeys)}"
                shownKeys.isEmpty() -> "Not assigned"
                else -> formatKeyChord(shownKeys)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (shownKeys.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (action.supportsHoldRepeat && binding != null) {
            Row(
                Modifier.toggleable(
                    value = binding.repeatWhileHeld,
                    role = Role.Checkbox,
                    onValueChange = onRepeatChanged,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(binding.repeatWhileHeld, onCheckedChange = null)
                Text("Repeat while held", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** True on the Linux desktop, where the global shortcuts are bound through the desktop portal. */
private fun isLinuxDesktop(): Boolean =
    System.getProperty("os.name").startsWith("Linux", ignoreCase = true)

private fun formatKeyChord(keys: List<Int>): String = keys.joinToString(" + ") { key ->
    when (key) {
        0x10 -> "Shift"
        0x11 -> "Ctrl"
        0x12 -> "Alt"
        0x5B -> "Win"
        0x5D -> "Menu"
        0xBA -> ";"
        0xBB -> "="
        0xBC -> ","
        0xBD -> "-"
        0xBE -> "."
        0xBF -> "/"
        0xC0 -> "`"
        0xDB -> "["
        0xDC -> "\\"
        0xDD -> "]"
        0xDE -> "'"
        else -> KeyEvent.getKeyText(key)
    }
}

private val DesktopKeybindAction.keybindLabel: String
    get() = when (this) {
        DesktopKeybindAction.PLAY_PAUSE -> "Play / pause"
        DesktopKeybindAction.NEXT -> "Next track"
        DesktopKeybindAction.PREVIOUS -> "Previous track"
        DesktopKeybindAction.VOLUME_UP -> "Volume up"
        DesktopKeybindAction.VOLUME_DOWN -> "Volume down"
        DesktopKeybindAction.MUTE_TOGGLE -> "Mute / unmute"
        DesktopKeybindAction.SEEK_FORWARD -> "Seek forward"
        DesktopKeybindAction.SEEK_BACKWARD -> "Seek backward"
        DesktopKeybindAction.SPEED_UP -> "Playback speed up"
        DesktopKeybindAction.SPEED_DOWN -> "Playback speed down"
        DesktopKeybindAction.SPEED_RESET -> "Reset playback speed"
        DesktopKeybindAction.SHUFFLE_TOGGLE -> "Toggle shuffle"
        DesktopKeybindAction.REPEAT_CYCLE -> "Cycle repeat mode"
        DesktopKeybindAction.TOGGLE_LYRICS -> "Show / hide lyrics"
        DesktopKeybindAction.TOGGLE_QUEUE -> "Show / hide queue"
    }

private val DesktopKeybindAction.keybindDescription: String
    get() = when (this) {
        DesktopKeybindAction.PLAY_PAUSE,
        DesktopKeybindAction.NEXT,
        DesktopKeybindAction.PREVIOUS,
        -> "Alternative to hardware media keys."
        DesktopKeybindAction.VOLUME_UP,
        DesktopKeybindAction.VOLUME_DOWN,
        -> "Changes ArchiveTune's own player volume by 2%."
        DesktopKeybindAction.MUTE_TOGGLE -> "Mutes ArchiveTune without changing system volume."
        DesktopKeybindAction.SEEK_FORWARD,
        DesktopKeybindAction.SEEK_BACKWARD,
        -> "Moves through the current track in 5 second steps."
        DesktopKeybindAction.SPEED_UP,
        DesktopKeybindAction.SPEED_DOWN,
        -> "Changes playback speed in 0.05× steps."
        DesktopKeybindAction.SPEED_RESET -> "Returns playback speed to 1.00×."
        DesktopKeybindAction.SHUFFLE_TOGGLE -> "Turns queue shuffle on or off."
        DesktopKeybindAction.REPEAT_CYCLE -> "Cycles off → all → one."
        DesktopKeybindAction.TOGGLE_LYRICS -> "Opens or closes the existing lyrics view."
        DesktopKeybindAction.TOGGLE_QUEUE -> "Opens or closes the existing queue."
    }

@Composable
private fun AudioOutputSetting(
    audioOutput: DesktopAudioOutputControl,
    onNotice: (String) -> Unit,
) {
    val outputState by audioOutput.audioOutputState.collectAsState()
    val scope = rememberCoroutineScope()
    var menuOpen by remember(audioOutput) { mutableStateOf(false) }

    LaunchedEffect(audioOutput) {
        audioOutput.refreshAudioOutputDevices()
    }

    val selectedName = outputState.devices
        .firstOrNull { it.id == outputState.selectedDeviceId }
        ?.name
        ?: "System default"
    val outputError = outputState.errorMessage
    val statusText = outputError ?: if (outputState.resolved) selectedName else "Loading audio outputs…"

    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeUp, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Audio output", style = MaterialTheme.typography.titleMedium)
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (outputError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                enabled = outputState.resolved && outputState.devices.isNotEmpty(),
            ) { Text("Choose") }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                outputState.devices.forEach { device ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (device.id == outputState.selectedDeviceId) "✓ ${device.name}" else device.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                val result = audioOutput.selectAudioOutputDevice(device.id)
                                if (result.errorMessage == null) onNotice("Audio output updated.")
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    val result = audioOutput.refreshAudioOutputDevices()
                    if (result.errorMessage == null) onNotice("Audio outputs refreshed.")
                }
            },
        ) { Text("Refresh") }
    }
}

internal data class LocalLibraryScanFeedback(
    val status: String,
    val showProgress: Boolean,
    val showRetry: Boolean,
    val rescanEnabled: Boolean,
    val folderActionsEnabled: Boolean,
)

internal fun localLibraryScanFeedback(
    folderCount: Int,
    trackCount: Int,
    scanning: Boolean,
    error: String?,
): LocalLibraryScanFeedback = when {
    scanning -> LocalLibraryScanFeedback(
        status = "Scanning local library…",
        showProgress = true,
        showRetry = false,
        rescanEnabled = false,
        folderActionsEnabled = false,
    )
    !error.isNullOrBlank() -> LocalLibraryScanFeedback(
        status = error,
        showProgress = false,
        showRetry = true,
        rescanEnabled = folderCount > 0,
        folderActionsEnabled = true,
    )
    folderCount == 0 -> LocalLibraryScanFeedback(
        status = "Add a folder to index local music.",
        showProgress = false,
        showRetry = false,
        rescanEnabled = false,
        folderActionsEnabled = true,
    )
    else -> LocalLibraryScanFeedback(
        status = "$trackCount track${if (trackCount == 1) "" else "s"} indexed from $folderCount folder${if (folderCount == 1) "" else "s"}.",
        showProgress = false,
        showRetry = false,
        rescanEnabled = true,
        folderActionsEnabled = true,
    )
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
private fun ToggleRow(
    title: String,
    checked: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Expose one toggle semantics node instead of nesting a clickable row and Checkbox. The
    // latter produced two keyboard/accessibility targets that both changed the same setting.
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, Modifier.weight(1f))
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
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

@Composable
private fun EqualizerGainRow(
    label: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.coerceIn(-20f, 20f),
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            valueRange = -20f..20f,
        )
        Text(formatEqualizerDb(value), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatEqualizerDb(value: Float): String = "%+.1f dB".format(value.coerceIn(-20f, 20f))

private fun formatEqualizerFrequency(hz: Float): String =
    if (hz >= 1_000f) "%.1f kHz".format(hz / 1_000f) else "%.0f Hz".format(hz)

private fun DesktopEqualizerSettings.toPlaybackProfile(): DesktopEqualizerProfile =
    DesktopEqualizerProfile(
        enabled = enabled,
        preampDb = preampDb,
        bandGainsDb = bandGainsDb,
    )

private fun DesktopSettings.toCrossfadeConfiguration(): DesktopCrossfadeConfiguration =
    DesktopCrossfadeConfiguration(
        enabled = crossfadeEnabled,
        durationMs = DesktopSettingsPolicy.crossfadeDurationMillis(this),
        preserveGaplessAlbumTransitions = crossfadeGaplessAlbumTransitions,
    )

@Composable
private fun AccentChoices(state: DesktopContentState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Accent color", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.themePreference == DesktopThemePreference.DYNAMIC) {
                    "Dynamic follows the current artwork. Your accent is kept for System, Light and Dark."
                } else {
                    "Tint buttons, selections and highlights without changing the light or dark surface palette."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { state.setAccentSeedArgb(null) }) {
            if (state.accentSeedArgb == null) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text("Default")
        }
        AccentPresets.forEach { preset ->
            val selected = state.accentSeedArgb == preset.argb
            Surface(
                onClick = { state.setAccentSeedArgb(preset.argb) },
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = preset.color,
                border = BorderStroke(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                if (selected) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Rounded.Check, contentDescription = "${preset.label} accent selected", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

private data class AccentPreset(val label: String, val argb: Long, val color: Color)

private val AccentPresets = listOf(
    AccentPreset("Blue", 0xFF4D7CFEL, Color(0xFF4D7CFE)),
    AccentPreset("Purple", 0xFF8C66FFL, Color(0xFF8C66FF)),
    AccentPreset("Rose", 0xFFC75573L, Color(0xFFC75573)),
    AccentPreset("Orange", 0xFFBA6B24L, Color(0xFFBA6B24)),
    AccentPreset("Teal", 0xFF178C8CL, Color(0xFF178C8C)),
)

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
private val DesktopCanvasSource.displayName: String
    get() = when (this) {
        DesktopCanvasSource.ALL -> "All providers"
        DesktopCanvasSource.BETTER_LYRICS -> "Better Lyrics"
        DesktopCanvasSource.APPLE_MUSIC -> "Apple Music"
        DesktopCanvasSource.TIDAL -> "Tidal"
        DesktopCanvasSource.SPOTIFY -> "Spotify"
    }
private val Int.canvasCacheLabel: String
    get() = if (this == 0) "Off" else "$this MB"
private val DesktopLyricsTranslationProvider.label: String
    get() = when (this) {
        DesktopLyricsTranslationProvider.GOOGLE -> "Google Translate"
        DesktopLyricsTranslationProvider.OPENAI_COMPATIBLE -> "OpenAI compatible"
    }
