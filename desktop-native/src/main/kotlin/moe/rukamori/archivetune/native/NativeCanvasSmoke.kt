package moe.rukamori.archivetune.native

import java.awt.Color
import java.awt.Container
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.desktop.VlcjCanvasVideoPlayer
import kotlin.system.exitProcess

/** Local diagnostic uses the installed VLC tree and exercises Native Image video callbacks. */
internal fun runNativeCanvasSmoke(args: List<String>) {
    val result = runCatching {
        require(args.size == 2) { "Usage: --canvas-smoke <local-video> <output-png>" }
        val video = Path.of(args[0]).toAbsolutePath().normalize()
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        VlcjCanvasVideoPlayer().use { player ->
            check(player.show(video.toUri().toString(), true)) { "VLC rejected the local video" }
            runBlocking { withTimeout(15_000) { player.ready.first { it } } }
            val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
            EventQueue.invokeAndWait {
                val component = player.awtComponent as Container
                component.setSize(64, 64)
                component.doLayout()
                val graphics = image.createGraphics()
                try { component.paint(graphics) } finally { graphics.dispose() }
            }
            val pixel = Color(image.getRGB(32, 32))
            check(pixel.red > 180 && pixel.green < 70) { "Expected a red test frame; got $pixel" }
            check(ImageIO.write(image, "png", output.toFile())) { "PNG writer unavailable" }
            check(player.show(video.toUri().toString(), false)) { "Pause failed" }
            player.show(null, false)
            check(!player.ready.value) { "Stop did not clear frame readiness" }
        }
        EventQueue.invokeAndWait { }
        println("Native Canvas smoke passed: decoded frame, paint, pause, stop and release")
    }
    result.exceptionOrNull()?.printStackTrace()
    exitProcess(if (result.isSuccess) 0 else 1)
}
