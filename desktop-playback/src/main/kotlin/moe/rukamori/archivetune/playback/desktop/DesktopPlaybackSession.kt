/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.session.PlaybackSessionHost
import moe.rukamori.archivetune.playback.session.QueuePersistencePolicy
import okhttp3.OkHttpClient

/**
 * Creates an independently configurable headless desktop playback session backed by a
 * [VlcjPlaybackController] and optional durable queue persistence / next-track prefetching.
 */
fun createDesktopPlaybackSession(
    sourceResolver: PlaybackSourceResolver,
    runtimeConfiguration: VlcRuntimeConfiguration = VlcRuntimeConfiguration(),
    onStreamHttpFailure: suspend (PlaybackItem, ResolvedPlaybackSource, Int) -> Boolean = { _, _, _ -> false },
    initialAudioOutputDeviceId: String? = null,
    initialPitchSemitones: Float = 0f,
    mediaHttpClient: OkHttpClient? = null,
    queueFile: Path? = null,
    queuePersistencePolicy: QueuePersistencePolicy = QueuePersistencePolicy(),
    prefetchResolver: (suspend (item: PlaybackItem, cacheAllowed: () -> Boolean) -> Result<ResolvedPlaybackSource>)? = null,
    parentContext: CoroutineContext = Dispatchers.Default,
): PlaybackSessionHost {
    val controller = VlcjPlaybackController(
        sourceResolver = sourceResolver,
        runtimeConfiguration = runtimeConfiguration,
        onStreamHttpFailure = onStreamHttpFailure,
        initialAudioOutputDeviceId = initialAudioOutputDeviceId,
        initialPitchSemitones = initialPitchSemitones,
        mediaHttpClient = mediaHttpClient,
    )
    return try {
        createDesktopPlaybackSession(
            controller = controller,
            ownsController = true,
            queueFile = queueFile,
            queuePersistencePolicy = queuePersistencePolicy,
            prefetchResolver = prefetchResolver,
            parentContext = parentContext,
        )
    } catch (error: Throwable) {
        runCatching(controller::close).onFailure(error::addSuppressed)
        throw error
    }
}

/**
 * Creates a [PlaybackSessionHost] wrapping a caller-supplied [PlaybackController], primarily for
 * headless integration and unit testing.
 */
fun createDesktopPlaybackSession(
    controller: PlaybackController,
    ownsController: Boolean = true,
    queueFile: Path? = null,
    queuePersistencePolicy: QueuePersistencePolicy = QueuePersistencePolicy(),
    persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
    prefetchResolver: (suspend (item: PlaybackItem, cacheAllowed: () -> Boolean) -> Result<ResolvedPlaybackSource>)? = null,
    parentContext: CoroutineContext = Dispatchers.Default,
): PlaybackSessionHost =
    PlaybackSessionHost(
        controller = controller,
        ownsController = ownsController,
        parentContext = parentContext,
        queuePersistence = queueFile?.let { PlaybackQueueStore(it).asQueuePersistence() },
        queuePersistencePolicy = queuePersistencePolicy,
        persistenceDispatcher = persistenceDispatcher,
        prefetchResolver = prefetchResolver,
    )
