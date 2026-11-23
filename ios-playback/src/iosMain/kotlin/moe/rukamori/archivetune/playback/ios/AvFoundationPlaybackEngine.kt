/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.playback.api.PlaybackFailure
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.setActive
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSLog
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyDefaultPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIApplicationDidBecomeActiveNotification

/**
 * AVFoundation implementation of [IosPlaybackEngine].
 *
 * Owns `AVPlayer`, `AVAudioSession`, `MPNowPlayingInfoCenter`, and `MPRemoteCommandCenter`.
 * All transport commands and notification observers execute on the iOS main thread.
 * Signed URLs, request headers, and `NSError.userInfo` are never logged.
 */
@OptIn(ExperimentalForeignApi::class)
class AvFoundationPlaybackEngine : IosPlaybackEngine {
    override val canAdjustVolume: Boolean = true
    private val mutableState = MutableStateFlow(IosEngineState())
    override val state: StateFlow<IosEngineState> = mutableState.asStateFlow()

    private val player = AVPlayer()
    private val audioSession = AVAudioSession.sharedInstance()
    private val notificationCenter = NSNotificationCenter.defaultCenter
    private val remoteCenter = MPRemoteCommandCenter.sharedCommandCenter()
    private val nowPlaying = MPNowPlayingInfoCenter.defaultCenter()
    private val remoteScope = MainScope()

    private val globalObservers = mutableListOf<Any>()
    private val itemObservers = mutableListOf<Any>()
    private val remoteTargets = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private var timeObserver: Any? = null
    private var heartbeat: NSTimer? = null
    private var currentItem: AVPlayerItem? = null
    private var currentPlaybackItem: PlaybackItem? = null
    private var generation = 0L
    private var seekGeneration = 0L
    private var pendingSeekMs: Long? = null
    private var wantsPlayback = false
    private var playbackSpeed = 1f
    private var resumeAfterInterruption = false
    private var closed = false
    private var reportedFailureGeneration = -1L
    private var reportedTimelineGeneration = -1L
    private var remoteHandlers: IosRemoteCommandHandlers? = null
    private var hasPreviousTrack = false
    private var hasNextTrack = false

    private var lastNowPlayingItemId: String? = null
    private var lastNowPlayingTitle: String? = null
    private var lastNowPlayingArtist: String? = null
    private var lastNowPlayingSecond = -1L
    private var lastNowPlayingDuration = -1L
    private var lastNowPlayingRate = -1.0

    init {
        timeObserver = player.addPeriodicTimeObserverForInterval(
            CMTimeMake(1, 4),
            queue = null,
        ) { _ -> refresh() }
        heartbeat = NSTimer.scheduledTimerWithTimeInterval(0.5, repeats = true) { _ -> refresh() }

        globalObservers += observe(AVAudioSessionInterruptionNotification, audioSession) { notice ->
            val type = number(notice, AVAudioSessionInterruptionTypeKey)?.toULong()
            when (type) {
                AVAudioSessionInterruptionTypeBegan.toULong() -> {
                    resumeAfterInterruption = wantsPlayback
                    wantsPlayback = false
                    player.pause()
                    refresh()
                }
                AVAudioSessionInterruptionTypeEnded.toULong() -> {
                    val options = number(notice, AVAudioSessionInterruptionOptionKey)?.toULong() ?: 0UL
                    val shouldResume = options and AVAudioSessionInterruptionOptionShouldResume.toULong() != 0UL
                    val resume = resumeAfterInterruption && shouldResume
                    resumeAfterInterruption = false
                    if (resume) play() else refresh()
                }
            }
        }
        globalObservers += observe(AVAudioSessionRouteChangeNotification, audioSession) { notice ->
            val reason = number(notice, AVAudioSessionRouteChangeReasonKey)?.toULong()
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable.toULong()) {
                resumeAfterInterruption = false
                pause()
            }
        }
        globalObservers += observe(UIApplicationDidBecomeActiveNotification, null) { refresh() }
        installRemoteCommands()
        updateRemoteAvailability()
    }

    override fun bindRemoteCommands(handlers: IosRemoteCommandHandlers?) {
        if (closed) return
        remoteHandlers = handlers
        updateRemoteAvailability()
    }

    override fun updateNavigationAvailability(hasPrevious: Boolean, hasNext: Boolean) {
        if (closed) return
        hasPreviousTrack = hasPrevious
        hasNextTrack = hasNext
        updateRemoteAvailability()
    }

    override fun load(item: PlaybackItem, source: ResolvedPlaybackSource, startPositionMs: Long) {
        checkOpen()
        validateIosPlaybackSource(source)?.let { throw IosPlaybackException(it) }
        val url = NSURL(string = source.uri)
        if (url == null ||
            url.scheme?.lowercase() != "https" ||
            url.host.isNullOrBlank() ||
            url.user != null ||
            url.password != null
        ) {
            throw IosPlaybackException(
                PlaybackFailure(
                    message = "The player requires a direct HTTPS media URL.",
                    kind = PlaybackFailure.Kind.UNSUPPORTED_FORMAT,
                    recoverable = false,
                ),
            )
        }

        ++generation
        ++seekGeneration
        pendingSeekMs = null
        clearItemObservers()
        wantsPlayback = false
        resumeAfterInterruption = false
        player.pause()

        val avItem = AVPlayerItem(uRL = url)
        currentItem = avItem
        currentPlaybackItem = item
        player.replaceCurrentItemWithPlayerItem(avItem)

        val clampedStart = startPositionMs.coerceAtLeast(0L)
        val initialDuration = item.metadata.durationHintMs?.coerceAtLeast(0L) ?: 0L
        mutableState.value = IosEngineState(
            itemId = item.metadata.id,
            isPlaying = false,
            isBuffering = false,
            positionMs = clampedStart,
            bufferedPositionMs = clampedStart,
            durationMs = initialDuration,
            ended = false,
            error = null,
        )

        val expectedGeneration = generation
        itemObservers += observe(AVPlayerItemDidPlayToEndTimeNotification, avItem) {
            if (isCurrent(expectedGeneration, avItem)) {
                wantsPlayback = false
                val endPos = durationOrPosition()
                mutableState.value = mutableState.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    ended = true,
                    positionMs = endPos,
                    bufferedPositionMs = endPos,
                    error = null,
                )
                updateNowPlaying(force = true)
            }
        }
        itemObservers += observe(AVPlayerItemFailedToPlayToEndTimeNotification, avItem) {
            if (isCurrent(expectedGeneration, avItem)) {
                fail(
                    PlaybackFailure(
                        message = "Playback failed. The stream may have expired or become unavailable.",
                        kind = PlaybackFailure.Kind.NETWORK,
                        recoverable = true,
                    ),
                )
            }
        }
        itemObservers += observe(AVPlayerItemPlaybackStalledNotification, avItem) {
            if (isCurrent(expectedGeneration, avItem) && wantsPlayback) {
                mutableState.value = mutableState.value.copy(isPlaying = false, isBuffering = true)
                updateNowPlaying()
            }
        }

        if (clampedStart > 0L) {
            seekTo(clampedStart)
        }
        updateRemoteAvailability()
        updateNowPlaying(force = true)
        refresh()
    }

    override fun play() {
        if (closed || currentItem == null) return
        if (mutableState.value.ended) seekTo(0L)
        if (!audioSession.setCategory(AVAudioSessionCategoryPlayback, error = null) ||
            !audioSession.setActive(true, error = null)
        ) {
            fail(
                PlaybackFailure(
                    message = "Audio output could not be activated.",
                    kind = PlaybackFailure.Kind.ENGINE_UNAVAILABLE,
                    recoverable = true,
                ),
            )
            return
        }
        wantsPlayback = true
        player.play()
        player.rate = playbackSpeed
        refresh()
    }

    override fun setSpeed(speed: Float) {
        if (closed || !speed.isFinite()) return
        playbackSpeed = speed.coerceIn(IosPlaybackController.MIN_IOS_SPEED, IosPlaybackController.MAX_IOS_SPEED)
        if (wantsPlayback) player.rate = playbackSpeed
        refresh()
    }

    override fun setVolume(volume: Float) {
        if (!closed && volume.isFinite()) player.volume = volume.coerceIn(0f, 1f)
    }

    override fun pause() {
        if (closed) return
        wantsPlayback = false
        resumeAfterInterruption = false
        player.pause()
        refresh()
    }

    override fun seekTo(positionMs: Long) {
        if (closed || currentItem == null) return
        val duration = mutableState.value.durationMs
        val target = positionMs.coerceAtLeast(0L).let { if (duration > 0L) it.coerceAtMost(duration) else it }
        mutableState.value = mutableState.value.copy(
            ended = false,
            positionMs = target,
            bufferedPositionMs = target,
            error = null,
        )
        val expectedGeneration = generation
        val expectedSeek = ++seekGeneration
        pendingSeekMs = target
        player.seekToTime(CMTimeMakeWithSeconds(target / 1000.0, 600)) { _ ->
            if (!closed && expectedGeneration == generation && expectedSeek == seekGeneration) {
                pendingSeekMs = null
                refresh()
            }
        }
        updateNowPlaying(force = true)
    }

    override fun stop() {
        if (closed) return
        ++generation
        ++seekGeneration
        pendingSeekMs = null
        wantsPlayback = false
        resumeAfterInterruption = false
        clearItemObservers()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        currentItem = null
        currentPlaybackItem = null
        mutableState.value = IosEngineState()
        updateRemoteAvailability()
        clearNowPlaying()
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        heartbeat?.invalidate()
        heartbeat = null
        timeObserver?.let { player.removeTimeObserver(it) }
        timeObserver = null
        globalObservers.forEach { notificationCenter.removeObserver(it) }
        globalObservers.clear()
        remoteTargets.forEach { (command, target) -> command.removeTarget(target) }
        remoteTargets.clear()
        remoteHandlers = null
        remoteScope.cancel()
        audioSession.setActive(false, error = null)
    }

    private fun checkOpen() {
        check(!closed) { "The iOS player is closed." }
    }

    private fun isCurrent(expected: Long, item: AVPlayerItem): Boolean =
        !closed && generation == expected && currentItem == item

    private fun clearItemObservers() {
        itemObservers.forEach { notificationCenter.removeObserver(it) }
        itemObservers.clear()
    }

    private fun observe(name: String?, owner: Any?, block: (NSNotification) -> Unit): Any =
        notificationCenter.addObserverForName(name, `object` = owner, queue = NSOperationQueue.mainQueue) { notice ->
            notice?.let(block)
        }

    private fun number(notice: NSNotification, key: String?): ULong? =
        (notice.userInfo?.get(key) as? NSNumber)?.unsignedLongLongValue

    private fun durationOrPosition(): Long =
        mutableState.value.durationMs.takeIf { it > 0L } ?: mutableState.value.positionMs

    private fun fail(failure: PlaybackFailure) {
        wantsPlayback = false
        player.pause()
        mutableState.value = mutableState.value.copy(
            isPlaying = false,
            isBuffering = false,
            error = failure,
        )
        updateNowPlaying(force = true)
    }

    private fun refresh() {
        if (closed) return
        val item = currentItem ?: return
        if (item.status == AVPlayerItemStatusFailed || player.status == AVPlayerStatusFailed) {
            if (reportedFailureGeneration != generation) {
                reportedFailureGeneration = generation
                val failure = item.error ?: player.error
                // Deliberately exclude userInfo/description: those can contain signed URLs.
                println("ArchiveTune AVPlayer failure: domain=${failure?.domain}, code=${failure?.code}")
            }
            fail(
                PlaybackFailure(
                    message = "Playback failed. Try resolving this song again.",
                    kind = PlaybackFailure.Kind.ENGINE,
                    recoverable = true,
                ),
            )
            return
        }
        val old = mutableState.value
        val measuredDuration = CMTimeGetSeconds(item.duration)
        if (measuredDuration.isFinite() && measuredDuration > 0 && reportedTimelineGeneration != generation) {
            reportedTimelineGeneration = generation
            val raw = item.duration.useContents { "value=$value timescale=$timescale flags=$flags" }
            NSLog(
                "%@",
                "ArchiveTune media timeline: $raw seconds=$measuredDuration metadataMs=${currentPlaybackItem?.metadata?.durationHintMs}",
            )
        }
        val duration = if (measuredDuration.isFinite() && measuredDuration > 0) {
            (measuredDuration * 1000).toLong()
        } else {
            old.durationMs
        }
        val measuredPosition = CMTimeGetSeconds(player.currentTime())
        val position = pendingSeekMs ?: if (measuredPosition.isFinite() && measuredPosition >= 0) {
            (measuredPosition * 1000).toLong()
        } else {
            old.positionMs
        }
        val audible = wantsPlayback && !old.ended && old.error == null &&
            player.timeControlStatus == AVPlayerTimeControlStatusPlaying && player.rate > 0f
        val safePosition = position.coerceAtLeast(0L)
        mutableState.value = old.copy(
            positionMs = safePosition,
            bufferedPositionMs = safePosition,
            durationMs = duration.coerceAtLeast(0L),
            isPlaying = audible,
            isBuffering = wantsPlayback && !old.ended && old.error == null && !audible,
        )
        updateNowPlaying()
    }

    private fun installRemoteCommands() {
        install(remoteCenter.playCommand) { _ ->
            remoteHandlers?.onPlay?.invoke() ?: play()
        }
        install(remoteCenter.pauseCommand) { _ ->
            remoteHandlers?.onPause?.invoke() ?: pause()
        }
        install(remoteCenter.togglePlayPauseCommand) { _ ->
            remoteHandlers?.onTogglePlayPause?.invoke() ?: if (wantsPlayback) pause() else play()
        }
        install(remoteCenter.nextTrackCommand) { _ ->
            remoteHandlers?.onNext?.invoke()
        }
        install(remoteCenter.previousTrackCommand) { _ ->
            remoteHandlers?.onPrevious?.invoke()
        }
        install(remoteCenter.changePlaybackPositionCommand) { event ->
            (event as? MPChangePlaybackPositionCommandEvent)?.let {
                if (it.positionTime.isFinite()) {
                    val targetMs = (it.positionTime * 1000).toLong()
                    remoteHandlers?.onSeekTo?.invoke(targetMs) ?: seekTo(targetMs)
                }
            }
        }
    }

    private fun install(command: MPRemoteCommand, operation: (MPRemoteCommandEvent) -> Unit) {
        val token = command.addTargetWithHandler { event ->
            if (closed || currentItem == null || event == null) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                remoteScope.launch {
                    if (!closed && currentItem != null) operation(event)
                }
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        remoteTargets += command to token
    }

    private fun updateRemoteAvailability() {
        val enabled = !closed && currentItem != null
        remoteCenter.playCommand.enabled = enabled
        remoteCenter.pauseCommand.enabled = enabled
        remoteCenter.togglePlayPauseCommand.enabled = enabled
        remoteCenter.changePlaybackPositionCommand.enabled = enabled
        remoteCenter.nextTrackCommand.enabled = enabled && remoteHandlers != null && hasNextTrack
        remoteCenter.previousTrackCommand.enabled = enabled && remoteHandlers != null && hasPreviousTrack
    }

    private fun clearNowPlaying() {
        nowPlaying.nowPlayingInfo = null
        lastNowPlayingItemId = null
        lastNowPlayingTitle = null
        lastNowPlayingArtist = null
        lastNowPlayingSecond = -1L
        lastNowPlayingDuration = -1L
        lastNowPlayingRate = -1.0
    }

    private fun updateNowPlaying(force: Boolean = false) {
        val current = state.value
        val metadata = currentPlaybackItem?.metadata ?: return clearNowPlaying()
        val elapsedSeconds = current.positionMs / 1000L
        val playbackRate = if (current.isPlaying) playbackSpeed.toDouble() else 0.0
        val artist = metadata.artistText
        if (!force &&
            lastNowPlayingItemId == metadata.id &&
            lastNowPlayingTitle == metadata.title &&
            lastNowPlayingArtist == artist &&
            lastNowPlayingSecond == elapsedSeconds &&
            lastNowPlayingDuration == current.durationMs &&
            lastNowPlayingRate == playbackRate
        ) {
            return
        }
        val info = mutableMapOf<Any?, Any>(
            MPMediaItemPropertyTitle to metadata.title,
            MPMediaItemPropertyArtist to artist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to (current.positionMs / 1000.0),
            MPNowPlayingInfoPropertyPlaybackRate to playbackRate,
            MPNowPlayingInfoPropertyDefaultPlaybackRate to 1.0,
        )
        if (current.durationMs > 0L) {
            info[MPMediaItemPropertyPlaybackDuration] = current.durationMs / 1000.0
        }
        nowPlaying.nowPlayingInfo = info
        lastNowPlayingItemId = metadata.id
        lastNowPlayingTitle = metadata.title
        lastNowPlayingArtist = artist
        lastNowPlayingSecond = elapsedSeconds
        lastNowPlayingDuration = current.durationMs
        lastNowPlayingRate = playbackRate
    }
}
