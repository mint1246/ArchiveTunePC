/*
 * ArchiveTune (2026)
 * Â© Rukamori â€” github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import moe.rukamori.archivetune.desktopauth.DesktopSystemBrowserLauncher
import moe.rukamori.archivetune.desktopauth.SecureSessionStore
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets

data class DesktopLastFmState(
    val username: String? = null,
    val credentialsConfigured: Boolean = false,
    val pendingAuthorization: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val connected: Boolean get() = !username.isNullOrBlank()
}

/** Last.fm-compatible auth and audible-time scrobbling for the desktop playback engine. */
class DesktopLastFmService(
    private val settings: DesktopSettingsStore,
    private val credentialStore: SecureSessionStore,
    parentScope: CoroutineScope,
    private val openBrowser: (URI) -> Result<Unit> = DesktopSystemBrowserLauncher::open,
    private val scrobbleClient: DesktopLastFmClient = SharedLastFmClient,
    private val clock: DesktopLastFmClock = SystemDesktopLastFmClock,
) : AutoCloseable {
    private val serviceJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + serviceJob)
    private val lock = Any()
    private val _state = MutableStateFlow(DesktopLastFmState())
    val state: StateFlow<DesktopLastFmState> = _state.asStateFlow()
    private var endpoint = settings.snapshot().lastFmEndpoint
    private var apiKey = ""
    private var secret = ""
    private var sessionKey: String? = null
    private var username: String? = null
    private var pendingToken: String? = null
    private var authGeneration = 0L
    private var sessionGeneration = 0L
    private var authJob: Job? = null
    private var activeTrackId: String? = null
    private var activeTrackInstance = 0L
    private var trackStartedEpochSeconds = 0L
    private var lastSampleElapsedMs: Long? = null
    private var lastSampleAudible = false
    private var audibleElapsedMs = 0L
    private var scrobbleSent = false
    private var nowPlayingSent = false
    private var networkJob: Job? = null

    fun restore() {
        try {
            val bytes = credentialStore.read() ?: return
            try {
            val payload = JSONObject(bytes.toString(StandardCharsets.UTF_8))
            synchronized(lock) {
                endpoint = payload.optString("endpoint", endpoint)
                apiKey = payload.optString("apiKey")
                secret = payload.optString("secret")
                sessionKey = payload.optString("sessionKey").takeIf(String::isNotBlank)
                username = payload.optString("username").takeIf(String::isNotBlank)
                configureClientLocked()
                updateStateLocked()
            }
            } finally {
                bytes.fill(0)
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            synchronized(lock) {
                endpoint = settings.snapshot().lastFmEndpoint
                apiKey = ""
                secret = ""
                sessionKey = null
                username = null
                pendingToken = null
                configureClientLocked()
                updateStateLocked(error = "Saved Last.fm credentials could not be restored: ${failure.userMessage()}")
            }
        }
    }

    /** Stores API credentials only in the existing DPAPI-backed secure-store format. */
    fun saveCredentials(newEndpoint: String, newApiKey: String, newSecret: String) {
        try {
            val normalizedEndpoint = LastFM.normalizeEndpoint(newEndpoint)
            val key = newApiKey.trim()
            val sharedSecret = newSecret.trim()
            require(key.isNotEmpty() && sharedSecret.isNotEmpty()) { "Enter both the Last.fm API key and shared secret." }
            synchronized(lock) {
                val changed = endpoint != normalizedEndpoint || apiKey != key || secret != sharedSecret
                if (changed) invalidateSessionLocked()
                endpoint = normalizedEndpoint
                apiKey = key
                secret = sharedSecret
                settings.update { it.copy(lastFmEndpoint = normalizedEndpoint) }
                if (changed) {
                    sessionKey = null
                    username = null
                    settings.update { it.copy(lastFmEnabled = false) }
                }
                persistLocked()
                configureClientLocked()
                updateStateLocked(message = "Credentials saved securely.")
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            synchronized(lock) { updateStateLocked(error = failure.userMessage()) }
        }
    }

    fun startAuthorization() {
        synchronized(lock) {
            if (_state.value.busy) return
            if (apiKey.isBlank() || secret.isBlank()) {
                updateStateLocked(error = "Set an API key and shared secret first.")
                return
            }
            configureClientLocked()
            val attempt = ++authGeneration
            authJob?.cancel()
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            authJob = scope.launch {
            try {
                val token = scrobbleClient.getToken().getOrThrow()
                val authUrl = synchronized(lock) {
                    if (attempt != authGeneration) return@synchronized null
                    pendingToken = token
                    LastFM.getAuthUrl(token)
                }
                if (authUrl == null) return@launch
                withContext(Dispatchers.IO) { openBrowser(URI(authUrl)).getOrThrow() }
                synchronized(lock) {
                    if (attempt == authGeneration) updateStateLocked(
                            busy = false,
                            pendingAuthorization = true,
                            message = "Authorize ArchiveTune in your browser, then choose Finish linking.",
                        )
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                synchronized(lock) {
                    if (attempt == authGeneration) {
                        pendingToken = null
                        updateStateLocked(busy = false, error = failure.userMessage())
                    }
                }
            }
            }
        }
    }

    fun finishAuthorization() {
        val (token, attempt) = synchronized(lock) {
            pendingToken?.let { it to ++authGeneration }?.also {
                authJob?.cancel()
                _state.value = _state.value.copy(busy = true, error = null)
            } ?: (null to authGeneration)
        }
        if (token == null) {
            synchronized(lock) { updateStateLocked(error = "Start linking first.") }
            return
        }
        authJob = scope.launch {
            try {
                val authentication = scrobbleClient.getSession(token).getOrThrow()
                synchronized(lock) {
                    if (attempt != authGeneration) return@synchronized
                    sessionKey = authentication.sessionKey
                    username = authentication.username
                    pendingToken = null
                    sessionGeneration++
                    persistLocked()
                    configureClientLocked()
                    updateStateLocked(
                        busy = false,
                        pendingAuthorization = false,
                        message = "Linked as ${authentication.username}.",
                    )
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                synchronized(lock) { if (attempt == authGeneration) updateStateLocked(busy = false, error = failure.userMessage()) }
            }
        }
    }

    fun disconnect() {
        synchronized(lock) {
            invalidateSessionLocked()
            networkJob?.cancel()
            networkJob = null
            sessionKey = null
            username = null
            pendingToken = null
            settings.update { it.copy(lastFmEnabled = false) }
            persistLocked()
            configureClientLocked()
            updateStateLocked(message = "Disconnected from Last.fm.")
            resetTrackLocked()
        }
    }

    fun setScrobblingEnabled(enabled: Boolean) {
        val connected = synchronized(lock) { !sessionKey.isNullOrBlank() && apiKey.isNotBlank() && secret.isNotBlank() }
        settings.update { it.copy(lastFmEnabled = enabled && connected) }
        if (!enabled || !connected) synchronized(lock) { resetTrackLocked() }
    }

    fun setNowPlayingEnabled(enabled: Boolean) {
        settings.update { it.copy(lastFmNowPlayingEnabled = enabled) }
    }

    fun onMediaTransition(snapshot: PlaybackSnapshot, eligible: Boolean, previousTrackId: String?) {
        synchronized(lock) {
            val current = snapshot.currentItem
            if (current != null && current.metadata.id == activeTrackId && previousTrackId != current.metadata.id) {
                onSnapshotLocked(snapshot, eligible)
                return
            }
            resetTrackLocked()
            val item = current ?: return
            beginTrackLocked(item.metadata.id, eligible)
            onSnapshotLocked(snapshot, eligible)
        }
    }

    fun onSnapshot(snapshot: PlaybackSnapshot, eligible: Boolean) {
        synchronized(lock) { onSnapshotLocked(snapshot, eligible) }
    }

    private fun onSnapshotLocked(snapshot: PlaybackSnapshot, eligible: Boolean) {
        val item = snapshot.currentItem
        if (item == null || item.metadata.id.isBlank() || item.metadata.isPodcast) {
            resetTrackLocked()
            return
        }
        if (activeTrackId != item.metadata.id) beginTrackLocked(item.metadata.id, eligible)

        val nowElapsed = clock.elapsedRealtimeMs()
        val previousSample = lastSampleElapsedMs
        val prefs = settings.snapshot()
        val audible = snapshot.status == PlaybackStatus.READY && snapshot.playWhenReady &&
            snapshot.volume > 0f && eligible && prefs.lastFmEnabled && !sessionKey.isNullOrBlank()
        if (previousSample != null && lastSampleAudible && audible) {
            audibleElapsedMs = safeAdd(audibleElapsedMs, (nowElapsed - previousSample).coerceAtLeast(0L))
        }
        lastSampleElapsedMs = nowElapsed
        lastSampleAudible = audible

        if (!prefs.lastFmEnabled || sessionKey.isNullOrBlank()) return
        if (audible && prefs.lastFmNowPlayingEnabled && !nowPlayingSent &&
            hasRequiredMetadata(item.metadata.title, item.metadata.artists.map { it.name })) {
            nowPlayingSent = true
            val instance = activeTrackInstance
            val session = sessionGeneration
            val metadata = item.metadata
            networkJob?.cancel()
            networkJob = scope.launch {
                if (isCurrentInstance(instance, session)) {
                    scrobbleClient.updateNowPlaying(
                        artist = metadata.artists.joinToString(", ") { it.name },
                        track = metadata.title,
                        album = metadata.album?.title,
                        trackNumber = metadata.trackNumber,
                        duration = snapshot.durationSecondsOrNull(),
                    ).onFailure { reportNetworkFailure(it, session) }
                }
            }
        }

        if (!audible || scrobbleSent || !hasRequiredMetadata(item.metadata.title, item.metadata.artists.map { it.name })) return
        val durationSeconds = (snapshot.durationMs.takeIf { it > 0L } ?: item.metadata.durationHintMs ?: 0L) / 1_000L
        val minimumDuration = prefs.lastFmMinTrackDurationSeconds
        if (durationSeconds <= minimumDuration) return
        val thresholdMs = minOf(
            (durationSeconds * 1_000L * prefs.lastFmScrobbleDelayPercent).toLong(),
            prefs.lastFmScrobbleDelaySeconds * 1_000L,
        )
        if (audibleElapsedMs < thresholdMs) return

        scrobbleSent = true
        val instance = activeTrackInstance
            val session = sessionGeneration
        val metadata = item.metadata
        val timestamp = trackStartedEpochSeconds
        networkJob = scope.launch {
            if (isCurrentInstance(instance, session)) {
                scrobbleClient.scrobble(
                    artist = metadata.artists.joinToString(", ") { it.name },
                    track = metadata.title,
                    timestamp = timestamp,
                    album = metadata.album?.title,
                    trackNumber = metadata.trackNumber,
                    duration = durationSeconds.toInt(),
                ).onFailure { reportNetworkFailure(it, session) }
            }
        }
    }

    private fun beginTrackLocked(id: String, eligible: Boolean) {
        resetTrackLocked()
        activeTrackId = id
        activeTrackInstance++
        trackStartedEpochSeconds = clock.wallTimeEpochMs() / 1_000L
        lastSampleElapsedMs = clock.elapsedRealtimeMs()
        lastSampleAudible = false
        if (!eligible) lastSampleElapsedMs = null
    }

    private fun resetTrackLocked() {
        networkJob?.cancel()
        networkJob = null
        activeTrackId = null
        activeTrackInstance++
        trackStartedEpochSeconds = 0L
        lastSampleElapsedMs = null
        lastSampleAudible = false
        audibleElapsedMs = 0L
        scrobbleSent = false
        nowPlayingSent = false
    }

    private fun invalidateSessionLocked() {
        sessionGeneration++
        authGeneration++
        authJob?.cancel()
        authJob = null
        pendingToken = null
        networkJob?.cancel()
        networkJob = null
        resetTrackLocked()
    }

    private fun isCurrentInstance(instance: Long, session: Long): Boolean = synchronized(lock) {
        activeTrackInstance == instance && sessionGeneration == session && settings.snapshot().lastFmEnabled && !sessionKey.isNullOrBlank()
    }

    private fun persistLocked() {
        val payload = JSONObject()
            .put("endpoint", endpoint)
            .put("apiKey", apiKey)
            .put("secret", secret)
            .put("sessionKey", sessionKey ?: "")
            .put("username", username ?: "")
        val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        try {
            if (apiKey.isBlank() && secret.isBlank() && sessionKey.isNullOrBlank()) credentialStore.delete()
            else credentialStore.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun configureClientLocked() {
        LastFM.configure(endpoint = endpoint, apiKey = apiKey, secret = secret, sessionKey = sessionKey)
    }

    private fun updateStateLocked(
        busy: Boolean = false,
        pendingAuthorization: Boolean = pendingToken != null,
        message: String? = null,
        error: String? = null,
    ) {
        _state.value = DesktopLastFmState(
            username = if (sessionKey.isNullOrBlank()) null else username,
            credentialsConfigured = apiKey.isNotBlank() && secret.isNotBlank(),
            pendingAuthorization = pendingAuthorization,
            busy = busy,
            message = message,
            error = error,
        )
    }

    private fun Throwable.userMessage(): String = when (this) {
        is LastFM.LastFmException -> "Last.fm error $code: $message"
        else -> message?.takeIf(String::isNotBlank) ?: "Last.fm request failed. Please try again."
    }

    private fun reportNetworkFailure(failure: Throwable, session: Long) {
        if (failure is CancellationException) return
        synchronized(lock) { if (sessionGeneration == session) updateStateLocked(error = failure.userMessage()) }
    }

    private fun hasRequiredMetadata(title: String, artists: List<String>): Boolean =
        title.isNotBlank() && artists.any(String::isNotBlank)

    private fun PlaybackSnapshot.durationSecondsOrNull(): Int? =
        durationMs.takeIf { it > 0L }?.div(1_000L)?.toInt()

    override fun close() {
        synchronized(lock) { resetTrackLocked() }
        serviceJob.cancel()
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

interface DesktopLastFmClient {
    suspend fun getToken(): Result<String>
    suspend fun getSession(token: String): Result<DesktopLastFmAuthentication>
    suspend fun updateNowPlaying(artist: String, track: String, album: String?, trackNumber: Int?, duration: Int?): Result<Unit>
    suspend fun scrobble(artist: String, track: String, timestamp: Long, album: String?, trackNumber: Int?, duration: Int?): Result<Unit>
}

data class DesktopLastFmAuthentication(val username: String, val sessionKey: String)

object SharedLastFmClient : DesktopLastFmClient {
    override suspend fun getToken(): Result<String> = LastFM.getToken().map { it.token }
    override suspend fun getSession(token: String): Result<DesktopLastFmAuthentication> =
        LastFM.getSession(token).map { DesktopLastFmAuthentication(it.session.name, it.session.key) }
    override suspend fun updateNowPlaying(artist: String, track: String, album: String?, trackNumber: Int?, duration: Int?): Result<Unit> =
        LastFM.updateNowPlaying(artist, track, album, trackNumber, duration).map { Unit }
    override suspend fun scrobble(artist: String, track: String, timestamp: Long, album: String?, trackNumber: Int?, duration: Int?): Result<Unit> =
        LastFM.scrobble(artist, track, timestamp, album, trackNumber, duration).map { Unit }
}

interface DesktopLastFmClock {
    fun elapsedRealtimeMs(): Long
    fun wallTimeEpochMs(): Long
}

object SystemDesktopLastFmClock : DesktopLastFmClock {
    override fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L
    override fun wallTimeEpochMs(): Long = System.currentTimeMillis()
}
