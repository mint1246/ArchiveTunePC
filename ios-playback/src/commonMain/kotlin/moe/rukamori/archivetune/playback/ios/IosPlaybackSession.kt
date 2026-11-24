/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.ios

import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.api.ResolvedSourceCache
import moe.rukamori.archivetune.playback.session.PlaybackQueuePersistence
import moe.rukamori.archivetune.playback.session.PlaybackSessionHost
import moe.rukamori.archivetune.playback.session.QueuePersistencePolicy

/**
 * Creates a turnkey [PlaybackSessionHost] wired to an [IosPlaybackController] and caller-supplied
 * [IosPlaybackEngine].
 */
fun createIosPlaybackSession(
    engine: IosPlaybackEngine,
    sourceResolver: PlaybackSourceResolver,
    ownsEngine: Boolean = true,
    queuePersistence: PlaybackQueuePersistence? = null,
    queuePersistencePolicy: QueuePersistencePolicy = QueuePersistencePolicy(),
    persistenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    prefetchResolver: (suspend (item: PlaybackItem, cacheAllowed: () -> Boolean) -> Result<ResolvedPlaybackSource>)? = null,
    parentContext: CoroutineContext = Dispatchers.Default,
    random: Random = Random.Default,
    sourceCache: ResolvedSourceCache? = ResolvedSourceCache(),
): PlaybackSessionHost {
    val controller = IosPlaybackController(
        engine = engine,
        sourceResolver = sourceResolver,
        ownsEngine = ownsEngine,
        parentContext = parentContext,
        random = random,
        sourceCache = sourceCache,
    )
    return try {
        PlaybackSessionHost(
            controller = controller,
            ownsController = true,
            parentContext = parentContext,
            queuePersistence = queuePersistence,
            queuePersistencePolicy = queuePersistencePolicy,
            persistenceDispatcher = persistenceDispatcher,
            prefetchResolver = prefetchResolver,
        )
    } catch (error: Throwable) {
        runCatching(controller::close).onFailure(error::addSuppressed)
        throw error
    }
}
