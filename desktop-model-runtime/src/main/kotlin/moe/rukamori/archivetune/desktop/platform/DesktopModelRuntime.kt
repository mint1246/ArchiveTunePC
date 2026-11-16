/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class DesktopModelAssetSpec(
    val key: String,
    val file: String,
    val size: Long,
    val sha256: String,
)

data class DesktopModelSpec(
    val id: String,
    val manifestKey: String,
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val assets: List<DesktopModelAssetSpec> = emptyList(),
    val modelPathEnvironment: String? = null,
    val assetPathEnvironment: Map<String, String> = emptyMap(),
    val legacyFileNames: List<String> = emptyList(),
)

data class DesktopModelBundle(
    val model: Path,
    val assets: Map<String, Path> = emptyMap(),
) {
    fun asset(key: String): Path =
        assets[key] ?: error("Model bundle is missing required asset '" + key + "'")
}

object DesktopModels {
    val STEMS =
        DesktopModelSpec(
            id = "stems",
            manifestKey = "stems",
            version = "htdemucs-6s-1s-dml-v1",
            file = "htdemucs-6s-core-1s-dml.onnx",
            size = 110_858_418L,
            sha256 = "30ef61623d5d3a768f12dd87faadfdaaca26c5b8ef6a1c73f26b8c949b6e047c",
            modelPathEnvironment = "ARCHIVETUNE_STEM_MODEL",
            legacyFileNames = listOf("htdemucs-core-1s-dml.onnx"),
        )

    val CHORDS =
        DesktopModelSpec(
            id = "chords",
            manifestKey = "chords",
            version = "chordmini-2e1d-v1",
            file = "chordmini-2e1d-v1.onnx",
            size = 9_604_664L,
            sha256 = "9a6570bf611cdc3f2c36286307af46fb94927fe7f6a2bc22a87c0ebf5f6c082e",
            assets =
                listOf(
                    DesktopModelAssetSpec(
                        key = "config",
                        file = "chordmini-2e1d-v1.config.json",
                        size = 3_009L,
                        sha256 = "1f26c11ebea51ec08f12e813eb213a729fa0ecc407ac7632dfdc7bad67e65aa4",
                    ),
                    DesktopModelAssetSpec(
                        key = "cqtPlan",
                        file = "chordmini-2e1d-v1.cqt-plan.bin",
                        size = 23_896L,
                        sha256 = "c31f0a6fd2d582d753be6628b5daecdee58acba53cba93b2bc2b5c75dee2ba48",
                    ),
                    DesktopModelAssetSpec(
                        key = "notice",
                        file = "chordmini-2e1d-v1.NOTICE.md",
                        size = 1_397L,
                        sha256 = "4b5c88848ec1bc339b3948fd145c34087d35be896978fc507a5784a2fd8716fe",
                    ),
                ),
            modelPathEnvironment = "ARCHIVETUNE_CHORD_MODEL",
            assetPathEnvironment =
                mapOf(
                    "config" to "ARCHIVETUNE_CHORD_CONFIG",
                    "cqtPlan" to "ARCHIVETUNE_CHORD_CQT_PLAN",
                    "notice" to "ARCHIVETUNE_CHORD_NOTICE",
                ),
        )
}

data class DesktopModelManifestAsset(
    val file: String,
    val size: Long,
    val sha256: String,
)

data class DesktopModelManifestEntry(
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val assets: Map<String, DesktopModelManifestAsset>,
)

fun parseDesktopModelManifest(
    root: JSONObject,
    spec: DesktopModelSpec,
): DesktopModelManifestEntry {
    val modelJson =
        root.optJSONObject("models")?.optJSONObject(spec.manifestKey)
            ?: (if (spec.id == "stems") root else null)
            ?: error("Model manifest does not contain '" + spec.manifestKey + "'")
    val assetsJson = modelJson.optJSONObject("assets")
    val assets =
        buildMap {
            if (assetsJson != null) {
                for (key in assetsJson.keySet()) {
                    val value = assetsJson.getJSONObject(key)
                    put(
                        key,
                        DesktopModelManifestAsset(
                            file = value.getString("file").trim(),
                            size = value.getLong("size"),
                            sha256 = value.getString("sha256").trim().lowercase(),
                        ),
                    )
                }
            }
        }
    return DesktopModelManifestEntry(
        version = modelJson.getString("version").trim(),
        file = modelJson.getString("file").trim(),
        size = modelJson.getLong("size"),
        sha256 = modelJson.getString("sha256").trim().lowercase(),
        assets = assets,
    )
}

fun validateDesktopModelManifest(
    entry: DesktopModelManifestEntry,
    spec: DesktopModelSpec,
): DesktopModelManifestEntry {
    check(entry.version == spec.version) {
        "Model manifest version " + entry.version + " does not match " + spec.version
    }
    check(entry.file == spec.file) {
        "Model manifest file " + entry.file + " does not match " + spec.file
    }
    check(entry.size == spec.size) {
        "Model manifest size " + entry.size + " does not match " + spec.size
    }
    check(entry.sha256 == spec.sha256) {
        "Model manifest SHA-256 does not match the pinned ArchiveTune " + spec.id + " model"
    }
    for (assetSpec in spec.assets) {
        val asset =
            entry.assets[assetSpec.key]
                ?: error("Model manifest is missing required " + spec.id + " asset '" + assetSpec.key + "'")
        check(asset.file == assetSpec.file) {
            "Model asset " + assetSpec.key + " file " + asset.file + " does not match " + assetSpec.file
        }
        check(asset.size == assetSpec.size) {
            "Model asset " + assetSpec.key + " size " + asset.size + " does not match " + assetSpec.size
        }
        check(asset.sha256 == assetSpec.sha256) {
            "Model asset " + assetSpec.key + " failed pinned SHA-256 validation"
        }
    }
    return entry
}

object DesktopModelRuntime {
    private const val RUNNER_NAME = "archivetune-model-runner.exe"
    private const val LEGACY_RUNNER_NAME = "archivetune-stems.exe"
    private val http =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    private val locks = Array(32) { Mutex() }

    fun findRunner(): Path? {
        val explicit =
            sequenceOf("ARCHIVETUNE_MODEL_RUNNER", "ARCHIVETUNE_STEM_WORKER")
                .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }
                .map(Path::of)
                .map { it.toAbsolutePath().normalize() }
                .firstOrNull(Files::isRegularFile)
        if (explicit != null) return explicit
        val appDir =
            System.getProperty("archivetune.app.dir")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.toAbsolutePath()
                ?.normalize()
        if (appDir != null) {
            val helperDir = appDir.resolve("stem-worker")
            return sequenceOf(RUNNER_NAME, LEGACY_RUNNER_NAME)
                .map(helperDir::resolve)
                .firstOrNull(Files::isRegularFile)
        }
        return developmentRoots()
            .flatMap { root ->
                sequenceOf(
                    root.resolve("stem-worker/target/release/" + RUNNER_NAME),
                    root.resolve("stem-worker/target/debug/" + RUNNER_NAME),
                    root.resolve("stem-worker/target/release/" + LEGACY_RUNNER_NAME),
                    root.resolve("stem-worker/target/debug/" + LEGACY_RUNNER_NAME),
                )
            }
            .firstOrNull(Files::isRegularFile)
    }

    fun findModel(directory: Path, spec: DesktopModelSpec): DesktopModelBundle? {
        explicitBundle(spec)?.let { return it }
        val bundle =
            DesktopModelBundle(
                model = directory.resolve(spec.file),
                assets = spec.assets.associate { asset -> asset.key to directory.resolve(asset.file) },
            )
        if (!isBundleUsable(bundle, spec)) return null
        spec.legacyFileNames.forEach { legacy ->
            runCatching { Files.deleteIfExists(directory.resolve(legacy)) }
        }
        return bundle
    }

    suspend fun ensureModel(
        directory: Path,
        spec: DesktopModelSpec,
        manifestUrl: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DesktopModelBundle {
        val cacheKey = directory.toAbsolutePath().normalize().toString() + "\u0000" + spec.id
        findModel(directory, spec)?.let { return it }
        return locks[(cacheKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            findModel(directory, spec)?.let { return@withLock it }
            downloadModel(directory, spec, manifestUrl, onProgress)
        }
    }

    private suspend fun downloadModel(
        directory: Path,
        spec: DesktopModelSpec,
        defaultManifestUrl: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DesktopModelBundle {
        val manifestUrl =
            sequenceOf(
                System.getenv("ARCHIVETUNE_MODEL_MANIFEST"),
                if (spec.id == "stems") System.getenv("ARCHIVETUNE_STEM_MODEL_MANIFEST") else null,
                defaultManifestUrl,
            ).firstOrNull { !it.isNullOrBlank() }!!
        val manifestUri = URI.create(manifestUrl)
        val response =
            http.send(
                HttpRequest.newBuilder(manifestUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        check(response.statusCode() in 200..299) { "Model manifest HTTP " + response.statusCode() }
        val entry =
            validateDesktopModelManifest(
                parseDesktopModelManifest(JSONObject(response.body()), spec),
                spec,
            )
        Files.createDirectories(directory)
        val total = entry.size + entry.assets.values.sumOf(DesktopModelManifestAsset::size)
        var completed = 0L
        onProgress(0L, total)

        suspend fun downloadOne(file: String, size: Long, expectedHash: String): Path {
            val destination = directory.resolve(file)
            if (isFileUsable(destination, size, expectedHash)) {
                completed += size
                onProgress(completed, total)
                return destination
            }
            val temporary = directory.resolve("." + file + ".part")
            Files.deleteIfExists(temporary)
            var downloaded = 0L
            try {
                val fileResponse =
                    http.send(
                        HttpRequest.newBuilder(manifestUri.resolve(file))
                            .timeout(Duration.ofMinutes(10))
                            .header("Accept", "application/octet-stream")
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofInputStream(),
                    )
                check(fileResponse.statusCode() in 200..299) {
                    "Model download HTTP " + fileResponse.statusCode()
                }
                fileResponse.body().use { input ->
                    Files.newOutputStream(
                        temporary,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    ).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            downloaded += count
                            check(downloaded <= size) { "Model file " + file + " is larger than expected" }
                            onProgress(completed + downloaded, total)
                        }
                    }
                }
                check(downloaded == size) {
                    "Model file " + file + " is incomplete: " + downloaded + " of " + size + " bytes"
                }
                check(sha256(temporary) == expectedHash) {
                    "Downloaded model file " + file + " failed SHA-256 verification"
                }
                atomicReplace(temporary, destination)
                completed += size
                onProgress(completed, total)
                return destination
            } catch (error: Throwable) {
                runCatching { Files.deleteIfExists(temporary) }
                throw error
            }
        }

        val model = downloadOne(entry.file, entry.size, entry.sha256)
        val assets =
            buildMap {
                for (assetSpec in spec.assets) {
                    val asset = checkNotNull(entry.assets[assetSpec.key])
                    put(assetSpec.key, downloadOne(asset.file, asset.size, asset.sha256))
                }
            }
        return DesktopModelBundle(model, assets)
    }

    fun configureRunnerEnvironment(builder: ProcessBuilder, runner: Path) {
        val directory = findRuntimeDirectory(runner) ?: return
        builder.environment()["ARCHIVETUNE_ONNXRUNTIME"] = directory.resolve("onnxruntime.dll").toString()
        val existingPath = builder.environment()["PATH"].orEmpty()
        builder.environment()["PATH"] =
            directory.toString() + if (existingPath.isBlank()) "" else ";" + existingPath
    }

    private fun explicitBundle(spec: DesktopModelSpec): DesktopModelBundle? {
        val model =
            spec.modelPathEnvironment
                ?.let(System::getenv)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.toAbsolutePath()
                ?.normalize()
                ?.takeIf(Files::isRegularFile)
                ?: return null
        val assets = mutableMapOf<String, Path>()
        for (asset in spec.assets) {
            val env = spec.assetPathEnvironment[asset.key] ?: return null
            val path =
                System.getenv(env)
                    ?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                    ?.toAbsolutePath()
                    ?.normalize()
                    ?.takeIf(Files::isRegularFile)
                    ?: return null
            assets[asset.key] = path
        }
        return DesktopModelBundle(model, assets)
    }

    private fun isBundleUsable(bundle: DesktopModelBundle, spec: DesktopModelSpec): Boolean =
        isFileUsable(bundle.model, spec.size, spec.sha256) &&
            spec.assets.all { asset ->
                bundle.assets[asset.key]?.let { isFileUsable(it, asset.size, asset.sha256) } == true
            }

    private fun isFileUsable(path: Path, size: Long, expectedHash: String): Boolean =
        Files.isRegularFile(path) &&
            runCatching { Files.size(path) }.getOrDefault(-1L) == size &&
            runCatching { sha256(path) }.getOrNull() == expectedHash

    private fun atomicReplace(source: Path, destination: Path) {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun findRuntimeDirectory(runner: Path): Path? {
        val explicit =
            sequenceOf("ARCHIVETUNE_MODEL_RUNTIME", "ARCHIVETUNE_STEM_RUNTIME")
                .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }
                .map(Path::of)
                .map { it.toAbsolutePath().normalize() }
                .firstOrNull(::hasRuntime)
        if (explicit != null) return explicit
        runner.parent?.toAbsolutePath()?.normalize()?.takeIf(::hasRuntime)?.let { return it }
        if (!System.getProperty("archivetune.app.dir").isNullOrBlank()) return null
        return developmentRoots().map { it.resolve("build/stem-runtime/windows-x64") }.firstOrNull(::hasRuntime)
    }

    private fun developmentRoots(): Sequence<Path> {
        val starts =
            buildList {
                System.getenv("ARCHIVETUNE_PROJECT_ROOT")
                    ?.takeIf(String::isNotBlank)
                    ?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
                    ?.let(::add)
                runCatching {
                    Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                }.getOrNull()?.let(::add)
            }
        return starts.asSequence().flatMap { start -> generateSequence(start) { it.parent }.take(8) }.distinct()
    }

    private fun hasRuntime(directory: Path): Boolean =
        Files.isRegularFile(directory.resolve("onnxruntime.dll")) &&
            Files.isRegularFile(directory.resolve("DirectML.dll")) &&
            Files.isRegularFile(directory.resolve("onnxruntime_providers_shared.dll"))
}
