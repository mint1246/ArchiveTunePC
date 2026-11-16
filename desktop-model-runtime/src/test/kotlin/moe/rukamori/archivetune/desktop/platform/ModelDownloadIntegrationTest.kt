package moe.rukamori.archivetune.desktop.platform

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ModelDownloadIntegrationTest {
    @Test
    fun `rejects corrupted downloads and removes partial files`() = runBlocking {
        val expected = "expected".toByteArray()
        val wrong = "tampered".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }
        val spec = DesktopModelSpec("bad", "bad", "v1", "model.bin", expected.size.toLong(), hash)
        val entry = JSONObject().put("version", "v1").put("file", "model.bin").put("size", expected.size).put("sha256", hash)
        val manifest = JSONObject().put("models", JSONObject().put("bad", entry)).toString().toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = if (exchange.requestURI.path == "/manifest.json") manifest else wrong
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val directory = Files.createTempDirectory("model-runtime-invalid")
        try {
            assertFailsWith<IllegalStateException> {
                DesktopModelRuntime.ensureModel(directory, spec, "http://127.0.0.1:${server.address.port}/manifest.json")
            }
            assertFalse(Files.exists(directory.resolve("model.bin")))
            assertFalse(Files.exists(directory.resolve(".model.bin.part")))
        } finally {
            server.stop(0)
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `downloads pinned model and asset coalesces requests and separates cache directories`() = runBlocking {
        val model = "model bytes".toByteArray()
        val asset = "asset bytes".toByteArray()
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val spec = DesktopModelSpec(
            id = "test", manifestKey = "test", version = "v1", file = "model.bin", size = model.size.toLong(), sha256 = hash(model),
            assets = listOf(DesktopModelAssetSpec("config", "config.bin", asset.size.toLong(), hash(asset))),
        )
        val entry = JSONObject().put("version", spec.version).put("file", spec.file).put("size", spec.size).put("sha256", spec.sha256)
            .put("assets", JSONObject().put("config", JSONObject().put("file", "config.bin").put("size", asset.size).put("sha256", hash(asset))))
        val manifest = JSONObject().put("models", JSONObject().put("test", entry)).toString().toByteArray()
        val manifestRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = when (exchange.requestURI.path) {
                "/manifest.json" -> { manifestRequests.incrementAndGet(); manifest }
                "/model.bin" -> model
                "/config.bin" -> asset
                else -> error("Unexpected request")
            }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val directory = Files.createTempDirectory("model-runtime-test")
        val otherDirectory = Files.createTempDirectory("model-runtime-other")
        try {
            val url = "http://127.0.0.1:${server.address.port}/manifest.json"
            val bundles = List(2) { async(Dispatchers.IO) { DesktopModelRuntime.ensureModel(directory, spec, url) } }.awaitAll()
            assertEquals(1, manifestRequests.get())
            assertContentEquals(model, Files.readAllBytes(bundles.first().model))
            assertContentEquals(asset, Files.readAllBytes(bundles.first().asset("config")))
            assertNull(DesktopModelRuntime.findModel(otherDirectory, spec))
            Files.write(bundles.first().model, "corrupt".toByteArray())
            assertNull(DesktopModelRuntime.findModel(directory, spec))
            val redownloaded = DesktopModelRuntime.ensureModel(directory, spec, url)
            assertContentEquals(model, Files.readAllBytes(redownloaded.model))
            assertEquals(2, manifestRequests.get())
        } finally {
            server.stop(0)
            for (root in listOf(directory, otherDirectory)) {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
}
