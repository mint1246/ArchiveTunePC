/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import androidx.datastore.preferences.core.edit
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.App
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.constants.R2UpdateManifestEtagKey
import moe.rukamori.archivetune.constants.R2UpdateManifestJsonKey
import moe.rukamori.archivetune.constants.R2UpdateManifestLastCheckedAtKey
import moe.rukamori.archivetune.constants.UpdateChannel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class GitCommit(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
    val url: String,
    val authorAvatarUrl: String? = null,
)

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String?,
    val publishedAt: String,
    val htmlUrl: String,
    val versionCode: Long? = null,
    val downloadUrl: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
)

private data class ReleasesNetworkResult(
    val status: HttpStatusCode,
    val body: String?,
    val etag: String?,
)

object Updater {
    private val client = HttpClient()
    private const val ReleaseCacheCheckIntervalMs: Long = 6 * 60 * 60 * 1000L
    private const val R2BaseUrl = "https://pub-90881360725b42cab24caebe4e55fa2d.r2.dev"
    private const val UpdateManifestUrl = "$R2BaseUrl/latest.json"
    private const val SourceRepository = "mint1246/ArchiveTunePC"
    var lastCheckTime = -1L
        private set
    private var latestReleaseDownloadUrl: String? = null
    private var latestCanaryDownloadUrl: String? = null
    private val knownVersionCodes = ConcurrentHashMap<String, Long>()

    private val isUpdaterDistribution: Boolean
        get() =
            BuildConfig.UPDATER_AVAILABLE &&
                when (BuildConfig.DISTRIBUTION) {
                    "gms", "foss" -> true
                    else -> false
                }

    internal fun currentArtifactKey(): String =
        "${BuildConfig.DISTRIBUTION}-${BuildConfig.DEVICE}-${BuildConfig.ARCHITECTURE}"

    private data class SemVer(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: List<PreReleaseIdentifier>,
    ) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            val majorCompare = major.compareTo(other.major)
            if (majorCompare != 0) return majorCompare
            val minorCompare = minor.compareTo(other.minor)
            if (minorCompare != 0) return minorCompare
            val patchCompare = patch.compareTo(other.patch)
            if (patchCompare != 0) return patchCompare

            val thisIsStable = preRelease.isEmpty()
            val otherIsStable = other.preRelease.isEmpty()
            if (thisIsStable && !otherIsStable) return 1
            if (!thisIsStable && otherIsStable) return -1

            val maxIndex = minOf(preRelease.size, other.preRelease.size)
            for (i in 0 until maxIndex) {
                val c = preRelease[i].compareTo(other.preRelease[i])
                if (c != 0) return c
            }
            return preRelease.size.compareTo(other.preRelease.size)
        }

        fun normalizedName(): String =
            if (preRelease.isEmpty()) {
                "$major.$minor.$patch"
            } else {
                "$major.$minor.$patch-" + preRelease.joinToString(".") { it.raw }
            }
    }

    private sealed interface PreReleaseIdentifier : Comparable<PreReleaseIdentifier> {
        val raw: String
    }

    private data class NumericIdentifier(
        override val raw: String,
        val value: Long,
    ) : PreReleaseIdentifier {
        override fun compareTo(other: PreReleaseIdentifier): Int =
            when (other) {
                is NumericIdentifier -> value.compareTo(other.value)
                is AlphaIdentifier -> -1
            }
    }

    private data class AlphaIdentifier(
        override val raw: String,
    ) : PreReleaseIdentifier {
        override fun compareTo(other: PreReleaseIdentifier): Int =
            when (other) {
                is NumericIdentifier -> 1
                is AlphaIdentifier -> raw.compareTo(other.raw)
            }
    }

    private val semVerRegex =
        Regex("""(?i)\bv?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?\b""")

    private fun parseSemVerOrNull(text: String): SemVer? {
        val match = semVerRegex.find(text) ?: return null
        val major = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minor = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val patch = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val preReleaseText = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }
        val preRelease =
            preReleaseText
                ?.split('.')
                ?.filter { it.isNotBlank() }
                ?.map { identifier ->
                    if (identifier.all { it.isDigit() }) {
                        NumericIdentifier(raw = identifier, value = identifier.toLong())
                    } else {
                        AlphaIdentifier(raw = identifier)
                    }
                }
                ?: emptyList()
        return SemVer(
            major = major,
            minor = minor,
            patch = patch,
            preRelease = preRelease,
        )
    }

    private fun parseReleaseSemVerOrNull(release: ReleaseInfo): SemVer? =
        parseSemVerOrNull(release.tagName) ?: parseSemVerOrNull(release.name)

    internal fun isSameVersion(
        a: String,
        b: String,
    ): Boolean {
        val aSemVer = parseSemVerOrNull(a)
        val bSemVer = parseSemVerOrNull(b)
        return if (aSemVer != null && bSemVer != null) {
            aSemVer.major == bSemVer.major &&
                aSemVer.minor == bSemVer.minor &&
                aSemVer.patch == bSemVer.patch &&
                aSemVer.preRelease == bSemVer.preRelease
        } else {
            a.trim() == b.trim()
        }
    }

    internal fun isUpdateAvailable(
        latestVersion: String,
        currentVersion: String,
    ): Boolean {
        if (currentVersion == BuildConfig.VERSION_NAME) {
            knownVersionCodes[latestVersion.trim()]?.let { latestVersionCode ->
                return latestVersionCode > BuildConfig.VERSION_CODE.toLong()
            }
        }
        val latestSemVer = parseSemVerOrNull(latestVersion)
        val currentSemVer = parseSemVerOrNull(currentVersion)
        return if (latestSemVer != null && currentSemVer != null) {
            latestSemVer > currentSemVer
        } else {
            !isSameVersion(latestVersion, currentVersion)
        }
    }

    internal fun findLatestRelease(releases: List<ReleaseInfo>): ReleaseInfo? {
        if (releases.isEmpty()) return null
        val parsed =
            releases.mapNotNull { release ->
                parseReleaseSemVerOrNull(release)?.let { version -> version to release }
            }

        if (parsed.isEmpty()) return releases.firstOrNull()

        val stable = parsed.filter { it.first.preRelease.isEmpty() }
        val candidates = stable.ifEmpty { parsed }
        return candidates.maxWithOrNull(compareBy({ it.first }, { it.second.publishedAt }))?.second
    }

    internal fun findLatestCanaryRelease(releases: List<ReleaseInfo>): ReleaseInfo? =
        releases.maxWithOrNull(compareBy<ReleaseInfo>({ it.versionCode ?: Long.MIN_VALUE }, { it.publishedAt }))

    private fun preferredReleaseVersionNameOrNull(release: ReleaseInfo): String? =
        parseReleaseSemVerOrNull(release)?.normalizedName()

    internal fun getReleaseVersionName(release: ReleaseInfo): String =
        preferredReleaseVersionNameOrNull(release) ?: release.name.ifBlank { release.tagName }

    internal fun parseAndroidUpdateManifest(
        json: String,
        channel: UpdateChannel,
        artifactKey: String = currentArtifactKey(),
    ): ReleaseInfo? {
        val root = JSONObject(json)
        val android = root.optJSONObject("android") ?: return null
        require(android.optInt("schema", 1) == 1) { "Unsupported Android update manifest schema" }
        val channelObject = android.optJSONObject(channel.manifestKey) ?: return null
        val version = channelObject.getString("version").trim()
        require(version.isNotBlank()) { "Android update manifest has an empty version" }
        val versionCode = channelObject.optLong("versionCode", -1L)
        require(versionCode > 0L) { "Android update manifest has an invalid versionCode" }

        val artifacts = channelObject.getJSONObject("artifacts")
        val artifact =
            artifacts.optJSONObject(artifactKey)
                ?: universalFallbackKey(artifactKey)?.let(artifacts::optJSONObject)
                ?: return null
        val downloadUrl = artifact.getString("url").trim()
        require(downloadUrl.startsWith("https://")) { "Android update URL must use HTTPS" }
        val sha256 = artifact.getString("sha256").trim().lowercase()
        require(sha256.matches(Sha256Regex)) { "Android update manifest has an invalid SHA-256" }

        return ReleaseInfo(
            tagName = channelObject.optString("tag", version).ifBlank { version },
            name = channelObject.optString("name", "ArchiveTune $version").ifBlank { "ArchiveTune $version" },
            body = channelObject.optString("notes").takeIf(String::isNotBlank),
            publishedAt = channelObject.optString("publishedAt"),
            htmlUrl = channelObject.optString("pageUrl").takeIf(String::isNotBlank) ?: downloadUrl,
            versionCode = versionCode,
            downloadUrl = downloadUrl,
            sha256 = sha256,
            sizeBytes = artifact.optLong("size", -1L).takeIf { it > 0L },
        )
    }

    private fun universalFallbackKey(artifactKey: String): String? {
        if (artifactKey.endsWith("-universal")) return null
        val architectureSeparator = artifactKey.lastIndexOf('-')
        if (architectureSeparator <= 0) return null
        return artifactKey.substring(0, architectureSeparator + 1) + "universal"
    }

    private suspend fun fetchManifestNetwork(cachedEtag: String?): ReleasesNetworkResult {
        val response: HttpResponse =
            client.get(UpdateManifestUrl) {
                headers {
                    append("Accept", "application/json")
                    append("User-Agent", "ArchiveTune")
                    if (!cachedEtag.isNullOrBlank()) {
                        append("If-None-Match", cachedEtag)
                    }
                }
            }
        val etag = response.headers["ETag"]
        return when (response.status) {
            HttpStatusCode.NotModified ->
                ReleasesNetworkResult(
                    status = response.status,
                    body = null,
                    etag = cachedEtag ?: etag,
                )

            else ->
                ReleasesNetworkResult(
                    status = response.status,
                    body = response.bodyAsText(),
                    etag = etag,
                )
        }
    }

    internal fun isUpdateAvailable(release: ReleaseInfo): Boolean =
        release.versionCode?.let { it > BuildConfig.VERSION_CODE.toLong() }
            ?: isUpdateAvailable(getReleaseVersionName(release), BuildConfig.VERSION_NAME)

    internal fun updateIdentity(versionName: String): String =
        knownVersionCodes[versionName.trim()]?.let { "$versionName#$it" } ?: versionName

    private suspend fun getManifestJson(forceRefresh: Boolean): String {
        val now = System.currentTimeMillis()
        val dataStore = App.instance.dataStore
        val cachedJson = dataStore.getAsync(R2UpdateManifestJsonKey)?.takeIf(String::isNotBlank)
        val cachedEtag = dataStore.getAsync(R2UpdateManifestEtagKey)
        val lastCheckedAt = dataStore.getAsync(R2UpdateManifestLastCheckedAtKey, 0L)
        val shouldCheckNetwork =
            forceRefresh || cachedJson == null || (now - lastCheckedAt) >= ReleaseCacheCheckIntervalMs

        if (!shouldCheckNetwork && cachedJson != null) {
            lastCheckTime = now
            return cachedJson
        }

        val networkResult =
            try {
                fetchManifestNetwork(cachedEtag)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }

        if (networkResult == null) {
            lastCheckTime = now
            return cachedJson ?: throw IllegalStateException("Failed to fetch the R2 Android update manifest")
        }

        return when {
            networkResult.status == HttpStatusCode.NotModified && cachedJson != null -> {
                dataStore.edit { settings ->
                    settings[R2UpdateManifestLastCheckedAtKey] = now
                    networkResult.etag?.let { settings[R2UpdateManifestEtagKey] = it }
                }
                lastCheckTime = now
                cachedJson
            }

            networkResult.status.value in 200..299 && !networkResult.body.isNullOrBlank() -> {
                val networkBody = networkResult.body
                val root = JSONObject(networkBody)
                val android = root.optJSONObject("android")
                    ?: throw IllegalStateException("R2 update manifest has no Android section")
                require(android.optInt("schema", 1) == 1) { "Unsupported Android update manifest schema" }
                dataStore.edit { settings ->
                    settings[R2UpdateManifestLastCheckedAtKey] = now
                    settings[R2UpdateManifestJsonKey] = networkBody
                    networkResult.etag?.let { settings[R2UpdateManifestEtagKey] = it }
                }
                lastCheckTime = now
                networkBody
            }

            cachedJson != null -> {
                lastCheckTime = now
                cachedJson
            }

            else -> throw IllegalStateException("R2 Android update manifest HTTP ${networkResult.status.value}")
        }
    }

    private suspend fun getCachedReleases(channel: UpdateChannel): List<ReleaseInfo> {
        if (!isUpdaterDistribution) return emptyList()
        val cachedJson = App.instance.dataStore.getAsync(R2UpdateManifestJsonKey) ?: return emptyList()
        val release = runCatching { parseAndroidUpdateManifest(cachedJson, channel) }.getOrNull() ?: return emptyList()
        rememberLatest(channel, release)
        return listOf(release)
    }

    private fun rememberLatest(
        channel: UpdateChannel,
        release: ReleaseInfo,
    ) {
        when (channel) {
            UpdateChannel.STABLE -> {
                latestReleaseDownloadUrl = release.downloadUrl
            }

            UpdateChannel.CANARY -> {
                latestCanaryDownloadUrl = release.downloadUrl
            }
        }
        release.versionCode?.let { knownVersionCodes[getReleaseVersionName(release).trim()] = it }
    }

    private suspend fun getAllReleases(
        channel: UpdateChannel,
        perPage: Int,
        forceRefresh: Boolean,
    ): Result<List<ReleaseInfo>> {
        if (!isUpdaterDistribution) return Result.success(emptyList())
        return runCatchingCancellable {
            val manifest = getManifestJson(forceRefresh)
            val release = parseAndroidUpdateManifest(manifest, channel)
                ?: throw IllegalStateException("No ${channel.name.lowercase()} release is published for ${currentArtifactKey()}")
            rememberLatest(channel, release)
            listOf(release).take(perPage.coerceAtLeast(0))
        }
    }

    suspend fun getCachedReleases(): List<ReleaseInfo> = getCachedReleases(UpdateChannel.STABLE)

    suspend fun getLatestVersionName(): Result<String> = getLatestReleaseInfo().map(::getReleaseVersionName)

    suspend fun getLatestReleaseNotes(): Result<String?> = getLatestReleaseInfo().map { it.body }

    suspend fun getLatestReleaseInfo(forceRefresh: Boolean = false): Result<ReleaseInfo> =
        getAllReleases(UpdateChannel.STABLE, 1, forceRefresh).mapCatching { releases ->
            releases.firstOrNull() ?: throw IllegalStateException("No stable R2 Android release found")
        }

    suspend fun getLatestCanaryVersionName(): Result<String> =
        getLatestCanaryReleaseInfo().map(::getReleaseVersionName)

    suspend fun getLatestCanaryReleaseNotes(): Result<String?> = getLatestCanaryReleaseInfo().map { it.body }

    suspend fun getLatestCanaryReleaseInfo(forceRefresh: Boolean = false): Result<ReleaseInfo> =
        getAllReleases(UpdateChannel.CANARY, 1, forceRefresh).mapCatching { releases ->
            releases.firstOrNull() ?: throw IllegalStateException("No Canary R2 Android release found")
        }

    suspend fun getCachedCanaryReleases(): List<ReleaseInfo> = getCachedReleases(UpdateChannel.CANARY)

    suspend fun getAllCanaryReleases(
        perPage: Int = 10,
        forceRefresh: Boolean = false,
    ): Result<List<ReleaseInfo>> = getAllReleases(UpdateChannel.CANARY, perPage, forceRefresh)

    suspend fun getAllReleases(
        perPage: Int = 30,
        forceRefresh: Boolean = false,
    ): Result<List<ReleaseInfo>> = getAllReleases(UpdateChannel.STABLE, perPage, forceRefresh)

    fun getLatestDownloadUrl(): String {
        if (!isUpdaterDistribution) return ""
        return latestReleaseDownloadUrl ?: "$R2BaseUrl/releases/android/latest-${currentArtifactKey()}.apk"
    }

    fun getLatestCanaryDownloadUrl(): String {
        if (!isUpdaterDistribution) return ""
        return latestCanaryDownloadUrl ?: "$R2BaseUrl/releases/android/canary-${currentArtifactKey()}.apk"
    }

    suspend fun getCommitHistory(
        count: Int = 20,
        branch: String = "main",
    ): Result<List<GitCommit>> =
        runCatchingCancellable {
            if (!isUpdaterDistribution) return@runCatchingCancellable emptyList()
            val response =
                client
                    .get("https://api.github.com/repos/$SourceRepository/commits?sha=$branch&per_page=$count")
                    .bodyAsText()
            val jsonArray = JSONArray(response)
            buildList {
                for (i in 0 until jsonArray.length()) {
                    val commitObj = jsonArray.getJSONObject(i)
                    val commit = commitObj.getJSONObject("commit")
                    val authorObj = commit.optJSONObject("author")
                    val githubAuthorObj = commitObj.optJSONObject("author")
                    add(
                        GitCommit(
                            sha = commitObj.optString("sha", "").take(7),
                            message = commit.optString("message", "").lines().firstOrNull() ?: "",
                            author = authorObj?.optString("name", "Unknown") ?: "Unknown",
                            date = authorObj?.optString("date", "") ?: "",
                            url = commitObj.optString("html_url", ""),
                            authorAvatarUrl = githubAuthorObj?.optString("avatar_url")?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }

    private val UpdateChannel.manifestKey: String
        get() = name.lowercase()

    private val Sha256Regex = Regex("^[0-9a-f]{64}$")

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
}
