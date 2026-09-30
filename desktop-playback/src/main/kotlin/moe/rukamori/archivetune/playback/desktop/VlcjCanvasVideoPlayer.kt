/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.component.InputEvents
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.MediaPlayer
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.JPanel
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/**
 * Muted, independent libVLC surface for decorative Canvas videos. It intentionally does not
 * share or reconfigure the application's audio player.
 */
class VlcjCanvasVideoPlayer(
    runtimeConfiguration: VlcRuntimeConfiguration = VlcRuntimeConfiguration(),
) : AutoCloseable {
    private val released = AtomicBoolean(false)
    private val logger = Logger.getLogger("ArchiveTune.DesktopCanvasVideo")
    private val factory: MediaPlayerFactory
    private lateinit var component: CallbackMediaPlayerComponent
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val surface = CanvasVideoSurface { mutableReady.value = true }
    private val mutableFailed = MutableStateFlow(false)
    val failed = mutableFailed.asStateFlow()
    @Volatile private var currentUri: String? = null

    val awtComponent: java.awt.Component
        get() = component

    init {
        val layout = VlcRuntimeLocator.find(runtimeConfiguration)
            ?: throw IllegalStateException("Packaged VLC runtime is unavailable for Canvas video")
        VlcRuntimeLocator.prepareNative(layout)
        factory = MediaPlayerFactory(
            "--quiet",
            "--no-audio",
            "--plugin-path=${layout.plugins}",
        )
        try {
            onAwtEventThread {
                component = CallbackMediaPlayerComponent(factory, null, InputEvents.NONE, true, surface, surface, surface)
                component.mediaPlayer().events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                    override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
                        if (newCount == 0) mutableReady.value = false
                    }

                    override fun error(mediaPlayer: MediaPlayer) {
                        mutableReady.value = false
                        mutableFailed.value = true
                    }

                    override fun finished(mediaPlayer: MediaPlayer) {
                        mutableReady.value = false
                        mutableFailed.value = true
                    }
                })
            }
        } catch (error: Throwable) {
            factory.release()
            throw error
        }
    }

    /** Starts/replaces the decorative video, or pauses it when [playing] is false. */
    fun show(uri: String?, playing: Boolean): Boolean {
        if (released.get()) return false
        val player = component.mediaPlayer()
        return runCatching {
            player.audio().setMute(true)
            if (uri.isNullOrBlank()) {
                player.controls().stop()
                currentUri = null
                mutableReady.value = false
                mutableFailed.value = false
                return@runCatching false
            } else if (!uri.equals(currentUri, ignoreCase = true)) {
                mutableReady.value = false
                mutableFailed.value = false
                if (!player.media().play(uri, ":no-audio", ":input-repeat=999999")) return@runCatching false
                currentUri = uri
                player.audio().setMute(true)
            } else if (playing && !player.status().isPlaying()) {
                player.controls().play()
            }
            if (!playing) player.controls().pause()
            true
        }.onFailure { error ->
            logger.fine("Could not update decorative Canvas video (${error.javaClass.simpleName})")
        }.getOrDefault(false)
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        val release: () -> Unit = {
            runCatching { component.release() }
                .onFailure { logger.fine("Could not release decorative Canvas video (${it.javaClass.simpleName})") }
            runCatching { factory.release() }
                .onFailure { logger.fine("Could not release decorative Canvas runtime (${it.javaClass.simpleName})") }
            Unit
        }
        if (EventQueue.isDispatchThread()) release() else EventQueue.invokeLater(release)
    }

    private fun onAwtEventThread(action: () -> Unit) {
        if (EventQueue.isDispatchThread()) {
            action()
        } else {
            EventQueue.invokeAndWait(action)
        }
    }

    internal fun isPlaying(): Boolean = component.mediaPlayer().status().isPlaying()

    internal fun isMuted(): Boolean = component.mediaPlayer().audio().isMute
}

/** Reuses a small RGB image so decorative video cannot allocate full-resolution frame trees. */
private class CanvasVideoSurface(private val onFrame: () -> Unit) : JPanel(), BufferFormatCallback, RenderCallback {
    private var image: BufferedImage? = null
    private val imageLock = Any()

    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
        val (width, height) = canvasVideoDimensions(sourceWidth, sourceHeight)
        synchronized(imageLock) { image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB) }
        return RV32BufferFormat(width, height)
    }

    override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit
    override fun allocatedBuffers(buffers: Array<ByteBuffer>) = Unit
    override fun lock(mediaPlayer: MediaPlayer) = Unit
    override fun unlock(mediaPlayer: MediaPlayer) = Unit

    override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<ByteBuffer>, format: BufferFormat, displayWidth: Int, displayHeight: Int) {
        synchronized(imageLock) {
            val target = image ?: return
            val pixels = (target.raster.dataBuffer as DataBufferInt).data
            val buffer = nativeBuffers.firstOrNull() ?: return
            val values = buffer.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
            if (values.remaining() < pixels.size) return
            values.get(pixels)
        }
        onFrame()
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        synchronized(imageLock) { image?.let { graphics.drawImage(it, 0, 0, width, height, null) } }
    }
}

internal fun canvasVideoDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
    val width = sourceWidth.coerceAtLeast(1)
    val height = sourceHeight.coerceAtLeast(1)
    val scale = minOf(1.0, 256.0 / maxOf(width, height))
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}
