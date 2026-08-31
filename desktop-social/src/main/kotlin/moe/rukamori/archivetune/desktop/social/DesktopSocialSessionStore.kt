package moe.rukamori.archivetune.desktop.social

import java.nio.charset.StandardCharsets
import moe.rukamori.archivetune.desktopauth.SecureSessionStore
import org.json.JSONObject

class DesktopSocialSessionStore(
    private val secureStore: SecureSessionStore,
) {
    fun read(): StoredSocialSession? {
        val bytes = secureStore.read() ?: return null
        return runCatching {
            val value = JSONObject(String(bytes, StandardCharsets.UTF_8))
            StoredSocialSession(
                token = value.getString("token"),
                expiresAt = value.getLong("expiresAt"),
            )
        }.getOrNull()
    }

    fun write(session: StoredSocialSession) {
        val bytes = JSONObject()
            .put("token", session.token)
            .put("expiresAt", session.expiresAt)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        secureStore.write(bytes)
    }

    fun delete() {
        secureStore.delete()
    }
}
