package moe.rukamori.archivetune.shared

import moe.rukamori.archivetune.playback.api.RepeatMode

typealias MusicRepeatMode = RepeatMode

data class MusicPlaybackOptions(
    val shuffle: Boolean = false,
    val repeatMode: MusicRepeatMode = MusicRepeatMode.OFF,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val sleepTimerMinutes: Int? = null,
    val sleepAfterCurrent: Boolean = false,
)
