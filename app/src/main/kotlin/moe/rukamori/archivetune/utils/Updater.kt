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
import moe.rukamori.archivetune.sdk.update.GitCommitRecord
import moe.rukamori.archivetune.sdk.update.ReleaseChannelKey
import moe.rukamori.archivetune.sdk.update.UpdateReleaseRecord
import moe.rukamori.archivetune.sdk.update.findLatestCanaryRelease as findLatestSdkCanaryRelease
import moe.rukamori.archivetune.sdk.update.findLatestStableOrNewestRelease
import moe.rukamori.archivetune.sdk.update.isNewerSemVerOrDifferent
import moe.rukamori.archivetune.sdk.update.isSameSemVerOrLiteral
import moe.rukamori.archivetune.sdk.update.parseAndroidUpdateManifest as parseSdkAndroidUpdateManifest
import moe.rukamori.archivetune.sdk.update.parseGitHubCommitsJson
import moe.rukamori.archivetune.sdk.update.releaseVersionName
import moe.rukamori.archivetune.sdk.update.shouldRefreshManifestCache
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

    internal fun isSameVersion(
        a: String,
        b: String,
    ): Boolean = isSameSemVerOrLiteral(a, b)

    internal fun isUpdateAvailable(
        latestVersion: String,
        currentVersion: String,
    ): Boolean {
        if (currentVersion == BuildConfig.VERSION_NAME) {
            knownVersionCodes[latestVersion.trim()]?.let { latestVersionCode ->
                return latestVersionCode > BuildConfig.VERSION_CODE.toLong()
            }
        }
        return isNewerSemVerOrDifferent(latestVersion, currentVersion)
    }

    internal fun findLatestRelease(releases: List<ReleaseInfo>): ReleaseInfo? =
        findLatestStableOrNewestRelease(releases.map { it.toUpdateReleaseRecord() })?.toReleaseInfo()

    internal fun findLatestCanaryRelease(releases: List<ReleaseInfo>): ReleaseInfo? =
        findLatestSdkCanaryRelease(releases.map { it.toUpdateReleaseRecord() })?.toReleaseInfo()

    internal fun getReleaseVersionName(release: ReleaseInfo): String =
        releaseVersionName(release.toUpdateReleaseRecord())

    internal fun parseAndroidUpdateManifest(
        json: String,
        channel: UpdateChannel,
        artifactKey: String = currentArtifactKey(),
    ): ReleaseInfo? =
        parseSdkAndroidUpdateManifest(
            json = json,
            channel = channel.toSdkChannelKey(),
            artifactKey = artifactKey,
        )?.toReleaseInfo()

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
            shouldRefreshManifestCache(
                forceRefresh = forceRefresh,
                hasCachedJson = cachedJson != null,
                nowMs = now,
                lastCheckedAtMs = lastCheckedAt,
                intervalMs = ReleaseCacheCheckIntervalMs,
            )

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

            UpdateChannel.ARTIFACT -> {
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
        getAllReleases(UpdateChannel.ARTIFACT, 1, forceRefresh).mapCatching { releases ->
            releases.firstOrNull() ?: throw IllegalStateException("No Canary R2 Android release found")
        }

    suspend fun getCachedCanaryReleases(): List<ReleaseInfo> = getCachedReleases(UpdateChannel.ARTIFACT)

    suspend fun getCachedArtifactReleases(): List<ReleaseInfo> = getCachedCanaryReleases()

    suspend fun getAllArtifactReleases(
        perPage: Int = 30,
        forceRefresh: Boolean = false,
    ): Result<List<ReleaseInfo>> = getAllReleases(UpdateChannel.ARTIFACT, perPage, forceRefresh)

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
            parseGitHubCommitsJson(response).map { it.toGitCommit() }
        }

    private fun UpdateChannel.toSdkChannelKey(): ReleaseChannelKey =
        when (this) {
            UpdateChannel.STABLE -> ReleaseChannelKey.STABLE
            // R2's schema predates the upstream channel rename and continues to call this channel "canary".
            UpdateChannel.ARTIFACT -> ReleaseChannelKey.CANARY
        }

    private fun ReleaseInfo.toUpdateReleaseRecord(): UpdateReleaseRecord =
        UpdateReleaseRecord(
            tagName = tagName,
            name = name,
            body = body,
            publishedAt = publishedAt,
            htmlUrl = htmlUrl,
            versionCode = versionCode,
            downloadUrl = downloadUrl,
            sha256 = sha256,
            sizeBytes = sizeBytes,
        )

    private fun UpdateReleaseRecord.toReleaseInfo(): ReleaseInfo =
        ReleaseInfo(
            tagName = tagName,
            name = name,
            body = body,
            publishedAt = publishedAt,
            htmlUrl = htmlUrl,
            versionCode = versionCode,
            downloadUrl = downloadUrl,
            sha256 = sha256,
            sizeBytes = sizeBytes,
        )

    private fun GitCommitRecord.toGitCommit(): GitCommit =
        GitCommit(
            sha = sha,
            message = message,
            author = author,
            date = date,
            url = url,
            authorAvatarUrl = authorAvatarUrl,
        )

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
}
