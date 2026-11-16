package moe.rukamori.archivetune.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.setActive
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
import platform.AVFoundation.*
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotification
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
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
 * AVPlayer owns the actual stream; all public transport calls and observer callbacks execute
 * on the iOS main thread. The engine is owned by application services, not a Compose screen.
 *
 * No AVURLAsset HTTP header injection: only self-contained, direct HTTPS AAC/MP4 or HLS URLs
 * are supported. All failure strings are fixed text; signed URLs and headers are never logged.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPlayerEngine : PlayerEngine {
    override val canAdjustVolume = true
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

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
    private var generation = 0L
    private var seekGeneration = 0L
    private var pendingSeekMs: Long? = null
    private var wantsPlayback = false
    private var playbackSpeed = 1f
    private var resumeAfterInterruption = false
    private var closed = false
    private var reportedFailureGeneration = -1L
    private var reportedTimelineGeneration = -1L
    private var nextHandler: (() -> Unit)? = null
    private var previousHandler: (() -> Unit)? = null

    private var lastNowPlayingTrack: MusicTrack? = null
    private var lastNowPlayingSecond = -1L
    private var lastNowPlayingDuration = -1L
    private var lastNowPlayingRate = -1.0

    init {
        // A periodic time observer publishes actual progress; the timer additionally detects
        // stalled buffering and item failures when the presentation clock stops advancing.
        timeObserver = player.addPeriodicTimeObserverForInterval(
            CMTimeMake(1, 4), queue = null,
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
                // Do not suddenly send music to the speakers after headphones are unplugged.
                resumeAfterInterruption = false
                pause()
            }
        }
        globalObservers += observe(UIApplicationDidBecomeActiveNotification, null) { refresh() }
        installRemoteCommands()
        updateRemoteAvailability()
    }

    /** Queue navigation must go through MusicController, which owns the authoritative index. */
    fun bindQueueCommands(onNext: () -> Unit, onPrevious: () -> Unit) {
        if (closed) return
        nextHandler = onNext
        previousHandler = onPrevious
        updateRemoteAvailability()
    }

    override fun load(track: MusicTrack, source: ResolvedAudio) {
        checkOpen()
        // Validate BEFORE changing the current player item, so rejected sources never play.
        require(source.trackId == track.id) { "Audio source does not match the selected track." }
        require(source.headers.isEmpty()) { "Streams requiring custom HTTP headers are not supported on iOS." }
        val type = source.mimeType.substringBefore(';').trim().lowercase()
        require(type == "audio/mp4" || type == "application/vnd.apple.mpegurl" ||
            type == "application/x-mpegurl"
        ) { "This audio format is not supported by the iOS player." }
        val url = NSURL(string = source.url)
        require(url != null && url.scheme?.lowercase() == "https" && !url.host.isNullOrBlank() &&
            url.user == null && url.password == null
        ) { "The player requires a direct HTTPS media URL." }

        ++generation
        ++seekGeneration
        pendingSeekMs = null
        clearItemObservers()
        wantsPlayback = false
        resumeAfterInterruption = false
        player.pause()
        val item = AVPlayerItem(uRL = url)
        currentItem = item
        player.replaceCurrentItemWithPlayerItem(item)
        mutableState.value = PlaybackState(
            track = track, isPlaying = false, isBuffering = false,
            positionMs = 0, durationMs = track.durationMs?.coerceAtLeast(0) ?: 0,
        )
        val expectedGeneration = generation
        itemObservers += observe(AVPlayerItemDidPlayToEndTimeNotification, item) {
            if (isCurrent(expectedGeneration, item)) {
                wantsPlayback = false
                mutableState.value = mutableState.value.copy(
                    isPlaying = false, isBuffering = false, ended = true,
                    positionMs = durationOrPosition(), error = null,
                )
                updateNowPlaying()
            }
        }
        itemObservers += observe(AVPlayerItemFailedToPlayToEndTimeNotification, item) {
            if (isCurrent(expectedGeneration, item)) fail("Playback failed. The stream may have expired or become unavailable.")
        }
        itemObservers += observe(AVPlayerItemPlaybackStalledNotification, item) {
            if (isCurrent(expectedGeneration, item) && wantsPlayback) {
                mutableState.value = mutableState.value.copy(isPlaying = false, isBuffering = true)
                updateNowPlaying()
            }
        }
        updateRemoteAvailability()
        updateNowPlaying(force = true)
        refresh()
    }

    override fun play() {
        if (closed || currentItem == null) return
        if (mutableState.value.ended) seekTo(0)
        if (!audioSession.setCategory(AVAudioSessionCategoryPlayback, error = null) ||
            !audioSession.setActive(true, error = null)
        ) {
            fail("Audio output could not be activated.")
            return
        }
        wantsPlayback = true
        player.play()
        player.rate = playbackSpeed
        refresh()
    }

    override fun setSpeed(speed: Float) {
        if (closed || !speed.isFinite()) return
        playbackSpeed = speed.coerceIn(0.25f, 2f)
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
        val target = positionMs.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
        // Explicitly clear end state: AVPlayer.play() alone cannot restart a finished item.
        mutableState.value = mutableState.value.copy(ended = false, positionMs = target, error = null)
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
        mutableState.value = PlaybackState()
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
        nextHandler = null
        previousHandler = null
        remoteScope.cancel()
        audioSession.setActive(false, error = null)
    }

    private fun checkOpen() { check(!closed) { "The iOS player is closed." } }

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
        mutableState.value.durationMs.takeIf { it > 0 } ?: mutableState.value.positionMs

    private fun fail(message: String) {
        wantsPlayback = false
        player.pause()
        mutableState.value = mutableState.value.copy(
            isPlaying = false, isBuffering = false, error = message,
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
            fail("Playback failed. Try resolving this song again.")
            return
        }
        val old = mutableState.value
        val measuredDuration = CMTimeGetSeconds(item.duration)
        if (measuredDuration.isFinite() && measuredDuration > 0 && reportedTimelineGeneration != generation) {
            reportedTimelineGeneration = generation
            val raw = item.duration.useContents { "value=$value timescale=$timescale flags=$flags" }
            NSLog("%@", "ArchiveTune media timeline: $raw seconds=$measuredDuration metadataMs=${old.track?.durationMs}")
        }
        val duration = if (measuredDuration.isFinite() && measuredDuration > 0)
            (measuredDuration * 1000).toLong() else old.durationMs
        val measuredPosition = CMTimeGetSeconds(player.currentTime())
        val position = pendingSeekMs ?: if (measuredPosition.isFinite() && measuredPosition >= 0)
            (measuredPosition * 1000).toLong() else old.positionMs
        val audible = wantsPlayback && !old.ended && old.error == null &&
            player.timeControlStatus == AVPlayerTimeControlStatusPlaying && player.rate > 0f
        mutableState.value = old.copy(
            positionMs = position.coerceAtLeast(0),
            durationMs = duration.coerceAtLeast(0),
            isPlaying = audible,
            isBuffering = wantsPlayback && !old.ended && old.error == null && !audible,
        )
        updateNowPlaying()
    }

    private fun installRemoteCommands() {
        install(remoteCenter.playCommand, ::play)
        install(remoteCenter.pauseCommand, ::pause)
        install(remoteCenter.togglePlayPauseCommand) { _: MPRemoteCommandEvent ->
            if (wantsPlayback) pause() else play()
        }
        install(remoteCenter.nextTrackCommand) { _: MPRemoteCommandEvent -> nextHandler?.invoke() }
        install(remoteCenter.previousTrackCommand) { _: MPRemoteCommandEvent -> previousHandler?.invoke() }
        install(remoteCenter.changePlaybackPositionCommand) { event: MPRemoteCommandEvent ->
            (event as? MPChangePlaybackPositionCommandEvent)?.let {
                if (it.positionTime.isFinite()) seekTo((it.positionTime * 1000).toLong())
            }
        }
    }

    private fun install(command: MPRemoteCommand, operation: (MPRemoteCommandEvent) -> Unit) {
        val token = command.addTargetWithHandler { event ->
            if (closed || currentItem == null || event == null) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                // Remote events may arrive on a background queue; transport mutates on main.
                remoteScope.launch {
                    if (!closed && currentItem != null) operation(event)
                }
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        remoteTargets += command to token
    }

    private fun install(command: MPRemoteCommand, operation: () -> Unit) =
        install(command) { _ -> operation() }

    private fun updateRemoteAvailability() {
        val enabled = !closed && currentItem != null
        remoteCenter.playCommand.enabled = enabled
        remoteCenter.pauseCommand.enabled = enabled
        remoteCenter.togglePlayPauseCommand.enabled = enabled
        remoteCenter.changePlaybackPositionCommand.enabled = enabled
        remoteCenter.nextTrackCommand.enabled = enabled && nextHandler != null
        remoteCenter.previousTrackCommand.enabled = enabled && previousHandler != null
    }

    private fun clearNowPlaying() {
        nowPlaying.nowPlayingInfo = null
        lastNowPlayingTrack = null
        lastNowPlayingSecond = -1
        lastNowPlayingDuration = -1
        lastNowPlayingRate = -1.0
    }

    private fun updateNowPlaying(force: Boolean = false) {
        val current = state.value
        val track = current.track ?: return clearNowPlaying()
        val elapsedSeconds = current.positionMs / 1000
        val playbackRate = if (current.isPlaying) playbackSpeed.toDouble() else 0.0
        if (!force && lastNowPlayingTrack == track && lastNowPlayingSecond == elapsedSeconds &&
            lastNowPlayingDuration == current.durationMs && lastNowPlayingRate == playbackRate
        ) return
        val info = mutableMapOf<Any?, Any>(
            MPMediaItemPropertyTitle to track.title,
            MPMediaItemPropertyArtist to track.artist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to (current.positionMs / 1000.0),
            MPNowPlayingInfoPropertyPlaybackRate to playbackRate,
            MPNowPlayingInfoPropertyDefaultPlaybackRate to 1.0,
        )
        if (current.durationMs > 0) {
            info[MPMediaItemPropertyPlaybackDuration] = current.durationMs / 1000.0
        }
        nowPlaying.nowPlayingInfo = info
        lastNowPlayingTrack = track
        lastNowPlayingSecond = elapsedSeconds
        lastNowPlayingDuration = current.durationMs
        lastNowPlayingRate = playbackRate
    }
}
