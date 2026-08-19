package moe.rukamori.archivetune.desktop.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val QueueOverlayMaxWidth = 360.dp
private const val QueueOverlayViewportFraction = 0.48f

internal fun desktopQueueOverlayWidth(viewportWidth: Dp): Dp =
    minOf(QueueOverlayMaxWidth, (viewportWidth * QueueOverlayViewportFraction).coerceAtLeast(0.dp))
