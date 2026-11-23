/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import moe.rukamori.archivetune.shared.resources.*

@Composable
internal fun AndroidLyricsOverlay(controller: MusicController, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, onQueueClick: () -> Unit, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(if (expanded) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), label = "mikoLyricsTransition")
    val p = progress.coerceIn(0f, 1f)
    if (expanded || p > .001f) {
        Box(modifier.fillMaxSize().graphicsLayer { alpha = p }.background(Color.Black.copy(alpha = .24f * p))) {
            Column(Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(.5f, 1f)
                scaleX = .92f + .08f * p
                scaleY = .78f + .22f * p
                alpha = .2f + .8f * p
                translationY = size.height * .16f * (1f - p)
            }.clip(RoundedCornerShape(32.dp * (1f - p))).background(MaterialTheme.colorScheme.surface)) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { onExpandedChange(false) }) { Icon(painterResource(Res.drawable.expand_more), "Close lyrics") }
                    IconButton(onClick = onQueueClick) { Icon(painterResource(Res.drawable.queue_music), "Queue") }
                }
                NowPlayingLyricsPane(controller, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
