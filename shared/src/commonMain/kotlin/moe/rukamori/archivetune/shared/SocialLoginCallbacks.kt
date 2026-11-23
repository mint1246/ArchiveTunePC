package moe.rukamori.archivetune.shared

import moe.rukamori.archivetune.social.parseLocalhostDiscordCallbackCode

/** Delegates localhost Discord OAuth callback validation to `social-sdk`. */
internal fun discordCallbackCode(target: String, expectedNonce: String): String =
    parseLocalhostDiscordCallbackCode(targetUrl = target, expectedNonce = expectedNonce, expectedPort = 49152)
