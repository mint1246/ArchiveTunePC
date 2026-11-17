package example.archivetune.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import moe.rukamori.archivetune.playback.android.Media3PlayerStateObserver

/** Call on the player's application thread and close the returned observer when finished. */
fun openLocalAudio(player: Player, audioUri: Uri): Media3PlayerStateObserver {
    player.setMediaItem(MediaItem.fromUri(audioUri))
    player.prepare()
    return Media3PlayerStateObserver(player)
}
