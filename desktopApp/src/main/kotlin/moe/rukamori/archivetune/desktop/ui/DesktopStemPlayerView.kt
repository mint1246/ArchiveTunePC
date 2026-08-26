/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.platform.AppPaths
import moe.rukamori.archivetune.desktop.platform.DesktopMusicServices
import moe.rukamori.archivetune.desktop.platform.DesktopStemStorage
import moe.rukamori.archivetune.desktop.platform.DesktopStemStorageUsage
import moe.rukamori.archivetune.desktop.platform.DesktopStemPhase
import moe.rukamori.archivetune.desktop.platform.DesktopStemChannelControl
import moe.rukamori.archivetune.desktop.platform.DesktopStemControls
import moe.rukamori.archivetune.desktop.platform.DesktopStemState
import moe.rukamori.archivetune.desktop.platform.formatStemStorageBytes
import kotlin.math.roundToInt

private data class StemChannel(
    val label: String,
    val shortLabel: String,
    val value: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
)

@Composable
internal fun DesktopStemPlayerView(
    services: DesktopMusicServices,
    paths: AppPaths,
    modifier: Modifier = Modifier,
    useShellDynamicBackdrop: Boolean = false,
) {
    val snapshot by services.playback.state.collectAsState()
    val stemState by services.stems.state.collectAsState()
    val item = snapshot.currentItem
    val scope = rememberCoroutineScope()
    val channels = remember(item?.metadata?.id) {
        val remembered = services.stems.controlsFor(item)
        mutableStateListOf(
            StemChannel("Vocals", "VOX", remembered.vocals.value, remembered.vocals.muted, remembered.vocals.solo),
            StemChannel("Drums", "DRM", remembered.drums.value, remembered.drums.muted, remembered.drums.solo),
            StemChannel("Bass", "BAS", remembered.bass.value, remembered.bass.muted, remembered.bass.solo),
            StemChannel("Guitar", "GTR", remembered.guitar.value, remembered.guitar.muted, remembered.guitar.solo),
            StemChannel("Piano", "PNO", remembered.piano.value, remembered.piano.muted, remembered.piano.solo),
            StemChannel("Other", "OTH", remembered.other.value, remembered.other.muted, remembered.other.solo),
        )
    }
    var storage by remember { mutableStateOf(DesktopStemStorageUsage(0L, 0L)) }
    var storageLoading by remember { mutableStateOf(true) }

    fun publishControls() {
        fun control(index: Int): DesktopStemChannelControl {
            val channel = channels[index]
            return DesktopStemChannelControl(channel.value, channel.muted, channel.solo)
        }
        services.stems.setControls(
            item,
            DesktopStemControls(
                vocals = control(0),
                drums = control(1),
                bass = control(2),
                guitar = control(3),
                piano = control(4),
                other = control(5),
            ),
        )
    }

    fun updateChannel(index: Int, channel: StemChannel) {
        channels[index] = channel
        publishControls()
    }

    fun refreshStorage() {
        scope.launch {
            storageLoading = true
            storage = withContext(Dispatchers.IO) { DesktopStemStorage.measure(paths) }
            storageLoading = false
        }
    }

    LaunchedEffect(paths.cache, item?.metadata?.id) {
        refreshStorage()
        services.stems.ensureReady(item)
    }
    LaunchedEffect(stemState.phase) {
        if (stemState.phase == DesktopStemPhase.READY) refreshStorage()
    }
    LaunchedEffect(item?.metadata?.id, stemState.rendererActive) {
        publishControls()
    }
    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (useShellDynamicBackdrop) Color.Transparent else MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
            val compact = maxWidth < 920.dp || maxHeight < 620.dp
            if (compact) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StemTrackHeader(item?.metadata?.artworkUri, item?.metadata?.title, item?.metadata?.artistText)
                    StemGenerationStatus(
                        state = stemState,
                        hasTrack = item != null,
                        onRetry = { services.stems.retry(item) },
                        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
                    )
                    StemDevice(
                        channels = channels,
                        onChannelChange = ::updateChannel,
                        isPlaying = snapshot.isPlaying,
                        canSkipPrevious = snapshot.canSkipPrevious,
                        canSkipNext = snapshot.canSkipNext,
                        progress = if (snapshot.durationMs > 0L) snapshot.positionMs.toFloat() / snapshot.durationMs else 0f,
                        onTogglePlayback = { scope.launch { if (snapshot.isPlaying) services.playback.pause() else services.playback.play() } },
                        onSkipPrevious = { scope.launch { services.playback.skipPrevious() } },
                        onSkipNext = { scope.launch { services.playback.skipNext() } },
                        onSeek = { progress -> scope.launch { services.playback.seekTo((progress * snapshot.durationMs).toLong()) } },
                        enabled = stemState.ready,
                        modifier = Modifier.height(520.dp).fillMaxWidth().widthIn(max = 720.dp),
                    )
                    StemStorageCard(storage, storageLoading, ::refreshStorage, Modifier.fillMaxWidth().widthIn(max = 720.dp))
                }
            } else {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(0.34f).fillMaxHeight().padding(vertical = 30.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        StemTrackHeader(item?.metadata?.artworkUri, item?.metadata?.title, item?.metadata?.artistText)
                        Spacer(Modifier.height(28.dp))
                        Text(
                            "split the track into six parts, then shape the mix without leaving now playing.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("local • on-device • cached") },
                            leadingIcon = { Icon(Icons.Rounded.Lock, null, Modifier.size(17.dp)) },
                        )
                        Spacer(Modifier.height(28.dp))
                        StemGenerationStatus(
                            state = stemState,
                            hasTrack = item != null,
                            onRetry = { services.stems.retry(item) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(18.dp))
                        StemStorageCard(storage, storageLoading, ::refreshStorage, Modifier.fillMaxWidth())
                    }
                    Box(
                        modifier = Modifier.weight(0.66f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        StemDevice(
                            channels = channels,
                            onChannelChange = ::updateChannel,
                            isPlaying = snapshot.isPlaying,
                            canSkipPrevious = snapshot.canSkipPrevious,
                            canSkipNext = snapshot.canSkipNext,
                            progress = if (snapshot.durationMs > 0L) snapshot.positionMs.toFloat() / snapshot.durationMs else 0f,
                            onTogglePlayback = { scope.launch { if (snapshot.isPlaying) services.playback.pause() else services.playback.play() } },
                            onSkipPrevious = { scope.launch { services.playback.skipPrevious() } },
                            onSkipNext = { scope.launch { services.playback.skipNext() } },
                            onSeek = { progress -> scope.launch { services.playback.seekTo((progress * snapshot.durationMs).toLong()) } },
                            enabled = stemState.ready,
                            modifier = Modifier.fillMaxWidth(0.84f).fillMaxHeight(0.84f).widthIn(max = 980.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StemTrackHeader(artworkUri: String?, title: String?, artist: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(74.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            if (!artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.GraphicEq, null, Modifier.size(34.dp))
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title ?: "Nothing playing",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!artist.isNullOrBlank()) {
                Text(
                    artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StemDevice(
    channels: List<StemChannel>,
    onChannelChange: (Int, StemChannel) -> Unit,
    isPlaying: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    progress: Float,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSeek: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val anySolo = channels.any { it.solo }
    Card(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(58.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("STEM PLAYER", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("6-track mix", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    onClick = {
                        channels.forEachIndexed { index, channel ->
                            onChannelChange(index, channel.copy(value = 1f, muted = false, solo = false))
                        }
                    },
                ) { Text("Reset mix") }
            }

            Row(
                Modifier.fillMaxWidth().weight(1f).padding(vertical = 22.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                channels.forEachIndexed { index, channel ->
                    val audible = !channel.muted && (!anySolo || channel.solo)
                    StemTouchStrip(
                        channel = channel,
                        audible = audible,
                        enabled = enabled,
                        onValueChange = { onChannelChange(index, channel.copy(value = it)) },
                        onMute = { onChannelChange(index, channel.copy(muted = !channel.muted)) },
                        onSolo = { onChannelChange(index, channel.copy(solo = !channel.solo)) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onSkipPrevious, enabled = canSkipPrevious) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous")
                }
                FilledIconButton(onClick = onTogglePlayback) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = onSkipNext, enabled = canSkipNext) {
                    Icon(Icons.Rounded.SkipNext, "Next")
                }
            }
            Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = onSeek,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            )

            Text(
                "drag the light strips up or down • mute or solo each stem",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StemTouchStrip(
    channel: StemChannel,
    audible: Boolean,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
) {
    val animatedValue by animateFloatAsState(channel.value, tween(100), label = "stem-${channel.shortLabel}-gain")
    val currentValue by rememberUpdatedState(channel.value)
    val dragRangePx = with(LocalDensity.current) { 214.dp.toPx() }
    val activeColor = if (audible && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(channel.shortLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text("${(channel.value * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier
                .width(58.dp)
                .height(236.dp)
                .pointerInput(dragRangePx, enabled) {
                    if (enabled) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            onValueChange((currentValue - dragAmount / dragRangePx).coerceIn(0f, 1f))
                        }
                    }
                },
            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.72f),
            shape = RoundedCornerShape(29.dp),
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 15.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(10) { segment ->
                    val threshold = (10 - segment) / 10f
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(
                                if (animatedValue + 0.001f >= threshold) activeColor
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StemToggleButton("M", channel.muted, enabled, onMute)
            StemToggleButton("S", channel.solo, enabled, onSolo)
        }
        Text(channel.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun StemToggleButton(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(34.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = CircleShape,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StemGenerationStatus(
    state: DesktopStemState,
    hasTrack: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            val title = when {
                !hasTrack -> "Play a track to use stems"
                state.phase == DesktopStemPhase.READY && state.rendererActive -> "Live stem mixer"
                state.phase == DesktopStemPhase.READY -> "Stems ready"
                state.phase == DesktopStemPhase.UNAVAILABLE -> "Stem engine unavailable"
                state.phase == DesktopStemPhase.ERROR -> "Stem generation failed"
                state.busy -> state.message ?: "Preparing stems…"
                else -> "Preparing stem player…"
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            when {
                state.busy -> {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(state.progress * 100).roundToInt()}% • ${state.message.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.phase == DesktopStemPhase.READY -> {
                    Text(
                        if (state.rendererActive) {
                            "6 stereo stems • live sample-synchronous mix"
                        } else {
                            state.rendererMessage ?: "6 stereo stems • cached locally as compact Opus files"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.phase == DesktopStemPhase.ERROR || state.phase == DesktopStemPhase.UNAVAILABLE -> {
                    Text(
                        state.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.phase == DesktopStemPhase.ERROR) {
                        OutlinedButton(onClick = onRetry) { Text("Try again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StemStorageCard(
    usage: DesktopStemStorageUsage,
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Stem storage", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (loading) "Measuring…" else "${formatStemStorageBytes(usage.totalBytes)} total",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!loading) {
                    Text(
                        "${formatStemStorageBytes(usage.stemsBytes)} stems • ${formatStemStorageBytes(usage.modelsBytes)} models",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Rounded.Refresh, "Refresh stem storage")
            }
        }
    }
}
