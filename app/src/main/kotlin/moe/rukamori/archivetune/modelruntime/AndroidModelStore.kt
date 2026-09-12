/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.modelruntime

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AndroidModelAsset(
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String,
)

/** Android-fixed HTDemucs graph derived from the desktop 1-second six-source model. */
object AndroidStemModelSpec {
    const val VERSION = "htdemucs-6s-1s-android-v1"
    const val FILE = "htdemucs-6s-core-1s-android-v1.onnx"
    const val SIZE = 115_120_667L
    const val SHA256 = "f5d58cd44d88e55c4a4309969ce517f55803a58e329fc8bf54fbb479afcb7855"
    val LEGACY_FILES = setOf("htdemucs-6s-core-1s-dml.onnx")
    const val MANIFEST_URL = "https://pub-90881360725b42cab24caebe4e55fa2d.r2.dev/models/manifest.json"
}

class AndroidModelStore(
    private val root: File,
    private val manifestUrl: String = AndroidStemModelSpec.MANIFEST_URL,
) {
    private val lock = Mutex()

    suspend fun ensureStemModel(onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): File =
        lock.withLock {
            withContext(Dispatchers.IO) {
                root.mkdirs()
                val destination = File(root, AndroidStemModelSpec.FILE)
                if (isVerified(destination)) return@withContext destination

                val manifest = fetchText(manifestUrl)
                val asset = validateStemManifest(parseStemManifest(manifest))
                val temporary = File(root, ".${asset.file}.${UUID.randomUUID()}.part")
                try {
                    onProgress(0L, asset.size)
                    downloadVerified(resolveRelativeUrl(manifestUrl, asset.file), temporary, asset, onProgress)
                    if (destination.exists() && !destination.delete()) error("Could not replace old stem model")
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        if (!temporary.delete()) temporary.deleteOnExit()
                    }
                    check(isVerified(destination)) { "Installed HTDemucs model failed verification" }
                    AndroidStemModelSpec.LEGACY_FILES.forEach { legacy ->
                        File(root, legacy).takeIf(File::isFile)?.delete()
                    }
                    destination
                } finally {
                    temporary.delete()
                }
            }
        }

    internal fun isVerified(file: File): Boolean =
        file.isFile && file.length() == AndroidStemModelSpec.SIZE && sha256(file) == AndroidStemModelSpec.SHA256

    private fun fetchText(url: String): String {
        val connection = open(url, "application/json", 30_000, 30_000)
        return try {
            check(connection.responseCode in 200..299) { "Model manifest HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        asset: AndroidModelAsset,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val connection = open(url, "application/octet-stream", 30_000, 10 * 60_000)
        try {
            check(connection.responseCode in 200..299) { "Stem model HTTP ${connection.responseCode}" }
            var downloaded = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(destination).use { output ->
                connection.inputStream.buffered(1024 * 1024).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        downloaded += count
                        check(downloaded <= asset.size) { "Stem model is larger than pinned size" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(downloaded, asset.size)
                    }
                    output.fd.sync()
                }
            }
            check(downloaded == asset.size) { "Stem model is incomplete: $downloaded / ${asset.size}" }
            val actualHash = digest.digest().toHex()
            check(actualHash == asset.sha256) { "Stem model SHA-256 mismatch" }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        url: String,
        accept: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", accept)
            instanceFollowRedirects = true
        }

    companion object {
        fun parseStemManifest(json: String): AndroidModelAsset {
            val root = JSONObject(json)
            val models = root.optJSONObject("models")
            val entry = models?.optJSONObject("stemsAndroid") ?: models?.optJSONObject("stems") ?: root
            return AndroidModelAsset(
                version = entry.getString("version").trim(),
                file = entry.getString("file").trim(),
                size = entry.getLong("size"),
                sha256 = entry.getString("sha256").trim().lowercase(),
            )
        }

        fun validateStemManifest(asset: AndroidModelAsset): AndroidModelAsset = asset.also {
            check(it.version == AndroidStemModelSpec.VERSION) { "Unexpected stem model version ${it.version}" }
            check(it.file == AndroidStemModelSpec.FILE) { "Unexpected stem model file ${it.file}" }
            check(it.size == AndroidStemModelSpec.SIZE) { "Unexpected stem model size ${it.size}" }
            check(it.sha256 == AndroidStemModelSpec.SHA256) { "Unexpected stem model SHA-256" }
        }

        internal fun resolveRelativeUrl(manifestUrl: String, child: String): String = URL(URL(manifestUrl), child).toString()

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1024 * 1024).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().toHex()
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
