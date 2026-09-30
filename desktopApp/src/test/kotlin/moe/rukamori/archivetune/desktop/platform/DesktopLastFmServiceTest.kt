package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import moe.rukamori.archivetune.desktopauth.SecureSessionStore
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLastFmServiceTest {
    @Test
    fun `browser auth stores credentials securely and scrobble timer counts only audible eligible time`() = runBlocking {
        val root = Files.createTempDirectory("lastfm-desktop-test")
        val settingsFile = root.resolve("settings.properties")
        val settings = DesktopSettingsStore(settingsFile)
        val secureStore = MemorySecureStore()
        val client = FakeLastFmClient()
        val clock = FakeClock()
        val service = DesktopLastFmService(
            settings = settings,
            credentialStore = secureStore,
            parentScope = this,
            openBrowser = { uri ->
                assertTrue(uri.toString().startsWith("https://www.last.fm/api/auth/?"))
                Result.success(Unit)
            },
            scrobbleClient = client,
            clock = clock,
            defaultApiKey = "",
            defaultSecret = "",
        )

        service.saveCredentials("https://ws.audioscrobbler.com/2.0/", "api-key", "shared-secret")
        assertTrue(secureStore.value?.toString(Charsets.UTF_8)?.contains("shared-secret") == true)
        service.startAuthorization()
        withTimeout(5_000) { service.state.first { !it.busy } }
        assertTrue(service.state.value.pendingAuthorization)
        service.finishAuthorization()
        withTimeout(5_000) { service.state.first { !it.busy } }
        assertEquals("listener", service.state.value.username)

        service.setScrobblingEnabled(true)
        val playing = snapshot(positionMs = 0L)
        service.onMediaTransition(playing, eligible = true, previousTrackId = null)
        yield()
        assertEquals(1, client.nowPlaying.size)

        clock.advance(60_000L)
        service.onSnapshot(snapshot(positionMs = 90_000L), eligible = true)
        clock.advance(10 * 60_000L)
        service.onSnapshot(snapshot(playWhenReady = false, positionMs = 160_000L), eligible = true)
        clock.advance(60_000L)
        service.onSnapshot(snapshot(positionMs = 30_000L), eligible = true)
        assertTrue(client.scrobbles.isEmpty(), "pause duration and seek distance must not satisfy the gate")

        clock.advance(60_000L)
        service.onSnapshot(snapshot(positionMs = 90_000L), eligible = true)
        yield()
        assertEquals(1, client.scrobbles.size)
        assertEquals(1, client.scrobbles.single().timestamp.toInt())
        service.onSnapshot(snapshot(positionMs = 200_000L), eligible = true)
        yield()
        assertEquals(1, client.scrobbles.size, "a playback instance must be scrobbled at most once")

        service.close()
        settings.close()
        assertFalse(Files.readString(settingsFile).contains("shared-secret"))
    }

    @Test
    fun `ineligible party time and podcasts do not accrue scrobble time`() = runBlocking {
        val settings = DesktopSettingsStore(Files.createTempDirectory("lastfm-party-test").resolve("settings.properties"))
        val client = FakeLastFmClient()
        val clock = FakeClock()
        val service = DesktopLastFmService(settings, MemorySecureStore(), this, { Result.success(Unit) }, client, clock, defaultApiKey = "", defaultSecret = "")
        service.saveCredentials("https://ws.audioscrobbler.com/2.0/", "api", "secret")
        service.startAuthorization()
        withTimeout(5_000) { service.state.first { !it.busy } }
        service.finishAuthorization()
        withTimeout(5_000) { service.state.first { !it.busy } }
        service.setScrobblingEnabled(true)

        val playing = snapshot(positionMs = 0L)
        service.onMediaTransition(playing, eligible = false, previousTrackId = null)
        clock.advance(5 * 60_000L)
        service.onSnapshot(playing, eligible = false)
        clock.advance(60_000L)
        service.onSnapshot(playing, eligible = true)
        assertTrue(client.scrobbles.isEmpty())

        val podcast = playing.copy(queue = listOf(playing.currentItem!!.copy(metadata = playing.currentItem!!.metadata.copy(isPodcast = true))))
        service.onMediaTransition(podcast, eligible = true, previousTrackId = playing.currentItem?.metadata?.id)
        clock.advance(5 * 60_000L)
        service.onSnapshot(podcast, eligible = true)
        assertTrue(client.scrobbles.isEmpty())
        service.close()
        settings.close()
    }

    @Test
    fun `late authorization cannot reconnect after disconnect`() = runBlocking {
        val settings = DesktopSettingsStore(Files.createTempDirectory("lastfm-auth-race").resolve("settings.properties"))
        val response = CompletableDeferred<DesktopLastFmAuthentication>()
        val requested = CompletableDeferred<Unit>()
        val client = object : DesktopLastFmClient by FakeLastFmClient() {
            override suspend fun getSession(token: String): Result<DesktopLastFmAuthentication> = withContext(NonCancellable) {
                requested.complete(Unit)
                Result.success(response.await())
            }
        }
        val secure = MemorySecureStore()
        val service = DesktopLastFmService(settings, secure, this, { Result.success(Unit) }, client, defaultApiKey = "", defaultSecret = "")
        try {
            service.saveCredentials(LastFM.DEFAULT_API_ENDPOINT, "api", "secret")
            service.startAuthorization()
            withTimeout(5_000) { service.state.first { !it.busy } }
            service.finishAuthorization()
            withTimeout(5_000) { requested.await() }
            service.disconnect()
            response.complete(DesktopLastFmAuthentication("stale listener", "stale session"))
            yield()
            assertFalse(service.state.value.connected)
            assertFalse(secure.value!!.toString(Charsets.UTF_8).contains("stale session"))
        } finally { service.close(); settings.close() }
    }

    @Test
    fun `corrupt protected credentials do not fail startup`() = runBlocking {
        val settings = DesktopSettingsStore(Files.createTempDirectory("lastfm-corrupt").resolve("settings.properties"))
        val secure = MemorySecureStore().apply { value = "invalid json".toByteArray() }
        val service = DesktopLastFmService(settings, secure, this, { Result.success(Unit) }, FakeLastFmClient(), defaultApiKey = "", defaultSecret = "")
        try {
            service.restore()
            assertFalse(service.state.value.connected)
            assertFalse(service.state.value.credentialsConfigured)
            assertTrue(service.state.value.error != null)
        } finally { service.close(); settings.close() }
    }

    @Test
    fun `build credentials allow linking without entering developer keys`() = runBlocking {
        val settings = DesktopSettingsStore(Files.createTempDirectory("lastfm-defaults").resolve("settings.properties"))
        val secure = MemorySecureStore()
        val service = DesktopLastFmService(
            settings, secure, this, { Result.success(Unit) }, FakeLastFmClient(),
            defaultApiKey = "build-api", defaultSecret = "build-secret",
        )
        try {
            service.restore()
            assertTrue(service.state.value.credentialsConfigured)
            assertEquals("build-api", LastFM.currentConfig().apiKey)
            assertEquals("build-secret", LastFM.currentConfig().secret)
            assertTrue(secure.value == null, "restore must not persist application defaults in a plain settings file")
            service.startAuthorization()
            withTimeout(5_000) { service.state.first { !it.busy } }
            assertTrue(service.state.value.pendingAuthorization)
            service.finishAuthorization()
            withTimeout(5_000) { service.state.first { !it.busy } }
            assertTrue(service.state.value.connected)
            service.disconnect()
            assertTrue(service.state.value.credentialsConfigured)
            assertFalse(service.state.value.connected)
        } finally { service.close(); settings.close() }
    }

    @Test
    fun `saved overrides and their session take priority over build credentials`() = runBlocking {
        val settings = DesktopSettingsStore(Files.createTempDirectory("lastfm-override").resolve("settings.properties"))
        val secure = MemorySecureStore().apply {
            value = """{"apiKey":"saved-api","secret":"saved-secret","sessionKey":"saved-session","username":"saved-user"}""".toByteArray()
        }
        val service = DesktopLastFmService(
            settings, secure, this, { Result.success(Unit) }, FakeLastFmClient(),
            defaultApiKey = "build-api", defaultSecret = "build-secret",
        )
        try {
            service.restore()
            assertEquals("saved-api", LastFM.currentConfig().apiKey)
            assertEquals("saved-secret", LastFM.currentConfig().secret)
            assertEquals("saved-session", LastFM.currentConfig().sessionKey)
            assertEquals("saved-user", service.state.value.username)
        } finally { service.close(); settings.close() }
    }

    @Test
    fun `empty or corrupt stored credentials fall back without reusing an unrelated session`() = runBlocking {
        for (payload in listOf("invalid json", """{"apiKey":"","secret":"","sessionKey":"stale","username":"stale"}""")) {
            val settings = DesktopSettingsStore(Files.createTempDirectory("lastfm-default-recovery").resolve("settings.properties"))
            val secure = MemorySecureStore().apply { value = payload.toByteArray() }
            val service = DesktopLastFmService(
                settings, secure, this, { Result.success(Unit) }, FakeLastFmClient(),
                defaultApiKey = "build-api", defaultSecret = "build-secret",
            )
            try {
                service.restore()
                assertTrue(service.state.value.credentialsConfigured)
                assertFalse(service.state.value.connected)
                assertEquals("build-api", LastFM.currentConfig().apiKey)
                assertTrue(LastFM.currentConfig().sessionKey == null)
            } finally { service.close(); settings.close() }
        }
    }

    @Test
    fun `Lastfm build credentials are not sent to alternate endpoints`() = runBlocking {
        val settings = DesktopSettingsStore(Files.createTempDirectory("lastfm-alternate").resolve("settings.properties"))
        settings.update { it.copy(lastFmEndpoint = LastFM.LIBREFM_API_ENDPOINT) }
        val service = DesktopLastFmService(
            settings, MemorySecureStore(), this, { Result.success(Unit) }, FakeLastFmClient(),
            defaultApiKey = "build-api", defaultSecret = "build-secret",
        )
        try {
            service.restore()
            assertFalse(service.state.value.credentialsConfigured)
            assertEquals("", LastFM.currentConfig().apiKey)
            assertEquals("", LastFM.currentConfig().secret)
        } finally { service.close(); settings.close() }
    }

    private fun snapshot(
        playWhenReady: Boolean = true,
        positionMs: Long,
    ) = PlaybackSnapshot(
        queue = listOf(
            PlaybackItem(
                PlaybackMetadata(
                    id = "track-1",
                    title = "Test track",
                    artists = listOf(PlaybackArtist(name = "Test artist")),
                    durationHintMs = 240_000L,
                ),
            ),
        ),
        currentIndex = 0,
        status = PlaybackStatus.READY,
        playWhenReady = playWhenReady,
        positionMs = positionMs,
        durationMs = 240_000L,
    )

    private class FakeLastFmClient : DesktopLastFmClient {
        data class Scrobble(val timestamp: Long)

        val nowPlaying = mutableListOf<String>()
        val scrobbles = mutableListOf<Scrobble>()
        override suspend fun getToken(): Result<String> = Result.success("auth-token")
        override suspend fun getSession(token: String): Result<DesktopLastFmAuthentication> =
            Result.success(DesktopLastFmAuthentication("listener", "session-key"))
        override suspend fun updateNowPlaying(artist: String, track: String, album: String?, trackNumber: Int?, duration: Int?): Result<Unit> {
            nowPlaying += track
            return Result.success(Unit)
        }
        override suspend fun scrobble(artist: String, track: String, timestamp: Long, album: String?, trackNumber: Int?, duration: Int?): Result<Unit> {
            scrobbles += Scrobble(timestamp)
            return Result.success(Unit)
        }
    }

    private class MemorySecureStore : SecureSessionStore {
        var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun delete() { value = null }
    }

    private class FakeClock : DesktopLastFmClock {
        private var elapsed = 0L
        override fun elapsedRealtimeMs(): Long = elapsed
        override fun wallTimeEpochMs(): Long = 1_000L + elapsed
        fun advance(milliseconds: Long) { elapsed += milliseconds }
    }
}
