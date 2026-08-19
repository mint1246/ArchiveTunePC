package moe.rukamori.archivetune.playback.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VlcjCallbackMediaTest {
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
