/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R

/** Album/playlist entry point for the service-owned real HTDemucs batch queue. */
@Composable
fun AndroidStemBatchAction(
    mediaItems: List<MediaItem>,
    navController: NavController,
    label: String = "Create stems",
    modifier: Modifier = Modifier,
) {
    val coordinator = LocalPlayerConnection.current?.service?.stemPlayback ?: return
    val queue by coordinator.queueState.collectAsStateWithLifecycle()
    val remaining = queue.pending + if (queue.activeTrackId != null) 1 else 0

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    coordinator.enqueueAll(mediaItems)
                    navController.navigate("stems")
                },
                enabled = mediaItems.isNotEmpty(),
            ) {
                Icon(painterResource(R.drawable.graphic_eq), contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text(label)
            }
            if (queue.active) {
                Text(
                    "$remaining remaining",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (queue.active) {
            Text(
                buildString {
                    append("Stem queue")
                    queue.activeTitle?.let { append(" · Creating ").append(it) }
                    if (queue.completed > 0) append(" · ").append(queue.completed).append(" ready")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        if (queue.failed > 0) {
            Text(
                "${queue.failed} stem job${if (queue.failed == 1) "" else "s"} failed" +
                    queue.lastError?.let { ": $it" }.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}
