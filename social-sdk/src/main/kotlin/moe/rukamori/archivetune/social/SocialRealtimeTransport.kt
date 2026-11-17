package moe.rukamori.archivetune.social

interface SocialRealtimeTransport : AutoCloseable {
    suspend fun connect(
        token: String,
        deviceId: String,
        onConnected: suspend () -> Unit,
        onMessage: (String) -> Unit,
    )

    suspend fun publishPresence(presence: SocialPresence)
    fun disconnect()
}
