package moe.rukamori.archivetune.playback.desktop

import java.awt.Color
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VlcjCanvasVideoPlayerTest {
    @Test
    fun `frame dimensions are bounded for high resolution videos`() {
        assertEquals(144 to 256, canvasVideoDimensions(2160, 3840))
        assertEquals(64 to 64, canvasVideoDimensions(64, 64))
        assertEquals(1 to 1, canvasVideoDimensions(0, 0))
    }

    @Test
    fun `shipped vmem runtime renders real video frames without a window output plugin`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val video = Files.createTempFile("archivetune-canvas-", ".mkv")
        Files.write(video, fixture())
        System.getenv("ARCHIVETUNE_CANVAS_FIXTURE_OUTPUT")?.let { Files.copy(video, java.nio.file.Path.of(it), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        val player = VlcjCanvasVideoPlayer(VlcRuntimeConfiguration(directory = runtime.root))
        try {
            assertTrue(player.show(video.toUri().toString(), true))
            withTimeout(10_000) { player.ready.first { it } }
            val rendered = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
            EventQueue.invokeAndWait {
                val component = player.awtComponent
                component.setSize(64, 64)
                (component as java.awt.Container).doLayout()
                val graphics = rendered.createGraphics()
                try { component.paint(graphics) } finally { graphics.dispose() }
            }
            val pixel = Color(rendered.getRGB(32, 32))
            assertTrue(pixel.red > 180 && pixel.green < 70, "Decoded callback frame must reach the artwork surface: $pixel")
            assertTrue(player.show(video.toUri().toString(), false))
            player.show(null, false)
            assertEquals(false, player.ready.value)
        } finally {
            player.close()
            EventQueue.invokeAndWait { }
            Files.deleteIfExists(video)
        }
    }

    /** Tiny MJPEG Matroska fixture encoded with the JDK, using the packaged mkv/avcodec plugins. */
    private fun fixture(): ByteArray {
        fun unsigned(value: Long): ByteArray {
            val bytes = ByteArray(8) { index -> (value ushr (56 - index * 8)).toByte() }
            return bytes.dropWhile { it == 0.toByte() }.ifEmpty { listOf(0.toByte()) }.toByteArray()
        }
        fun element(id: String, payload: ByteArray): ByteArray {
            val identifier = id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            var length = 1
            while (payload.size.toLong() >= (1L shl (7 * length)) - 1) length++
            val size = payload.size.toLong() or (1L shl (7 * length))
            val encodedSize = ByteArray(length) { index -> (size ushr (8 * (length - index - 1))).toByte() }
            return identifier + encodedSize + payload
        }
        fun number(id: String, value: Long) = element(id, unsigned(value))
        fun text(id: String, value: String) = element(id, value.toByteArray())
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().run { color = Color.RED; fillRect(0, 0, 64, 64); dispose() }
        val jpeg = ByteArrayOutputStream().apply { ImageIO.write(image, "jpeg", this) }.toByteArray()
        val header = element("1A45DFA3", number("4286", 1) + number("42F7", 1) + number("42F2", 4) + number("42F3", 8) + text("4282", "matroska") + number("4287", 4) + number("4285", 2))
        val info = element("1549A966", number("2AD7B1", 1_000_000) + text("4D80", "ArchiveTune test") + text("5741", "ArchiveTune test"))
        val track = element("AE", number("D7", 1) + number("73C5", 1) + number("83", 1) + number("23E383", 100_000_000) + text("86", "V_MJPEG") + element("E0", number("B0", 64) + number("BA", 64)))
        val blocks = ByteArrayOutputStream().apply {
            repeat(40) { index ->
                val timestamp = index * 100
                write(element("A3", byteArrayOf(0x81.toByte(), (timestamp ushr 8).toByte(), timestamp.toByte(), 0x80.toByte()) + jpeg))
            }
        }.toByteArray()
        return header + element("18538067", info + element("1654AE6B", track) + element("1F43B675", number("E7", 0) + blocks))
    }
}
