/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.constants.UpdateChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private val validSha = "a1b2c3d4".repeat(8)
    private val universalSha = "f0e1d2c3".repeat(8)

    @Test
    fun parseAndroidUpdateManifestSupportsExactAbiAndUniversalFallback() {
        val json = """
            {
              "android": {
                "schema": 1,
                "stable": {
                  "version": "14.2.0",
                  "versionCode": 140200,
                  "tag": "v14.2.0",
                  "name": "ArchiveTune 14.2.0",
                  "notes": "Release notes",
                  "publishedAt": "2026-11-17T10:00:00Z",
                  "pageUrl": "https://example.test/releases/v14.2.0",
                  "artifacts": {
                    "gms-mobile-arm64": {
                      "url": "https://cdn.example.test/app-arm64.apk",
                      "sha256": "$validSha",
                      "size": 123456
                    },
                    "gms-mobile-universal": {
                      "url": "https://cdn.example.test/app-universal.apk",
                      "sha256": "$universalSha"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val exact = Updater.parseAndroidUpdateManifest(json, UpdateChannel.STABLE, "gms-mobile-arm64")
        assertNotNull(exact)
        assertEquals("https://cdn.example.test/app-arm64.apk", exact?.downloadUrl)
        assertEquals(validSha, exact?.sha256)
        assertEquals(123456L, exact?.sizeBytes)

        val fallback = Updater.parseAndroidUpdateManifest(json, UpdateChannel.STABLE, "gms-mobile-x86_64")
        assertNotNull(fallback)
        assertEquals("https://cdn.example.test/app-universal.apk", fallback?.downloadUrl)
        assertEquals(universalSha, fallback?.sha256)
    }

    @Test
    fun isSameVersionDelegatesToSemVerAndLiteralRules() {
        assertTrue(Updater.isSameVersion("v14.2.0+build.1", "14.2.0"))
        assertTrue(Updater.isSameVersion("14.2.0-alpha.2", "14.2.0-alpha.2"))
        assertFalse(Updater.isSameVersion("14.2.0-alpha.2", "14.2.0-alpha.10"))
        assertTrue(Updater.isSameVersion("  custom-build  ", "custom-build"))
    }

    @Test
    fun findLatestReleasePrefersHighestStableSemVer() {
        val pre = ReleaseInfo("v14.3.0-alpha.1", "14.3.0-alpha.1", null, "2026-11-17T12:00:00Z", "https://example.test/pre")
        val oldStable = ReleaseInfo("v14.1.0", "14.1.0", null, "2026-11-01T12:00:00Z", "https://example.test/old")
        val newStable = ReleaseInfo("v14.2.0", "14.2.0", null, "2026-11-10T12:00:00Z", "https://example.test/new")

        val latest = Updater.findLatestRelease(listOf(pre, oldStable, newStable))
        assertEquals(newStable, latest)
    }
}
