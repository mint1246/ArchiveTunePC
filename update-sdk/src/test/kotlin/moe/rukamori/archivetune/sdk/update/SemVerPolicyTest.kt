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

class SemVerPolicyTest {
    @Test
    fun `pre-release ordering follows numeric and lexicographical rules`() {
        val alpha2 = assertNotNull(parseSemVerOrNull("1.0.0-alpha.2"))
        val alpha10 = assertNotNull(parseSemVerOrNull("1.0.0-alpha.10"))
        val beta = assertNotNull(parseSemVerOrNull("1.0.0-beta"))
        val stable = assertNotNull(parseSemVerOrNull("1.0.0"))

        assertTrue(alpha2 < alpha10)
        assertTrue(alpha10 < beta)
        assertTrue(beta < stable)

        assertTrue(isNewerSemVerOrDifferent("1.0.0-alpha.10", "1.0.0-alpha.2"))
        assertTrue(isNewerSemVerOrDifferent("1.0.0-beta", "1.0.0-alpha.10"))
        assertTrue(isNewerSemVerOrDifferent("1.0.0", "1.0.0-beta"))
        assertFalse(isNewerSemVerOrDifferent("1.0.0-alpha.2", "1.0.0-alpha.10"))
    }

    @Test
    fun `numeric pre-release identifiers sort before alphanumeric identifiers`() {
        val numeric = assertNotNull(parseSemVerOrNull("2.1.0-1"))
        val alpha = assertNotNull(parseSemVerOrNull("2.1.0-alpha"))
        val longer = assertNotNull(parseSemVerOrNull("2.1.0-alpha.1"))

        assertTrue(numeric < alpha)
        assertTrue(alpha < longer)
    }

    @Test
    fun `v prefix and build metadata normalize cleanly`() {
        val v1 = assertNotNull(parseSemVerOrNull("v14.2.0-rc.1+build.42"))
        val v2 = assertNotNull(parseSemVerOrNull("14.2.0-rc.1+other"))

        assertEquals("14.2.0-rc.1", v1.normalizedName())
        assertTrue(isSameSemVerOrLiteral("v14.2.0-rc.1+build.42", "14.2.0-rc.1"))
        assertEquals(0, v1.compareTo(v2))
        assertFalse(isNewerSemVerOrDifferent("14.2.0-rc.1+other", "v14.2.0-rc.1+build.42"))
    }

    @Test
    fun `non-semver strings fall back to trimmed literal comparison`() {
        assertNull(parseSemVerOrNull("nightly-2026-11-17"))
        assertTrue(isSameSemVerOrLiteral("  nightly-2026  ", "nightly-2026"))
        assertFalse(isSameSemVerOrLiteral("nightly-2026", "nightly-2025"))
        assertTrue(isNewerSemVerOrDifferent("nightly-2026", "nightly-2025"))
        assertFalse(isNewerSemVerOrDifferent("nightly-2026", "  nightly-2026 "))
    }

    @Test
    fun `desktop numeric version comparison handles variable segment counts`() {
        assertTrue(compareNumericVersionParts("14.2.0", "14.1.9") > 0)
        assertTrue(compareNumericVersionParts("15.0", "14.99.99") > 0)
        assertTrue(compareNumericVersionParts("v14.1.1", "14.1.0") > 0)
        assertEquals(0, compareNumericVersionParts("14.1", "14.1.0"))
    }

    @Test
    fun `desktop staging uses exeSha256 override when present and falls back to version`() {
        val current = "1".repeat(64)
        val different = "2".repeat(64)

        assertTrue(shouldStageDesktopUpdate("14.15", "14.15", different.uppercase(), current))
        assertTrue(shouldStageDesktopUpdate("1.0", "99.0", different, current))
        assertFalse(shouldStageDesktopUpdate("99.0", "1.0", current, current))

        assertTrue(shouldStageDesktopUpdate("14.16", "14.15", null, null))
        assertFalse(shouldStageDesktopUpdate("14.15", "14.15", "   ", null))
        assertFalse(shouldStageDesktopUpdate("14.14", "14.15", null, null))
    }

    @Test
    fun `desktop staging rejects invalid or missing executable hashes`() {
        val valid = "a".repeat(64)

        val invalidExpected = assertFailsWith<IllegalArgumentException> {
            shouldStageDesktopUpdate("14.15", "14.15", "not-a-hash", valid)
        }
        assertEquals("latest.json contains an invalid exeSha256", invalidExpected.message)

        val missingCurrent = assertFailsWith<IllegalStateException> {
            shouldStageDesktopUpdate("14.15", "14.15", valid, null)
        }
        assertEquals("Current executable SHA-256 is unavailable", missingCurrent.message)

        val invalidCurrent = assertFailsWith<IllegalArgumentException> {
            shouldStageDesktopUpdate("14.15", "14.15", valid, "1234")
        }
        assertEquals("Current executable SHA-256 is invalid", invalidCurrent.message)
    }
}
