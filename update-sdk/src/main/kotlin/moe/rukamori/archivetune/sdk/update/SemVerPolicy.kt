/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.update

sealed interface PreReleaseIdentifier : Comparable<PreReleaseIdentifier> {
    val raw: String
}

data class NumericIdentifier(
    override val raw: String,
    val value: Long,
) : PreReleaseIdentifier {
    override fun compareTo(other: PreReleaseIdentifier): Int =
        when (other) {
            is NumericIdentifier -> value.compareTo(other.value)
            is AlphaIdentifier -> -1
        }
}

data class AlphaIdentifier(
    override val raw: String,
) : PreReleaseIdentifier {
    override fun compareTo(other: PreReleaseIdentifier): Int =
        when (other) {
            is NumericIdentifier -> 1
            is AlphaIdentifier -> raw.compareTo(other.raw)
        }
}

data class SemVer(
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

private val semVerRegex =
    Regex("""(?i)\bv?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?\b""")

fun parseSemVerOrNull(text: String): SemVer? {
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

fun isSameSemVerOrLiteral(
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

fun isNewerSemVerOrDifferent(
    latestVersion: String,
    currentVersion: String,
): Boolean {
    val latestSemVer = parseSemVerOrNull(latestVersion)
    val currentSemVer = parseSemVerOrNull(currentVersion)
    return if (latestSemVer != null && currentSemVer != null) {
        latestSemVer > currentSemVer
    } else {
        !isSameSemVerOrLiteral(latestVersion, currentVersion)
    }
}

fun compareNumericVersionParts(
    left: String,
    right: String,
): Int {
    val leftParts = normalizedVersionParts(left)
    val rightParts = normalizedVersionParts(right)
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val a = leftParts.getOrElse(index) { 0 }
        val b = rightParts.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}

fun shouldStageDesktopUpdate(
    manifestVersion: String,
    currentVersion: String,
    expectedExecutableSha256: String?,
    currentExecutableSha256: String?,
): Boolean {
    val expected = expectedExecutableSha256?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    if (expected != null) {
        require(isValidSha256Hex(expected)) { "latest.json contains an invalid exeSha256" }
        val current = currentExecutableSha256?.trim()?.lowercase()
            ?: error("Current executable SHA-256 is unavailable")
        require(isValidSha256Hex(current)) { "Current executable SHA-256 is invalid" }
        return current != expected
    }
    return compareNumericVersionParts(manifestVersion, currentVersion) > 0
}

private fun normalizedVersionParts(version: String): List<Int> =
    version.trim().removePrefix("v").split('.', '-', '+')
        .map { token -> token.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
