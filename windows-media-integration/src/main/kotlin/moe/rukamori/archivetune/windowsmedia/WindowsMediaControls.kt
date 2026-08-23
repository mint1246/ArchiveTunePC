/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.windowsmedia

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import java.awt.Window
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Commands which may be supplied by a platform media-control surface. */
enum class MediaCommand { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS, SEEK_FORWARD, SEEK_BACKWARD }

/** Sanitized now-playing data retained by the integration for a future native publisher. */
data class NowPlayingMetadata(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUri: String?,
    val durationMs: Long?,
)

data class WindowsMediaPresentation(
    val metadata: NowPlayingMetadata?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

/** Fields that can actually change the retained/native media-control presentation. */
data class WindowsMediaUpdateKey(
    val metadata: PlaybackMetadata?,
    val isPlaying: Boolean,
    val durationMs: Long,
    val hasPreviousItem: Boolean,
    val hasNextItem: Boolean,
    val volume: Float,
)

/** Deliberately excludes position so the 20 Hz playback clock does not fan out into OS updates. */
fun PlaybackSnapshot.windowsMediaUpdateKey(): WindowsMediaUpdateKey =
    WindowsMediaUpdateKey(
        metadata = currentItem?.metadata,
        isPlaying = isPlaying,
        durationMs = durationMs.coerceAtLeast(0L),
        hasPreviousItem = hasPreviousItem,
        hasNextItem = hasNextItem,
        volume = volume,
    )

enum class WindowsMediaAvailability {
    NOT_STARTED,
    ACTIVE_SMTC,
    ACTIVE_HOTKEYS_ONLY,
    UNSUPPORTED,
    CLOSED,
}

/**
 * Platform-neutral bridge from [PlaybackSnapshot] to operating-system media controls.
 *
 * Standard media-key hotkeys are retained as a fallback. Once the Compose/AWT top-level window is
 * available, [attachWindow] also publishes metadata/playback state through Windows SMTC and routes
 * shell transport-button presses back through [commands].
 */
class WindowsMediaControls(
    private val hotKeys: WindowsHotKeySource = JnaWindowsHotKeySource(),
    private val smtcPublisher: WindowsSmtcPublisher = JnaWindowsSmtcPublisher(),
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "archive-tune-media-controls").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val lock = Any()
    private val _commands = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 16)
    private val _presentation = MutableStateFlow(
        WindowsMediaPresentation(metadata = null, isPlaying = false, positionMs = 0, durationMs = 0),
    )
    private val _availability = MutableStateFlow(WindowsMediaAvailability.NOT_STARTED)
    private var registration: AutoCloseable? = null
    private var closed = false

    val commands: SharedFlow<MediaCommand> = _commands.asSharedFlow()
    val presentation: StateFlow<WindowsMediaPresentation> = _presentation.asStateFlow()
    val availability: StateFlow<WindowsMediaAvailability> = _availability.asStateFlow()

    /** Starts global hardware media-key capture. Safe to call repeatedly. */
    fun start() {
        synchronized(lock) {
        if (closed || registration != null || _availability.value == WindowsMediaAvailability.ACTIVE_SMTC) return
        val startedRegistration: AutoCloseable? = try {
            hotKeys.start { command ->
                // Win32 message-loop callbacks must never execute app playback on the native thread.
                callbackExecutor.execute { _commands.tryEmit(command) }
            }
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: LinkageError) {
            null
        } catch (_: SecurityException) {
            null
        }
        registration = startedRegistration
        _availability.value = if (startedRegistration == null) {
            WindowsMediaAvailability.UNSUPPORTED
        } else {
            WindowsMediaAvailability.ACTIVE_HOTKEYS_ONLY
        }
        }
    }

    /** Binds the real top-level desktop HWND to Windows System Media Transport Controls. */
    fun attachWindow(window: Window) {
        synchronized(lock) {
            if (closed) return
        }
        val attached = runCatching {
            smtcPublisher.attach(window) { command ->
                callbackExecutor.execute { _commands.tryEmit(command) }
            }
        }.getOrDefault(false)
        synchronized(lock) {
            if (!closed && attached) {
                // Let Windows own media-key routing once SMTC is live. Keeping RegisterHotKey active
                // here would steal media keys from whichever system media session is actually active.
                registration?.close()
                registration = null
                _availability.value = WindowsMediaAvailability.ACTIVE_SMTC
            }
        }
    }

    /** Updates retained now-playing information without exposing playback URLs or headers. */
    fun update(snapshot: PlaybackSnapshot) {
        if (closed) return
        val item = snapshot.currentItem
        val source = item?.metadata
        _presentation.value = WindowsMediaPresentation(
            metadata = source?.let {
                NowPlayingMetadata(
                    id = it.id,
                    title = it.title.ifBlank { "Unknown title" },
                    artist = it.artistText.ifBlank { "Unknown artist" },
                    album = it.album?.title?.takeIf(String::isNotBlank),
                    artworkUri = it.artworkUri,
                    durationMs = it.durationHintMs?.coerceAtLeast(0L),
                )
            },
            isPlaying = snapshot.isPlaying,
            positionMs = snapshot.positionMs.coerceAtLeast(0L),
            durationMs = snapshot.durationMs.coerceAtLeast(0L),
        )
        runCatching { smtcPublisher.update(snapshot) }
    }

    override fun close() {
        synchronized(lock) {
        if (closed) return
        closed = true
        registration?.close()
        registration = null
        smtcPublisher.close()
        callbackExecutor.shutdownNow()
        _availability.value = WindowsMediaAvailability.CLOSED
        }
    }
}

/** Maps platform-neutral commands to the existing playback boundary. */
class PlaybackMediaCommandRouter(
    private val controller: PlaybackController,
    private val seekStepMs: Long = 10_000L,
    private val commandAllowed: () -> Boolean = { true },
) {
    init { require(seekStepMs > 0) }

    suspend fun handle(command: MediaCommand) {
        if (!commandAllowed()) return
        when (command) {
            MediaCommand.PLAY -> if (!controller.state.value.isPlaying) controller.play()
            MediaCommand.PAUSE -> if (controller.state.value.isPlaying) controller.pause()
            MediaCommand.PLAY_PAUSE -> if (controller.state.value.isPlaying) controller.pause() else controller.play()
            MediaCommand.NEXT -> controller.skipNext()
            MediaCommand.PREVIOUS -> controller.skipPrevious()
            MediaCommand.SEEK_FORWARD -> controller.seekTo(controller.state.value.positionMs + seekStepMs)
            MediaCommand.SEEK_BACKWARD -> controller.seekTo((controller.state.value.positionMs - seekStepMs).coerceAtLeast(0L))
        }
    }
}
