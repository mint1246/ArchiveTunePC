package moe.rukamori.archivetune.social

interface SocialSessionStore {
    suspend fun read(): StoredSocialSession?
    suspend fun write(session: StoredSocialSession)
    suspend fun clear()
}
