package moe.rukamori.archivetune.playback.desktop

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VlcjCallbackMediaTest {
    @Test
    fun `materialize downloads the complete ranged object and preserves headers`() {
        val body = ByteArray(4 * 1024 * 1024 + 257) { index -> (index % 251).toByte() }
        withServer(
            handler = { exchange ->
                if (exchange.requestHeaders.getFirst("X-ArchiveTune-Test") != "preserved") {
                    exchange.sendResponseHeaders(400, -1)
                    exchange.close()
                    return@withServer
                }
                val range = exchange.requestHeaders.getFirst("Range")
                    ?: error("materialize request did not include Range")
                val requested = range.removePrefix("bytes=").split('-', limit = 2)
                val start = requested[0].toLong()
                val requestedEnd = requested[1].toLong()
                val end = minOf(requestedEnd, body.lastIndex.toLong())
                val length = (end - start + 1).toInt()
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
                exchange.sendResponseHeaders(206, length.toLong())
                exchange.responseBody.use { output ->
                    output.write(body, start.toInt(), length)
                }
                exchange.close()
            },
        ) { url, requestCount ->
            val destination = Files.createTempFile("archivetune-materialized-", ".media")
            try {
                VlcjRangeHttpTransport().use { transport ->
                    transport.materialize(
                        url = url,
                        headers = mapOf("X-ArchiveTune-Test" to "preserved", "Range" to "ignored"),
                        destination = destination,
                    )
                }
                assertEquals(2, requestCount.get())
                assertContentEquals(body, Files.readAllBytes(destination))
            } finally {
                Files.deleteIfExists(destination)
            }
        }
    }

    @Test
    fun `initial http failure reaches recovery callback`() {
        listOf(403, 416).forEach { status ->
            withServer(
                handler = { exchange ->
                    exchange.sendResponseHeaders(status, -1)
                    exchange.close()
                },
            ) { url, _ ->
                val failures = mutableListOf<Int>()
                VlcjRangeHttpTransport().use { transport ->
                    transport.media(url, onHttpFailure = failures::add).use { media ->
                        assertEquals(0L, media.openSize())
                    }
                }
                assertEquals(listOf(status), failures)
            }
        }
    }

    @Test
    fun `closing media preserves shared client for the next track`() = withServer(
        handler = { exchange ->
            val body = byteArrayOf(1, 2, 3, 4)
            exchange.responseHeaders.add("Content-Range", "bytes 0-3/4")
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        },
    ) { url, requestCount ->
        val transport = VlcjRangeHttpTransport()
        val executor = transport.client.dispatcher.executorService

        transport.media(url).use { first ->
            assertEquals(4L, first.openSize())
        }
        assertFalse(executor.isShutdown)

        transport.media(url).use { second ->
            assertEquals(4L, second.openSize())
        }
        assertEquals(2, requestCount.get())
        assertFalse(executor.isShutdown)

        transport.close()
        assertTrue(executor.isShutdown)
        assertFailsWith<IllegalStateException> { transport.media(url) }
    }

    @Test
    fun `closing one media cancels only its active call`() {
        val slowStarted = CountDownLatch(1)
        val slowServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        slowServer.createContext("/audio") { exchange ->
            val chunk = ByteArray(64 * 1024) { 7 }
            exchange.responseHeaders.add("Content-Range", "bytes 0-${8 * 1024 * 1024 - 1}/${8 * 1024 * 1024}")
            exchange.sendResponseHeaders(206, (8 * 1024 * 1024).toLong())
            slowStarted.countDown()
            try {
                exchange.responseBody.use { output ->
                    repeat(128) {
                        output.write(chunk)
                        output.flush()
                        Thread.sleep(25)
                    }
                }
            } catch (_: IOException) {
                // Expected when the media-specific OkHttp call is cancelled.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                exchange.close()
            }
        }
        slowServer.start()

        val fastServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        fastServer.createContext("/audio") { exchange ->
            val body = byteArrayOf(1, 2, 3, 4)
            exchange.responseHeaders.add("Content-Range", "bytes 0-3/4")
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        fastServer.start()

        val executor = Executors.newSingleThreadExecutor()
        val recoveryCalls = AtomicInteger()
        val transport = VlcjRangeHttpTransport()
        val slowUrl = "http://${slowServer.address.hostString}:${slowServer.address.port}/audio"
        val fastUrl = "http://${fastServer.address.hostString}:${fastServer.address.port}/audio"
        val first = transport.media(slowUrl, onHttpFailure = { recoveryCalls.incrementAndGet() })
        try {
            val opening = executor.submit<Long> { first.openSize() }
            assertTrue(slowStarted.await(2, TimeUnit.SECONDS))

            first.close()
            assertEquals(0L, opening.get(2, TimeUnit.SECONDS))
            assertEquals(0, recoveryCalls.get())

            transport.media(fastUrl).use { second ->
                assertEquals(4L, second.openSize())
            }
            assertFalse(transport.client.dispatcher.executorService.isShutdown)
        } finally {
            first.close()
            transport.close()
            executor.shutdownNow()
            slowServer.stop(0)
            fastServer.stop(0)
        }
    }

    @Test
    fun `whole object fallback is file backed seekable and deleted on close`() = withServer(
        handler = { exchange ->
            val body = ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
            if (exchange.requestHeaders.getFirst("Range") != null) {
                exchange.sendResponseHeaders(416, -1)
            } else {
                // Zero selects chunked transfer, exercising the unknown-length bounded streaming path.
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        },
    ) { url, _ ->
        val body = ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
        val fallbackDirectory = Files.createTempDirectory("archivetune-vlcj-fallback-")
        val transport = VlcjRangeHttpTransport()
        val failures = mutableListOf<Int>()
        val media = VlcjRangeCallbackMedia(
            url = url,
            onHttpFailure = failures::add,
            httpClient = transport.client,
            fallbackDirectory = fallbackDirectory,
        )
        try {
            assertEquals(body.size.toLong(), media.openSize())
            assertTrue(failures.isEmpty())
            Files.list(fallbackDirectory).use { files ->
                val paths = files.toList()
                assertEquals(1, paths.size)
                assertEquals(body.size.toLong(), Files.size(paths.single()))
            }

            val prefix = ByteArray(257)
            assertEquals(prefix.size, media.readInto(prefix, prefix.size))
            assertContentEquals(body.copyOfRange(0, prefix.size), prefix)

            val seekOffset = 1_234_567
            assertTrue(media.seekTo(seekOffset.toLong()))
            val middle = ByteArray(513)
            assertEquals(middle.size, media.readInto(middle, middle.size))
            assertContentEquals(body.copyOfRange(seekOffset, seekOffset + middle.size), middle)

            assertTrue(media.seekTo((body.size - 37).toLong()))
            val tail = ByteArray(128)
            assertEquals(37, media.readInto(tail, tail.size))
            assertContentEquals(body.copyOfRange(body.size - 37, body.size), tail.copyOf(37))
            assertEquals(-1, media.readInto(tail, tail.size))
        } finally {
            media.close()
            transport.close()
        }
        Files.list(fallbackDirectory).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `unknown length whole object fallback respects byte cap and cleans partial file`() = withServer(
        handler = { exchange ->
            if (exchange.requestHeaders.getFirst("Range") != null) {
                exchange.sendResponseHeaders(416, -1)
            } else {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(ByteArray(128 * 1024) { 9 }) }
            }
            exchange.close()
        },
    ) { url, _ ->
        val fallbackDirectory = Files.createTempDirectory("archivetune-vlcj-cap-")
        val failures = mutableListOf<Int>()
        VlcjRangeHttpTransport().use { transport ->
            VlcjRangeCallbackMedia(
                url = url,
                onHttpFailure = failures::add,
                httpClient = transport.client,
                fallbackDirectory = fallbackDirectory,
                maxFullObjectFallbackBytes = 64L * 1024L,
            ).use { media ->
                assertEquals(0L, media.openSize())
            }
        }
        assertEquals(listOf(416), failures)
        Files.list(fallbackDirectory).use { assertEquals(0L, it.count()) }
    }

    private fun withServer(
        handler: (com.sun.net.httpserver.HttpExchange) -> Unit,
        block: (String, AtomicInteger) -> Unit,
    ) {
        val requestCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/audio") { exchange ->
            requestCount.incrementAndGet()
            handler(exchange)
        }
        server.start()
        try {
            block("http://${server.address.hostString}:${server.address.port}/audio", requestCount)
        } finally {
            server.stop(0)
        }
    }
}
