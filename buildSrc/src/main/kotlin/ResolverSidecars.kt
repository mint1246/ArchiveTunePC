/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

import org.gradle.api.GradleException
import java.util.Locale

enum class ResolverSidecarKind(
    val displayName: String,
    val taskInfix: String,
    val manifestPrefix: String,
) {
    YT_DLP("yt-dlp", "YtDlp", "yt-dlp"),
    QUICKJS("quickjs-ng", "QuickJs", "quickjs-ng"),
}

/**
 * One pinned upstream release asset.
 *
 * [assetName] is the name published on GitHub, while [fileName] is the name the
 * desktop resolver looks for inside the bundled `resolver` directory.
 */
data class ResolverSidecarAsset(
    val kind: ResolverSidecarKind,
    val platformId: String,
    val version: String,
    val assetName: String,
    val sha256: String,
    val url: String,
) {
    val fileName: String
        get() = when (kind) {
            ResolverSidecarKind.YT_DLP ->
                if (platformId.startsWith("windows-")) "yt-dlp.exe" else "yt-dlp"

            ResolverSidecarKind.QUICKJS ->
                if (platformId.startsWith("windows-")) "qjs.exe" else "qjs"
        }
}

/**
 * Single source of truth for the yt-dlp and quickjs-ng sidecars bundled with the
 * experimental desktop resolver.
 *
 * The versions, URLs, and SHA-256 values below were verified against the
 * upstream GitHub release assets. Update this file, packaging/resolver/README.md,
 * and the staged manifest together.
 */
object ResolverSidecars {
    const val YT_DLP_VERSION = "2026.08.19"

    /** yt-dlp 2026.08.19 ships this EJS solver bundled inside the frozen executable. */
    const val YT_DLP_BUNDLED_EJS_VERSION = "0.8.0"

    const val QUICKJS_VERSION = "0.17.0"
    private const val QUICKJS_TAG = "v$QUICKJS_VERSION"

    const val WINDOWS_X86_64 = "windows-x86_64"
    const val LINUX_X86_64 = "linux-x86_64"

    private const val YT_DLP_BASE_URL =
        "https://github.com/yt-dlp/yt-dlp/releases/download/$YT_DLP_VERSION"
    private const val QUICKJS_BASE_URL =
        "https://github.com/quickjs-ng/quickjs/releases/download/$QUICKJS_TAG"

    private const val YT_DLP_LINUX_SHA256 =
        "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a"
    private const val YT_DLP_WINDOWS_SHA256 =
        "66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a"
    private const val QUICKJS_LINUX_SHA256 =
        "0bfc02511a9f549c28b53880d988fc7cd5d361e90c5e8afdfcd7dc6774ceace5"
    private const val QUICKJS_WINDOWS_SHA256 =
        "2aeabf0092c3262d6b2609824418f7dd7ed1f1df939f73b2b15645230cac0d77"

    val supportedPlatforms: List<String> = listOf(WINDOWS_X86_64, LINUX_X86_64)

    val assets: List<ResolverSidecarAsset> = listOf(
        ResolverSidecarAsset(
            kind = ResolverSidecarKind.YT_DLP,
            platformId = LINUX_X86_64,
            version = YT_DLP_VERSION,
            assetName = "yt-dlp_linux",
            sha256 = YT_DLP_LINUX_SHA256,
            url = "$YT_DLP_BASE_URL/yt-dlp_linux",
        ),
        ResolverSidecarAsset(
            kind = ResolverSidecarKind.YT_DLP,
            platformId = WINDOWS_X86_64,
            version = YT_DLP_VERSION,
            assetName = "yt-dlp.exe",
            sha256 = YT_DLP_WINDOWS_SHA256,
            url = "$YT_DLP_BASE_URL/yt-dlp.exe",
        ),
        ResolverSidecarAsset(
            kind = ResolverSidecarKind.QUICKJS,
            platformId = LINUX_X86_64,
            version = QUICKJS_VERSION,
            assetName = "qjs-linux-x86_64",
            sha256 = QUICKJS_LINUX_SHA256,
            url = "$QUICKJS_BASE_URL/qjs-linux-x86_64",
        ),
        ResolverSidecarAsset(
            kind = ResolverSidecarKind.QUICKJS,
            platformId = WINDOWS_X86_64,
            version = QUICKJS_VERSION,
            assetName = "qjs-windows-x86_64.exe",
            sha256 = QUICKJS_WINDOWS_SHA256,
            url = "$QUICKJS_BASE_URL/qjs-windows-x86_64.exe",
        ),
    )

    fun assetsFor(platformId: String): List<ResolverSidecarAsset> =
        assets.filter { it.platformId == platformId }.also {
            if (it.size != ResolverSidecarKind.entries.size) {
                throw GradleException("No complete resolver sidecar set is pinned for platform '$platformId'")
            }
        }

    fun assetFor(platformId: String, kind: ResolverSidecarKind): ResolverSidecarAsset =
        assetsFor(platformId).first { it.kind == kind }

    fun downloadTaskName(asset: ResolverSidecarAsset): String =
        "downloadResolver${asset.kind.taskInfix}${platformTaskSuffix(asset.platformId)}"

    fun downloadPath(asset: ResolverSidecarAsset): String =
        "resolver/downloads/${asset.platformId}/${asset.assetName}"

    /**
     * Selects the sidecar platform for the current build.
     *
     * The default follows the host because Compose Desktop and GraalVM Native
     * Image cannot cross-compile a complete distribution. The
     * `archivetune.resolverPlatform` Gradle property exists for staging and
     * verification on a matching host, for example when reproducing a release
     * staging layout. It must name the target platform, not an arbitrary one.
     */
    fun resolvePlatform(override: String?): String? {
        val requested = override?.trim()?.takeIf(String::isNotEmpty)
        if (requested != null) {
            if (requested !in supportedPlatforms) {
                throw GradleException(
                    "Unsupported archivetune.resolverPlatform '$requested'. " +
                        "Supported values: ${supportedPlatforms.joinToString()}",
                )
            }
            return requested
        }
        return hostPlatformOrNull()
    }

    fun hostPlatformOrNull(): String? {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
        if (arch != "amd64" && arch != "x86_64") return null
        return when {
            os.startsWith("windows") -> WINDOWS_X86_64
            os.startsWith("linux") -> LINUX_X86_64
            else -> null
        }
    }

    fun unsupportedPlatformMessage(): String {
        val os = System.getProperty("os.name").orEmpty()
        val arch = System.getProperty("os.arch").orEmpty()
        return "The experimental desktop resolver bundles sidecars only for Windows/Linux x86_64, " +
            "but this build is running on '$os/$arch'. Build the distribution on a supported host " +
            "or pass -Parchivetune.resolverPlatform=<${supportedPlatforms.joinToString("|")}> " +
            "when preparing resources for that target."
    }

    private fun platformTaskSuffix(platformId: String): String =
        when (platformId) {
            WINDOWS_X86_64 -> "Windows"
            LINUX_X86_64 -> "Linux"
            else -> throw GradleException("Unknown resolver sidecar platform '$platformId'")
        }
}
