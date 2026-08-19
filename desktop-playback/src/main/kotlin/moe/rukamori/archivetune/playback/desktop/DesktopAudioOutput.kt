/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.player.base.MediaPlayer

/** Desktop-only audio-output choice exposed by the VLC-backed player. A null id is system default. */
data class DesktopAudioOutputDevice(
    val id: String?,
    val name: String,
) {
    companion object {
        val SystemDefault = DesktopAudioOutputDevice(id = null, name = "System default")
    }
}

data class DesktopAudioOutputState(
    val devices: List<DesktopAudioOutputDevice> = listOf(DesktopAudioOutputDevice.SystemDefault),
    val selectedDeviceId: String? = null,
    /** True only after a backend device enumeration succeeded. */
    val resolved: Boolean = false,
    val errorMessage: String? = null,
)

/** Keeps VLC-specific output routing out of the generic cross-platform playback API. */
interface DesktopAudioOutputControl {
    val audioOutputState: StateFlow<DesktopAudioOutputState>

    suspend fun refreshAudioOutputDevices(): DesktopAudioOutputState

    suspend fun selectAudioOutputDevice(deviceId: String?): DesktopAudioOutputState
}

internal interface DesktopAudioOutputBackend {
    fun outputDevices(): List<DesktopAudioOutputDevice>

    /** A null id asks the backend to follow the operating-system default output. */
    fun selectOutputDevice(deviceId: String?)
}

/**
 * Small state machine shared by production VLC and deterministic fake-backend tests. The preferred
 * device is reapplied whenever a player/backend is attached. If enumeration proves that the saved
 * device no longer exists, selection permanently falls back to system default so the stale id can
 * be cleared by the settings collector.
 */
internal class DesktopAudioOutputSelection(initialDeviceId: String? = null) {
    private val lock = Any()
    private var backend: DesktopAudioOutputBackend? = null
    private var preferredDeviceId: String? = initialDeviceId.normalizedDeviceId()
    private val _state = MutableStateFlow(DesktopAudioOutputState())

    val state: StateFlow<DesktopAudioOutputState> = _state.asStateFlow()

    fun replaceBackend(next: DesktopAudioOutputBackend?): DesktopAudioOutputState = synchronized(lock) {
        backend = next
        if (next == null) {
            DesktopAudioOutputState().also { _state.value = it }
        } else {
            refreshLocked(next)
        }
    }

    fun refresh(): DesktopAudioOutputState = synchronized(lock) {
        val activeBackend = backend ?: return@synchronized _state.value
        refreshLocked(activeBackend)
    }

    fun select(deviceId: String?): DesktopAudioOutputState = synchronized(lock) {
        preferredDeviceId = deviceId.normalizedDeviceId()
        val activeBackend = backend ?: return@synchronized _state.value
        refreshLocked(activeBackend)
    }

    private fun refreshLocked(activeBackend: DesktopAudioOutputBackend): DesktopAudioOutputState {
        val enumerated = runCatching { activeBackend.outputDevices() }
            .getOrElse { error ->
                val previous = _state.value
                return DesktopAudioOutputState(
                    devices = previous.devices,
                    selectedDeviceId = previous.selectedDeviceId,
                    resolved = false,
                    errorMessage = error.message ?: "Could not enumerate audio outputs.",
                ).also { _state.value = it }
            }

        val devices = normalizeDevices(enumerated)
        val requested = preferredDeviceId
        val effective = requested?.takeIf { id -> devices.any { it.id == id } }
        // Missing/unplugged saved devices become a real system-default preference instead of an
        // invalid selection that is retried on every player creation.
        if (requested != null && effective == null) preferredDeviceId = null

        val selectionFailure = runCatching { activeBackend.selectOutputDevice(effective) }.exceptionOrNull()
        if (selectionFailure == null) {
            return DesktopAudioOutputState(
                devices = devices,
                selectedDeviceId = effective,
                resolved = true,
            ).also { _state.value = it }
        }

        val fallbackFailure = runCatching { activeBackend.selectOutputDevice(null) }.exceptionOrNull()
        if (fallbackFailure == null) preferredDeviceId = null
        return DesktopAudioOutputState(
            devices = devices,
            selectedDeviceId = if (fallbackFailure == null) null else _state.value.selectedDeviceId,
            resolved = fallbackFailure == null,
            errorMessage = (fallbackFailure ?: selectionFailure).message ?: "Could not change audio output.",
        ).also { _state.value = it }
    }

    private fun normalizeDevices(devices: List<DesktopAudioOutputDevice>): List<DesktopAudioOutputDevice> =
        buildList {
            add(DesktopAudioOutputDevice.SystemDefault)
            val seen = HashSet<String>()
            devices.forEach { device ->
                val id = device.id.normalizedDeviceId() ?: return@forEach
                if (!seen.add(id)) return@forEach
                add(
                    DesktopAudioOutputDevice(
                        id = id,
                        name = device.name.trim().ifEmpty { id },
                    ),
                )
            }
        }
}

internal class VlcjAudioOutputBackend(private val mediaPlayer: MediaPlayer) : DesktopAudioOutputBackend {
    override fun outputDevices(): List<DesktopAudioOutputDevice> =
        mediaPlayer.audio().outputDevices().map { device ->
            DesktopAudioOutputDevice(
                id = device.deviceId,
                name = device.longName,
            )
        }

    override fun selectOutputDevice(deviceId: String?) {
        // libVLC accepts null output-module/device values to resume the current/system default.
        mediaPlayer.audio().setOutputDevice(null, deviceId)
    }
}

private fun String?.normalizedDeviceId(): String? = this?.trim()?.takeIf(String::isNotEmpty)
