package moe.rukamori.archivetune.desktop.artwork

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class DesktopArtworkPipelineTest {
    @Test
    fun `concurrent consumers share one artwork load`() = runBlocking {
        val calls = AtomicInteger()
        val image = image(0xFF336699.toInt())
        DesktopArtworkPipeline(loader = {
            calls.incrementAndGet()
            delay(50)
            image
        }).use { pipeline ->
            val first = async { pipeline.load("https://example.test/cover.jpg") }
            val second = async { pipeline.load("https://example.test/cover.jpg") }

            assertSame(image, first.await())
            assertSame(image, second.await())
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `google artwork size variants share normalized cache payload`() = runBlocking {
        val calls = AtomicInteger()
        val image = image(0xFFAA5500.toInt())
        DesktopArtworkPipeline(loader = {
            calls.incrementAndGet()
            image
        }).use { pipeline ->
            val small = "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"
            val large = "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj"

            assertEquals(normalizeRemoteArtworkKey(small), normalizeRemoteArtworkKey(large))
            assertEquals(canonicalRemoteArtworkLocation(small), canonicalRemoteArtworkLocation(large))
            assertSame(image, pipeline.load(small))
            assertSame(image, pipeline.load(large))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `cache is bounded and least recently used entry is evicted`() = runBlocking {
        val calls = HashMap<String, Int>()
        DesktopArtworkPipeline(maxEntries = 2, loader = { source ->
            calls[source.cacheKey] = (calls[source.cacheKey] ?: 0) + 1
            image(source.cacheKey.hashCode())
        }).use { pipeline ->
            val a = "https://example.test/a.jpg"
            val b = "https://example.test/b.jpg"
            val c = "https://example.test/c.jpg"
            pipeline.load(a)
            pipeline.load(b)
            pipeline.load(a) // touch A, so B becomes eldest
            pipeline.load(c)
            pipeline.load(b)

            assertEquals(1, calls[a])
            assertEquals(2, calls[b])
            assertEquals(1, calls[c])
        }
    }

    @Test
    fun `oversized encoded artwork is rejected without retaining partial payload`() = runBlocking {
        val maximum = 32
        val exact = ByteArray(maximum) { it.toByte() }
        val oversized = ByteArray(maximum + 1) { it.toByte() }

        assertEquals(exact.toList(), readBoundedArtworkBytes(ByteArrayInputStream(exact), maximum)?.toList())
        assertNull(readBoundedArtworkBytes(ByteArrayInputStream(oversized), maximum))
    }

    @Test
    fun `cancelled consumer does not poison shared in flight result`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val image = image(0xFF123456.toInt())
        DesktopArtworkPipeline(loader = {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
            image
        }).use { pipeline ->
            val staleConsumer = async { pipeline.load("https://example.test/cover.jpg") }
            started.await()
            val currentConsumer = async { pipeline.load("https://example.test/cover.jpg") }
            delay(10) // let the current consumer attach to the same in-flight request
            staleConsumer.cancelAndJoin()
            release.complete(Unit)

            assertSame(image, currentConsumer.await())
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `in flight load is cancelled when its last consumer disappears`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        DesktopArtworkPipeline(loader = {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }).use { pipeline ->
            val consumer = async { pipeline.load("https://example.test/cover.jpg") }
            started.await()
            consumer.cancelAndJoin()
            cancelled.await()
        }
    }

    @Test
    fun `failed load is retryable rather than cached as failure`() = runBlocking {
        val calls = AtomicInteger()
        val image = image(0xFF654321.toInt())
        DesktopArtworkPipeline(loader = {
            if (calls.incrementAndGet() == 1) null else image
        }).use { pipeline ->
            assertNull(pipeline.load("https://example.test/cover.jpg"))
            assertSame(image, pipeline.load("https://example.test/cover.jpg"))
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `decoder downsamples source while retaining aspect and representative pixels`() {
        val source = BufferedImage(1024, 512, BufferedImage.TYPE_INT_RGB)
        source.createGraphics().use { graphics ->
            graphics.color = java.awt.Color(20, 80, 160)
            graphics.fillRect(0, 0, source.width, source.height)
        }
        val bytes = ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(source, "png", output))
            output.toByteArray()
        }

        val decoded = assertNotNull(decodeArtworkSmall(bytes, targetMaxDimension = 256))
        assertTrue(decoded.width <= 256)
        assertTrue(decoded.height <= 256)
        assertEquals(2.0, decoded.width.toDouble() / decoded.height.toDouble(), absoluteTolerance = 0.02)
        val pixel = decoded.argb[(decoded.height / 2) * decoded.width + decoded.width / 2]
        assertTrue(kotlin.math.abs(20 - (pixel ushr 16 and 0xFF)) <= 2)
        assertTrue(kotlin.math.abs(80 - (pixel ushr 8 and 0xFF)) <= 2)
        assertTrue(kotlin.math.abs(160 - (pixel and 0xFF)) <= 2)
    }

    @Test
    fun `normalization never strips arbitrary non google query data`() {
        val signed = "https://example.test/cover.jpg?token=secret&w=120&h=120"
        assertEquals(signed, normalizeRemoteArtworkKey(signed))
        assertFalse(normalizeRemoteArtworkKey(signed).contains("#"))
    }

    @Test
    fun `google query parameters are preserved conservatively`() {
        val signed = "https://lh3.googleusercontent.com/cover?token=keep&w=120&h=120"

        assertEquals(signed, normalizeRemoteArtworkKey(signed))
        assertEquals(signed, canonicalRemoteArtworkLocation(signed))
    }

    private fun image(pixel: Int) = DesktopArtworkImage(1, 1, intArrayOf(pixel))

    private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R =
        try {
            block(this)
        } finally {
            dispose()
        }
}
