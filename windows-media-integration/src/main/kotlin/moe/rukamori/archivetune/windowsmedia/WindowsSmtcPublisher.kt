/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.windowsmedia

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import java.awt.Window
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal WinRT ABI bridge for publishing ArchiveTune through Windows System Media Transport
 * Controls (SMTC). Keeping this in the Windows-only module avoids adding a CLR/native helper just
 * for the shell media flyout and continues to work in the Graal native executable through JNA.
 */
interface WindowsSmtcPublisher : AutoCloseable {
    fun attach(window: Window, onCommand: (MediaCommand) -> Unit): Boolean
    fun update(snapshot: PlaybackSnapshot)
}

internal class JnaWindowsSmtcPublisher : WindowsSmtcPublisher {
    private val lock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "archive-tune-smtc").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var session: SmtcSession? = null
    @Volatile private var pendingSnapshot: PlaybackSnapshot = PlaybackSnapshot()

    override fun attach(window: Window, onCommand: (MediaCommand) -> Unit): Boolean {
        if (!Platform.isWindows() || closed) return false
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return false
        return runCatching {
            executor.submit<Boolean> {
                if (closed) return@submit false
                session?.close()
                session = SmtcSession.open(hwnd, onCommand)?.also { it.update(pendingSnapshot) }
                session != null
            }.get(ATTACH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    override fun update(snapshot: PlaybackSnapshot) {
        pendingSnapshot = snapshot
        if (closed) return
        runCatching {
            executor.execute {
                if (!closed) runCatching { session?.update(snapshot) }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        runCatching {
            executor.submit {
                session?.close()
                session = null
            }.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        executor.shutdownNow()
    }

    private companion object {
        const val ATTACH_TIMEOUT_SECONDS = 3L
        const val CLOSE_TIMEOUT_SECONDS = 2L
    }
}

private class SmtcSession private constructor(
    private val api: WinRtApi,
    private val smtc: Pointer,
    private val displayUpdater: Pointer,
    private val buttonHandler: WinRtButtonHandler,
    private val buttonToken: Long,
    private val initializedWinRt: Boolean,
) : AutoCloseable {
    private var closed = false
    private var lastMetadata: NowPlayingMetadata? = null
    private var lastArtworkUri: String? = null

    fun update(snapshot: PlaybackSnapshot) {
        if (closed) return
        val item = snapshot.currentItem
        if (item == null) {
            if (lastMetadata != null) {
                api.callHr(displayUpdater, DISPLAY_CLEAR_ALL)
                api.callHr(displayUpdater, DISPLAY_UPDATE)
                lastMetadata = null
                lastArtworkUri = null
            }
            api.callHr(smtc, SMTC_PUT_PLAYBACK_STATUS, MEDIA_PLAYBACK_STATUS_STOPPED)
            api.callHr(smtc, SMTC_PUT_IS_ENABLED, WINRT_FALSE)
            return
        }

        val source = item.metadata
        val metadata = NowPlayingMetadata(
            id = source.id,
            title = source.title.ifBlank { "Unknown title" },
            artist = source.artistText.ifBlank { "Unknown artist" },
            album = source.album?.title?.takeIf(String::isNotBlank),
            artworkUri = source.artworkUri,
            durationMs = source.durationHintMs?.coerceAtLeast(0L),
        )

        api.callHr(smtc, SMTC_PUT_IS_ENABLED, WINRT_TRUE)
        api.callHr(smtc, SMTC_PUT_IS_PLAY_ENABLED, WINRT_TRUE)
        api.callHr(smtc, SMTC_PUT_IS_PAUSE_ENABLED, WINRT_TRUE)
        api.callHr(smtc, SMTC_PUT_IS_STOP_ENABLED, WINRT_FALSE)
        api.callHr(smtc, SMTC_PUT_IS_PREVIOUS_ENABLED, if (snapshot.canSkipPrevious) WINRT_TRUE else WINRT_FALSE)
        api.callHr(smtc, SMTC_PUT_IS_NEXT_ENABLED, if (snapshot.canSkipNext) WINRT_TRUE else WINRT_FALSE)
        api.callHr(smtc, SMTC_PUT_IS_FAST_FORWARD_ENABLED, WINRT_TRUE)
        api.callHr(smtc, SMTC_PUT_IS_REWIND_ENABLED, WINRT_TRUE)
        api.callHr(smtc, SMTC_PUT_PLAYBACK_STATUS, snapshot.toSmtcPlaybackStatus())

        if (metadata != lastMetadata) {
            publishMetadata(metadata)
            lastMetadata = metadata
        }
    }

    private fun publishMetadata(metadata: NowPlayingMetadata) {
        api.callHr(displayUpdater, DISPLAY_PUT_TYPE, MEDIA_PLAYBACK_TYPE_MUSIC)
        api.withHString(metadata.id) { appMediaId ->
            api.callHr(displayUpdater, DISPLAY_PUT_APP_MEDIA_ID, appMediaId)
        }

        val musicRef = PointerByReference()
        api.callHr(displayUpdater, DISPLAY_GET_MUSIC_PROPERTIES, musicRef)
        val music = musicRef.value
        try {
            api.withHString(metadata.title) { title -> api.callHr(music, MUSIC_PUT_TITLE, title) }
            api.withHString(metadata.artist) { artist -> api.callHr(music, MUSIC_PUT_ARTIST, artist) }
            api.withHString(metadata.artist) { artist -> api.callHr(music, MUSIC_PUT_ALBUM_ARTIST, artist) }
            api.queryInterface(music, IID_MUSIC_DISPLAY_PROPERTIES_2)?.let { music2 ->
                try {
                    api.withHString(metadata.album.orEmpty()) { albumTitle ->
                        api.callHr(music2, MUSIC2_PUT_ALBUM_TITLE, albumTitle)
                    }
                } finally {
                    api.release(music2)
                }
            }
        } finally {
            api.release(music)
        }

        if (metadata.artworkUri != lastArtworkUri) {
            setArtwork(metadata.artworkUri)
            lastArtworkUri = metadata.artworkUri
        }
        api.callHr(displayUpdater, DISPLAY_UPDATE)
    }

    private fun setArtwork(uriText: String?) {
        if (uriText.isNullOrBlank()) {
            api.callHr(displayUpdater, DISPLAY_PUT_THUMBNAIL, null)
            return
        }
        val uriFactory = api.activationFactory(CLASS_URI, IID_URI_RUNTIME_CLASS_FACTORY) ?: return
        try {
            val uriRef = PointerByReference()
            val createResult = api.withHString(uriText) { hstring ->
                api.tryCallHr(uriFactory, URI_FACTORY_CREATE_URI, hstring, uriRef)
            }
            if (createResult < 0 || uriRef.value == null) return
            val uri = uriRef.value
            try {
                val streamFactory = api.activationFactory(
                    CLASS_RANDOM_ACCESS_STREAM_REFERENCE,
                    IID_RANDOM_ACCESS_STREAM_REFERENCE_STATICS,
                ) ?: return
                try {
                    val streamRef = PointerByReference()
                    if (api.tryCallHr(streamFactory, STREAM_REFERENCE_CREATE_FROM_URI, uri, streamRef) >= 0 && streamRef.value != null) {
                        try {
                            api.callHr(displayUpdater, DISPLAY_PUT_THUMBNAIL, streamRef.value)
                        } finally {
                            api.release(streamRef.value)
                        }
                    }
                } finally {
                    api.release(streamFactory)
                }
            } finally {
                api.release(uri)
            }
        } finally {
            api.release(uriFactory)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { api.callHr(smtc, SMTC_REMOVE_BUTTON_PRESSED, buttonToken) }
        runCatching { api.callHr(smtc, SMTC_PUT_IS_ENABLED, WINRT_FALSE) }
        buttonHandler.close()
        api.release(displayUpdater)
        api.release(smtc)
        if (initializedWinRt) api.roUninitialize()
    }

    companion object {
        fun open(hwnd: Pointer, onCommand: (MediaCommand) -> Unit): SmtcSession? {
            val api = runCatching { WinRtApi() }.getOrNull() ?: return null
            val initHr = api.roInitialize()
            if (initHr < 0) return null
            var smtc: Pointer? = null
            var updater: Pointer? = null
            var handler: WinRtButtonHandler? = null
            try {
                val factory = api.activationFactory(CLASS_SYSTEM_MEDIA_TRANSPORT_CONTROLS, IID_SMTC_INTEROP)
                    ?: error("SMTC activation factory is unavailable")
                try {
                    val smtcRef = PointerByReference()
                    api.callHr(factory, INTEROP_GET_FOR_WINDOW, hwnd, IID_SMTC, smtcRef)
                    smtc = smtcRef.value ?: error("GetForWindow returned no SMTC instance")
                } finally {
                    api.release(factory)
                }

                val updaterRef = PointerByReference()
                api.callHr(smtc, SMTC_GET_DISPLAY_UPDATER, updaterRef)
                updater = updaterRef.value ?: error("SMTC returned no display updater")

                handler = WinRtButtonHandler(api, onCommand)
                val tokenRef = LongByReference()
                api.callHr(smtc, SMTC_ADD_BUTTON_PRESSED, handler.pointer, tokenRef)

                return SmtcSession(
                    api = api,
                    smtc = smtc,
                    displayUpdater = updater,
                    buttonHandler = handler,
                    buttonToken = tokenRef.value,
                    initializedWinRt = true,
                )
            } catch (_: Throwable) {
                handler?.close()
                updater?.let(api::release)
                smtc?.let(api::release)
                api.roUninitialize()
                return null
            }
        }
    }
}

private class WinRtButtonHandler(
    private val api: WinRtApi,
    private val onCommand: (MediaCommand) -> Unit,
) : AutoCloseable {
    private val references = AtomicInteger(1)
    private val objectMemory = Memory(Native.POINTER_SIZE.toLong())
    // WinRT delegate interfaces derive directly from IUnknown, so their vtable is QI/AddRef/Release/Invoke.
    private val vtableMemory = Memory((Native.POINTER_SIZE * 4).toLong())
    private var closed = false

    private val queryInterface = object : QueryInterfaceCallback {
        override fun invoke(thisPointer: Pointer?, riid: Pointer?, out: PointerByReference?): Int {
            return if (riid != null && out != null && supports(riid)) {
                out.value = objectMemory
                references.incrementAndGet()
                S_OK
            } else {
                out?.value = null
                E_NOINTERFACE
            }
        }
    }
    private val addRef = object : AddRefCallback {
        override fun invoke(thisPointer: Pointer?): Int = references.incrementAndGet()
    }
    private val release = object : ReleaseCallback {
        override fun invoke(thisPointer: Pointer?): Int =
            references.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }
    private val invoke = object : InvokeCallback {
        override fun invoke(thisPointer: Pointer?, sender: Pointer?, args: Pointer?): Int {
            if (!closed && args != null) {
                runCatching {
                    val buttonRef = IntByReference()
                    api.callHr(args, BUTTON_ARGS_GET_BUTTON, buttonRef)
                    buttonRef.value.toMediaCommand()?.let(onCommand)
                }
            }
            return S_OK
        }
    }
    private val callbacks: Array<Callback> = arrayOf(
        queryInterface,
        addRef,
        release,
        invoke,
    )

    val pointer: Pointer
        get() = objectMemory

    init {
        callbacks.forEachIndexed { index, callback ->
            vtableMemory.setPointer((index * Native.POINTER_SIZE).toLong(), CallbackReference.getFunctionPointer(callback))
        }
        objectMemory.setPointer(0, vtableMemory)
    }

    private fun supports(riid: Pointer): Boolean {
        val bytes = riid.getByteArray(0, 16)
        return bytes.contentEquals(IID_IUNKNOWN.getByteArray(0, 16)) ||
            bytes.contentEquals(IID_IAGILE_OBJECT.getByteArray(0, 16)) ||
            bytes.contentEquals(IID_BUTTON_PRESSED_HANDLER.getByteArray(0, 16))
    }

    override fun close() {
        closed = true
    }
}

private interface QueryInterfaceCallback : StdCallLibrary.StdCallCallback {
    fun invoke(thisPointer: Pointer?, riid: Pointer?, out: PointerByReference?): Int
}
private interface AddRefCallback : StdCallLibrary.StdCallCallback {
    fun invoke(thisPointer: Pointer?): Int
}
private interface ReleaseCallback : StdCallLibrary.StdCallCallback {
    fun invoke(thisPointer: Pointer?): Int
}
private interface InvokeCallback : StdCallLibrary.StdCallCallback {
    fun invoke(thisPointer: Pointer?, sender: Pointer?, args: Pointer?): Int
}

private class WinRtApi {
    private val combase = NativeLibrary.getInstance("combase")
    private val roInitialize = combase.getFunction("RoInitialize", Function.ALT_CONVENTION)
    private val roUninitialize = combase.getFunction("RoUninitialize", Function.ALT_CONVENTION)
    private val roGetActivationFactory = combase.getFunction("RoGetActivationFactory", Function.ALT_CONVENTION)
    private val windowsCreateString = combase.getFunction("WindowsCreateString", Function.ALT_CONVENTION)
    private val windowsDeleteString = combase.getFunction("WindowsDeleteString", Function.ALT_CONVENTION)

    fun roInitialize(): Int = roInitialize.invokeInt(arrayOf(RO_INIT_MULTITHREADED))
    fun roUninitialize() = roUninitialize.invokeVoid(emptyArray())

    fun activationFactory(className: String, iid: Pointer): Pointer? {
        val result = PointerByReference()
        val hr = withHString(className) { hstring ->
            roGetActivationFactory.invokeInt(arrayOf(hstring, iid, result))
        }
        return result.value.takeIf { hr >= 0 }
    }

    fun queryInterface(pointer: Pointer, iid: Pointer): Pointer? {
        val result = PointerByReference()
        val hr = callRaw(pointer, IUNKNOWN_QUERY_INTERFACE, iid, result)
        return if (hr >= 0) result.value else null
    }

    fun release(pointer: Pointer?) {
        if (pointer != null) runCatching { callRaw(pointer, IUNKNOWN_RELEASE) }
    }

    fun callHr(pointer: Pointer, index: Int, vararg args: Any?) {
        val hr = callRaw(pointer, index, *args)
        if (hr < 0) throw IllegalStateException("WinRT call $index failed: 0x${hr.toUInt().toString(16)}")
    }

    fun tryCallHr(pointer: Pointer, index: Int, vararg args: Any?): Int = callRaw(pointer, index, *args)

    fun <T> withHString(value: String, block: (Pointer?) -> T): T {
        val result = PointerByReference()
        val hr = windowsCreateString.invokeInt(arrayOf(WString(value), value.length, result))
        if (hr < 0) throw IllegalStateException("WindowsCreateString failed: 0x${hr.toUInt().toString(16)}")
        return try {
            block(result.value)
        } finally {
            result.value?.let { windowsDeleteString.invokeInt(arrayOf(it)) }
        }
    }

    private fun callRaw(pointer: Pointer, index: Int, vararg args: Any?): Int {
        val vtable = pointer.getPointer(0)
        val functionPointer = vtable.getPointer((index * Native.POINTER_SIZE).toLong())
        val function = Function.getFunction(functionPointer, Function.ALT_CONVENTION)
        return function.invokeInt(arrayOf(pointer, *args))
    }
}

private fun PlaybackSnapshot.toSmtcPlaybackStatus(): Int = when {
    currentItem == null -> MEDIA_PLAYBACK_STATUS_STOPPED
    status == PlaybackStatus.RESOLVING || status == PlaybackStatus.LOADING || status == PlaybackStatus.BUFFERING ->
        MEDIA_PLAYBACK_STATUS_CHANGING
    isPlaying -> MEDIA_PLAYBACK_STATUS_PLAYING
    playWhenReady.not() -> MEDIA_PLAYBACK_STATUS_PAUSED
    status == PlaybackStatus.ENDED || status == PlaybackStatus.IDLE -> MEDIA_PLAYBACK_STATUS_STOPPED
    else -> MEDIA_PLAYBACK_STATUS_CHANGING
}

private fun Int.toMediaCommand(): MediaCommand? = when (this) {
    SMTC_BUTTON_PLAY -> MediaCommand.PLAY
    SMTC_BUTTON_PAUSE -> MediaCommand.PAUSE
    SMTC_BUTTON_FAST_FORWARD -> MediaCommand.SEEK_FORWARD
    SMTC_BUTTON_REWIND -> MediaCommand.SEEK_BACKWARD
    SMTC_BUTTON_NEXT -> MediaCommand.NEXT
    SMTC_BUTTON_PREVIOUS -> MediaCommand.PREVIOUS
    else -> null
}

private fun guid(value: String): Memory {
    val parts = value.trim('{', '}').split('-')
    require(parts.size == 5)
    return Memory(16).apply {
        setInt(0, parts[0].toLong(16).toInt())
        setShort(4, parts[1].toInt(16).toShort())
        setShort(6, parts[2].toInt(16).toShort())
        val tail = parts[3] + parts[4]
        for (index in 0 until 8) {
            setByte((8 + index).toLong(), tail.substring(index * 2, index * 2 + 2).toInt(16).toByte())
        }
    }
}

private const val S_OK = 0
private const val E_NOINTERFACE = -2147467262 // 0x80004002
private const val RO_INIT_MULTITHREADED = 1
private const val WINRT_FALSE: Byte = 0
private const val WINRT_TRUE: Byte = 1

private const val MEDIA_PLAYBACK_STATUS_CHANGING = 1
private const val MEDIA_PLAYBACK_STATUS_STOPPED = 2
private const val MEDIA_PLAYBACK_STATUS_PLAYING = 3
private const val MEDIA_PLAYBACK_STATUS_PAUSED = 4
private const val MEDIA_PLAYBACK_TYPE_MUSIC = 1

private const val SMTC_BUTTON_PLAY = 0
private const val SMTC_BUTTON_PAUSE = 1
private const val SMTC_BUTTON_FAST_FORWARD = 4
private const val SMTC_BUTTON_REWIND = 5
private const val SMTC_BUTTON_NEXT = 6
private const val SMTC_BUTTON_PREVIOUS = 7

private const val IUNKNOWN_QUERY_INTERFACE = 0
private const val IUNKNOWN_RELEASE = 2
private const val INTEROP_GET_FOR_WINDOW = 6

private const val SMTC_PUT_PLAYBACK_STATUS = 7
private const val SMTC_GET_DISPLAY_UPDATER = 8
private const val SMTC_PUT_IS_ENABLED = 11
private const val SMTC_PUT_IS_PLAY_ENABLED = 13
private const val SMTC_PUT_IS_STOP_ENABLED = 15
private const val SMTC_PUT_IS_PAUSE_ENABLED = 17
private const val SMTC_PUT_IS_FAST_FORWARD_ENABLED = 21
private const val SMTC_PUT_IS_REWIND_ENABLED = 23
private const val SMTC_PUT_IS_PREVIOUS_ENABLED = 25
private const val SMTC_PUT_IS_NEXT_ENABLED = 27
private const val SMTC_ADD_BUTTON_PRESSED = 32
private const val SMTC_REMOVE_BUTTON_PRESSED = 33

private const val BUTTON_ARGS_GET_BUTTON = 6

private const val DISPLAY_PUT_TYPE = 7
private const val DISPLAY_PUT_APP_MEDIA_ID = 9
private const val DISPLAY_PUT_THUMBNAIL = 11
private const val DISPLAY_GET_MUSIC_PROPERTIES = 12
private const val DISPLAY_CLEAR_ALL = 16
private const val DISPLAY_UPDATE = 17

private const val MUSIC_PUT_TITLE = 7
private const val MUSIC_PUT_ALBUM_ARTIST = 9
private const val MUSIC_PUT_ARTIST = 11
private const val MUSIC2_PUT_ALBUM_TITLE = 7

private const val URI_FACTORY_CREATE_URI = 6
private const val STREAM_REFERENCE_CREATE_FROM_URI = 7

private val IID_IUNKNOWN = guid("00000000-0000-0000-c000-000000000046")
private val IID_IAGILE_OBJECT = guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90")
private val IID_SMTC_INTEROP = guid("ddb0472d-c911-4a1f-86d9-dc3d71a95f5a")
private val IID_SMTC = guid("99fa3ff4-1742-42a6-902e-087d41f965ec")
private val IID_BUTTON_PRESSED_HANDLER = guid("0557e996-7b23-5bae-aa81-ea0d671143a4")
private val IID_MUSIC_DISPLAY_PROPERTIES_2 = guid("00368462-97d3-44b9-b00f-008afcefaf18")
private val IID_URI_RUNTIME_CLASS_FACTORY = guid("44a9796f-723e-4fdf-a218-033e75b0c084")
private val IID_RANDOM_ACCESS_STREAM_REFERENCE_STATICS = guid("857309dc-3fbf-4e7d-986f-ef3b1a07a964")

private const val CLASS_SYSTEM_MEDIA_TRANSPORT_CONTROLS = "Windows.Media.SystemMediaTransportControls"
private const val CLASS_URI = "Windows.Foundation.Uri"
private const val CLASS_RANDOM_ACCESS_STREAM_REFERENCE = "Windows.Storage.Streams.RandomAccessStreamReference"
