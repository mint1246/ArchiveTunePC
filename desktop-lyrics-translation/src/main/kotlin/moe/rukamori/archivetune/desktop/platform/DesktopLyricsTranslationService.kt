/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import moe.rukamori.archivetune.desktop.lyrics.DesktopLyricsTranslator
import moe.rukamori.archivetune.desktop.settings.DesktopLyricsTranslationProvider
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import moe.rukamori.archivetune.desktopauth.SecureSessionStore
import moe.rukamori.archivetune.lyrics.JvmLyricsTranslationCache
import moe.rukamori.archivetune.lyrics.JvmLyricsTranslationClient
import moe.rukamori.archivetune.lyrics.LyricsTranslationCacheKey
import moe.rukamori.archivetune.lyrics.LyricsTranslationConfig
import moe.rukamori.archivetune.lyrics.LyricsTranslationProvider
import java.nio.charset.StandardCharsets

/** Bridges desktop preferences, Windows-protected credentials and the platform-neutral client. */
class DesktopLyricsTranslationService(
    private val settings: DesktopSettingsStore,
    private val credentialStore: SecureSessionStore,
    private val cache: JvmLyricsTranslationCache,
    private val client: JvmLyricsTranslationClient = JvmLyricsTranslationClient(),
) : DesktopLyricsTranslator {
    fun hasApiKey(): Boolean = runCatching { readApiKey()?.isNotBlank() == true }.getOrDefault(false)

    fun setApiKey(value: String?) {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)
        if (normalized == null) {
            credentialStore.delete()
        } else {
            val bytes = normalized.toByteArray(StandardCharsets.UTF_8)
            try {
                credentialStore.write(bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }

    override suspend fun translate(
        itemId: String,
        document: moe.rukamori.archivetune.lyrics.LyricsDocument,
        sourceLines: List<String>,
        targetLanguage: String,
    ): List<String> {
        val current = settings.snapshot()
        val translationProvider = when (current.lyricsTranslationProvider) {
            DesktopLyricsTranslationProvider.GOOGLE -> LyricsTranslationProvider.GOOGLE
            DesktopLyricsTranslationProvider.OPENAI_COMPATIBLE -> LyricsTranslationProvider.OPENAI_COMPATIBLE
        }
        val config = LyricsTranslationConfig(
            provider = translationProvider,
            targetLanguage = targetLanguage,
            endpoint = current.lyricsTranslationEndpoint,
            model = current.lyricsTranslationModel,
            customPrompt = current.lyricsTranslationCustomPrompt,
        )
        val key = LyricsTranslationCacheKey(
            trackId = itemId,
            sourceText = document.text,
            targetLanguage = targetLanguage,
            provider = translationProvider,
            endpoint = config.endpoint,
            model = config.model,
            customPrompt = config.customPrompt,
        )
        cache.get(key)?.let { cached ->
            if (cached.size == sourceLines.size) return cached
        }
        val apiKey = if (translationProvider == LyricsTranslationProvider.OPENAI_COMPATIBLE) {
            readApiKey()?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Save an API key in Settings to use the AI translation provider")
        } else {
            null
        }
        val requestConfig = config.copy(apiKey = apiKey)
        return client.translate(sourceLines, requestConfig).also { translated -> cache.put(key, translated) }
    }

    private fun readApiKey(): String? {
        val bytes = credentialStore.read() ?: return null
        return try {
            bytes.toString(StandardCharsets.UTF_8).takeIf(String::isNotEmpty)
        } finally {
            bytes.fill(0)
        }
    }
}
