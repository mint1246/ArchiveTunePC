/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.utils.dataStore

interface SocialSessionStore {
    suspend fun read(): StoredSocialSession?
    suspend fun write(session: StoredSocialSession)
    suspend fun clear()
}

class AndroidSocialSessionStore(
    context: Context,
) : SocialSessionStore {
    private val dataStore = context.applicationContext.dataStore

    override suspend fun read(): StoredSocialSession? {
        val preferences = dataStore.data.first()
        val token = preferences[TOKEN_KEY]?.takeIf(String::isNotBlank) ?: return null
        val expiresAt = preferences[EXPIRES_AT_KEY] ?: return null
        return StoredSocialSession(token, expiresAt)
    }

    override suspend fun write(session: StoredSocialSession) {
        dataStore.edit { preferences ->
            preferences[TOKEN_KEY] = session.token
            preferences[EXPIRES_AT_KEY] = session.expiresAt
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(TOKEN_KEY)
            preferences.remove(EXPIRES_AT_KEY)
        }
    }

    private companion object {
        val TOKEN_KEY = stringPreferencesKey("archivetune_social_session_token")
        val EXPIRES_AT_KEY = longPreferencesKey("archivetune_social_session_expires_at")
    }
}
