/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.update

import org.json.JSONArray
import org.json.JSONObject

enum class ReleaseChannelKey(
    val manifestKey: String,
) {
    STABLE("stable"),
    CANARY("canary"),
}

data class UpdateReleaseRecord(
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

data class DesktopUpdateManifest(
    val version: String,
    val downloadPathOrUrl: String,
    val msiSha256: String,
    val exeSha256: String?,
)

data class GitCommitRecord(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
    val url: String,
    val authorAvatarUrl: String? = null,
)

private val Sha256Regex = Regex("^[0-9a-f]{64}$")

fun universalArtifactFallbackKey(artifactKey: String): String? {
    if (artifactKey.endsWith("-universal")) return null
    val architectureSeparator = artifactKey.lastIndexOf('-')
    if (architectureSeparator <= 0) return null
    return artifactKey.substring(0, architectureSeparator + 1) + "universal"
}

fun isValidSha256Hex(value: String): Boolean = value.matches(Sha256Regex)

fun parseAndroidUpdateManifest(
    json: String,
    channel: ReleaseChannelKey,
    artifactKey: String,
): UpdateReleaseRecord? {
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
            ?: universalArtifactFallbackKey(artifactKey)?.let(artifacts::optJSONObject)
            ?: return null
    val downloadUrl = artifact.getString("url").trim()
    require(downloadUrl.startsWith("https://")) { "Android update URL must use HTTPS" }
    val sha256 = artifact.getString("sha256").trim().lowercase()
    require(isValidSha256Hex(sha256)) { "Android update manifest has an invalid SHA-256" }

    return UpdateReleaseRecord(
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

private fun parseReleaseSemVerOrNull(release: UpdateReleaseRecord): SemVer? =
    parseSemVerOrNull(release.tagName) ?: parseSemVerOrNull(release.name)

fun findLatestStableOrNewestRelease(releases: List<UpdateReleaseRecord>): UpdateReleaseRecord? {
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

fun findLatestCanaryRelease(releases: List<UpdateReleaseRecord>): UpdateReleaseRecord? =
    releases.maxWithOrNull(compareBy<UpdateReleaseRecord>({ it.versionCode ?: Long.MIN_VALUE }, { it.publishedAt }))

fun releaseVersionName(release: UpdateReleaseRecord): String =
    parseReleaseSemVerOrNull(release)?.normalizedName() ?: release.name.ifBlank { release.tagName }

fun parseDesktopUpdateManifest(json: String): DesktopUpdateManifest {
    val manifest = JSONObject(json)
    val version = manifest.getString("version").trim()
    val downloadPathOrUrl =
        manifest.optString("url").takeIf(String::isNotBlank)
            ?: manifest.optString("msi").takeIf(String::isNotBlank)
            ?: error("latest.json is missing url/msi")
    val msiSha256 = manifest.getString("sha256").trim().lowercase()
    val exeSha256 =
        manifest.optString("exeSha256")
            .trim()
            .lowercase()
            .takeIf(String::isNotBlank)
    return DesktopUpdateManifest(
        version = version,
        downloadPathOrUrl = downloadPathOrUrl,
        msiSha256 = msiSha256,
        exeSha256 = exeSha256,
    )
}

fun parseGitHubCommitsJson(json: String): List<GitCommitRecord> {
    val jsonArray = JSONArray(json)
    return buildList {
        for (i in 0 until jsonArray.length()) {
            val commitObj = jsonArray.getJSONObject(i)
            val commit = commitObj.getJSONObject("commit")
            val authorObj = commit.optJSONObject("author")
            val githubAuthorObj = commitObj.optJSONObject("author")
            add(
                GitCommitRecord(
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

fun shouldRefreshManifestCache(
    forceRefresh: Boolean,
    hasCachedJson: Boolean,
    nowMs: Long,
    lastCheckedAtMs: Long,
    intervalMs: Long,
): Boolean = forceRefresh || !hasCachedJson || (nowMs - lastCheckedAtMs) >= intervalMs
