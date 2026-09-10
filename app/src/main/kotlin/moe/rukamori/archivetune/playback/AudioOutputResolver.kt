/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.models.ActiveOutputDevice
import moe.rukamori.archivetune.models.PlayerOutputDevice

@Immutable
data class AudioOutputDevice(
    val id: String?,
    val name: String,
    val type: PlayerOutputDevice,
) {
    companion object {
        val SystemDefault =
            AudioOutputDevice(
                id = null,
                name = "System default",
                type = PlayerOutputDevice.Unknown,
            )
    }
}

@Immutable
data class AudioOutputState(
    val devices: List<AudioOutputDevice> = listOf(AudioOutputDevice.SystemDefault),
    val selectedDeviceId: String? = null,
    val activeDevice: ActiveOutputDevice =
        ActiveOutputDevice(PlayerOutputDevice.Unknown, PlayerOutputDevice.Unknown.defaultName),
    val resolved: Boolean = false,
    val errorMessage: String? = null,
)

data class AudioOutputResolution internal constructor(
    val state: AudioOutputState,
    internal val preferredDevice: AudioDeviceInfo?,
    val selectionDropped: Boolean,
)

class AudioOutputResolver(
    private val audioManager: AudioManager,
) {
    private val lock = Any()
    private val mediaQueryAttributes =
        android.media.AudioAttributes
            .Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private val _activeAudioDevice =
        MutableStateFlow(
            ActiveOutputDevice(PlayerOutputDevice.Unknown, PlayerOutputDevice.Unknown.defaultName),
        )
    private val _audioOutputState = MutableStateFlow(AudioOutputState())
    private var preferredDeviceId: String? = null

    val activeAudioDevice = _activeAudioDevice.asStateFlow()
    val audioOutputState = _audioOutputState.asStateFlow()

    fun refresh(): AudioOutputResolution = synchronized(lock) {
        refreshLocked()
    }

    fun selectPreferredDevice(deviceId: String?): AudioOutputResolution = synchronized(lock) {
        preferredDeviceId = deviceId?.trim()?.takeIf(String::isNotEmpty)
        refreshLocked()
    }

    private fun refreshLocked(): AudioOutputResolution {
        val sinkDevices =
            runCatching {
                audioManager
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .filter { it.isSink }
            }.getOrElse { error ->
                val previous = _audioOutputState.value
                val failed =
                    previous.copy(
                        resolved = false,
                        errorMessage = error.message ?: "Could not enumerate audio outputs.",
                    )
                _audioOutputState.value = failed
                _activeAudioDevice.value = failed.activeDevice
                return AudioOutputResolution(
                    state = failed,
                    preferredDevice = null,
                    selectionDropped = false,
                )
            }

        val devicesByRuntimeId = sinkDevices.associateBy(AudioDeviceInfo::getId)
        val identities =
            sinkDevices.map { device ->
                val type = PlayerOutputDevice.from(device)
                AudioOutputIdentity(
                    runtimeId = device.id,
                    type = device.type,
                    address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else null,
                    productName =
                        device.productName
                            ?.toString()
                            ?.takeUnless { it.isBlank() || type == PlayerOutputDevice.BuiltinSpeaker },
                    fallbackName = type.defaultName,
                )
            }
        val choices = AudioOutputPolicy.normalize(identities)
        val devices =
            buildList {
                add(AudioOutputDevice.SystemDefault)
                choices.forEach { choice ->
                    val info = devicesByRuntimeId[choice.runtimeId] ?: return@forEach
                    add(
                        AudioOutputDevice(
                            id = choice.persistentId,
                            name = choice.displayName,
                            type = PlayerOutputDevice.from(info),
                        ),
                    )
                }
            }

        val requested = preferredDeviceId
        val runtimeId = AudioOutputPolicy.resolveRuntimeId(requested, choices)
        val selectionDropped = requested != null && runtimeId == null
        if (selectionDropped) preferredDeviceId = null
        val selectedDeviceId = if (runtimeId == null) null else requested
        val preferredDevice = runtimeId?.let(devicesByRuntimeId::get)
        val active = runCatching(::resolveActiveDevice).getOrElse { _activeAudioDevice.value }
        val state =
            AudioOutputState(
                devices = devices,
                selectedDeviceId = selectedDeviceId,
                activeDevice = active,
                resolved = true,
                errorMessage = null,
            )
        _activeAudioDevice.value = active
        _audioOutputState.value = state
        return AudioOutputResolution(
            state = state,
            preferredDevice = preferredDevice,
            selectionDropped = selectionDropped,
        )
    }

    private fun resolveActiveDevice(): ActiveOutputDevice {
        val device =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                resolveActiveDeviceApi33() ?: resolveActiveDeviceHeuristic()
            } else {
                resolveActiveDeviceHeuristic()
            }

        val type = PlayerOutputDevice.from(device)
        val name =
            if (type.isProduct) {
                device?.productName?.toString()?.takeUnless { it.isBlank() } ?: type.defaultName
            } else {
                type.defaultName
            }
        return ActiveOutputDevice(type, name)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun resolveActiveDeviceApi33(): AudioDeviceInfo? =
        audioManager
            .getAudioDevicesForAttributes(mediaQueryAttributes)
            .firstOrNull { it.isSink }

    private fun resolveActiveDeviceHeuristic(): AudioDeviceInfo? {
        val sinks =
            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink }

        return sinks.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: sinks.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
            }
            ?: sinks.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }
            ?: sinks.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            ?: sinks.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_HDMI ||
                    it.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                    it.type == AudioDeviceInfo.TYPE_HDMI_EARC
            }
            ?: sinks.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE ||
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
    }
}
