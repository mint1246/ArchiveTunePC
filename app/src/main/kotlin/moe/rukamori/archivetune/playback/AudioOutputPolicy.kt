/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import java.security.MessageDigest
import java.util.Locale

internal data class AudioOutputIdentity(
    val runtimeId: Int,
    val type: Int,
    val address: String?,
    val productName: String?,
    val fallbackName: String,
)

internal data class AudioOutputPolicyChoice(
    val persistentId: String,
    val runtimeId: Int,
    val displayName: String,
)

internal object AudioOutputPolicy {
    private const val ID_PREFIX = "android-audio-v1"

    fun normalize(identities: List<AudioOutputIdentity>): List<AudioOutputPolicyChoice> {
        val seen = HashSet<String>()
        return identities.mapNotNull { identity ->
            val persistentId = persistentId(identity)
            if (!seen.add(persistentId)) return@mapNotNull null
            AudioOutputPolicyChoice(
                persistentId = persistentId,
                runtimeId = identity.runtimeId,
                displayName = displayName(identity),
            )
        }
    }

    fun resolveRuntimeId(
        persistedId: String?,
        choices: List<AudioOutputPolicyChoice>,
    ): Int? {
        val normalized = persistedId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return choices.firstOrNull { it.persistentId == normalized }?.runtimeId
    }

    fun persistentId(identity: AudioOutputIdentity): String {
        val address = identity.address.normalizedIdentityToken()
        val productName = identity.productName.normalizedIdentityToken()
        val fallbackName = identity.fallbackName.normalizedIdentityToken().orEmpty()
        val discriminator =
            if (address != null) {
                "address:$address"
            } else {
                "name:${productName ?: fallbackName}"
            }
        return "$ID_PREFIX|${identity.type}|${discriminator.sha256Prefix()}"
    }

    fun displayName(identity: AudioOutputIdentity): String =
        identity.productName
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: identity.fallbackName.trim().ifEmpty { "Audio output" }

    private fun String?.normalizedIdentityToken(): String? =
        this
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotEmpty)

    private fun String.sha256Prefix(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
}
