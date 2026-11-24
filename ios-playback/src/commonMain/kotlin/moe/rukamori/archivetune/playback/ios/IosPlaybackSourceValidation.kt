/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.ios

import moe.rukamori.archivetune.playback.api.PlaybackFailure
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource

private val SUPPORTED_IOS_MIME_TYPES = setOf(
    "audio/mp4",
    "audio/m4a",
    "audio/x-m4a",
    "audio/aac",
    "audio/mpeg",
    "audio/mp3",
    "audio/wav",
    "audio/x-wav",
    "audio/flac",
    "audio/x-flac",
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
)

private const val DEFAULT_PLAYBACK_ERROR_MESSAGE =
    "This song could not be played. Try another song or retry."

/**
 * Validates that [source] is safe and playable by AVFoundation without custom HTTP headers.
 * Returns null when valid, or a sanitized [PlaybackFailure] (never containing URLs or headers).
 */
fun validateIosPlaybackSource(source: ResolvedPlaybackSource): PlaybackFailure? {
    if (source.headers.isNotEmpty()) {
        return PlaybackFailure(
            message = "Streams requiring custom HTTP headers are not supported on iOS.",
            kind = PlaybackFailure.Kind.UNSUPPORTED_FORMAT,
            recoverable = false,
        )
    }

    val normalizedMime = source.mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (normalizedMime == null || normalizedMime !in SUPPORTED_IOS_MIME_TYPES) {
        return PlaybackFailure(
            message = "This audio format is not supported by the iOS player.",
            kind = PlaybackFailure.Kind.UNSUPPORTED_FORMAT,
            recoverable = false,
        )
    }

    val uri = source.uri
    if (!isValidDirectHttpsUrl(uri) && !isValidLocalFileUrl(uri)) {
        return PlaybackFailure(
            message = "The player requires a direct HTTPS or local file media URL.",
            kind = PlaybackFailure.Kind.UNSUPPORTED_FORMAT,
            recoverable = false,
        )
    }

    return null
}

internal fun isValidLocalFileUrl(rawUrl: String): Boolean {
    if (rawUrl.length < 9 || rawUrl.any { it.code < 32 }) return false
    if (!rawUrl.startsWith("file:///", ignoreCase = true)) return false
    val path = rawUrl.substring(7)
    return path.length > 1 && !path.contains("..")
}

internal fun isValidDirectHttpsUrl(rawUrl: String): Boolean {
    if (rawUrl.length < 9 || rawUrl.any { it.isWhitespace() || it.code < 32 }) return false
    if (!rawUrl.startsWith("https://", ignoreCase = true)) return false
    val afterScheme = rawUrl.substring(8)
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isBlank() || '@' in authority) return false
    val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
    } else {
        authority.substringBefore(':')
    }
    return host.isNotBlank()
}

/** Redacts URLs and maps arbitrary failures to safe, user-facing playback messages. */
fun sanitizePlaybackErrorMessage(
    message: String?,
    fallback: String = DEFAULT_PLAYBACK_ERROR_MESSAGE,
): String {
    val clean = message?.trim()?.takeIf(String::isNotEmpty) ?: return fallback
    if (clean.contains("http://", ignoreCase = true) ||
        clean.contains("https://", ignoreCase = true) ||
        clean.contains("googlevideo.com", ignoreCase = true) ||
        clean.contains("Authorization", ignoreCase = true) ||
        clean.contains("Cookie", ignoreCase = true)
    ) {
        return fallback
    }
    return clean.take(200)
}
