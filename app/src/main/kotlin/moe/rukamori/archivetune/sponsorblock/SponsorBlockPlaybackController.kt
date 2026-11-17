/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sponsorblock

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.sponsorblock.core.SponsorBlockRange
import moe.rukamori.archivetune.sponsorblock.core.SponsorBlockSkipPlanner
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SponsorBlockPlaybackController
    @Inject
    constructor(
        private val observeSettings: ObserveSponsorBlockSettingsUseCase,
        private val getSegments: GetSponsorBlockSegmentsUseCase,
    ) {
        private val currentMediaId = MutableStateFlow<String?>(null)
        private var attachedPlayer: Player? = null
        private var observationJob: Job? = null

        private val playerListener =
            object : Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    currentMediaId.value = mediaItem.normalizedMediaId()
                }

                override fun onTimelineChanged(
                    timeline: androidx.media3.common.Timeline,
                    reason: Int,
                ) {
                    currentMediaId.value = attachedPlayer?.currentMediaItem.normalizedMediaId()
                }
            }

        fun attach(
            player: Player,
            scope: CoroutineScope,
        ) {
            if (attachedPlayer === player && observationJob?.isActive == true) return
            detach()

            attachedPlayer = player
            currentMediaId.value = player.currentMediaItem.normalizedMediaId()
            player.addListener(playerListener)
            observationJob =
                scope.launch {
                    try {
                        combine(
                            observeSettings(),
                            currentMediaId,
                        ) { settings, mediaId -> settings to mediaId }
                            .collectLatest { (settings, mediaId) ->
                                val activeMediaId = mediaId ?: return@collectLatest
                                if (!settings.enabled || settings.categories.isEmpty()) return@collectLatest

                                val segments =
                                    try {
                                        getSegments(activeMediaId, settings)
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (throwable: Throwable) {
                                        Timber
                                            .tag(TAG)
                                            .w(throwable, "SponsorBlock segment lookup failed")
                                        return@collectLatest
                                    }
                                if (segments.isEmpty() || currentMediaId.value != activeMediaId) {
                                    return@collectLatest
                                }

                                monitorPlayback(
                                    player = player,
                                    mediaId = activeMediaId,
                                    segments = segments,
                                )
                            }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        Timber.tag(TAG).w(throwable, "SponsorBlock settings observation failed")
                    }
                }
        }

        fun detach() {
            observationJob?.cancel()
            observationJob = null
            attachedPlayer?.removeListener(playerListener)
            attachedPlayer = null
            currentMediaId.value = null
        }

        private suspend fun monitorPlayback(
            player: Player,
            mediaId: String,
            segments: List<SponsorBlockSegment>,
        ) {
            val planner = SponsorBlockSkipPlanner(segments.map { SponsorBlockRange(it.startMs, it.endMs) })
            while (
                currentCoroutineContext().isActive &&
                attachedPlayer === player &&
                player.currentMediaItem.normalizedMediaId() == mediaId
            ) {
                if (!player.isPlaying || player.playbackState != Player.STATE_READY) {
                    delay(INACTIVE_POLL_INTERVAL_MILLIS)
                    continue
                }

                val positionMs = player.currentPosition.coerceAtLeast(0L)
                val knownDuration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                planner.targetPosition(positionMs, knownDuration)?.let(player::seekTo)
                delay(ACTIVE_POLL_INTERVAL_MILLIS)
            }
        }

        private fun MediaItem?.normalizedMediaId(): String? =
            this
                ?.mediaId
                ?.trim()
                ?.takeIf(String::isNotEmpty)

        private companion object {
            const val TAG = "SponsorBlock"
            const val ACTIVE_POLL_INTERVAL_MILLIS = 200L
            const val INACTIVE_POLL_INTERVAL_MILLIS = 750L
        }
    }
