/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktopauth

import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Safe-to-display metadata only; cookie names and values are intentionally omitted. */
data class YouTubeCookieJsonSummary(
    val acceptedCookieCount: Int,
    val domains: List<String>,
)

sealed interface YouTubeCookieJsonInspection {
    data class Ready(val summary: YouTubeCookieJsonSummary) : YouTubeCookieJsonInspection
    data class Rejected(val reason: YouTubeCookieJsonFailure) : YouTubeCookieJsonInspection
}

sealed interface YouTubeCookieJsonImportResult {
    class Ready internal constructor(internal val parsed: ParsedYouTubeCookieJson) : YouTubeCookieJsonImportResult
    data class Rejected(val reason: YouTubeCookieJsonFailure) : YouTubeCookieJsonImportResult
}

enum class YouTubeCookieJsonFailure {
    EMPTY,
    TOO_LARGE,
    MALFORMED_JSON,
    NO_SUPPORTED_COOKIES,
    MISSING_LOGIN_COOKIE,
    UNSAFE_COOKIE_VALUE,
    HEADER_TOO_LARGE,
}

/**
 * Parsed cookie material. Its `toString` deliberately contains no secrets; callers should use
 * [summary] for UI and diagnostics, then consume [cookieHeader] only for immediate validation.
 */
internal class ParsedYouTubeCookieJson(
    internal val cookieHeader: String,
    internal val expiresAtEpochMillis: Long?,
    val summary: YouTubeCookieJsonSummary,
) {
    override fun toString(): String = "ParsedYouTubeCookieJson(summary=$summary)"
}

/**
 * Imports standard browser-extension/DevTools JSON without accepting executable content, cookie
 * header text, arbitrary domains, or unsafe header characters. The only output secret is a
 * short-lived Cookie header passed directly to session validation.
 */
object YouTubeCookieJsonImporter {
    fun inspect(rawJson: String, nowEpochMillis: Long = System.currentTimeMillis()): YouTubeCookieJsonInspection =
        when (val result = parse(rawJson, nowEpochMillis)) {
            is YouTubeCookieJsonImportResult.Ready -> YouTubeCookieJsonInspection.Ready(result.parsed.summary)
            is YouTubeCookieJsonImportResult.Rejected -> YouTubeCookieJsonInspection.Rejected(result.reason)
        }

    fun parse(rawJson: String, nowEpochMillis: Long = System.currentTimeMillis()): YouTubeCookieJsonImportResult {
        if (rawJson.length > MAX_JSON_CHARACTERS) return YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.TOO_LARGE)
        if (rawJson.isBlank()) return YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.EMPTY)

        val root =
            try {
                JSONTokener(rawJson).nextValue()
            } catch (_: Exception) {
                return YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.MALFORMED_JSON)
            }
        val rawCookies =
            try {
                extractCookies(root)
            } catch (_: Exception) {
                return YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.MALFORMED_JSON)
            }
        if (rawCookies.isEmpty()) return YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.NO_SUPPORTED_COOKIES)

        val accepted = mutableListOf<ImportedCookie>()
        var encounteredUnsafeValue = false
        rawCookies.take(MAX_EXPORTED_COOKIES).forEachIndexed { index, json ->
            val candidate = parseCookie(json, index) ?: return@forEachIndexed
            // Match Android's CookieManager.getCookie(YouTube URL) behavior: only cookies that
            // belong to YouTube are eligible for the Cookie header sent to music.youtube.com.
            // Google-domain cookies are browser-login state, not YouTube request cookies.
            if (!candidate.appliesToMusicRoot()) return@forEachIndexed
            if (!candidate.name.isSafeCookieName() || !candidate.value.isSafeCookieValue()) {
                encounteredUnsafeValue = true
                return@forEachIndexed
            }
            if (candidate.expiresAtEpochMillis?.let { it <= nowEpochMillis } == true) return@forEachIndexed
            accepted += candidate
        }
        if (accepted.isEmpty()) {
            return YouTubeCookieJsonImportResult.Rejected(
                if (encounteredUnsafeValue) YouTubeCookieJsonFailure.UNSAFE_COOKIE_VALUE else YouTubeCookieJsonFailure.NO_SUPPORTED_COOKIES,
            )
        }

        val selected =
            accepted
                .groupBy(ImportedCookie::name)
                .values
                .map { choices -> choices.maxWithOrNull(cookieSpecificityComparator)!! }
                .sortedBy(ImportedCookie::name)
        val header = selected.joinToString("; ") { "${it.name}=${it.value}" }
        if (header.length > MAX_COOKIE_HEADER_CHARACTERS) return YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.HEADER_TOO_LARGE)
        if (!hasYouTubeLoginCookie(header)) return YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.MISSING_LOGIN_COOKIE)

        return YouTubeCookieJsonImportResult.Ready(
            ParsedYouTubeCookieJson(
                cookieHeader = header,
                expiresAtEpochMillis = selected
                    .filter { it.name in LOGIN_COOKIE_NAMES }
                    .mapNotNull { it.expiresAtEpochMillis }
                    .minOrNull(),
                summary = YouTubeCookieJsonSummary(
                    acceptedCookieCount = selected.size,
                    domains = selected.map { it.domain.displayDomain() }.distinct().sorted(),
                ),
            ),
        )
    }

    private fun extractCookies(root: Any?): List<JSONObject> =
        when (root) {
            is JSONArray -> root.asCookieObjects()
            is JSONObject -> {
                val cookies = root.opt("cookies")
                when (cookies) {
                    is JSONArray -> cookies.asCookieObjects()
                    is JSONObject -> simpleCookieObject(cookies)
                    else -> simpleCookieObject(root)
                }
            }
            else -> emptyList()
        }

    /** A simple name→value object implies the currently selected music.youtube.com site. */
    private fun simpleCookieObject(value: JSONObject): List<JSONObject> =
        value.keys().asSequence().mapNotNull { name ->
            val cookieValue = value.opt(name)
            if (cookieValue is String || cookieValue is Number || cookieValue is Boolean) {
                JSONObject().put("name", name).put("value", cookieValue.toString()).put("domain", DEFAULT_IMPLIED_DOMAIN)
            } else {
                null
            }
        }.toList()

    private fun JSONArray.asCookieObjects(): List<JSONObject> =
        (0 until length()).mapNotNull { opt(it) as? JSONObject }

    private fun parseCookie(value: JSONObject, sourceIndex: Int): ImportedCookie? {
        // A partitioned cookie belongs to a separate browser context. The portable header has no
        // partition key, so it cannot safely substitute for a top-level Music session cookie.
        if (value.opt("partitionKey")?.let { it != JSONObject.NULL } == true || value.optBoolean("partitioned", false)) return null
        val name = value.optString("name").trim().takeIf(String::isNotEmpty) ?: return null
        val cookieValue = value.opt("value")?.takeUnless { it == JSONObject.NULL }?.toString() ?: return null
        val domain = value.optString("domain", DEFAULT_IMPLIED_DOMAIN).normalizeDomain() ?: return null
        val path = value.optString("path", "/").takeIf { it.startsWith('/') } ?: "/"
        return ImportedCookie(
            name = name,
            value = cookieValue,
            domain = domain,
            path = path,
            hostOnly = value.optBoolean("hostOnly", false),
            expiresAtEpochMillis = value.firstExpiry(),
            sourceIndex = sourceIndex,
        )
    }

    private fun JSONObject.firstExpiry(): Long? =
        sequenceOf("expirationDate", "expires", "expiry", "expiration")
            .mapNotNull { key -> opt(key).takeUnless { it == JSONObject.NULL } }
            .mapNotNull(::parseExpiry)
            .firstOrNull()

    private fun parseExpiry(value: Any): Long? =
        when (value) {
            is Number -> numericExpiry(value.toDouble())
            is String -> value.trim().toDoubleOrNull()?.let(::numericExpiry) ?: parseDateExpiry(value)
            else -> null
        }

    private fun numericExpiry(value: Double): Long? {
        if (!value.isFinite() || value <= 0.0) return null // Browser exports use -1 for session cookies.
        return if (value >= 10_000_000_000.0) value.toLong() else (value * 1_000.0).toLong()
    }

    private fun parseDateExpiry(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()

    private fun String.normalizeDomain(): String? =
        trim().lowercase(Locale.US).removePrefix(".").takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }

    // A full browser export can contain a fresher same-name cookie from www.youtube.com or
    // studio.youtube.com. It must not replace the cookie the browser sends to Music. The shared
    // header is also used for the Music homepage, so only root-path cookies are applicable.
    private fun ImportedCookie.appliesToMusicRoot(): Boolean =
        path == "/" &&
            (domain == DEFAULT_IMPLIED_DOMAIN || (domain == "youtube.com" && !hostOnly))

    private fun String.displayDomain(): String = ".$this"

    private fun String.isSafeCookieName(): Boolean = COOKIE_NAME.matches(this)

    private fun String.isSafeCookieValue(): Boolean =
        isNotEmpty() && all { character -> character.code in 0x21..0x7E && character != ';' && character != ',' }

    private data class ImportedCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val hostOnly: Boolean,
        val expiresAtEpochMillis: Long?,
        val sourceIndex: Int,
    )

    private val cookieSpecificityComparator =
        compareBy<ImportedCookie> { it.domain.length }
            .thenBy { it.path.length }
            .thenBy { it.expiresAtEpochMillis ?: Long.MAX_VALUE }
            .thenByDescending { it.sourceIndex }

    private const val DEFAULT_IMPLIED_DOMAIN = "music.youtube.com"
    private const val MAX_JSON_CHARACTERS = 1_000_000
    private const val MAX_EXPORTED_COOKIES = 5_000
    private const val MAX_COOKIE_HEADER_CHARACTERS = 24_000
    private val COOKIE_NAME = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")
    private val LOGIN_COOKIE_NAMES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID", "APISID")
}
