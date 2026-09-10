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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import moe.rukamori.archivetune.stems.AndroidStemSource

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
