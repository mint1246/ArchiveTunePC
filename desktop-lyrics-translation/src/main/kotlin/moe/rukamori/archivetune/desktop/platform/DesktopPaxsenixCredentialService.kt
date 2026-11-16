/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import moe.rukamori.archivetune.desktopauth.SecureSessionStore
import moe.rukamori.archivetune.paxsenix.PaxsenixLyrics
import java.nio.charset.StandardCharsets

/** Keeps the Paxsenix API key in Windows DPAPI storage and configures the shared lyrics client. */
class DesktopPaxsenixCredentialService(
    private val credentialStore: SecureSessionStore,
) {
    @Volatile private var apiKeyConfigured = false

    fun restore() {
        val key = readApiKey().orEmpty()
        apiKeyConfigured = key.isNotBlank()
        PaxsenixLyrics.setApiKey(key)
    }

    fun hasApiKey(): Boolean = apiKeyConfigured

    fun setApiKey(value: String?) {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)
        if (normalized == null) {
            try {
                credentialStore.delete()
            } finally {
                apiKeyConfigured = false
                PaxsenixLyrics.setApiKey("")
            }
            return
        }

        val bytes = normalized.toByteArray(StandardCharsets.UTF_8)
        try {
            credentialStore.write(bytes)
            PaxsenixLyrics.setApiKey(normalized)
            apiKeyConfigured = true
        } finally {
            bytes.fill(0)
        }
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
