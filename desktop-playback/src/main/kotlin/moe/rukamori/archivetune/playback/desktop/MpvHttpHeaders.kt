/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

private val HTTP_HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

/** Builds mpv's string-list property without ever joining separate headers with a delimiter. */
internal fun Map<String, String>.toMpvHttpHeaders(): List<String> =
    map { (name, value) ->
        require(HTTP_HEADER_NAME.matches(name)) { "Invalid HTTP header name" }
        require('\r' !in value && '\n' !in value) { "Invalid HTTP header value" }
        "$name: $value"
    }
