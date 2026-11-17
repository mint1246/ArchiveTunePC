/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.update

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class UpdateManifestParserTest {
    private val validSha = "a1b2c3d4".repeat(8)
    private val universalSha = "f0e1d2c3".repeat(8)

    @Test
    fun `parses android stable and canary manifests with exact artifact match`() {
        val json = """
            {
              "android": {
                "schema": 1,
                "stable": {
                  "version": "14.2.0",
                  "versionCode": 140200,
                  "tag": "v14.2.0",
                  "name": "ArchiveTune 14.2.0",
                  "notes": "Bug fixes",
                  "publishedAt": "2026-11-17T10:00:00Z",
                  "pageUrl": "https://example.test/releases/v14.2.0",
                  "artifacts": {
                    "gms-mobile-arm64": {
                      "url": "https://cdn.example.test/app-arm64.apk",
                      "sha256": "${validSha.uppercase()}",
                      "size": 42000000
                    }
                  }
                },
                "canary": {
                  "version": "14.3.0-canary.5",
                  "versionCode": 140305,
                  "artifacts": {
                    "gms-mobile-universal": {
                      "url": "https://cdn.example.test/canary-universal.apk",
                      "sha256": "$universalSha"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val stable = assertNotNull(
            parseAndroidUpdateManifest(json, ReleaseChannelKey.STABLE, "gms-mobile-arm64"),
        )
        assertEquals("v14.2.0", stable.tagName)
        assertEquals("ArchiveTune 14.2.0", stable.name)
        assertEquals("Bug fixes", stable.body)
        assertEquals("2026-11-17T10:00:00Z", stable.publishedAt)
        assertEquals("https://example.test/releases/v14.2.0", stable.htmlUrl)
        assertEquals(140200L, stable.versionCode)
        assertEquals("https://cdn.example.test/app-arm64.apk", stable.downloadUrl)
        assertEquals(validSha, stable.sha256)
        assertEquals(42000000L, stable.sizeBytes)

        val canary = assertNotNull(
            parseAndroidUpdateManifest(json, ReleaseChannelKey.CANARY, "gms-mobile-armeabi"),
        )
        assertEquals("14.3.0-canary.5", canary.tagName)
        assertEquals("ArchiveTune 14.3.0-canary.5", canary.name)
        assertNull(canary.body)
        assertEquals("https://cdn.example.test/canary-universal.apk", canary.downloadUrl)
        assertEquals("https://cdn.example.test/canary-universal.apk", canary.htmlUrl)
        assertEquals(universalSha, canary.sha256)
        assertNull(canary.sizeBytes)
    }

    @Test
    fun `universal fallback key resolves abi suffix and returns null when already universal`() {
        assertEquals("gms-mobile-universal", universalArtifactFallbackKey("gms-mobile-arm64"))
        assertEquals("foss-tv-universal", universalArtifactFallbackKey("foss-tv-x86_64"))
        assertNull(universalArtifactFallbackKey("gms-mobile-universal"))
        assertNull(universalArtifactFallbackKey("universal"))
    }

    @Test
    fun `rejects non-https android download url and invalid sha256 or schema`() {
        fun manifest(
            schema: Int = 1,
            url: String = "https://cdn.example.test/app.apk",
            sha256: String = validSha,
        ) = """
            {
              "android": {
                "schema": $schema,
                "stable": {
                  "version": "14.2.0",
                  "versionCode": 140200,
                  "artifacts": {
                    "gms-phone-arm64-v8a": {
                      "url": "$url",
                      "sha256": "$sha256"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            parseAndroidUpdateManifest(manifest(schema = 2), ReleaseChannelKey.STABLE, "gms-phone-arm64-v8a")
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndroidUpdateManifest(
                manifest(url = "http://cdn.example.test/app.apk"),
                ReleaseChannelKey.STABLE,
                "gms-phone-arm64-v8a",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndroidUpdateManifest(
                manifest(sha256 = "short-hash"),
                ReleaseChannelKey.STABLE,
                "gms-phone-arm64-v8a",
            )
        }
    }

    @Test
    fun `selects latest stable or canary release and formats version name`() {
        val pre = UpdateReleaseRecord("v14.2.0-beta.1", "Beta 1", null, "2026-11-10T00:00:00Z", "https://example.test/1", versionCode = 140201)
        val stableOld = UpdateReleaseRecord("v14.1.0", "14.1.0", null, "2026-11-01T00:00:00Z", "https://example.test/2", versionCode = 140100)
        val stableNew = UpdateReleaseRecord("v14.2.0", "14.2.0", null, "2026-11-12T00:00:00Z", "https://example.test/3", versionCode = 140200)
        val canaryNew = UpdateReleaseRecord("canary-99", "Canary 99", null, "2026-11-15T00:00:00Z", "https://example.test/4", versionCode = 140300)

        assertEquals(stableNew, findLatestStableOrNewestRelease(listOf(pre, stableOld, stableNew)))
        assertEquals(pre, findLatestStableOrNewestRelease(listOf(pre)))
        assertEquals(canaryNew, findLatestCanaryRelease(listOf(pre, stableNew, canaryNew)))
        assertEquals("14.2.0", releaseVersionName(stableNew))
        assertEquals("14.2.0-beta.1", releaseVersionName(pre))
        assertEquals("Canary 99", releaseVersionName(canaryNew))
    }

    @Test
    fun `parses desktop update manifest with url or legacy msi field`() {
        val withUrl = parseDesktopUpdateManifest(
            """
            {
              "version": " 14.15.1 ",
              "url": "releases/desktop/ArchiveTune-14.15.1.msi",
              "sha256": "${validSha.uppercase()}",
              "exeSha256": "${universalSha.uppercase()}"
            }
            """.trimIndent(),
        )
        assertEquals("14.15.1", withUrl.version)
        assertEquals("releases/desktop/ArchiveTune-14.15.1.msi", withUrl.downloadPathOrUrl)
        assertEquals(validSha, withUrl.msiSha256)
        assertEquals(universalSha, withUrl.exeSha256)

        val withLegacyMsi = parseDesktopUpdateManifest(
            """
            {
              "version": "14.14.0",
              "msi": "ArchiveTune-14.14.0.msi",
              "sha256": "$validSha"
            }
            """.trimIndent(),
        )
        assertEquals("ArchiveTune-14.14.0.msi", withLegacyMsi.downloadPathOrUrl)
        assertNull(withLegacyMsi.exeSha256)

        assertFailsWith<IllegalStateException> {
            parseDesktopUpdateManifest("""{"version":"14.15.0","sha256":"$validSha"}""")
        }
    }

    @Test
    fun `parses github commit history json`() {
        val json = """
            [
              {
                "sha": "1234567890abcdef",
                "html_url": "https://github.com/mint1246/ArchiveTunePC/commit/1234567",
                "commit": {
                  "message": "feat: extract update-sdk\n\nDetailed body",
                  "author": {
                    "name": "Rukamori",
                    "date": "2026-11-17T20:00:00Z"
                  }
                },
                "author": {
                  "avatar_url": "https://avatars.githubusercontent.com/u/1?v=4"
                }
              },
              {
                "sha": "abcdef123456",
                "html_url": "https://github.com/mint1246/ArchiveTunePC/commit/abcdef1",
                "commit": {
                  "message": "fix: edge case"
                }
              }
            ]
        """.trimIndent()

        val commits = parseGitHubCommitsJson(json)
        assertEquals(2, commits.size)
        assertEquals("1234567", commits[0].sha)
        assertEquals("feat: extract update-sdk", commits[0].message)
        assertEquals("Rukamori", commits[0].author)
        assertEquals("2026-11-17T20:00:00Z", commits[0].date)
        assertEquals("https://github.com/mint1246/ArchiveTunePC/commit/1234567", commits[0].url)
        assertEquals("https://avatars.githubusercontent.com/u/1?v=4", commits[0].authorAvatarUrl)

        assertEquals("abcdef1", commits[1].sha)
        assertEquals("fix: edge case", commits[1].message)
        assertEquals("Unknown", commits[1].author)
        assertNull(commits[1].authorAvatarUrl)
    }

    @Test
    fun `manifest cache refresh honors forceRefresh, cache presence, and interval`() {
        val interval = 6 * 60 * 60 * 1000L
        assertTrue(shouldRefreshManifestCache(forceRefresh = true, hasCachedJson = true, nowMs = 1000L, lastCheckedAtMs = 999L, intervalMs = interval))
        assertTrue(shouldRefreshManifestCache(forceRefresh = false, hasCachedJson = false, nowMs = 1000L, lastCheckedAtMs = 999L, intervalMs = interval))
        assertFalse(shouldRefreshManifestCache(forceRefresh = false, hasCachedJson = true, nowMs = interval, lastCheckedAtMs = 1L, intervalMs = interval))
        assertTrue(shouldRefreshManifestCache(forceRefresh = false, hasCachedJson = true, nowMs = interval + 1L, lastCheckedAtMs = 1L, intervalMs = interval))
    }
}
