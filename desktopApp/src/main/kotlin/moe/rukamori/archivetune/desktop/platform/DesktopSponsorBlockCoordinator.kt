/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.settings.DesktopSettings
import moe.rukamori.archivetune.desktop.sponsorblock.DesktopSponsorBlockSegmentProvider
import moe.rukamori.archivetune.desktop.sponsorblock.DesktopSponsorBlockRepository
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies enabled SponsorBlock segments to remote YouTube tracks. The party predicate must be
 * false for a guest whose seeks are not authoritative; the coordinator then leaves that guest's
 * transport untouched while the owner remains in control.
 */
class DesktopSponsorBlockCoordinator(
    private val playback: PlaybackController,
    private val settingsProvider: () -> DesktopSettings,
    private val canAutoSeek: () -> Boolean,
    private val segmentProvider: DesktopSponsorBlockSegmentProvider = DesktopSponsorBlockRepository(),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    @Volatile private var runner: Job? = null

    @Synchronized
    fun start(): Job {
        if (started.compareAndSet(false, true)) {
            runner = scope.launch {
                playback.state
                    .map { snapshot ->
                        val settings = settingsProvider()
                        Trigger(
                            track = trackKey(snapshot),
                            enabled = settings.sponsorBlockEnabled,
                            categories = settings.sponsorBlockCategories,
                            apiUrl = settings.sponsorBlockApiUrl,
                        )
                    }
                    .distinctUntilChanged()
                    .collectLatest { trigger ->
                        val key = trigger.track ?: return@collectLatest
                        if (!trigger.enabled || trigger.categories.isEmpty()) return@collectLatest
                        val segments = try {
                            segmentProvider.fetchSegments(key.videoId, trigger.categories, trigger.apiUrl)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            emptyList()
                        }
                        if (segments.isEmpty()) return@collectLatest

                        val skipped = mutableSetOf<Int>()
                        playback.state.collect { snapshot ->
                            if (trackKey(snapshot) != key || !snapshot.isPlaying || !canAutoSeek()) return@collect
                            segments.indices.filter { snapshot.positionMs < segments[it].startMs }.forEach(skipped::remove)
                            val index = segments.indexOfFirst { snapshot.positionMs >= it.startMs && snapshot.positionMs < it.endMs }
                            if (index < 0 || !skipped.add(index)) return@collect
                            if (trackKey(playback.state.value) != key || !canAutoSeek()) return@collect
                            val segment = segments[index]
                            runCatching { playback.seekTo(segment.endMs) }
                        }
                    }
            }
        }
        return checkNotNull(runner)
    }

    override fun close() {
        runner?.cancel()
        scope.cancel()
    }

    private fun trackKey(snapshot: PlaybackSnapshot): TrackKey? {
        val item = snapshot.currentItem ?: return null
        if (item.source is PlaybackSource.LocalFile) return null
        if (!YOUTUBE_VIDEO_ID.matches(item.metadata.id)) return null
        return TrackKey(item.metadata.id, snapshot.currentIndex)
    }

    private data class TrackKey(val videoId: String, val queueIndex: Int)
    private data class Trigger(
        val track: TrackKey?,
        val enabled: Boolean,
        val categories: Set<String>,
        val apiUrl: String,
    )

    private companion object {
        val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    }
}
