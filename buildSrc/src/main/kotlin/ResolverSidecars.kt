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
    BUN("bun", "Bun", "bun"),
}

/**
 * One pinned upstream release asset.
 *
 * [assetName] is the name published on GitHub, while [fileName] is the name the
 * desktop resolver looks for inside the bundled `resolver` directory.
 *
 * Some upstreams ship a raw executable, others (Bun) ship a ZIP. For archives,
 * [sha256] covers the downloaded ZIP, [archiveMember] names the single regular
 * file inside it, and [binarySha256] covers that extracted executable. For raw
 * executables both digests are the same and [archiveMember] is null.
 */
data class ResolverSidecarAsset(
    val kind: ResolverSidecarKind,
    val platformId: String,
    val version: String,
    val assetName: String,
    val sha256: String,
    val url: String,
    val archiveMember: String? = null,
    val binarySha256: String = sha256,
) {
    val fileName: String
        get() = when (kind) {
            ResolverSidecarKind.YT_DLP ->
                if (platformId.startsWith("windows-")) "yt-dlp.exe" else "yt-dlp"

            ResolverSidecarKind.BUN ->
                if (platformId.startsWith("windows-")) "bun.exe" else "bun"
        }

    val isArchive: Boolean
        get() = archiveMember != null
}

/**
 * Single source of truth for the yt-dlp and Bun sidecars bundled with the
 * experimental desktop resolver.
 *
 * The versions, URLs, and SHA-256 values below were verified against the
 * upstream GitHub release assets. Update this file, packaging/resolver/README.md,
 * packaging/resolver/THIRD_PARTY_NOTICES.md, and the staged manifest together.
 */
object ResolverSidecars {
    const val YT_DLP_VERSION = "2026.08.19"

    /** yt-dlp 2026.08.19 ships this EJS solver bundled inside the frozen executable. */
    const val YT_DLP_BUNDLED_EJS_VERSION = "0.8.0"

    const val BUN_VERSION = "1.4.3"
    private const val BUN_TAG = "bun-v$BUN_VERSION"

    const val WINDOWS_X86_64 = "windows-x86_64"
    const val LINUX_X86_64 = "linux-x86_64"

    private const val YT_DLP_BASE_URL =
        "https://github.com/yt-dlp/yt-dlp/releases/download/$YT_DLP_VERSION"
    private const val BUN_BASE_URL =
        "https://github.com/oven-sh/bun/releases/download/$BUN_TAG"

    private const val YT_DLP_LINUX_SHA256 =
        "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a"
    private const val YT_DLP_WINDOWS_SHA256 =
        "66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a"

    // Official bun-linux-x64.zip from bun-v1.4.3. The GitHub release publishes
    // this digest in its clearsigned SHASUMS256.txt. Bun's -baseline ZIP is a
    // compatibility alias that contains this exact same executable, so the
    // standard asset already targets Nehalem/SSE4.2 with runtime AVX2 dispatch.
    private const val BUN_LINUX_ZIP_SHA256 =
        "3e730528d89775f03f87f05c06787c4df4099d0a37f3f252e7b829cb23816e18"
    private const val BUN_LINUX_BINARY_SHA256 =
        "7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b"

    // Official bun-windows-x64.zip from bun-v1.4.3.
    private const val BUN_WINDOWS_ZIP_SHA256 =
        "0bf84e808552beea67bf1202a287a35c6c825d837b287b3f8e9dd513dba35d3d"
    private const val BUN_WINDOWS_BINARY_SHA256 =
        "095db4abae203d42f29fdec26da4a882fcde6333cdd84e40c0c7828c0cf543f6"

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
            kind = ResolverSidecarKind.BUN,
            platformId = LINUX_X86_64,
            version = BUN_VERSION,
            assetName = "bun-linux-x64.zip",
            sha256 = BUN_LINUX_ZIP_SHA256,
            url = "$BUN_BASE_URL/bun-linux-x64.zip",
            archiveMember = "bun-linux-x64/bun",
            binarySha256 = BUN_LINUX_BINARY_SHA256,
        ),
        ResolverSidecarAsset(
            kind = ResolverSidecarKind.BUN,
            platformId = WINDOWS_X86_64,
            version = BUN_VERSION,
            assetName = "bun-windows-x64.zip",
            sha256 = BUN_WINDOWS_ZIP_SHA256,
            url = "$BUN_BASE_URL/bun-windows-x64.zip",
            archiveMember = "bun-windows-x64/bun.exe",
            binarySha256 = BUN_WINDOWS_BINARY_SHA256,
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

    fun extractTaskName(asset: ResolverSidecarAsset): String =
        "extractResolver${asset.kind.taskInfix}${platformTaskSuffix(asset.platformId)}"

    fun downloadPath(asset: ResolverSidecarAsset): String =
        "resolver/downloads/${asset.platformId}/${asset.assetName}"

    /**
     * Verified, extracted executable produced by an archive asset. Raw assets
     * are already their own executable, so callers use [downloadPath] instead.
     */
    fun extractedBinaryPath(asset: ResolverSidecarAsset): String =
        "resolver/extracted/${asset.platformId}/${asset.fileName}"

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
