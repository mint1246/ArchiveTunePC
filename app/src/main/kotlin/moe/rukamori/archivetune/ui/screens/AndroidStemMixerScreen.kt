/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlin.math.roundToInt
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.stems.AndroidStemChannelControl
import moe.rukamori.archivetune.stems.AndroidStemControls
import moe.rukamori.archivetune.stems.AndroidStemQueueState
import moe.rukamori.archivetune.stems.AndroidStemSource
import moe.rukamori.archivetune.stems.AndroidStemStorageState
import moe.rukamori.archivetune.stems.formatAndroidStemStorageBytes

/** Mobile surface for the service-owned six-source HTDemucs renderer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidStemMixerScreen(navController: NavController) {
    val playerConnection = LocalPlayerConnection.current
    val coordinator = playerConnection?.service?.stemPlayback
    if (coordinator == null) {
        StemUnavailableScreen(navController)
        return
    }
    val state by coordinator.state.collectAsStateWithLifecycle()
    val queue by coordinator.queueState.collectAsStateWithLifecycle()
    val storage by coordinator.storageState.collectAsStateWithLifecycle()
    var confirmClearStems by remember { mutableStateOf(false) }
    var confirmClearModel by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stem mixer") },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = state.message ?: if (state.cacheReady) "6-stem cache ready" else "Generate six stems for the current track",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.providerDiagnostics?.selectedProvider?.let { provider ->
                        Text(
                            text = "ONNX: $provider",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.separationProgress?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            progress.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { coordinator.separateCurrent() },
                            enabled = state.trackId != null && !state.cacheReady && !state.separationActive,
                        ) {
                            Text(if (state.separationActive) "Generating…" else "Generate stems")
                        }
                        TextButton(
                            onClick = { coordinator.setCurrentControls(AndroidStemControls()) },
                            enabled = state.trackId != null && !state.controls.isDefault(),
                        ) {
                            Text("Reset mix")
                        }
                    }
                    if (state.trackId == null) {
                        Text(
                            "Play a track to generate or mix stems.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            StemBatchQueueCard(
                state = queue,
                onCancel = coordinator::cancelBatchQueue,
            )

            StemStorageCard(
                state = storage,
                onRefresh = { coordinator.refreshStorage() },
                onClearStems = { confirmClearStems = true },
                onClearModel = { confirmClearModel = true },
            )

            AndroidStemSource.workerOrder.forEachIndexed { index, source ->
                if (index > 0) HorizontalDivider()
                StemChannelRow(
                    source = source,
                    control = state.controls.control(source),
                    enabled = state.trackId != null && state.cacheReady && !state.separationActive,
                    onChange = { control ->
                        coordinator.setCurrentControls(state.controls.withControl(source, control))
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClearStems) {
        AlertDialog(
            onDismissRequest = { confirmClearStems = false },
            title = { Text("Clear generated stems?") },
            text = { Text("This removes every generated six-stem set. The HTDemucs model stays installed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearStems = false
                        coordinator.clearStemCache()
                    },
                ) { Text("Clear stems") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearStems = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmClearModel) {
        AlertDialog(
            onDismissRequest = { confirmClearModel = false },
            title = { Text("Delete HTDemucs model?") },
            text = { Text("The 6-stem model will be downloaded and verified again the next time stems are generated. Other local models are not touched.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearModel = false
                        coordinator.clearStemModelCache()
                    },
                ) { Text("Delete model") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearModel = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StemBatchQueueCard(
    state: AndroidStemQueueState,
    onCancel: () -> Unit,
) {
    if (!state.active && state.completed == 0 && state.failed == 0) return
    val remaining = state.pending + if (state.activeTrackId != null) 1 else 0
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("Stem queue", style = MaterialTheme.typography.titleMedium)
            if (state.active) {
                Text(
                    buildString {
                        append(remaining).append(" remaining")
                        state.activeTitle?.let { append(" · Creating ").append(it) }
                        if (state.completed > 0) append(" · ").append(state.completed).append(" ready")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.activeProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.activeMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancel queue") }
            } else if (state.completed > 0) {
                Text("${state.completed} stem set${if (state.completed == 1) "" else "s"} ready")
            }
            if (state.failed > 0) {
                Text(
                    "${state.failed} failed${state.lastError?.let { ": $it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StemStorageCard(
    state: AndroidStemStorageState,
    onRefresh: () -> Unit,
    onClearStems: () -> Unit,
    onClearModel: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Stem storage", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.measuring) "Measuring…" else "${formatAndroidStemStorageBytes(state.usage.totalBytes)} total",
                style = MaterialTheme.typography.titleLarge,
            )
            if (!state.measuring) {
                Text(
                    "${formatAndroidStemStorageBytes(state.usage.stemsBytes)} stems · ${formatAndroidStemStorageBytes(state.usage.modelBytes)} HTDemucs model",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRefresh, enabled = !state.busy) { Text("Refresh") }
                OutlinedButton(
                    onClick = onClearStems,
                    enabled = !state.busy && state.usage.stemsBytes > 0L,
                ) { Text(if (state.clearingStems) "Clearing…" else "Clear stems") }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onClearModel,
                    enabled = !state.busy && state.usage.modelBytes > 0L,
                ) { Text(if (state.clearingModel) "Deleting…" else "Delete model") }
            }
        }
    }
}

@Composable
private fun StemChannelRow(
    source: AndroidStemSource,
    control: AndroidStemChannelControl,
    enabled: Boolean,
    onChange: (AndroidStemChannelControl) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = source.displayName(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(control.gain * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = control.gain,
            onValueChange = { onChange(control.copy(gain = it)) },
            valueRange = 0f..1f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text("Mute", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = control.muted,
                onCheckedChange = { onChange(control.copy(muted = it)) },
                enabled = enabled,
            )
            Spacer(Modifier.width(18.dp))
            Text("Solo", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = control.solo,
                onCheckedChange = { onChange(control.copy(solo = it)) },
                enabled = enabled,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StemUnavailableScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stem mixer") },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("Playback service is not connected.")
        }
    }
}

private fun AndroidStemSource.displayName(): String =
    when (this) {
        AndroidStemSource.DRUMS -> "Drums"
        AndroidStemSource.BASS -> "Bass"
        AndroidStemSource.OTHER -> "Other"
        AndroidStemSource.VOCALS -> "Vocals"
        AndroidStemSource.GUITAR -> "Guitar"
        AndroidStemSource.PIANO -> "Piano"
    }
