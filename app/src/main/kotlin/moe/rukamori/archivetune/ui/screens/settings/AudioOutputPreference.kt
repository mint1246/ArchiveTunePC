/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.playback.AudioOutputDevice
import moe.rukamori.archivetune.playback.MusicService
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.utils.oem.SystemMediaControlResolver

@Composable
internal fun AudioOutputPreference() {
    val playerConnection = LocalPlayerConnection.current
    if (playerConnection == null) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.audio_output)) },
            description = stringResource(R.string.audio_output_service_unavailable),
            icon = { Icon(painterResource(R.drawable.volume_up), contentDescription = null) },
            isEnabled = false,
        )
        return
    }

    val service = playerConnection.service
    val outputState by service.audioOutputState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(service) {
        service.refreshAudioOutputDevices()
    }
    DisposableEffect(service, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    service.refreshAudioOutputDevices()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val preferredName =
        outputState.devices
            .firstOrNull { it.id == outputState.selectedDeviceId }
            ?.name
            ?: stringResource(R.string.system_default)
    val description =
        outputState.errorMessage
            ?: stringResource(
                R.string.audio_output_route_summary,
                outputState.activeDevice.name,
                preferredName,
            )

    PreferenceEntry(
        title = { Text(stringResource(R.string.audio_output)) },
        description = description,
        icon = { Icon(painterResource(R.drawable.volume_up), contentDescription = null) },
        onClick = { showDialog = true },
        isEnabled = outputState.resolved,
    )

    if (showDialog) {
        AudioOutputDialog(
            service = service,
            devices = outputState.devices,
            selectedDeviceId = outputState.selectedDeviceId,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun AudioOutputDialog(
    service: MusicService,
    devices: List<AudioOutputDevice>,
    selectedDeviceId: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val canUseSystemSwitcher = SystemMediaControlResolver.canOpenMediaOutputSwitcher()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_audio_output)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                devices.forEach { device ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    service.selectAudioOutputDevice(device.id)
                                }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = device.id == selectedDeviceId,
                            onClick = { service.selectAudioOutputDevice(device.id) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            imageVector = device.type.imageVector,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(device.name)
                    }
                }

                if (canUseSystemSwitcher) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(
                        onClick = {
                            service.selectAudioOutputDevice(null)
                            SystemMediaControlResolver.openMediaOutputSwitcher(context)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.system_output_switcher))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}
