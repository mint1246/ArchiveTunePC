package moe.rukamori.archivetune.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** The lyrics view follows transport state without owning or recreating the audio services. */
@Composable
internal fun NowPlayingLyricsPane(controller: MusicController, modifier: Modifier = Modifier) {
    val lyrics by controller.lyrics.collectAsState()
    val transport by controller.playback.collectAsState()
    val settings = controller.library?.collectAsState()?.value?.settings ?: MusicSettings()
    val showArtworkBackdrop = settings.lyricsBackgroundStyle != MusicLyricsBackgroundStyle.SOLID

    Box(modifier) {
        if (showArtworkBackdrop) {
            transport.track?.artworkUrl?.let { artwork ->
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                        .then(if (!settings.disableBlur) Modifier.blur(32.dp) else Modifier)
                        .alpha(0.24f),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                        ),
                    ),
                ),
            )
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Text(
                transport.track?.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                transport.track?.artist.orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            when {
                lyrics.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                lyrics.error != null -> Column(Modifier.padding(vertical = 32.dp)) {
                    Text(lyrics.error?.message.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = controller::retryLyrics) { Text("Try again") }
                }
                lyrics.lines.isEmpty() -> Text(
                    "No lyrics available for this song.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
                else -> AndroidTimedLyrics(
                    lines = lyrics.lines,
                    positionMs = transport.positionMs,
                    isPlaying = transport.isPlaying,
                    onSeek = controller::seekTo,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textSizeSp = settings.lyricsTextSize,
                    lineSpacingMultiplier = settings.lyricsLineSpacing,
                    alignment = settings.lyricsAlignment,
                    lineBlurEnabled = settings.lyricsLineBlur && !settings.disableBlur,
                    clickToSeek = settings.lyricsClick,
                    autoScroll = settings.lyricsScroll,
                    offsetMs = settings.lyricsOffsetMs,
                )
            }
        }
    }
}
