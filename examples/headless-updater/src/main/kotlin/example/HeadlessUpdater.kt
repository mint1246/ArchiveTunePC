package example

import moe.rukamori.archivetune.sdk.update.ReleaseChannelKey
import moe.rukamori.archivetune.sdk.update.isNewerSemVerOrDifferent
import moe.rukamori.archivetune.sdk.update.parseAndroidUpdateManifest
import moe.rukamori.archivetune.sdk.update.parseDesktopUpdateManifest
import moe.rukamori.archivetune.sdk.update.releaseVersionName
import moe.rukamori.archivetune.sdk.update.shouldStageDesktopUpdate
import moe.rukamori.archivetune.sdk.update.universalArtifactFallbackKey

fun main() {
    val sampleManifest = """
        {
          "version": "14.16.0",
          "url": "releases/desktop/ArchiveTune-14.16.0.msi",
          "sha256": "${"a".repeat(64)}",
          "exeSha256": "${"b".repeat(64)}",
          "android": {
            "schema": 1,
            "stable": {
              "version": "14.16.0",
              "versionCode": 141600,
              "tag": "v14.16.0",
              "artifacts": {
                "gms-mobile-universal": {
                  "url": "https://cdn.example.test/ArchiveTune-14.16.0-universal.apk",
                  "sha256": "${"c".repeat(64)}"
                }
              }
            }
          }
        }
    """.trimIndent()

    val requestedArtifact = "gms-mobile-arm64"
    val fallbackKey = universalArtifactFallbackKey(requestedArtifact)
    val androidRelease = checkNotNull(
        parseAndroidUpdateManifest(sampleManifest, ReleaseChannelKey.STABLE, requestedArtifact),
    )
    val androidVersion = releaseVersionName(androidRelease)
    val androidUpdateAvailable = isNewerSemVerOrDifferent(androidVersion, "14.15.0-rc.1")

    val desktopManifest = parseDesktopUpdateManifest(sampleManifest)
    val shouldStageByVersion = shouldStageDesktopUpdate(
        manifestVersion = desktopManifest.version,
        currentVersion = "14.15.0",
        expectedExecutableSha256 = null,
        currentExecutableSha256 = null,
    )
    val shouldStageBySha = shouldStageDesktopUpdate(
        manifestVersion = desktopManifest.version,
        currentVersion = "14.16.0",
        expectedExecutableSha256 = desktopManifest.exeSha256,
        currentExecutableSha256 = "0".repeat(64),
    )

    println("Fallback key for $requestedArtifact -> $fallbackKey")
    println("Android release: $androidVersion (${androidRelease.downloadUrl}), updateAvailable=$androidUpdateAvailable")
    println("Desktop manifest: ${desktopManifest.version} (${desktopManifest.downloadPathOrUrl})")
    println("Desktop stage checks: byVersion=$shouldStageByVersion, bySha=$shouldStageBySha")
}
