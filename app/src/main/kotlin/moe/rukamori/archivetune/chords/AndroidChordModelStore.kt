/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.chords

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

internal data class AndroidChordAssetSpec(
    val key: String,
    val file: String,
    val size: Long,
    val sha256: String,
)

internal object AndroidChordModelSpec {
    // Android uses a statically-shaped single-window graph. The upstream/directml export leaves
    // the batch dimension symbolic, which expands into Shape/Range/Scatter machinery that none of
    // our accelerated Android EPs can own completely while generic CPU fallback is disabled.
    const val VERSION = "chordmini-2e1d-android-static1-v1"
    const val FILE = "chordmini-2e1d-android-static1-v1.onnx"
    const val SIZE = 9_445_643L
    const val SHA256 = "490f787d6b58388d2443ae1048399058d4e0bad23553a4b55edac05b942b56d1"
    const val MANIFEST_URL = "https://pub-90881360725b42cab24caebe4e55fa2d.r2.dev/models/manifest.json"
    const val SAMPLE_RATE = 22_050
    const val SEQUENCE_LENGTH = 108
    const val INPUT_BINS = 144
    const val OUTPUT_CLASSES = 170

    val assets =
        listOf(
            AndroidChordAssetSpec(
                key = "config",
                file = "chordmini-2e1d-v1.config.json",
                size = 3_009L,
                sha256 = "1f26c11ebea51ec08f12e813eb213a729fa0ecc407ac7632dfdc7bad67e65aa4",
            ),
            AndroidChordAssetSpec(
                key = "cqtPlan",
                file = "chordmini-2e1d-v1.cqt-plan.bin",
                size = 23_896L,
                sha256 = "c31f0a6fd2d582d753be6628b5daecdee58acba53cba93b2bc2b5c75dee2ba48",
            ),
            AndroidChordAssetSpec(
                key = "notice",
                file = "chordmini-2e1d-v1.NOTICE.md",
                size = 1_397L,
                sha256 = "4b5c88848ec1bc339b3948fd145c34087d35be896978fc507a5784a2fd8716fe",
            ),
        )
}

internal data class AndroidChordManifest(
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val assets: Map<String, AndroidChordAssetSpec>,
)

internal data class AndroidChordModelBundle(
    val model: File,
    val config: File,
    val cqtPlan: File,
    val notice: File,
)

/** Chord-local downloader until the shared Android model store exposes generic bundles. */
internal class AndroidChordModelStore(
    private val root: File,
    private val manifestUrl: String = AndroidChordModelSpec.MANIFEST_URL,
) {
    private val lock = Mutex()

    suspend fun ensureBundle(onProgress: (asset: String, downloaded: Long, total: Long) -> Unit = { _, _, _ -> }): AndroidChordModelBundle =
        lock.withLock {
            withContext(Dispatchers.IO) {
                root.mkdirs()
                val allSpecs = listOf(AndroidChordAssetSpec("model", AndroidChordModelSpec.FILE, AndroidChordModelSpec.SIZE, AndroidChordModelSpec.SHA256)) + AndroidChordModelSpec.assets
                val destinations = allSpecs.associate { it.key to File(root, it.file) }
                if (allSpecs.all { spec -> isVerified(checkNotNull(destinations[spec.key]), spec) }) {
                    return@withContext bundle(destinations)
                }

                val manifest = parseAndValidateManifest(fetchText(manifestUrl))
                val manifestSpecs =
                    listOf(AndroidChordAssetSpec("model", manifest.file, manifest.size, manifest.sha256)) +
                        AndroidChordModelSpec.assets.map { checkNotNull(manifest.assets[it.key]) }
                for (spec in manifestSpecs) {
                    currentCoroutineContext().ensureActive()
                    val destination = checkNotNull(destinations[spec.key])
                    if (isVerified(destination, spec)) continue
                    val temporary = File(root, ".${spec.file}.${UUID.randomUUID()}.part")
                    try {
                        downloadVerified(resolveRelativeUrl(manifestUrl, spec.file), temporary, spec, onProgress)
                        if (destination.exists() && !destination.delete()) error("Could not replace ${spec.file}")
                        if (!temporary.renameTo(destination)) {
                            temporary.copyTo(destination, overwrite = true)
                            temporary.delete()
                        }
                        check(isVerified(destination, spec)) { "Installed ${spec.file} failed verification" }
                    } finally {
                        temporary.delete()
                    }
                }
                bundle(destinations)
            }
        }

    private fun bundle(files: Map<String, File>) =
        AndroidChordModelBundle(
            model = checkNotNull(files["model"]),
            config = checkNotNull(files["config"]),
            cqtPlan = checkNotNull(files["cqtPlan"]),
            notice = checkNotNull(files["notice"]),
        )

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
        spec: AndroidChordAssetSpec,
        onProgress: (String, Long, Long) -> Unit,
    ) {
        val connection = open(url, "application/octet-stream", 30_000, 10 * 60_000)
        try {
            check(connection.responseCode in 200..299) { "Chord model asset HTTP ${connection.responseCode}" }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            FileOutputStream(destination).use { output ->
                connection.inputStream.buffered(1024 * 1024).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        downloaded += count
                        check(downloaded <= spec.size) { "${spec.file} is larger than its pinned size" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(spec.key, downloaded, spec.size)
                    }
                    output.fd.sync()
                }
            }
            check(downloaded == spec.size) { "${spec.file} is incomplete: $downloaded / ${spec.size}" }
            check(digest.digest().toHex() == spec.sha256) { "${spec.file} SHA-256 mismatch" }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, accept: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", accept)
            instanceFollowRedirects = true
        }

    companion object {
        internal fun parseAndValidateManifest(json: String): AndroidChordManifest {
            val root = JSONObject(json)
            check(root.optInt("schemaVersion", -1) == 2) { "Unsupported model manifest schema" }
            val entry = root.getJSONObject("models").getJSONObject("chordsAndroid")
            check(entry.getString("version").trim() == AndroidChordModelSpec.VERSION) { "Unexpected chord model version" }
            check(entry.getString("file").trim() == AndroidChordModelSpec.FILE) { "Unexpected chord model file" }
            check(entry.getLong("size") == AndroidChordModelSpec.SIZE) { "Unexpected chord model size" }
            check(entry.getString("sha256").trim().lowercase() == AndroidChordModelSpec.SHA256) { "Unexpected chord model SHA-256" }
            check(entry.getInt("sampleRate") == AndroidChordModelSpec.SAMPLE_RATE) { "Unexpected chord sample rate" }
            check(entry.getInt("sequenceLength") == AndroidChordModelSpec.SEQUENCE_LENGTH) { "Unexpected chord sequence length" }
            check(entry.getInt("inputBins") == AndroidChordModelSpec.INPUT_BINS) { "Unexpected chord input bins" }
            check(entry.getInt("chordClasses") == AndroidChordModelSpec.OUTPUT_CLASSES) { "Unexpected chord class count" }
            check(entry.getString("modelType") == "chordmini-chordnet-2e1d") { "Unexpected chord model type" }
            val assetsJson = entry.getJSONObject("assets")
            val assets =
                AndroidChordModelSpec.assets.associate { pinned ->
                    val value = assetsJson.getJSONObject(pinned.key)
                    val parsed =
                        AndroidChordAssetSpec(
                            key = pinned.key,
                            file = value.getString("file").trim(),
                            size = value.getLong("size"),
                            sha256 = value.getString("sha256").trim().lowercase(),
                        )
                    check(parsed == pinned) { "Unexpected chord asset ${pinned.key}" }
                    pinned.key to parsed
                }
            return AndroidChordManifest(
                version = AndroidChordModelSpec.VERSION,
                file = AndroidChordModelSpec.FILE,
                size = AndroidChordModelSpec.SIZE,
                sha256 = AndroidChordModelSpec.SHA256,
                assets = assets,
            )
        }

        internal fun isVerified(file: File, spec: AndroidChordAssetSpec): Boolean =
            file.isFile && file.length() == spec.size && sha256(file) == spec.sha256

        private fun sha256(file: File): String {
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

        private fun resolveRelativeUrl(manifestUrl: String, child: String): String = URL(URL(manifestUrl), child).toString()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
