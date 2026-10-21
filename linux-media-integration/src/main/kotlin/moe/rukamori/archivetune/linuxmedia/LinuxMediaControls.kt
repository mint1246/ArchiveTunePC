/* ArchiveTune (2026), GPL-3.0. See LICENSE. */
package moe.rukamori.archivetune.linuxmedia

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.InvalidMethodArgument
import org.freedesktop.dbus.errors.NotSupported
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownInterface
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

sealed interface LinuxMediaCommand {
    enum class Transport : LinuxMediaCommand { PLAY, PAUSE, PLAY_PAUSE, STOP, NEXT, PREVIOUS }
    data class Seek(val offsetMs: Long) : LinuxMediaCommand
    data class SetPosition(val trackPath: String, val positionMs: Long) : LinuxMediaCommand
    data class Volume(val volume: Float) : LinuxMediaCommand
    data class Rate(val rate: Float) : LinuxMediaCommand
    data class Repeat(val mode: RepeatMode) : LinuxMediaCommand
    data class Shuffle(val enabled: Boolean) : LinuxMediaCommand
}

enum class LinuxMediaAvailability { NOT_STARTED, ACTIVE, UNAVAILABLE, CLOSED }

/** MPRIS publishes only player metadata; D-Bus callbacks enqueue commands for the app's policy router. */
class LinuxMediaControls(
    val busName: String = "org.mpris.MediaPlayer2.ArchiveTune.instance${ProcessHandle.current().pid()}",
) : AutoCloseable {
    private val logger = Logger.getLogger("moe.rukamori.archivetune.desktop.linuxmedia")
    private val latest = AtomicReference(PlaybackSnapshot())
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val updatePending = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "archivetune-mpris").apply { isDaemon = true }
    }
    private val _commands = MutableSharedFlow<LinuxMediaCommand>(extraBufferCapacity = 64)
    val commands = _commands.asSharedFlow()
    private val _availability = MutableStateFlow(LinuxMediaAvailability.NOT_STARTED)
    val availability = _availability.asStateFlow()
    @Volatile private var connection: DBusConnection? = null
    private var published: Map<String, Variant<*>> = emptyMap()
    private var previousPosition: PlaybackSnapshot? = null
    private var previousUpdateNanos = 0L
    internal val endpoint = Endpoint()

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        worker.execute {
            var opened: DBusConnection? = null
            try {
                opened = DBusConnectionBuilder.forSessionBus().withShared(false).build()
                opened.exportObject(OBJECT_PATH, endpoint)
                opened.requestBusName(busName)
                val accepted = synchronized(lifecycleLock) {
                    if (closed.get()) false else {
                        connection = opened
                        _availability.value = LinuxMediaAvailability.ACTIVE
                        true
                    }
                }
                if (accepted) publishChanges() else opened.close()
            } catch (failure: Exception) {
                runCatching { opened?.close() }
                if (!closed.get()) {
                    _availability.value = LinuxMediaAvailability.UNAVAILABLE
                    logger.warning("Linux media controls unavailable: ${failure.javaClass.simpleName}")
                }
            }
        }
    }

    fun update(snapshot: PlaybackSnapshot) {
        if (closed.get()) return
        latest.set(snapshot)
        if (connection == null || !updatePending.compareAndSet(false, true)) return
        runCatching {
            worker.execute {
                updatePending.set(false)
                if (!closed.get()) publishChanges()
            }
        }.onFailure { updatePending.set(false) }
    }

    private fun publishChanges() {
        val bus = connection ?: return
        val current = playerProperties(latest.get())
        val changed = current.filter { (key, value) -> key != "Position" && published[key]?.value != value.value }
        published = current
        if (changed.isNotEmpty()) runCatching {
            bus.sendMessage(Properties.PropertiesChanged(OBJECT_PATH, PLAYER_INTERFACE, changed, emptyList()))
        }
        val now = System.nanoTime()
        val snapshot = latest.get()
        val previous = previousPosition
        if (previous != null && trackPath(previous) == trackPath(snapshot)) {
            val elapsedMs = (now - previousUpdateNanos).coerceAtLeast(0L) / 1_000_000
            val expected = previous.positionMs + if (previous.isPlaying) (elapsedMs * previous.speed).toLong() else 0L
            if (kotlin.math.abs(snapshot.positionMs - expected) > 1_000L) runCatching {
                bus.sendMessage(MprisInterfaces.Player.Seeked(OBJECT_PATH, micros(snapshot.positionMs)))
            }
        }
        previousPosition = snapshot
        previousUpdateNanos = now
    }

    private fun emit(command: LinuxMediaCommand) {
        if (!closed.get()) _commands.tryEmit(command)
    }

    inner class Endpoint : MprisInterfaces.Root, MprisInterfaces.Player, Properties {
        override fun getObjectPath(): String = OBJECT_PATH
        override fun Raise() = Unit // CanRaise=false
        override fun Quit() = Unit // CanQuit=false
        override fun Play() = emit(LinuxMediaCommand.Transport.PLAY)
        override fun Pause() = emit(LinuxMediaCommand.Transport.PAUSE)
        override fun PlayPause() = emit(LinuxMediaCommand.Transport.PLAY_PAUSE)
        override fun Stop() = emit(LinuxMediaCommand.Transport.STOP)
        override fun Next() = emit(LinuxMediaCommand.Transport.NEXT)
        override fun Previous() = emit(LinuxMediaCommand.Transport.PREVIOUS)
        override fun Seek(offset: Long) = emit(LinuxMediaCommand.Seek(offset / 1_000))
        override fun SetPosition(trackId: DBusPath, position: Long) {
            val snapshot = latest.get()
            if (snapshot.currentItem == null || trackId.path != trackPath(snapshot) || position < 0L) return
            val ms = position / 1_000
            if (snapshot.durationMs > 0 && ms > snapshot.durationMs) return
            emit(LinuxMediaCommand.SetPosition(trackId.path, ms))
        }
        override fun OpenUri(uri: String) { throw NotSupported("Opening external media is not supported") }
        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A =
            (GetAll(interfaceName)[propertyName] ?: throw UnknownProperty("Unknown player property")) as A
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            ROOT_INTERFACE -> rootProperties()
            PLAYER_INTERFACE -> playerProperties(latest.get())
            else -> throw UnknownInterface("Unknown media interface")
        }
        override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
            if (interfaceName != PLAYER_INTERFACE) {
                Get<Any>(interfaceName, propertyName)
                throw PropertyReadOnly("Player property is read only")
            }
            val raw = if (value is Variant<*>) value.value else value
            when (propertyName) {
                "Volume" -> {
                    val number = (raw as? Number)?.toDouble() ?: throw InvalidMethodArgument("Volume must be a number")
                    if (!number.isFinite()) throw InvalidMethodArgument("Volume must be finite")
                    emit(LinuxMediaCommand.Volume(number.coerceIn(0.0, 1.0).toFloat()))
                }
                "Rate" -> {
                    val number = (raw as? Number)?.toDouble() ?: throw InvalidMethodArgument("Rate must be a number")
                    if (!number.isFinite() || number !in 0.25..4.0) throw InvalidMethodArgument("Unsupported playback rate")
                    emit(LinuxMediaCommand.Rate(number.toFloat()))
                }
                "Shuffle" -> emit(LinuxMediaCommand.Shuffle(raw as? Boolean ?: throw InvalidMethodArgument("Shuffle must be boolean")))
                "LoopStatus" -> emit(LinuxMediaCommand.Repeat(when (raw) {
                    "None" -> RepeatMode.OFF
                    "Track" -> RepeatMode.ONE
                    "Playlist" -> RepeatMode.ALL
                    else -> throw InvalidMethodArgument("Unknown repeat mode")
                }))
                else -> { Get<Any>(interfaceName, propertyName); throw PropertyReadOnly("Player property is read only") }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val owned = synchronized(lifecycleLock) {
            _availability.value = LinuxMediaAvailability.CLOSED
            connection.also { connection = null }
        }
        owned?.let { bus ->
            runCatching { bus.unExportObject(OBJECT_PATH) }
            runCatching { bus.releaseBusName(busName) }
            runCatching { bus.close() }
        }
        worker.shutdownNow()
    }

    companion object {
        const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
        const val ROOT_INTERFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
        fun trackPath(snapshot: PlaybackSnapshot): String {
            val entry = snapshot.queueEntries.firstOrNull { it.isCurrent }?.id
            val item = snapshot.currentItem ?: return "/org/mpris/MediaPlayer2/TrackList/NoTrack"
            val identity = entry?.toString()?.replace('-', 'n')
                ?: java.security.MessageDigest.getInstance("SHA-256").digest(item.metadata.id.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            return "/org/mpris/MediaPlayer2/track/t$identity"
        }
        internal fun micros(ms: Long): Long = ms.coerceIn(0L, Long.MAX_VALUE / 1_000) * 1_000
        private fun rootProperties(): Map<String, Variant<*>> = linkedMapOf(
            "Identity" to Variant("ArchiveTune"), "DesktopEntry" to Variant("archivetune"),
            "CanQuit" to Variant(false), "CanRaise" to Variant(false), "HasTrackList" to Variant(false),
            "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
            "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
        )
        internal fun playerProperties(s: PlaybackSnapshot): Map<String, Variant<*>> {
            val item = s.currentItem
            val metadata = linkedMapOf<String, Variant<*>>()
            if (item != null) {
                metadata["mpris:trackid"] = Variant(DBusPath(trackPath(s)), "o")
                metadata["xesam:title"] = Variant(item.metadata.title)
                metadata["xesam:artist"] = Variant(item.metadata.artists.map { it.name }, "as")
                item.metadata.album?.let { metadata["xesam:album"] = Variant(it.title) }
                item.metadata.artworkUri?.takeIf { it.startsWith("https://") || it.startsWith("file:") }?.let {
                    metadata["mpris:artUrl"] = Variant(it)
                }
                if (s.durationMs > 0) metadata["mpris:length"] = Variant(micros(s.durationMs), "x")
            }
            val playing = s.playWhenReady && s.status in setOf(PlaybackStatus.READY, PlaybackStatus.BUFFERING, PlaybackStatus.LOADING, PlaybackStatus.RESOLVING)
            val status = if (playing) "Playing" else if (s.status == PlaybackStatus.READY && item != null) "Paused" else "Stopped"
            return linkedMapOf(
                "PlaybackStatus" to Variant(status),
                "LoopStatus" to Variant(when(s.repeatMode) { RepeatMode.OFF -> "None"; RepeatMode.ONE -> "Track"; RepeatMode.ALL -> "Playlist" }),
                "Rate" to Variant(s.speed.toDouble()), "Shuffle" to Variant(s.shuffleEnabled),
                "Metadata" to Variant(metadata, "a{sv}"), "Volume" to Variant(s.volume.toDouble()),
                "Position" to Variant(micros(s.positionMs), "x"), "MinimumRate" to Variant(0.25), "MaximumRate" to Variant(4.0),
                "CanGoNext" to Variant(s.hasNextItem), "CanGoPrevious" to Variant(s.hasPreviousItem),
                "CanPlay" to Variant(item != null), "CanPause" to Variant(item != null), "CanSeek" to Variant(item != null), "CanControl" to Variant(true),
            )
        }
    }
}
