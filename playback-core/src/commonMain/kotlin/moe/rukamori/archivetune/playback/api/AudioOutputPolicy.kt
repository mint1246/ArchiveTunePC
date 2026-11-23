/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import moe.rukamori.archivetune.playback.internal.sha256HexPrefix

/**
 * Platform-neutral description of an output device used to derive a stable selection id.
 * Android supplies ids from `AudioDeviceInfo`; no Android types are required here.
 */
data class AudioOutputIdentity(
    val runtimeId: Int,
    val type: Int,
    val address: String?,
    val productName: String?,
    val fallbackName: String,
)

/** A device exposed to the user plus the stable id persisted by the caller. */
data class AudioOutputPolicyChoice(
    val persistentId: String,
    val runtimeId: Int,
    val displayName: String,
)

/**
 * Normalizes platform output devices into stable, deduplicated choices and resolves a
 * previously persisted id back to the current runtime device. Storage is owned by the caller.
 */
object AudioOutputPolicy {
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
            ?.lowercase()
            ?.takeIf(String::isNotEmpty)

    private fun String.sha256Prefix(): String = sha256HexPrefix(this, byteCount = 16)
}
