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
import kotlinx.coroutines.IO
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.session.PlaybackQueuePersistence
import moe.rukamori.archivetune.playback.session.PlaybackSessionHost
import moe.rukamori.archivetune.playback.session.QueuePersistencePolicy

/**
 * Turnkey Apple iOS session factory wiring [AvFoundationPlaybackEngine] onto
 * `Dispatchers.Main.immediate` with background queue persistence on `Dispatchers.IO`.
 */
fun createIosPlaybackSession(
    sourceResolver: PlaybackSourceResolver,
    queuePersistence: PlaybackQueuePersistence? = null,
    queuePersistencePolicy: QueuePersistencePolicy = QueuePersistencePolicy(),
    persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
    prefetchResolver: (suspend (item: PlaybackItem, cacheAllowed: () -> Boolean) -> Result<ResolvedPlaybackSource>)? = null,
    parentContext: CoroutineContext = Dispatchers.Main.immediate,
    random: Random = Random.Default,
): PlaybackSessionHost {
    val engine = AvFoundationPlaybackEngine()
    return try {
        createIosPlaybackSession(
            engine = engine,
            sourceResolver = sourceResolver,
            ownsEngine = true,
            queuePersistence = queuePersistence,
            queuePersistencePolicy = queuePersistencePolicy,
            persistenceDispatcher = persistenceDispatcher,
            prefetchResolver = prefetchResolver,
            parentContext = parentContext,
            random = random,
        )
    } catch (error: Throwable) {
        runCatching(engine::close).onFailure(error::addSuppressed)
        throw error
    }
}
